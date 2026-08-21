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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.Item;

/** End-to-end stages 1-3: item + QR png -> raster bytes arriving at a fake printer. */
public class BrotherPTouchPrinterTest {

  private static byte[] qrPng() throws IOException {
    BufferedImage qr = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_BINARY);
    var g = qr.createGraphics();
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, 32, 32);
    g.setColor(java.awt.Color.BLACK);
    g.fillRect(0, 0, 16, 16);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(qr, "PNG", out);
    return out.toByteArray();
  }

  private static Item item() {
    return DefaultItem.builder().id("01ARZ3NDEKTSV4RRFFQ69G5FAV").name("smoke-item").type("tool")
        .timestamp(java.time.Instant.parse("2026-08-09T00:00:00Z")).build();
  }

  @Test
  public void printsRasterStreamToTheFakePrinter() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      assertTrue(printer.printLabel(item(), qrPng()).toCompletableFuture().get());

      byte[] bytes = received.get();
      // invalidate preamble then initialize
      for (int i = 0; i < 100; i++)
        assertEquals(0, bytes[i], "invalidate byte " + i);
      assertEquals(0x1B, bytes[100]);
      assertEquals(0x40, bytes[101]);
      assertEquals(0x1A, bytes[bytes.length - 1] & 0xFF, "ends with print-and-feed");
      assertTrue(bytes.length > 200, "raster lines present");
    }
  }

  @Test
  public void unreachablePrinterResolvesFalse() throws Exception {
    BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", 1, 24);
    assertFalse(printer.printLabel(item(), qrPng()).toCompletableFuture().get());
  }

  private final static String SCAN_URL = "http://localhost:8081/i/01ARZ3NDEKTSV4RRFFQ69G5FAV";

  private static byte[] capture(ServerSocket fake, java.util.concurrent.CompletionStage<Boolean> print)
      throws Exception {
    CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
      try (var socket = fake.accept()) {
        return socket.getInputStream().readAllBytes();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    assertTrue(print.toCompletableFuture().get());
    return received.get();
  }

  /**
   * Raster lines across a WHOLE job or batch, by walking the protocol: one-time init, then per page a print-information
   * header, modes, raster lines ('G' + 2-byte length + 16 data bytes, or 'Z'), ending 0x0C (more pages) or 0x1A (run
   * complete).
   */
  private static int rasterLines(byte[] job) {
    int lines = 0;
    int at = 100 + 2 + 4; // invalidate, ESC @, ESC i a 01 — once per job
    while (at < job.length) {
      int b = job[at] & 0xFF;
      if (b == 0x1B) {
        int cmd = job[at + 2] & 0xFF;
        at += switch (cmd) {
        case 0x41 -> 4; // ESC i A cut-each-N
        case 0x7A -> 13; // ESC i z print information
        case 0x4D -> 4; // ESC i M various mode
        case 0x4B -> 4; // ESC i K advanced mode
        case 0x64 -> 5; // ESC i d margin
        default -> throw new AssertionError("unexpected ESC command 0x" + Integer.toHexString(cmd));
        };
      } else if (b == 0x4D) {
        at += 2; // M 00 compression select
      } else if (b == 0x47) {
        lines++;
        at += 3 + 16; // 'G' + length + one head-width of data
      } else if (b == 0x5A) {
        lines++;
        at += 1;
      } else if (b == 0x0C) {
        at += 1; // page break: keep walking the next page
      } else if (b == 0x1A) {
        return lines; // run complete
      } else {
        throw new AssertionError("unexpected byte 0x" + Integer.toHexString(b) + " at " + at);
      }
    }
    throw new AssertionError("job has no terminator");
  }

  @Test
  public void aBatchIsOneConnectionEndingTheRunOnce() throws Exception {
    // the fix for the printer lockup: N labels must arrive as ONE job on ONE
    // connection, not N jobs that each say "more coming" and hang up
    try (ServerSocket fake = new ServerSocket(0)) {
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      var connections = new java.util.concurrent.atomic.AtomicInteger();
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          connections.incrementAndGet();
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      assertTrue(printer
          .printBatch(java.util.List.of(
              new io.artifexlabs.inventory.api.LabelPrinter.LabelRequest(item(), SCAN_URL, qrPng(), null),
              new io.artifexlabs.inventory.api.LabelPrinter.LabelRequest(item(), SCAN_URL, qrPng(), null)))
          .toCompletableFuture().get());
      byte[] bytes = received.get();
      assertEquals(1, connections.get(), "the whole run must use ONE connection");
      assertEquals(0x1A, bytes[bytes.length - 1] & 0xFF, "only the last page ends the job");
      int escAt = 0;
      for (int i = 0; i + 1 < bytes.length; i++)
        if ((bytes[i] & 0xFF) == 0x1B && (bytes[i + 1] & 0xFF) == 0x40)
          escAt++;
      assertEquals(1, escAt, "ESC @ once per run — repeating it discarded buffered pages");
    }
  }

  @Test
  public void feedReclaimsTheLeader() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      byte[] bytes = capture(fake, printer.feed());
      assertEquals(0x1A, bytes[bytes.length - 1] & 0xFF, "the feed job feeds and cuts");
      assertEquals(BrotherRasterEncoder.FEED_LINES, rasterLines(bytes), "blank job");
    }
  }

  @Test
  public void twelveMmTapeGoesQrOnlyWithTheUrlPayload() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // URL -> v3 = 29 modules; 70 dots / 29 = 2 dpm -> 58-dot code + 4 margin
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 12);
      byte[] bytes = capture(fake, printer.printLabel(item(), SCAN_URL, qrPng(), null));
      assertEquals(62, rasterLines(bytes), "QR-only label: 29-module URL code at 2 dots/module");
    }
  }

  @Test
  public void nineMmTapeTiersDownToTheBareUlid() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // URL needs 58 dots and the 9mm band has 50 -> bare-ULID v2 = 25
      // modules at 2 dpm = exactly 50, plus the 4-dot trailing margin
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 9);
      byte[] bytes = capture(fake, printer.printLabel(item(), SCAN_URL, qrPng(), null));
      assertEquals(54, rasterLines(bytes), "QR-only label: 25-module ULID code at 2 dots/module");
    }
  }

  @Test
  public void qrOnlyFormatForcesTheBigCodeOnWideTape() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // 24mm auto prints QR+text; format=qr-only must yield JUST the code:
      // URL v3 = 29 modules at 128/29 = 4 dots/module -> 116 + 4 margin
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      byte[] bytes = capture(fake, printer.printLabel(item(), SCAN_URL, qrPng(), "qr-only"));
      assertEquals(120, rasterLines(bytes), "a 116-dot URL code plus the 4-dot margin, no text");
    }
  }

  @Test
  public void namedTinyPrintsTheBareUlidCode() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // same geometry as 9mm automatic: 25-module ULID code at 2 dpm + margin
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 9);
      byte[] bytes = capture(fake, printer.printLabel(item(), SCAN_URL, qrPng(), "tiny"));
      assertEquals(54, rasterLines(bytes), "tiny: the bare ULID even though a URL was offered");
    }
  }

  @Test
  public void namedStandardQrPrintsTheUrlCode() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 12);
      byte[] bytes = capture(fake, printer.printLabel(item(), SCAN_URL, qrPng(), "standard-qr"));
      assertEquals(62, rasterLines(bytes), "29-module URL code at 2 dots/module plus margin");
    }
  }

  @Test
  public void namedLargeMatchesTheAutomaticWideLayout() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      byte[] auto = capture(fake, printer.printLabel(item(), SCAN_URL, qrPng(), null));
      byte[] named = capture(fake, printer.printLabel(item(), SCAN_URL, qrPng(), "large"));
      assertEquals(rasterLines(auto), rasterLines(named), "'large' IS the automatic wide layout, by name");
    }
  }

  @Test
  public void namedStandardCompactStripCarriesTextBesideTheCode() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 12);
      byte[] plain = capture(fake, printer.printLabel(item(), SCAN_URL, qrPng(), "standard"));
      assertTrue(rasterLines(plain) > 62, "the strip is wider than the bare 62-line code");

      var loaded = io.artifexlabs.inventory.api.DefaultItem.builder().id("01ARZ3NDEKTSV4RRFFQ69G5FAV")
          .name("smoke-item").type("tool").timestamp(java.time.Instant.parse("2026-08-09T00:00:00Z"))
          .weight(new io.artifexlabs.inventory.api.Weight(1500)).heavy(true).build();
      byte[] full = capture(fake, printer.printLabel(loaded, SCAN_URL, qrPng(), "standard"));
      assertTrue(rasterLines(full) > rasterLines(plain), "weight line and the H mark widen the strip");
    }
  }

  @Test
  public void namedFormatRefusesMismatchedTapeWithoutTouchingThePrinter() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // 24mm is loaded; every 9/12mm-bound name must refuse, not misprint
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), "standard").toCompletableFuture().get());
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), "standard-qr").toCompletableFuture().get());
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), "tiny").toCompletableFuture().get());
      assertFalse(received.isDone(), "nothing was sent to the printer");
    }
  }

  /** Records what a refusal told the user, so tests can assert BOTH faces. */
  private final static class RecordingStatus implements io.artifexlabs.inventory.api.events.StatusPublisher {
    private final java.util.List<io.artifexlabs.inventory.api.events.StatusEvent> events = java.util.Collections
        .synchronizedList(new java.util.ArrayList<>());

    @Override
    public void publish(io.artifexlabs.inventory.api.events.StatusEvent event) {
      this.events.add(event);
    }

    io.artifexlabs.inventory.api.events.StatusEvent only() {
      assertEquals(1, this.events.size(), "expected exactly one status event, got " + this.events);
      return this.events.get(0);
    }
  }

  private static void assertBothFaces(io.artifexlabs.inventory.api.events.StatusEvent e, String code) {
    assertEquals(code, e.code(), "the machine face");
    assertEquals("printer.brother", e.source());
    assertTrue(e.message() != null && !e.message().isBlank(), "the human face must be a sentence");
    assertTrue(e.subject().containsKey("itemId") || e.subject().containsKey("count"),
        "structured params must identify what failed: " + e.subject());
    assertEquals(io.artifexlabs.inventory.api.events.StatusEvent.Severity.ERROR, e.severity());
  }

  @Test
  public void tapeMismatchTellsTheUserWhichTapeIsNeeded() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      RecordingStatus status = new RecordingStatus();
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24, status);
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), "standard").toCompletableFuture().get());
      var e = status.only();
      assertBothFaces(e, "printer.tape-mismatch");
      assertEquals("12", e.subject().get("requiredTapeMm"));
      assertEquals("24", e.subject().get("loadedTapeMm"));
      assertTrue(e.message().contains("12 mm") && e.message().contains("24 mm"),
          "the sentence names both tapes: " + e.message());
    }
  }

  @Test
  public void unknownFormatSaysWhatItKnows() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      RecordingStatus status = new RecordingStatus();
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24, status);
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), "x-large").toCompletableFuture().get());
      var e = status.only();
      assertBothFaces(e, "printer.unknown-format");
      assertEquals("x-large", e.subject().get("format"));
      assertTrue(e.detailText().orElse("").contains("tiny"), "detail lists the known formats");
    }
  }

  @Test
  public void tapeTooNarrowForAnyScannableCodeReportsWhy() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // 6mm = 32 dots; the smallest payload (25-module ULID) needs 50
      RecordingStatus status = new RecordingStatus();
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 6, status);
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), null).toCompletableFuture().get());
      var e = status.only();
      assertBothFaces(e, "printer.no-scannable-qr");
      assertEquals("6", e.subject().get("tapeMm"));
      assertTrue(e.detailText().orElse("").contains("wider tape"), "detail says what to do about it");
    }
  }

  @Test
  public void unreachablePrinterIsReportedNotJustLogged() throws Exception {
    RecordingStatus status = new RecordingStatus();
    BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", 1, 24, status);
    assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), null).toCompletableFuture().get());
    assertBothFaces(status.only(), "printer.print-failed");
  }

  @Test
  public void refusalsStayQuietWhenNoStatusChannelIsWired() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // the 3-arg constructor keeps NOOP: emitting must never be mandatory
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), "standard").toCompletableFuture().get());
    }
  }

  @Test
  public void unknownFormatRefusesWithoutTouchingThePrinter() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      // names outside this printer's slice (x-large is Zebra-only) refuse loudly
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), "x-large").toCompletableFuture().get());
      assertFalse(received.isDone(), "nothing was sent to the printer");
    }
  }

  @Test
  public void aBatchHonoursPerRequestFormats() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 24);
      byte[] bytes = capture(fake,
          printer.printBatch(java.util.List.of(
              new io.artifexlabs.inventory.api.LabelPrinter.LabelRequest(item(), SCAN_URL, qrPng(), "qr-only"),
              new io.artifexlabs.inventory.api.LabelPrinter.LabelRequest(item(), SCAN_URL, qrPng(), "qr-only"))));
      assertEquals(0x1A, bytes[bytes.length - 1] & 0xFF);
      // two qr-only pages: the run carries exactly 2 x 120 raster lines
      assertEquals(240, rasterLines(bytes), "both pages rendered qr-only");
    }
  }

  @Test
  public void sixMmTapeRefusesWithoutTouchingThePrinter() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      BrotherPTouchPrinter printer = new BrotherPTouchPrinter("localhost", fake.getLocalPort(), 6);
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      assertFalse(printer.printLabel(item(), SCAN_URL, qrPng(), null).toCompletableFuture().get(),
          "no scannable code fits 32 dots — refuse rather than print garbage");
      assertFalse(received.isDone(), "nothing was sent to the printer");
    }
  }
}
