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

import io.artifexlabs.inventory.api.AssetInfo;
import io.artifexlabs.inventory.api.AssetStore;
import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.InventorySystem;

import io.vertx.core.json.JsonObject;

/**
 * In-memory {@link AssetStore} for dev and test profiles. Assets whose item
 * has since been deleted are pruned lazily on read.
 *
 * @author mykel
 *
 */
public class InMemoryAssetStore implements AssetStore {
  private final ConcurrentHashMap<String, StoredAsset> assets;
  private final InventorySystem items;
  private final AuditSink auditSink;
  private final String principal;

  public InMemoryAssetStore(InventorySystem items, AuditSink auditSink, String principal) {
    this(new ConcurrentHashMap<>(), items, auditSink, principal);
  }

  /** View constructor: shares the store, differs only in attribution. */
  private InMemoryAssetStore(ConcurrentHashMap<String, StoredAsset> assets, InventorySystem items,
      AuditSink auditSink, String principal) {
    this.assets = assets;
    this.items = requireNonNull(items, "items");
    this.auditSink = requireNonNull(auditSink, "auditSink");
    this.principal = requireNonNull(principal, "principal");
  }

  @Override
  public InMemoryAssetStore actingAs(String principal) {
    return new InMemoryAssetStore(this.assets, this.items.actingAs(principal), this.auditSink, principal);
  }

  @Override
  public CompletionStage<Optional<AssetInfo>> store(String itemId, String filename, String contentType,
      byte[] data, io.artifexlabs.inventory.api.LatLong explicitCoordinates, String kind) {
    return this.items.getItem(itemId).thenCompose(item -> {
      if (item.isEmpty())
        return CompletableFuture.completedStage(Optional.empty());
      io.artifexlabs.inventory.api.LatLong coords = explicitCoordinates != null ? explicitCoordinates
          : ExifGps.extract(data).orElse(null);
      Instant now = Instant.now();
      AssetInfo info = new AssetInfo(Ulid.next(), itemId, filename, contentType, data.length, now, now,
          coords, kind);
      this.assets.put(info.id(), new StoredAsset(info, data.clone()));
      return audit("asset.attach", info).thenApply(v -> Optional.of(info));
    });
  }

  @Override
  public CompletionStage<Optional<PhotoItem>> createItemFromPhoto(String name, String displayName, String type,
      String containerId, String filename, String contentType, byte[] data,
      io.artifexlabs.inventory.api.LatLong explicitCoordinates, String kind) {
    io.artifexlabs.inventory.api.LatLong coords = explicitCoordinates != null ? explicitCoordinates
        : ExifGps.extract(data).orElse(null);
    CompletionStage<Boolean> containerOk = containerId == null ? CompletableFuture.completedStage(true)
        : this.items.getItem(containerId).thenApply(Optional::isPresent);
    return containerOk.thenCompose(ok -> {
      if (!ok)
        return CompletableFuture.completedStage(Optional.empty());
      return this.items.createItem(name, displayName, type).thenCompose(created -> {
        CompletionStage<?> pinned = coords == null ? CompletableFuture.completedStage(null)
            : this.items
                .updateItem(io.artifexlabs.inventory.api.DefaultItem.builder(created).coordinates(coords).build());
        return pinned
            .thenCompose(v -> containerId == null ? CompletableFuture.completedStage(null)
                : this.items.addToContainer(containerId, created.getId()))
            .thenCompose(v -> store(created.getId(), filename, contentType, data, explicitCoordinates, kind))
            .thenCompose(asset -> this.items.getItem(created.getId())
                .thenApply(fresh -> asset.map(a -> new PhotoItem(fresh.orElse(created), a))));
      });
    });
  }

