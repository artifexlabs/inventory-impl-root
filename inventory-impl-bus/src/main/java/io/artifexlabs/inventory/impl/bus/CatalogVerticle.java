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
package io.artifexlabs.inventory.impl.bus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.CatalogEntry;
import io.artifexlabs.inventory.api.ItemTag;
import io.artifexlabs.inventory.api.UpcCatalog;
import io.artifexlabs.inventory.api.UpcItemCreation;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.Gtin;

import io.vertx.core.json.JsonObject;

/**
 * External-catalog lookups and the one-shot create-from-UPC over the bus. The catalog is PREFILL, never a dependency: a
 * miss (or {@code off}) still creates the item from the request fields alone. The image is downloaded BEFORE the
 * transaction, and only from URLs our own adapters produced — never from client-supplied URLs (SSRF).
 */
public class CatalogVerticle extends ServiceVerticle {

  private final UpcCatalog catalog;

  public CatalogVerticle(BusGuard guard, UpcCatalog catalog) {
    super(BusActions.addressOf(BusActions.CATALOG_UPC), guard);
    this.catalog = catalog;

    on(BusActions.CATALOG_UPC, env -> {
      String gtin = requireGtin(env.data());
      if (this.catalog == UpcCatalog.OFF)
        throw BusServiceException.unavailable("catalog lookups are disabled (inventory.catalog=off)");
      return this.catalog.lookup(gtin).thenApply(o -> o.map(CatalogEntry::toJson)
          .orElseThrow(() -> BusServiceException.notFound("no catalog knows that code")));
    });

    on(BusActions.CATALOG_CREATE_ITEM, env -> {
      String gtin = requireGtin(env.data());
      JsonObject data = env.data();
      String principal = env.principal();
      return this.catalog.lookup(gtin).thenCompose(found -> {
        CatalogEntry entry = found.orElse(null);
        // request fields win; the catalog fills the gaps
        String name = firstNonBlank(data.getString("name"), entry == null ? null : entry.name());
        if (name == null)
          throw BusServiceException
              .badRequest("name is required — no catalog knows " + gtin + ", so nothing can prefill it");
        String displayName = firstNonBlank(data.getString("displayName"), displayNameFrom(entry, name));
        String type = firstNonBlank(data.getString("type"), "thing");
        String description = firstNonBlank(data.getString("description"), entry == null ? null : entry.description());
        Double weightGrams = data.getDouble("weightGrams") != null ? data.getDouble("weightGrams")
            : entry == null ? null : entry.weightGrams();
        var spec = new UpcItemCreation(gtin, name, displayName, type, description, weightGrams,
            data.getString("container"), catalogTags(entry));
        CompletionStage<Optional<CatalogImages.Image>> image = entry == null || entry.imageUrl() == null
            ? CompletableFuture.completedStage(Optional.empty())
            : CatalogImages.fetch(entry.imageUrl());
        // the write is one atomic storage operation; this verticle only
        // performs the external lookup that precedes it (PLAN.md Phase 21, ask 2)
        JsonObject specJson = new JsonObject().put("gtin13", spec.gtin13()).put("name", spec.name())
            .put("displayName", spec.displayName()).put("type", spec.type()).put("description", spec.description())
            .put("weightGrams", spec.weightGrams()).put("containerId", spec.containerId())
            .put("tags", new io.vertx.core.json.JsonArray(
                spec.tags().stream().map(io.artifexlabs.inventory.api.ItemTag::toJson).toList()));
        return image.thenCompose(img -> storage(env, StorageVerticle.ASSETS_CREATE_FROM_UPC, null,
            new JsonObject().put("spec", specJson)
                .put("filename", img.isPresent() ? "upc-" + gtin + imageExtension(img.get().contentType()) : null)
                .put("contentType", img.map(CatalogImages.Image::contentType).orElse(null))
                .put("bytes", img.map(CatalogImages.Image::bytes).orElse(null))));
      }).exceptionally(e -> {
        Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause()
            : e;
        if (cause instanceof IllegalStateException conflict)
          throw BusServiceException.conflict(conflict.getMessage());
        if (cause instanceof BusServiceException bse)
          throw bse;
        throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
      });
    });
  }

  /** brand + tags carry the catalog metadata; nothing when the catalog missed. */
  private static List<ItemTag> catalogTags(CatalogEntry entry) {
    List<ItemTag> tags = new ArrayList<>();
    if (entry != null) {
      if (entry.brand() != null)
        tags.add(new ItemTag("brand", entry.brand()));
      if (entry.category() != null)
        tags.add(new ItemTag("category", entry.category()));
      if (entry.sourceUrl() != null)
        tags.add(new ItemTag("source", entry.sourceUrl()));
    }
    return tags;
  }

  private static String displayNameFrom(CatalogEntry entry, String name) {
    if (entry == null || entry.brand() == null)
      return null;
    return name.toLowerCase(java.util.Locale.ROOT).startsWith(entry.brand().toLowerCase(java.util.Locale.ROOT)) ? null
        : entry.brand() + " " + name;
  }

  private static String imageExtension(String contentType) {
    if (contentType == null)
      return ".jpg";
    if (contentType.contains("png"))
      return ".png";
    if (contentType.contains("webp"))
      return ".webp";
    return ".jpg";
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a.trim() : b;
  }

  private static String requireGtin(JsonObject data) {
    return Gtin.normalize(data.getString("gtin"))
        .orElseThrow(() -> BusServiceException.badRequest("not a valid UPC/EAN (check digit or length)"));
  }
}
