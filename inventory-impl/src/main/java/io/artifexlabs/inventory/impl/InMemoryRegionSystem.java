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

import io.artifexlabs.inventory.api.Ulid;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import io.artifexlabs.inventory.api.AssetRegion;
import io.artifexlabs.inventory.api.AssetStore;
import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.RegionSystem;

import io.vertx.core.json.JsonObject;

/**
 * In-memory {@link RegionSystem} for dev and test profiles. Item creation and
 * containment delegate to the {@link InventorySystem} (which audits those
 * steps itself, as the pg implementation's transaction does).
 *
 * @author mykel
 *
 */
public class InMemoryRegionSystem implements RegionSystem {
  private final ConcurrentHashMap<String, AssetRegion> regions;
  private final InventorySystem items;
  private final AssetStore assets;
  private final AuditSink auditSink;
  private final String principal;

  public InMemoryRegionSystem(InventorySystem items, AssetStore assets, AuditSink auditSink, String principal) {
    this(new ConcurrentHashMap<>(), items, assets, auditSink, principal);
  }

  /** View constructor: shares the store, differs only in attribution. */
  private InMemoryRegionSystem(ConcurrentHashMap<String, AssetRegion> regions, InventorySystem items,
      AssetStore assets, AuditSink auditSink, String principal) {
    this.regions = regions;
    this.items = requireNonNull(items, "items");
    this.assets = requireNonNull(assets, "assets");
    this.auditSink = requireNonNull(auditSink, "auditSink");
    this.principal = requireNonNull(principal, "principal");
  }

  @Override
  public InMemoryRegionSystem actingAs(String principal) {
    return new InMemoryRegionSystem(this.regions, this.items.actingAs(principal),
        this.assets.actingAs(principal), this.auditSink, principal);
  }

  @Override
  public CompletionStage<List<AssetRegion>> listRegions(String assetId) {
    return CompletableFuture.completedStage(this.regions.values().stream()
        .filter(r -> r.assetId().equals(assetId)).sorted(java.util.Comparator.comparing(AssetRegion::id))
        .toList());
  }

  @Override
  public CompletionStage<Optional<AssetRegion>> createRegion(String assetId, double x, double y, double w,
      double h, String label) {
    return this.assets.get(assetId).thenCompose(asset -> {
      if (asset.isEmpty())
        return CompletableFuture.completedStage(Optional.empty());
      AssetRegion region = new AssetRegion(Ulid.next(), assetId, x, y, w, h, null, label, Instant.now());
      this.regions.put(region.id(), region);
      return audit("region.create", asset.get().info().itemId(),
          new JsonObject().put("assetId", assetId).put("regionId", region.id()))
          .thenApply(v -> Optional.of(region));
    });
  }

  @Override
  public CompletionStage<Boolean> deleteRegion(String regionId) {
    AssetRegion removed = this.regions.remove(regionId);
    if (removed == null)
      return CompletableFuture.completedStage(false);
    return this.assets.get(removed.assetId())
        .thenCompose(asset -> audit("region.delete",
            asset.map(a -> a.info().itemId()).orElse(removed.assetId()),
            new JsonObject().put("assetId", removed.assetId()).put("regionId", regionId)))
        .thenApply(v -> true);
  }

  @Override
  public CompletionStage<Optional<Item>> createItemFromRegion(String assetId, double x, double y, double w,
      double h, String name, String type, String containerId) {
    return this.assets.get(assetId).thenCompose(asset -> {
      if (asset.isEmpty())
        return CompletableFuture.completedStage(Optional.empty());
      AssetRegion region = new AssetRegion(Ulid.next(), assetId, x, y, w, h, null, name, Instant.now());
      this.regions.put(region.id(), region);
      return promote(region, name, type, containerId);
    });
  }

  @Override
  public CompletionStage<Optional<Item>> makeItemFromRegion(String regionId, String name, String type,
      String containerId) {
    AssetRegion region = this.regions.get(regionId);
    if (region == null || region.itemId() != null)
      return CompletableFuture.completedStage(Optional.empty());
    return promote(region, name, type, containerId);
  }

  /** Create the item, contain it, link the region, audit — the shared tail. */
  private CompletionStage<Optional<Item>> promote(AssetRegion region, String name, String type,
      String containerId) {
    return this.items.createItem(name, null, type)
        .thenCompose(item -> (containerId == null ? CompletableFuture.completedStage(true)
            : this.items.addToContainer(containerId, item.getId())).thenCompose(contained -> {
              this.regions.put(region.id(), new AssetRegion(region.id(), region.assetId(), region.x(),
                  region.y(), region.w(), region.h(), item.getId(), name, region.timestamp()));
              return audit("item.create-from-region", item.getId(),
                  new JsonObject().put("assetId", region.assetId()).put("regionId", region.id())
                      .put("containerId", containerId))
                  .thenApply(v -> Optional.of(item));
            }));
  }

  private CompletionStage<Void> audit(String action, String targetId, JsonObject details) {
    return this.auditSink
        .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), this.principal, action, targetId, details));
  }
}