  @Override
  public CompletionStage<Optional<PhotoItem>> createItemFromUpc(io.artifexlabs.inventory.api.UpcItemCreation spec,
      String imageFilename, String imageContentType, byte[] imageBytes) {
    // check container then marker BEFORE any state changes, in the same
    // precedence as the Pg transaction, so both backends refuse identically
    CompletionStage<Boolean> containerOk = spec.containerId() == null ? CompletableFuture.completedStage(true)
        : this.items.getItem(spec.containerId()).thenApply(Optional::isPresent);
    return containerOk.thenCompose(containerPresent -> {
      if (!containerPresent)
        return CompletableFuture.completedStage(Optional.empty());
      return this.items.findByIdentity("upc", spec.gtin13()).thenCompose(claimed -> {
        if (claimed.isPresent())
          return CompletableFuture.failedStage(new IllegalStateException(
              "identity upc:" + spec.gtin13() + " already claims item " + claimed.get().getId()));
        return this.items.createItem(spec.name(), spec.displayName(), spec.type()).thenCompose(created -> {
          var enriched = io.artifexlabs.inventory.api.DefaultItem.builder(created)
              .description(spec.description())
              .weight(spec.weightGrams() == null ? null
                  : new io.artifexlabs.inventory.api.Weight(spec.weightGrams()))
              .build();
          CompletionStage<?> flow = this.items.updateItem(enriched);
          if (spec.containerId() != null)
            flow = flow.thenCompose(v -> this.items.addToContainer(spec.containerId(), created.getId()));
          flow = flow.thenCompose(
              v -> this.items.addIdentity(created.getId(),
                  new io.artifexlabs.inventory.api.ItemIdentity("upc", spec.gtin13())));
          for (var tag : spec.tags())
            flow = flow.thenCompose(v -> this.items.tag(created.getId(), tag));
          CompletionStage<Optional<AssetInfo>> asset = flow.thenCompose(v -> imageBytes == null
              ? CompletableFuture.completedStage(Optional.<AssetInfo>empty())
              : store(created.getId(), imageFilename, imageContentType, imageBytes, null,
                  AssetInfo.KIND_PHOTO));
          return asset.thenCompose(a -> this.items.getItem(created.getId())
              .thenApply(fresh -> Optional.of(new PhotoItem(fresh.orElse(created), a.orElse(null)))));
        });
      });
    });
  }

  /** A superseded version: what the asset was, held apart from the live map. */
  public record ArchivedAsset(String archiveId, AssetInfo info, byte[] data, Instant archivedAt,
      String auditEventId) {
  }

  private final java.util.List<ArchivedAsset> archive =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  /** Superseded versions of an asset, oldest first. (Method: CDI proxies delegate methods, not fields.) */
  public java.util.List<ArchivedAsset> archivedVersions(String assetId) {
    synchronized (this.archive) {
      return this.archive.stream().filter(a -> a.info().id().equals(assetId)).toList();
    }
  }

  @Override
  public CompletionStage<Optional<AssetInfo>> replace(String assetId, String filename, String contentType,
      byte[] data, io.artifexlabs.inventory.api.LatLong explicitCoordinates) {
    StoredAsset previous = this.assets.get(assetId);
    if (previous == null)
      return CompletableFuture.completedStage(Optional.empty());
    io.artifexlabs.inventory.api.LatLong coords = explicitCoordinates != null ? explicitCoordinates
        : ExifGps.extract(data).orElse(null);
    Instant now = Instant.now();
    AssetInfo next = previous.info().revised(filename, contentType, data.length, now, coords);
    this.assets.put(assetId, new StoredAsset(next, data));
    // the superseded version goes to the archive; the audit event carries a
    // REFERENCE — blobs must never ride the replay feed consumers page
    String archiveId = Ulid.next();
    String auditEventId = Ulid.next();
    this.archive.add(new ArchivedAsset(archiveId, previous.info(), previous.data(), now, auditEventId));
    io.vertx.core.json.JsonObject details = new io.vertx.core.json.JsonObject()
        .put("replaced", previous.info().toJson()).put("archiveId", archiveId)
        .put("current", next.toJson());
    return this.auditSink
        .record(new io.artifexlabs.inventory.api.DefaultAuditEvent(auditEventId, now, this.principal,
            "asset.replace", assetId, details))
        .thenApply(v -> Optional.of(next));
  }

  @Override
  public CompletionStage<Optional<StoredAsset>> get(String assetId) {
    StoredAsset a = this.assets.get(assetId);
    if (a == null)
      return CompletableFuture.completedStage(Optional.empty());
    return this.items.getItem(a.info().itemId()).thenApply(item -> {
      if (item.isEmpty()) {
        this.assets.remove(assetId);
        return Optional.empty();
      }
      return Optional.of(a);
    });
  }

  @Override
  public CompletionStage<List<AssetInfo>> listFor(String itemId) {
    return this.items.getItem(itemId).thenApply(item -> {
      if (item.isEmpty()) {
        this.assets.values().removeIf(a -> a.info().itemId().equals(itemId));
        return List.of();
      }
      return this.assets.values().stream().map(StoredAsset::info).filter(i -> i.itemId().equals(itemId))
          .sorted(java.util.Comparator.comparing(AssetInfo::attachedAt)).toList();
    });
  }

  @Override
  public CompletionStage<Boolean> delete(String assetId) {
    StoredAsset removed = this.assets.remove(assetId);
    return removed == null ? CompletableFuture.completedStage(false)
        : audit("asset.delete", removed.info()).thenApply(v -> true);
  }

  private CompletionStage<Void> audit(String action, AssetInfo info) {
    return this.auditSink.record(new DefaultAuditEvent(Ulid.next(), Instant.now(), this.principal, action,
        info.itemId(), new JsonObject().put("assetId", info.id()).put("filename", info.filename())));
  }
}
