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
package io.artifexlabs.inventory.impl.catalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.CatalogEntry;
import io.artifexlabs.inventory.api.UpcCatalog;
import io.artifexlabs.inventory.api.Weight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * UPCitemdb's free trial tier (keyless, ~100 lookups/day): the general-merchandise fallback behind the open-data
 * sources. A 429 (daily limit) — like every other failure — degrades to a miss and the composite moves on. Commercial
 * as-is data; the {@code source=} tag links its stable product page.
 */
public class UpcItemDbCatalog implements UpcCatalog {
  private final static Logger log = LoggerFactory.getLogger(UpcItemDbCatalog.class);

  public final static String DEFAULT_BASE = "https://api.upcitemdb.com/prod/trial";

  private final HttpClient http;
  private final String base;

  public UpcItemDbCatalog(String base) {
    this.base = base;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  @Override
  public CompletionStage<Optional<CatalogEntry>> lookup(String gtin13) {
    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(this.base + "/lookup?upc=" + gtin13))
        .timeout(Duration.ofSeconds(8)).header("Accept", "application/json").GET().build();
    return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .<Optional<CatalogEntry>>thenApply(response -> {
          if (response.statusCode() == 429) {
            log.info("UPCitemdb trial limit reached; treating {} as not found", gtin13);
            return Optional.empty();
          }
          return response.statusCode() == 200 ? parse(gtin13, response.body()) : Optional.empty();
        }).exceptionally(e -> {
          log.debug("UPCitemdb lookup failed for {}: {}", gtin13, e.toString());
          return Optional.empty();
        });
  }

  private Optional<CatalogEntry> parse(String gtin13, String body) {
    try {
      JsonObject json = new JsonObject(body);
      JsonArray items = json.getJsonArray("items", new JsonArray());
      if (items.isEmpty())
        return Optional.empty();
      JsonObject item = items.getJsonObject(0);
      String name = OpenFactsCatalog.blankToNull(item.getString("title"));
      if (name == null)
        return Optional.empty();
      JsonArray images = item.getJsonArray("images", new JsonArray());
      return Optional.of(new CatalogEntry(gtin13, name, OpenFactsCatalog.blankToNull(item.getString("brand")),
          OpenFactsCatalog.blankToNull(item.getString("description")), leafCategory(item.getString("category")),
          weightGrams(item.getString("weight")),
          images.isEmpty() ? null : OpenFactsCatalog.blankToNull(images.getString(0)),
          "https://www.upcitemdb.com/upc/" + gtin13, "upcitemdb.com"));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Categories arrive as a path ("Electronics > Audio > Headphones"): keep the leaf. */
  static String leafCategory(String category) {
    if (category == null || category.isBlank())
      return null;
    String[] parts = category.split(">");
    return OpenFactsCatalog.blankToNull(parts[parts.length - 1]);
  }

  /** Weight arrives as a display string ("1.2 pounds"): best-effort grams. */
  static Double weightGrams(String weight) {
    if (weight == null || weight.isBlank())
      return null;
    String[] parts = weight.trim().split("\\s+");
    if (parts.length != 2)
      return null;
    final double value;
    try {
      value = Double.parseDouble(parts[0]);
    } catch (NumberFormatException e) {
      return null;
    }
    return switch (parts[1].toLowerCase(Locale.ROOT)) {
    case "g", "gram", "grams" -> value;
    case "kg", "kilogram", "kilograms" -> value * 1000;
    case "oz", "ounce", "ounces" -> value * Weight.GRAMS_PER_OUNCE;
    case "lb", "lbs", "pound", "pounds" -> value * Weight.GRAMS_PER_POUND;
    default -> null;
    };
  }
}
