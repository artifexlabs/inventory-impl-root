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

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataInfo;
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.MediaKind;
import io.artifexlabs.inventory.api.Ulid;
import io.artifexlabs.inventory.api.AuditSink;

import io.vertx.core.json.JsonObject;

/**
 * In-memory {@link DataSystem} for dev and test profiles: the behavioral reference the Postgres implementation must
 * match, twin-for-twin.
 *
 * Paths are held as plain strings here. The dictionary encoding the Pg backend uses (path components and mime types as
 * integer ids) is a STORAGE choice, not a behavior — nothing observable through this interface reveals which one is
 * underneath, which is exactly the property the parity tests pin.
 */
public class InMemoryDataSystem implements DataSystem {

  /** itemId -> (path -> entry). Ordered per medium so listings are stable. */
  private final ConcurrentHashMap<String, Map<String, DataEntry>> manifests;
  private final InventorySystem items;
  private final AuditSink auditSink;
  private final String principal;

  public InMemoryDataSystem(InventorySystem items, AuditSink auditSink, String principal) {
    this(new ConcurrentHashMap<>(), items, auditSink, principal);
  }

  /** View constructor: shares the store, differs only in attribution. */
  private InMemoryDataSystem(ConcurrentHashMap<String, Map<String, DataEntry>> manifests, InventorySystem items,
      AuditSink auditSink, String principal)
  {
    this.manifests = manifests;
    this.items = requireNonNull(items, "items");
    this.auditSink = requireNonNull(auditSink, "auditSink");
    this.principal = requireNonNull(principal, "principal");
  }

  @Override
  public InMemoryDataSystem actingAs(String principal) {
    return new InMemoryDataSystem(this.manifests, this.items.actingAs(principal), this.auditSink, principal);
  }

  @Override
  public CompletionStage<Optional<Integer>> replaceManifest(String itemId, List<DataEntry> entries) {
    List<DataEntry> submitted = entries == null ? List.of() : List.copyOf(entries);
    return this.items.getItem(itemId).thenCompose(found -> {
      if (found.isEmpty() || !holdsData(found.get()))
        return CompletableFuture.completedStage(Optional.<Integer>empty());
      return retireArchives(itemId).thenCompose(v -> store(itemId, submitted))
          .thenCompose(count -> audit("data.replace", itemId,
              new JsonObject().put("entries", count).put("bytes", totalBytes(submitted)).put("archives",
                  (int) submitted.stream().filter(DataEntry::isArchive).count()))
              .thenApply(ignored -> Optional.of(count)));
    });
  }

  /**
   * Store one scope's entries, minting an item for every archive and recursing into it. The archive stays a row in this
   * manifest AND becomes a contained item carrying its own.
   */
  private CompletionStage<Integer> store(String itemId, List<DataEntry> entries) {
    Map<String, DataEntry> scope = new java.util.LinkedHashMap<>();
    for (DataEntry e : entries)
      scope.put(e.path(), e);
    this.manifests.put(itemId, scope);

    CompletionStage<Integer> counted = CompletableFuture.completedStage(entries.size());
    for (DataEntry entry : entries) {
      if (!entry.isArchive())
        continue;
      counted = counted.thenCompose(running -> mintArchive(itemId, entry)
          .thenCompose(archiveId -> store(archiveId, entry.archiveContents()).thenApply(inner -> running + inner)));
    }
    return counted;
  }

  /** An archive is a file AND a container: it earns an item of its own. */
  private CompletionStage<String> mintArchive(String containerId, DataEntry entry) {
    return this.items.createItem(entry.fileName(), entry.fileName(), "archive").thenCompose(created -> {
      Item marked = DefaultItem.builder(created).dataInfo(new DataInfo(MediaKind.PHYSICAL_MEDIA, false, true, null))
          .build();
      return this.items.updateItem(marked).thenCompose(ok -> this.items.addToContainer(containerId, created.getId()))
          .thenApply(ok -> created.getId());
    });
  }

  /** Re-describing a medium drops the archive items the last description made. */
  private CompletionStage<Void> retireArchives(String itemId) {
    Map<String, DataEntry> previous = this.manifests.get(itemId);
    if (previous == null || previous.isEmpty())
      return CompletableFuture.completedStage(null);
    List<String> archiveNames = previous.values().stream().filter(DataEntry::isArchive).map(DataEntry::fileName)
        .toList();
    if (archiveNames.isEmpty())
      return CompletableFuture.completedStage(null);
    return this.items.getItem(itemId).thenCompose(medium -> {
      if (medium.isEmpty())
        return CompletableFuture.<Void>completedStage(null);
      CompletionStage<Void> chain = CompletableFuture.completedStage(null);
      for (Item child : medium.get().getContainedItems().orElse(java.util.Set.of())) {
        boolean wasArchive = child.getDataInfo().map(DataInfo::archive).orElse(false)
            && archiveNames.contains(child.getName());
        if (!wasArchive)
          continue;
        String childId = child.getId();
        chain = chain.thenCompose(v -> retireArchives(childId))
            .thenCompose(v -> this.items.deleteItem(childId).thenApply(ignored -> (Void) null));
        this.manifests.remove(childId);
      }
      return chain;
    });
  }

