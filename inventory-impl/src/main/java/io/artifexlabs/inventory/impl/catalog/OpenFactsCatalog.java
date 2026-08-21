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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.CatalogEntry;
import io.artifexlabs.inventory.api.UpcCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonObject;

/**
 * The Open Food Facts family (ODbL open data, keyless REST): flavors are tried in order — products, food, beauty, pet
 * food — and the first hit wins. Every failure mode (HTTP error, timeout, unparseable body) degrades to a miss: lookup
 * is prefill, never a dependency.
 *
 * The {@code source=} tag carried on created items links the flavor's stable product page — our ODbL attribution.
 */
public class OpenFactsCatalog implements UpcCatalog {
  private final static Logger log = LoggerFactory.getLogger(OpenFactsCatalog.class);

  private final static String FIELDS = "product_name,brands,generic_name,categories,image_url,"
      + "product_quantity,product_quantity_unit";
  /** Flavor order: general products first — this is an inventory, not a pantry. */
  public final static List<String> DEFAULT_BASES = List.of("https://world.openproductsfacts.org",
      "https://world.openfoodfacts.org", "https://world.openbeautyfacts.org", "https://world.openpetfoodfacts.org");

  private final HttpClient http;
  private final List<String> bases;

  public OpenFactsCatalog(List<String> bases) {
    this.bases = List.copyOf(bases);
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  @Override
  public CompletionStage<Optional<CatalogEntry>> lookup(String gtin13) {
    return tryFlavor(gtin13, 0);
  }

  private CompletionStage<Optional<CatalogEntry>> tryFlavor(String gtin13, int index) {
    if (index >= this.bases.size())
      return CompletableFuture.completedStage(Optional.empty());
    String base = this.bases.get(index);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(base + "/api/v2/product/" + gtin13 + ".json?fields=" + FIELDS)).timeout(Duration.ofSeconds(8))
        .header("Accept", "application/json").GET().build();
    return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .<Optional<CatalogEntry>>thenApply(
            response -> response.statusCode() == 200 ? parse(gtin13, base, response.body()) : Optional.empty())
        .exceptionally(e -> {
          log.debug("Open Facts flavor {} failed for {}: {}", base, gtin13, e.toString());
          return Optional.empty();
        }).thenCompose(
            found -> found.isPresent() ? CompletableFuture.completedStage(found) : tryFlavor(gtin13, index + 1));
  }

  private static Optional<CatalogEntry> parse(String gtin13, String base, String body) {
    try {
      JsonObject json = new JsonObject(body);
      if (json.getInteger("status", 0) != 1)
        return Optional.empty();
      JsonObject product = json.getJsonObject("product", new JsonObject());
      String name = blankToNull(product.getString("product_name"));
      if (name == null)
        return Optional.empty(); // a nameless hit prefills nothing useful
      return Optional.of(new CatalogEntry(gtin13, name, blankToNull(product.getString("brands")),
          blankToNull(product.getString("generic_name")), leafCategory(product.getString("categories")),
          weightGrams(product), blankToNull(product.getString("image_url")), base + "/product/" + gtin13,
          URI.create(base).getHost()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** OFF categories run generic → specific, comma-separated: keep the leaf. */
  static String leafCategory(String categories) {
    if (categories == null || categories.isBlank())
      return null;
    String[] parts = categories.split(",");
    String leaf = parts[parts.length - 1].trim();
    // strip a language prefix like "en:"
    if (leaf.length() > 3 && leaf.charAt(2) == ':')
      leaf = leaf.substring(3);
    return blankToNull(leaf);
  }

  /** product_quantity + unit → grams; only mass units qualify (ml is volume). */
  private static Double weightGrams(JsonObject product) {
    Object qty = product.getValue("product_quantity");
    String unit = product.getString("product_quantity_unit");
    if (qty == null || unit == null)
      return null;
    final double value;
    try {
      value = qty instanceof Number n ? n.doubleValue() : Double.parseDouble(qty.toString());
    } catch (NumberFormatException e) {
      return null;
    }
    return switch (unit.trim().toLowerCase(java.util.Locale.ROOT)) {
    case "g" -> value;
    case "kg" -> value * 1000;
    case "oz" -> value * io.artifexlabs.inventory.api.Weight.GRAMS_PER_OUNCE;
    case "lb", "lbs" -> value * io.artifexlabs.inventory.api.Weight.GRAMS_PER_POUND;
    default -> null;
    };
  }

  static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
