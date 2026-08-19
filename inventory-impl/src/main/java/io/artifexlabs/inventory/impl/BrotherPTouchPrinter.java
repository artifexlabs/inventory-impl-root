/*
 * @formatter:off
 * Copyright © 2019 admin (admin@artifexlabs.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * @formatter:on
 */
package io.artifexlabs.inventory.impl;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.LabelPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LabelPrinter} for the Brother PT-P750W: compose (QR + name + id) →
 * Brother raster encode → TCP 9100. Selected by {@code inventory.printer=
 * brother-p750w}; the printable dot count comes from the configured tape
 * width (Brother's per-tape pin counts at 180 dpi).
 *
 * QRs render module-exact from the scan URL (ongoing item 11): wide tapes
 * (24/18 mm) carry the URL beside name+id text; narrow tapes (12/9 mm) print
 * a QR-only label, dropping to the bare-ULID payload (alphanumeric, version
 * 2 at ECC Q) when the URL cannot reach 2 dots per module — the practical
 * floor for a phone camera. 6 mm has no reliable QR at all (the smallest
 * code is 42 dots at that floor; the tape has 32) and refuses.
 *
 * One NAMED format exists: {@code qr-only} forces the QR-only layout on any
 * tape (a big square code with no text — e.g. a 116-dot, ~16 mm code on
 * 24 mm tape), riding the same {@code ?format=} plumbing the Zebra formats
 * use. Null/blank format keeps the automatic behavior; any other name is
 * refused without touching the printer, like the Zebra does.
 *
 * Chain printing (ongoing item 10) is expressed by {@link #printBatch}: a
 * run streams down ONE connection so the labels share a single ~25 mm
 * leader. It is deliberately NOT a config flag — the old
 * {@code inventory.printer.chain} sent each label as its own job ending
 * "print without feeding" and hung up, which silently destroyed labels and
 * wedged the printer (hardware-proven 2026-08-18).
 */
public class BrotherPTouchPrinter implements LabelPrinter {
  private final static Logger log = LoggerFactory.getLogger(BrotherPTouchPrinter.class);

  /** TZe tape width (mm) -> printable dots on the 128-pin head (per Brother's reference). */
  private final static Map<Integer, Integer> TAPE_DOTS = Map.of(24, 128, 18, 112, 12, 70, 9, 50, 6, 32);
  /** Below 2 dots per module a phone camera cannot resolve the code. */
  private final static int MIN_DOTS_PER_MODULE = 2;
  /** Tapes with fewer printable dots than this go QR-only (text is unreadable there). */
  private final static int MIN_TEXT_DOTS = 100;
  /** The one named Brother format: force the QR-only layout on any tape. */
  final static String FORMAT_QR_ONLY = "qr-only";

  private final LabelComposer composer = new LabelComposer();
  private final BrotherRasterEncoder encoder = new BrotherRasterEncoder();
  private final Tcp9100Transport transport;
  private final int tapeMm;
  private final int dots;

  public BrotherPTouchPrinter(String host, int port, int tapeMm) {
    this.transport = new Tcp9100Transport(host, port);
    this.tapeMm = tapeMm;
    Integer d = TAPE_DOTS.get(tapeMm);
    if (d == null)
      throw new IllegalArgumentException("unsupported TZe tape width: " + tapeMm + "mm (know "
          + TAPE_DOTS.keySet() + ")");
    this.dots = d;
  }

  @Override
  public CompletionStage<Boolean> printLabel(Item item, byte[] qrPng) {
    return printLabel(item, null, qrPng, null);
  }

  @Override
  public CompletionStage<Boolean> printLabel(Item item, String scanUrl, byte[] qrPng, String format) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        BufferedImage label = compose(item, scanUrl, format);
        if (label == null)
          return false;
        this.transport.send(this.encoder.encode(label, this.tapeMm));
        log.info("Printed label for item {} ({} raster lines, {}mm tape)", item.getId(),
            label.getWidth(), this.tapeMm);
        return true;
      } catch (Exception e) {
        log.warn("Label print failed for item {}: {}", item.getId(), e.toString());
        return false;
      }
    });
  }

  /**
   * A whole run as ONE job over ONE connection, so the labels share a single
   * leader instead of wasting ~25 mm per cut (ongoing item 10). Composing
   * happens up front; a label that cannot be rendered (e.g. no scannable QR
   * fits the tape) fails the batch rather than printing a partial run,
   * because a half-printed strip is worse than none.
   */
  @Override
  public CompletionStage<Boolean> printBatch(java.util.List<LabelRequest> requests,
      boolean halfCutBetween) {
    if (requests == null || requests.isEmpty())
      return CompletableFuture.completedStage(true);
    return CompletableFuture.supplyAsync(() -> {
      try {
        java.util.List<BufferedImage> labels = new java.util.ArrayList<>();
        for (LabelRequest r : requests) {
          BufferedImage label = compose(r.item(), r.scanUrl(), r.format());
          if (label == null) {
            log.warn("Batch refused: no scannable label fits {}mm tape for item {}", this.tapeMm,
                r.item().getId());
            return false;
          }
          labels.add(label);
        }
        // ONE send: the encoder streams every page with the correct
        // starting-page flags and ends only the last with 0x1A
        this.transport.send(this.encoder.encodeBatch(labels, this.tapeMm, halfCutBetween));
        log.info("Printed a chained run of {} labels ({}mm tape, one leader, {})", labels.size(),
            this.tapeMm, halfCutBetween ? "half cut between" : "full cut between");
        return true;
      } catch (Exception e) {
        log.warn("Batch label print failed: {}", e.toString());
        return false;
      }
    });
  }

  /** Feed and cut — reclaims the leader ("extend the tape"). */
  @Override
  public CompletionStage<Boolean> feed() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        this.transport.send(this.encoder.encodeFeed(this.tapeMm));
        log.info("Fed and cut ({}mm tape)", this.tapeMm);
        return true;
      } catch (Exception e) {
        log.warn("Tape feed failed: {}", e.toString());
        return false;
      }
    });
  }

  /** null = refused: unknown format, or no scannable label fits this tape. */
  private BufferedImage compose(Item item, String scanUrl, String format) {
    final boolean qrOnly;
    if (format == null || format.isBlank()) {
      // automatic: text layout on wide tapes, QR-only where text can't render
      qrOnly = this.dots < MIN_TEXT_DOTS;
    } else if (FORMAT_QR_ONLY.equals(format)) {
      qrOnly = true;
    } else {
      log.warn("Unknown label format {} for item {} (know [{}])", format, item.getId(), FORMAT_QR_ONLY);
      return null;
    }
    // no URL supplied (legacy callers): the bare ULID still resolves in our
    // own scanners, and stays module-exact
    String payload = scanUrl != null && !scanUrl.isBlank() ? scanUrl : item.getId();
    var ecc = payload.equals(item.getId())
        ? com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q
        : com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L;
    var matrix = QrCodes.bareMatrix(payload, ecc);

    if (!qrOnly) {
      int dpm = Math.max(1, this.dots / matrix.getWidth());
      return this.composer.compose(item.getDisplayName().orElse(item.getName()), item.getId(),
          QrCodes.render(matrix, dpm), 4 * dpm, this.dots);
    }
    // QR-only (narrow tape, or forced by format): the payload tiers down
    // until the modules fit at a scannable density
    if (this.dots / matrix.getWidth() < MIN_DOTS_PER_MODULE) {
      matrix = QrCodes.bareMatrix(item.getId(), com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q);
    }
    int dpm = this.dots / matrix.getWidth();
    if (dpm < MIN_DOTS_PER_MODULE) {
      log.warn("No scannable QR fits {}mm tape ({} dots) for item {} — smallest payload needs {} modules",
          this.tapeMm, this.dots, item.getId(), matrix.getWidth());
      return null;
    }
    return this.composer.composeQrOnly(QrCodes.render(matrix, dpm), this.dots);
  }
}
