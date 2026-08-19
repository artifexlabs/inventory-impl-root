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
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.imageio.ImageIO;

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.LabelPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LabelPrinter} for ZPL printers (first hardware: Zebra GK420t,
 * 203 dpi, Ethernet, die-cut media): compose per named format → {@code ^GFA}
 * ZPL encode → TCP 9100. Selected by {@code inventory.printer=zebra-gk420t};
 * the default format comes from {@code inventory.printer.format} and each
 * print may override it (the {@code ?format=} query parameter rides the bus
 * envelope to the labels worker).
 */
public class ZebraPrinter implements LabelPrinter {
  private final static Logger log = LoggerFactory.getLogger(ZebraPrinter.class);

  /** Named die-cut geometries in printer dots @203 dpi. */
  record Geometry(int width, int height) {
  }

  /**
   * format name -> media geometry: standard 2.25×1.25 in, large 2.25×4 in,
   * x-large 4×4 in, 2x-large 4×6.5 in (all @203 dpi).
   */
  private final static Map<String, Geometry> FORMATS = Map.of(
      "standard", new Geometry(457, 254),
      "large", new Geometry(457, 812),
      "x-large", new Geometry(812, 812),
      "2x-large", new Geometry(812, 1320));

  private final LabelComposer composer = new LabelComposer();
  private final ZplEncoder encoder = new ZplEncoder();
  private final Tcp9100Transport transport;
  private final String defaultFormat;
  private Function<String, CompletionStage<Optional<Item>>> containerLookup;

  public ZebraPrinter(String host, int port, String defaultFormat) {
    this.transport = new Tcp9100Transport(host, port);
    if (!FORMATS.containsKey(defaultFormat))
      throw new IllegalArgumentException(
          "unsupported label format: " + defaultFormat + " (know " + FORMATS.keySet() + ")");
    this.defaultFormat = defaultFormat;
  }

  /**
   * Resolve location names for the {@code large} layout (default: none —
   * the location line is simply omitted). Lookup failures never fail a
   * print; a label without its location line beats no label.
   */
  public ZebraPrinter withContainerLookup(Function<String, CompletionStage<Optional<Item>>> lookup) {
    this.containerLookup = lookup;
    return this;
  }

  @Override
  public CompletionStage<Boolean> printLabel(Item item, byte[] qrPng) {
    return printLabel(item, qrPng, null);
  }

  @Override
  public CompletionStage<Boolean> printLabel(Item item, byte[] qrPng, String format) {
    String chosen = format == null || format.isBlank() ? this.defaultFormat : format;
    Geometry geometry = FORMATS.get(chosen);
    if (geometry == null) {
      log.warn("Unknown label format {} for item {} (know {})", chosen, item.getId(), FORMATS.keySet());
      return CompletableFuture.completedStage(false);
    }
    return locationName(item).thenCompose(locName -> CompletableFuture.supplyAsync(() -> {
      try {
        BufferedImage qr = ImageIO.read(new ByteArrayInputStream(qrPng));
        String name = item.getDisplayName().orElse(item.getName());
        String expiresOn = item.getExpiration()
            .map(e -> LocalDate.ofInstant(e.when(), java.time.ZoneId.systemDefault()).toString()).orElse(null);
        boolean absolute = item.getExpiration().map(io.artifexlabs.inventory.api.Expiration::absolute)
            .orElse(false);
        BufferedImage label = switch (chosen) {
        case "large" -> this.composer.composeLarge(name, item.getId(), item.getType(),
            item.getQuantity().orElse(null), locName.orElse(null), item.isHeavy(), expiresOn, absolute,
            item.getDescription().orElse(null), LocalDate.now().toString(), qr, geometry.width(),
            geometry.height());
        case "x-large" -> this.composer.composeXLarge(name, item.getId(), item.getType(),
            item.getQuantity().orElse(null), locName.orElse(null), item.isHeavy(), expiresOn, absolute,
            LocalDate.now().toString(), qr, geometry.width(), geometry.height());
        case "2x-large" -> this.composer.compose2xLarge(name, item.getId(), item.getType(),
            item.getQuantity().orElse(null), locName.orElse(null), item.isHeavy(), expiresOn, absolute,
            coordinatesLine(item), tagsLine(item), item.getDescription().orElse(null),
            LocalDate.now().toString(), qr, geometry.width(), geometry.height());
        default -> this.composer.composeStandard(name, item.getId(), item.getType(),
            item.getQuantity().orElse(null), locName.orElse(null), item.isHeavy(), expiresOn, absolute,
            LocalDate.now().toString(), qr, geometry.width(), geometry.height());
        };
        this.transport.send(this.encoder.encode(label));
        log.info("Printed {} label for item {} ({}x{} dots, ZPL)", chosen, item.getId(),
            label.getWidth(), label.getHeight());
        return true;
      } catch (Exception e) {
        log.warn("Label print failed for item {}: {}", item.getId(), e.toString());
        return false;
      }
    }));
  }

  /**
   * The item's OWN pin, rendered for the 2x-large layout ("@ lat, long").
   * Deliberately not the effective/inherited coordinates — those belong to
   * the container chain the label already names via the location line.
   */
  private static String coordinatesLine(Item item) {
    return item.getCoordinates()
        .map(c -> String.format(java.util.Locale.ROOT, "@ %.5f, %.5f", c.latitude(), c.longitude()))
        .orElse(null);
  }

  /** Sorted, rendered tags ("scuba, color=orange") for the 2x-large layout. */
  private static String tagsLine(Item item) {
    if (item.getTags().isEmpty())
      return null;
    return item.getTags().stream().sorted().map(io.artifexlabs.inventory.api.ItemTag::render)
        .collect(java.util.stream.Collectors.joining(", "));
  }

  /**
   * The container's name — since Phase 15 "where is it" IS the container.
   * Best-effort: both layouts show it, and failure means absent.
   */
  private CompletionStage<Optional<String>> locationName(Item item) {
    if (this.containerLookup == null || item.getContainerId().isEmpty())
      return CompletableFuture.completedStage(Optional.empty());
    return this.containerLookup.apply(item.getContainerId().get())
        .thenApply(o -> o.map(c -> c.getDisplayName().orElse(c.getName())))
        .exceptionally(e -> {
          log.warn("Container lookup failed for item {}: {}", item.getId(), e.toString());
          return Optional.empty();
        });
  }
}
