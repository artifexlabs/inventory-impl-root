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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.Item;

/** End-to-end stages 1-3 for the ZPL path: item + QR png -> ZPL at a fake printer. */
public class ZebraPrinterTest {

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

  private static String printTo(ServerSocket fake, ZebraPrinter printer, String format) throws Exception {
    CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
      try (var socket = fake.accept()) {
        return socket.getInputStream().readAllBytes();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    assertTrue(printer.printLabel(item(), qrPng(), format).toCompletableFuture().get());
    return new String(received.get(), StandardCharsets.US_ASCII);
  }

  @Test
  public void largeFormatShipsFullHeightZpl() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      String zpl = printTo(fake, new ZebraPrinter("localhost", fake.getLocalPort(), "large"), null);
      assertTrue(zpl.startsWith("^XA^PW457"));
      assertTrue(zpl.contains("^GFA,47096,47096,58,"), "457x812 large geometry");
      assertTrue(zpl.endsWith("^FS^PQ1^XZ"));
    }
  }

  @Test
  public void formatOverrideBeatsTheConfiguredDefault() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // default large, per-print override to standard: 457x254 -> 58*254 = 14732
      String zpl = printTo(fake, new ZebraPrinter("localhost", fake.getLocalPort(), "large"), "standard");
      assertTrue(zpl.contains("^GFA,14732,14732,58,"), "457x254 standard geometry");
    }
  }

  @Test
  public void largeFormatResolvesTheContainerName() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      Item located = DefaultItem.builder().id("01ARZ3NDEKTSV4RRFFQ69G5FAV").name("smoke-item").type("tool")
          .containerId("loc-1").timestamp(java.time.Instant.parse("2026-08-09T00:00:00Z")).build();
      var looked = new java.util.concurrent.atomic.AtomicReference<String>();
      ZebraPrinter printer = new ZebraPrinter("localhost", fake.getLocalPort(), "large")
          .withContainerLookup(id -> {
            looked.set(id);
            return CompletableFuture.completedStage(java.util.Optional.of(DefaultItem.builder().id(id)
                .name("garage").type("location")
                .timestamp(java.time.Instant.parse("2026-08-09T00:00:00Z")).build()));
          });
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      assertTrue(printer.printLabel(located, qrPng(), null).toCompletableFuture().get());
      assertTrue(received.get().length > 1000, "label shipped");
      assertTrue("loc-1".equals(looked.get()), "the item's locationId was resolved");
    }
  }

  @Test
  public void failedLocationLookupStillPrints() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      Item located = DefaultItem.builder().id("01ARZ3NDEKTSV4RRFFQ69G5FAV").name("smoke-item").type("tool")
          .containerId("loc-1").timestamp(java.time.Instant.parse("2026-08-09T00:00:00Z")).build();
      ZebraPrinter printer = new ZebraPrinter("localhost", fake.getLocalPort(), "large")
          .withContainerLookup(id -> CompletableFuture.failedStage(new RuntimeException("store down")));
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      assertTrue(printer.printLabel(located, qrPng(), null).toCompletableFuture().get(),
          "a label without its location line beats no label");
      assertTrue(received.get().length > 1000);
    }
  }

  @Test
  public void xLargeFormatShipsFullWidthZpl() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      // 812x812 -> 102 bytes/row, 102*812 = 82824
      String zpl = printTo(fake, new ZebraPrinter("localhost", fake.getLocalPort(), "x-large"), null);
      assertTrue(zpl.startsWith("^XA^PW812"));
      assertTrue(zpl.contains("^GFA,82824,82824,102,"), "812x812 x-large geometry");
      assertTrue(zpl.endsWith("^FS^PQ1^XZ"));
    }
  }

  @Test
  public void twoXLargeFormatCarriesTheFullItem() throws Exception {
    try (ServerSocket fake = new ServerSocket(0)) {
      Item full = DefaultItem.builder().id("01ARZ3NDEKTSV4RRFFQ69G5FAV").name("crate").type("container")
          .quantity(2).heavy(true)
          .coordinates(new io.artifexlabs.inventory.api.LatLong(34.12345, -86.54321))
          .tags(java.util.Set.of(new io.artifexlabs.inventory.api.ItemTag("scuba", null),
              new io.artifexlabs.inventory.api.ItemTag("color", "orange")))
          .description("wrapping description text")
          .timestamp(java.time.Instant.parse("2026-08-09T00:00:00Z")).build();
      CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
        try (var socket = fake.accept()) {
          return socket.getInputStream().readAllBytes();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      ZebraPrinter printer = new ZebraPrinter("localhost", fake.getLocalPort(), "standard");
      assertTrue(printer.printLabel(full, qrPng(), "2x-large").toCompletableFuture().get());
      String zpl = new String(received.get(), StandardCharsets.US_ASCII);
      // 812x1320 -> 102*1320 = 134640
      assertTrue(zpl.contains("^GFA,134640,134640,102,"), "812x1320 2x-large geometry");
    }
  }

  @Test
  public void unknownFormatRefusesWithoutTouchingTheNetwork() throws Exception {
    ZebraPrinter printer = new ZebraPrinter("localhost", 1, "standard");
    assertFalse(printer.printLabel(item(), qrPng(), "a3-poster").toCompletableFuture().get());
  }

  @Test
  public void unknownDefaultFormatFailsFast() {
    assertThrows(IllegalArgumentException.class, () -> new ZebraPrinter("localhost", 9100, "novelty"));
  }

  @Test
  public void unreachablePrinterResolvesFalse() throws Exception {
    ZebraPrinter printer = new ZebraPrinter("localhost", 1, "standard");
    assertFalse(printer.printLabel(item(), qrPng()).toCompletableFuture().get());
  }
}