  @Override
  public CompletionStage<Integer> renamePath(String itemId, String fromPath, String toPath) {
    String from = DataEntry.normalizePath(fromPath);
    String to = DataEntry.normalizePath(toPath);
    Map<String, DataEntry> scope = this.manifests.get(itemId);
    if (scope == null)
      return CompletableFuture.completedStage(0);

    Map<String, DataEntry> rewritten = new java.util.LinkedHashMap<>();
    int touched = 0;
    for (Map.Entry<String, DataEntry> e : scope.entrySet()) {
      String path = e.getKey();
      String moved = repath(path, from, to);
      if (moved == null) {
        rewritten.put(path, e.getValue());
        continue;
      }
      DataEntry old = e.getValue();
      // the content hash is untouched: the file did not change, its name did
      rewritten.put(moved, new DataEntry(moved, old.sizeBytes(), old.hashAlgorithm(), old.hash(), old.mimeType(),
          old.modifiedAt(), old.archiveContents()));
      touched++;
    }
    if (touched == 0)
      return CompletableFuture.completedStage(0);
    this.manifests.put(itemId, rewritten);
    final int count = touched;
    return audit("data.rename", itemId, new JsonObject().put("from", from).put("to", to).put("entries", count))
        .thenApply(v -> count);
  }

  /** The renamed path, or null when this path is not under {@code from}. */
  private static String repath(String path, String from, String to) {
    if (path.equals(from))
      return to;
    if (path.startsWith(from + "/"))
      return to + path.substring(from.length());
    return null;
  }

  @Override
  public CompletionStage<List<DataEntry>> entriesOf(String itemId, String query, int page, int size) {
    Map<String, DataEntry> scope = this.manifests.getOrDefault(itemId, Map.of());
    String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    List<DataEntry> matched = scope.values().stream()
        .filter(e -> needle.isEmpty() || e.path().toLowerCase(Locale.ROOT).contains(needle))
        .sorted(Comparator.comparing(DataEntry::path)).toList();
    int from = Math.max(0, page) * Math.max(1, size);
    if (from >= matched.size())
      return CompletableFuture.completedStage(List.of());
    return CompletableFuture.completedStage(matched.subList(from, Math.min(matched.size(), from + Math.max(1, size))));
  }

  @Override
  public CompletionStage<ManifestSummary> summaryOf(String itemId) {
    Map<String, DataEntry> scope = this.manifests.get(itemId);
    if (scope == null || scope.isEmpty())
      return CompletableFuture.completedStage(ManifestSummary.EMPTY);
    return CompletableFuture.completedStage(new ManifestSummary(scope.size(), totalBytes(scope.values()),
        (int) scope.values().stream().filter(DataEntry::isArchive).count()));
  }

  @Override
  public CompletionStage<List<DataLocation>> findByHash(HashAlgorithm algorithm, String hash) {
    String wanted = hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
    List<Map.Entry<String, DataEntry>> hits = new ArrayList<>();
    for (Map.Entry<String, Map<String, DataEntry>> medium : this.manifests.entrySet())
      for (DataEntry entry : medium.getValue().values())
        if (entry.hashAlgorithm() == algorithm && entry.hash().equals(wanted))
          hits.add(Map.entry(medium.getKey(), entry));
    return locate(hits);
  }

  @Override
  public CompletionStage<List<DataLocation>> findMirrorsOf(String itemId) {
    Map<String, DataEntry> mine = this.manifests.getOrDefault(itemId, Map.of());
    if (mine.isEmpty())
      return CompletableFuture.completedStage(List.of());
    List<Map.Entry<String, DataEntry>> hits = new ArrayList<>();
    for (Map.Entry<String, Map<String, DataEntry>> medium : this.manifests.entrySet()) {
      if (medium.getKey().equals(itemId))
        continue;
      for (DataEntry entry : medium.getValue().values()) {
        DataEntry same = mine.get(entry.path());
        // same place AND same content: the pair the Pg backend indexes
        if (same != null && same.hashAlgorithm() == entry.hashAlgorithm() && same.hash().equals(entry.hash()))
          hits.add(Map.entry(medium.getKey(), entry));
      }
    }
    return locate(hits);
  }

  /** Attach the medium's name to each hit, so a result reads as a place. */
  private CompletionStage<List<DataLocation>> locate(List<Map.Entry<String, DataEntry>> hits) {
    CompletionStage<List<DataLocation>> chain = CompletableFuture.completedStage(new ArrayList<>());
    for (Map.Entry<String, DataEntry> hit : hits)
      chain = chain.thenCompose(acc -> this.items.getItem(hit.getKey()).thenApply(item -> {
        acc.add(new DataLocation(hit.getKey(), item.map(Item::getName).orElse(hit.getKey()), hit.getValue().path(),
            hit.getValue().sizeBytes()));
        return acc;
      }));
    return chain.thenApply(acc -> acc.stream()
        .sorted(Comparator.comparing(DataLocation::itemName).thenComparing(DataLocation::path)).toList());
  }

  private static boolean holdsData(Item item) {
    return item.getDataInfo().map(DataInfo::holdsData).orElse(false);
  }

  private static long totalBytes(Iterable<DataEntry> entries) {
    long total = 0L;
    for (DataEntry e : entries)
      total += e.sizeBytes();
    return total;
  }

  private CompletionStage<Void> audit(String action, String targetId, JsonObject details) {
    return this.auditSink
        .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), this.principal, action, targetId, details));
  }
}
