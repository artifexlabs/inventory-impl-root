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

import io.artifexlabs.inventory.impl.printer.common.QrCodes;

import io.artifexlabs.inventory.api.events.StatusEvent;
import io.artifexlabs.inventory.api.events.StatusPublisher;
import io.artifexlabs.inventory.impl.printer.common.LabelComposer;
import io.artifexlabs.inventory.impl.printer.common.Tcp9100Transport;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.LabelPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LabelPrinter} for the Brother PT-P750W: compose (QR + name + id) → Brother raster encode → TCP 9100. Selected
 * by {@code inventory.printer=
 * brother-p750w}; the printable dot count comes from the configured tape width (Brother's per-tape pin counts at 180
 * dpi).
 *
 * QRs render module-exact from the scan URL (ongoing item 11): wide tapes (24/18 mm) carry the URL beside name+id text;
 * narrow tapes (12/9 mm) print a QR-only label, dropping to the bare-ULID payload (alphanumeric, version 2 at ECC Q)
 * when the URL cannot reach 2 dots per module — the practical floor for a phone camera. 6 mm has no reliable QR at all
 * (the smallest code is 42 dots at that floor; the tape has 32) and refuses.
 *
 * One NAMED format exists: {@code qr-only} forces the QR-only layout on any tape (a big square code with no text — e.g.
 * a 116-dot, ~16 mm code on 24 mm tape), riding the same {@code ?format=} plumbing the Zebra formats use. Null/blank
 * format keeps the automatic behavior; any other name is refused without touching the printer, like the Zebra does.
 *
 * Chain printing (ongoing item 10) is expressed by {@link #printBatch}: a run streams down ONE connection so the labels
 * share a single ~25 mm leader. It is deliberately NOT a config flag — the old {@code inventory.printer.chain} sent
 * each label as its own job ending "print without feeding" and hung up, which silently destroyed labels and wedged the
 * printer (hardware-proven 2026-08-18).
 */
public class BrotherPTouchPrinter implements LabelPrinter {
  private final static Logger log = LoggerFactory.getLogger(BrotherPTouchPrinter.class);

  /** TZe tape width (mm) -> printable dots on the 128-pin head (per Brother's reference). */
  private final static Map<Integer, Integer> TAPE_DOTS = Map.of(24, 128, 18, 112, 12, 70, 9, 50, 6, 32);
  /** Below 2 dots per module a phone camera cannot resolve the code. */
  private final static int MIN_DOTS_PER_MODULE = 2;
  /** Tapes with fewer printable dots than this go QR-only (text is unreadable there). */
  private final static int MIN_TEXT_DOTS = 100;
  // This printer's slice of the LabelPrinter.FORMAT_* vocabulary (item 11
  // rework, 2026-08-21): the semantic names map onto tape widths here —
  // standard/standard-qr = 12 mm, large = 24 mm, tiny = 9 mm — and refuse
  // when the loaded width differs (a mismatched raster prints garbage;
  // refusal beats wasted stock). FORMAT_QR_ONLY alone is width-independent.
  /** The {@code source} every StatusEvent from this printer carries. */
  private final static String SOURCE = "printer.brother";
  private final static String KNOWN_FORMATS = String.join(", ", FORMAT_TINY, FORMAT_STANDARD_QR_ONLY, FORMAT_STANDARD,
      FORMAT_LARGE, FORMAT_QR_ONLY);

  private final LabelComposer composer = new LabelComposer();
  private final BrotherRasterEncoder encoder = new BrotherRasterEncoder();
  private final Tcp9100Transport transport;
  private final int tapeMm;
  private final int dots;
  /** Where refusals go so a HUMAN hears about them, not just the log (PLAN.md Phase 21). */
  private final StatusPublisher status;

  public BrotherPTouchPrinter(String host, int port, int tapeMm) {
    this(host, port, tapeMm, StatusPublisher.NOOP);
  }

  public BrotherPTouchPrinter(String host, int port, int tapeMm, StatusPublisher status) {
    this.transport = new Tcp9100Transport(host, port);
    this.status = status == null ? StatusPublisher.NOOP : status;
    this.tapeMm = tapeMm;
    Integer d = TAPE_DOTS.get(tapeMm);
    if (d == null)
      throw new IllegalArgumentException(
          "unsupported TZe tape width: " + tapeMm + "mm (know " + TAPE_DOTS.keySet() + ")");
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
        log.info("Printed label for item {} ({} raster lines, {}mm tape)", item.getId(), label.getWidth(), this.tapeMm);
        return true;
      } catch (Exception e) {
        log.warn("Label print failed for item {}: {}", item.getId(), e.toString());
        this.status.publish(StatusEvent
            .error("printer.print-failed", "The label could not be printed — the printer did not accept the job.")
            .source(SOURCE).subject("itemId", item.getId()).subject("tapeMm", String.valueOf(this.tapeMm))
            .detail("Sending the label to the Brother printer failed: " + e));
        return false;
      }
    });
  }

  /**
   * A whole run as ONE job over ONE connection, so the labels share a single leader instead of wasting ~25 mm per cut
   * (ongoing item 10). Composing happens up front; a label that cannot be rendered (e.g. no scannable QR fits the tape)
   * fails the batch rather than printing a partial run, because a half-printed strip is worse than none.
   */
  @Override
  public CompletionStage<Boolean> printBatch(java.util.List<LabelRequest> requests, boolean halfCutBetween) {
    if (requests == null || requests.isEmpty())
      return CompletableFuture.completedStage(true);
    return CompletableFuture.supplyAsync(() -> {
      try {
        java.util.List<BufferedImage> labels = new java.util.ArrayList<>();
        for (LabelRequest r : requests) {
          BufferedImage label = compose(r.item(), r.scanUrl(), r.format());
          if (label == null) {
            log.warn("Batch refused: no scannable label fits {}mm tape for item {}", this.tapeMm, r.item().getId());
            this.status.publish(StatusEvent
                .error("printer.batch-refused",
                    "The label run was refused because one of its labels could not be produced.")
                .source(SOURCE).subject("itemId", r.item().getId()).subject("tapeMm", String.valueOf(this.tapeMm))
                .subject("count", String.valueOf(requests.size()))
                .detail("A chained run prints as one strip, so a label that cannot be rendered "
                    + "cancels the whole run rather than printing a partial one."));
            return false;
          }
          labels.add(label);
        }
        // ONE send: the encoder streams every page with the correct
        // starting-page flags and ends only the last with 0x1A
        this.transport.send(this.encoder.encodeBatch(labels, this.tapeMm, halfCutBetween));
        log.info("Printed a chained run of {} labels ({}mm tape, one leader, {})", labels.size(), this.tapeMm,
            halfCutBetween ? "half cut between" : "full cut between");
        return true;
      } catch (Exception e) {
        log.warn("Batch label print failed: {}", e.toString());
        this.status.publish(StatusEvent
            .error("printer.print-failed", "The label run could not be printed — the printer did not accept the job.")
            .source(SOURCE).subject("count", String.valueOf(requests.size()))
            .subject("tapeMm", String.valueOf(this.tapeMm))
            .detail("Sending the chained run to the Brother printer failed: " + e));
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
        this.status.publish(
            StatusEvent.error("printer.feed-failed", "The tape could not be fed — the printer did not accept the job.")
                .source(SOURCE).subject("tapeMm", String.valueOf(this.tapeMm))
                .detail("Sending the feed command to the Brother printer failed: " + e));
        return false;
      }
    });
  }

  /** null = refused: unknown format, tape mismatch, or no scannable label fits. */
  private BufferedImage compose(Item item, String scanUrl, String format) {
    if (format == null || format.isBlank()) {
      // automatic: text layout on wide tapes, QR-only where text can't render
      return this.dots < MIN_TEXT_DOTS ? composeQrOnly(item, scanUrl) : composeNameId(item, scanUrl);
    }
    return switch (format) {
    case FORMAT_QR_ONLY -> composeQrOnly(item, scanUrl);
    case FORMAT_TINY -> tapeIs(9, format, item) ? composeQrOnly(item, null) : null;
    case FORMAT_STANDARD_QR_ONLY -> tapeIs(12, format, item) ? composeQrOnly(item, scanUrl) : null;
    case FORMAT_STANDARD -> tapeIs(12, format, item) ? composeCompact(item, scanUrl) : null;
    case FORMAT_LARGE -> tapeIs(24, format, item) ? composeNameId(item, scanUrl) : null;
    default -> {
      log.warn("Unknown label format {} for item {} (know [{}])", format, item.getId(), KNOWN_FORMATS);
      this.status.publish(StatusEvent
          .error("printer.unknown-format", "Label refused: '" + format + "' is not a format this printer knows.")
          .source(SOURCE).subject("itemId", item.getId()).subject("format", format)
          .detail("Known formats: " + KNOWN_FORMATS));
      yield null;
    }
    };
  }

  private boolean tapeIs(int requiredMm, String format, Item item) {
    if (this.tapeMm == requiredMm)
      return true;
    log.warn("Format {} needs {}mm tape but {}mm is loaded — refusing label for item {}", format, requiredMm,
        this.tapeMm, item.getId());
    this.status.publish(StatusEvent
        .error("printer.tape-mismatch",
            "Label refused: format '" + format + "' needs " + requiredMm + " mm tape, but " + this.tapeMm
                + " mm is loaded.")
        .source(SOURCE).subject("itemId", item.getId()).subject("format", format)
        .subject("requiredTapeMm", String.valueOf(requiredMm)).subject("loadedTapeMm", String.valueOf(this.tapeMm))
        .detail("Load " + requiredMm + " mm tape, or choose a format that matches the tape in the printer."));
    return false;
  }

  /**
   * The scannable matrix for this tape: the URL when given (ECC L keeps it v3), else — or when the URL cannot reach 2
   * dots/module — the bare ULID at ECC Q, which still resolves in our own scanners. null = nothing fits.
   */
  private com.google.zxing.common.BitMatrix scannableMatrix(Item item, String scanUrl) {
    String payload = scanUrl != null && !scanUrl.isBlank() ? scanUrl : item.getId();
    var ecc = payload.equals(item.getId()) ? com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q
        : com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L;
    var matrix = QrCodes.bareMatrix(payload, ecc);
    if (this.dots / matrix.getWidth() < MIN_DOTS_PER_MODULE) {
      matrix = QrCodes.bareMatrix(item.getId(), com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q);
    }
    if (this.dots / matrix.getWidth() < MIN_DOTS_PER_MODULE) {
      log.warn("No scannable QR fits {}mm tape ({} dots) for item {} — smallest payload needs {} modules", this.tapeMm,
          this.dots, item.getId(), matrix.getWidth());
      this.status.publish(StatusEvent
          .error("printer.no-scannable-qr",
              "Label refused: no QR code small enough to stay scannable fits " + this.tapeMm + " mm tape.")
          .source(SOURCE).subject("itemId", item.getId()).subject("tapeMm", String.valueOf(this.tapeMm))
          .subject("modulesNeeded", String.valueOf(matrix.getWidth()))
          .detail("Even the shortest payload needs " + matrix.getWidth() + " modules, which this tape cannot "
              + "print at a readable density. Use wider tape."));
      return null;
    }
    return matrix;
  }

  private BufferedImage composeQrOnly(Item item, String scanUrl) {
    var matrix = scannableMatrix(item, scanUrl);
    if (matrix == null)
      return null;
    return this.composer.composeQrOnly(QrCodes.render(matrix, this.dots / matrix.getWidth()), this.dots);
  }

  private BufferedImage composeNameId(Item item, String scanUrl) {
    String payload = scanUrl != null && !scanUrl.isBlank() ? scanUrl : item.getId();
    var ecc = payload.equals(item.getId()) ? com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q
        : com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L;
    var matrix = QrCodes.bareMatrix(payload, ecc);
    int dpm = Math.max(1, this.dots / matrix.getWidth());
    return this.composer.compose(item.getDisplayName().orElse(item.getName()), item.getId(),
        QrCodes.render(matrix, dpm), 4 * dpm, this.dots);
  }

  private BufferedImage composeCompact(Item item, String scanUrl) {
    var matrix = scannableMatrix(item, scanUrl);
    if (matrix == null)
      return null;
    int dpm = this.dots / matrix.getWidth();
    String weightLabel = item.getWeight()
        .map(w -> w.grams() >= 1000.0 ? String.format(java.util.Locale.ROOT, "%.1f kg", w.toKilograms())
            : Math.round(w.grams()) + " g")
        .orElse(null);
    return this.composer.composeCompactStrip(item.getDisplayName().orElse(item.getName()),
        java.time.LocalDate.now().toString(), weightLabel, item.isHeavy(), QrCodes.render(matrix, dpm), 4 * dpm,
        this.dots);
  }
}
