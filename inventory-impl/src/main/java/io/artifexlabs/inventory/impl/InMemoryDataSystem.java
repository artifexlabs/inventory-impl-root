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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataHashing;
import io.artifexlabs.inventory.api.DataInfo;
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.DataTree;
import io.artifexlabs.inventory.api.MerkleHash;
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
  /**
   * itemId -> (archive's path in THAT scope -> the item id carrying its contents). The Postgres backend keeps this on
   * {@code data_entries.archive_item_id}; here it is a map, because an archive's identity is its path and the item that
   * path minted, and nothing else in the manifest records the second half.
   */
  private final ConcurrentHashMap<String, Map<String, String>> archiveIds;
  /**
   * itemId -> (path -> what went wrong). Lives HERE, not on the hashing twin, because that is where Postgres keeps it:
   * {@code hash_state = 2} is a column on {@code data_entries}, so a manifest already knows which of its files could
   * not be read. Putting it anywhere else would make the rollup unable to tell damage from work still to do.
   */
  private final ConcurrentHashMap<String, Map<String, Damage>> damage;
  /**
   * itemId -> (directory path -> its content rollup): the in-memory {@code data_dirs}. Written only by {@link #rollUp},
   * exactly as the Postgres sweep writes those columns, so MERKLE and CONTENT matching becomes available at the same
   * moment on both backends rather than one being quietly ahead.
   */
  private final ConcurrentHashMap<String, Map<String, DataTree.Rollup>> rollups;
  private final InventorySystem items;
  private final AuditSink auditSink;
  private final String principal;

  public InMemoryDataSystem(InventorySystem items, AuditSink auditSink, String principal) {
    this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
        items, auditSink, principal);
  }

  /** View constructor: shares the store, differs only in attribution. */
  private InMemoryDataSystem(ConcurrentHashMap<String, Map<String, DataEntry>> manifests,
      ConcurrentHashMap<String, Map<String, String>> archiveIds, ConcurrentHashMap<String, Map<String, Damage>> damage,
      ConcurrentHashMap<String, Map<String, DataTree.Rollup>> rollups, InventorySystem items, AuditSink auditSink,
      String principal)
  {
    this.manifests = manifests;
    this.archiveIds = archiveIds;
    this.damage = damage;
    this.rollups = rollups;
    this.items = requireNonNull(items, "items");
    this.auditSink = requireNonNull(auditSink, "auditSink");
    this.principal = requireNonNull(principal, "principal");
  }

  @Override
  public InMemoryDataSystem actingAs(String principal) {
    return new InMemoryDataSystem(this.manifests, this.archiveIds, this.damage, this.rollups,
        this.items.actingAs(principal), this.auditSink, principal);
  }

  /** What a worker could not read, and how often it has tried. */
  record Damage(String reason, int attempts, Instant firstSeen, Instant lastAttempt) {
  }

  @Override
  public CompletionStage<Optional<Integer>> replaceManifest(String itemId, List<DataEntry> entries) {
    List<DataEntry> submitted = entries == null ? List.of() : List.copyOf(entries);
    return this.items.getItem(itemId).thenCompose(found -> {
      if (found.isEmpty() || !holdsData(found.get()))
        return CompletableFuture.completedStage(Optional.<Integer>empty());
      // no up-front retire: store() reuses the item at an archive path that came
      // back and reaps only what this description dropped, so the hashes inside a
      // re-listed archive survive exactly as the medium's own hashes do
      return store(itemId, submitted)
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
    // Carry completed hashes across a re-describe. Hashing a medium takes weeks;
    // re-scanning it must not throw that away. "Same file" is (path, size,
    // mtime) — a file whose size or timestamp moved is a different file that
    // happens to share a name, and keeping its digest would be a lie nothing
    // could later detect.
    Map<String, DataEntry> previous = this.manifests.getOrDefault(itemId, Map.of());
    Map<String, DataEntry> scope = new java.util.LinkedHashMap<>();
    for (DataEntry e : entries)
      scope.put(e.path(), carryHash(previous.get(e.path()), e));
    this.manifests.put(itemId, scope);
    // The tree changed, so the content rollup describing it is now a statement
    // about a manifest that no longer exists. Postgres gets this by rewriting
    // data_dirs with structure only, leaving the merkle columns null.
    this.rollups.remove(itemId);
    // Damage is carried exactly as hashes are: a file still described the same
    // way is still the same file, and its damage is still the repair target it
    // was. Anything that changed or left the manifest loses its record, because
    // the record was about bytes that are no longer being claimed to exist.
    Map<String, Damage> hurt = this.damage.get(itemId);
    if (hurt != null)
      hurt.keySet().removeIf(path -> {
        DataEntry old = previous.get(path);
        DataEntry fresh = scope.get(path);
        return fresh == null || old == null || old.sizeBytes() != fresh.sizeBytes()
            || !java.util.Objects.equals(old.modified().orElse(null), fresh.modified().orElse(null));
      });

    // Reuse before mint. An archive still at the same path is the SAME archive,
    // and its contents live in a scope keyed by its item id — so keeping that id
    // is the whole of what keeps the hashes inside it across a re-describe.
    Map<String, String> wasAt = this.archiveIds.getOrDefault(itemId, Map.of());
    Map<String, String> nowAt = new java.util.LinkedHashMap<>();
    CompletionStage<Integer> counted = CompletableFuture.completedStage(entries.size());
    for (DataEntry entry : entries) {
      if (!entry.isArchive())
        continue;
      counted = counted.thenCompose(running -> {
        String existing = wasAt.get(entry.path());
        CompletionStage<String> resolved = existing == null ? mintArchive(itemId, entry)
            : CompletableFuture.completedStage(existing);
        return resolved.thenCompose(archiveId -> {
          nowAt.put(entry.path(), archiveId);
          return store(archiveId, entry.archiveContents()).thenApply(inner -> running + inner);
        });
      });
    }
    return counted.thenCompose(total -> {
      this.archiveIds.put(itemId, nowAt);
      CompletionStage<Void> reaped = CompletableFuture.completedStage(null);
      for (Map.Entry<String, String> gone : wasAt.entrySet())
        if (!nowAt.containsValue(gone.getValue()))
          reaped = reaped.thenCompose(v -> retireArchive(gone.getValue()));
      return reaped.thenApply(v -> total);
    });
  }

  /** Give {@code fresh} the old digest when it truly describes the same file, otherwise leave it unhashed. */
  private static DataEntry carryHash(DataEntry old, DataEntry fresh) {
    if (old == null || old.hash() == null || fresh.hash() != null)
      return fresh;
    boolean sameFile = old.sizeBytes() == fresh.sizeBytes()
        && java.util.Objects.equals(old.modified().orElse(null), fresh.modified().orElse(null));
    return sameFile
        ? new DataEntry(fresh.path(), fresh.sizeBytes(), old.hashAlgorithm(), old.hash(), fresh.mimeType(),
            fresh.modifiedAt(), fresh.archiveContents())
        : fresh;
  }

  // ---- package-private hooks for InMemoryDataHashing --------------------
  //
  // The hashing twin needs to read a manifest and to attach a digest to one
  // entry. Exposing that here rather than duplicating the store keeps ONE copy
  // of the in-memory state: two structures claiming to hold the same manifest
  // would drift, and the parity tests would then be comparing Postgres against
  // whichever copy the test happened to touch.

  /** This medium's entries, or empty. */
  List<DataEntry> manifestOf(String itemId) {
    return List.copyOf(this.manifests.getOrDefault(itemId, Map.of()).values());
  }

  /** One entry by path, or null. */
  DataEntry entryAt(String itemId, String path) {
    return this.manifests.getOrDefault(itemId, Map.of()).get(path);
  }

  /** What could not be read on this medium, by path. Empty is the common and happy case. */
  Map<String, Damage> damageOn(String itemId) {
    return this.damage.getOrDefault(itemId, Map.of());
  }

  /** Record damage, or another attempt at damage already known — one row per file, never one per attempt. */
  void recordDamage(String itemId, String path, String reason) {
    Map<String, Damage> hurt = this.damage.computeIfAbsent(itemId, k -> new java.util.concurrent.ConcurrentHashMap<>());
    Instant now = Instant.now();
    Damage before = hurt.get(path);
    hurt.put(path, before == null ? new Damage(reason, 1, now, now)
        : new Damage(reason, before.attempts() + 1, before.firstSeen(), now));
  }

  /** Attach a computed digest, leaving everything else about the entry alone. */
  void applyHash(String itemId, String path, HashAlgorithm algorithm, String hash) {
    Map<String, DataEntry> scope = this.manifests.get(itemId);
    if (scope == null)
      return;
    DataEntry old = scope.get(path);
    if (old == null)
      return;
    scope.put(path, new DataEntry(old.path(), old.sizeBytes(), algorithm, hash, old.mimeType(), old.modifiedAt(),
        old.archiveContents()));
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

  /**
   * Drop one archive item and everything nested inside it — used only for an archive the new description genuinely
   * dropped. Matching is by ID, not by name: {@link #mintArchive} names an archive after its basename, so two
   * {@code backup.zip} on one medium cannot be told apart that way.
   */
  private CompletionStage<Void> retireArchive(String archiveId) {
    Map<String, String> nested = this.archiveIds.remove(archiveId);
    this.manifests.remove(archiveId);
    CompletionStage<Void> chain = CompletableFuture.completedStage(null);
    if (nested != null)
      for (String inner : nested.values())
        chain = chain.thenCompose(v -> retireArchive(inner));
    return chain.thenCompose(v -> this.items.deleteItem(archiveId).thenApply(ignored -> (Void) null));
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
    // the archive index is keyed by path, so it moves with the rows. The
    // Postgres backend gets this free — archive_item_id rides on the entry
    // itself — and without it an archive that was merely RENAMED would be
    // reaped and re-minted on the next re-describe, losing everything hashed
    // inside it.
    Map<String, String> archives = this.archiveIds.get(itemId);
    if (archives != null) {
      Map<String, String> movedArchives = new java.util.LinkedHashMap<>();
      for (Map.Entry<String, String> a : archives.entrySet()) {
        String moved = repath(a.getKey(), from, to);
        movedArchives.put(moved == null ? a.getKey() : moved, a.getValue());
      }
      this.archiveIds.put(itemId, movedArchives);
    }
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
        // an unhashed entry matches nothing: we do not know its content yet, and
        // guessing would be worse than omitting it
        if (entry.hash() != null && entry.hashAlgorithm() == algorithm && entry.hash().equals(wanted))
          hits.add(Map.entry(medium.getKey(), entry));
    return locate(hits);
  }

  @Override
  public CompletionStage<List<SectionMatch>> findDuplicateSections(SectionQuery q) {
    // Derives its tree through the SAME DataTree the Pg backend uses. This twin
    // exists to prove that one; two hand-rolled tree walks would let the pair
    // agree by luck and diverge the moment either was edited.
    Map<String, List<SectionLocation>> places = new LinkedHashMap<>();
    Map<String, long[]> sizes = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, DataEntry>> medium : this.manifests.entrySet()) {
      String itemId = medium.getKey();
      List<DataEntry> entries = List.copyOf(medium.getValue().values());
      Map<String, DataTree.Rollup> rolled = this.rollups.getOrDefault(itemId, Map.of());
      for (DataTree.Node node : DataTree.build(HashAlgorithm.SHA256, entries)) {
        if (node.subtreeFiles() < q.minFiles() || node.subtreeBytes() < q.minBytes() || node.depth() < q.minDepth())
          continue;
        byte[] ident = identityOf(q, node, rolled.get(node.path()));
        if (ident == null)
          continue; // MERKLE and CONTENT do not exist until the files are hashed
        // The grouping key carries the DAMAGE for the content flavours. An
        // unreadable file contributes nothing to a merkle, so a damaged folder
        // and an intact one holding only its readable half hash the same — and
        // calling those two copies of each other would be a lie with a digest
        // attached. STRUCTURE is read off the manifest and never sees damage at
        // all, so it groups on identity alone.
        byte[] hurt = q.match() == Match.STRUCTURE || rolled.get(node.path()) == null ? null
            : rolled.get(node.path()).unreadableHash();
        String hex = HexFormat.of().formatHex(ident);
        String key = hurt == null ? hex : hex + ":" + HexFormat.of().formatHex(hurt);
        places.computeIfAbsent(key, k -> new ArrayList<>())
            .add(new SectionLocation(itemId, nameOf(itemId), node.path(), node.depth()));
        sizes.computeIfAbsent(key, k -> new long[] {
            node.subtreeFiles(), node.subtreeBytes()
        });
      }
    }
    List<SectionMatch> out = new ArrayList<>();
    for (Map.Entry<String, List<SectionLocation>> e : places.entrySet()) {
      List<SectionLocation> at = e.getValue();
      if (at.size() < 2)
        continue;
      long media = at.stream().map(SectionLocation::itemId).distinct().count();
      boolean keep = switch (q.scope()) {
      case ACROSS_MEDIA -> media > 1;
      case WITHIN_MEDIUM -> at.size() > media; // the same subtree twice on ONE medium
      case BOTH -> true;
      };
      if (!keep || (q.itemId() != null && at.stream().noneMatch(l -> l.itemId().equals(q.itemId()))))
        continue;
      long[] sz = sizes.get(e.getKey());
      // the reported identity is the subtree's, not the composite grouping key
      out.add(new SectionMatch(e.getKey().split(":")[0], sz[0], sz[1], List.copyOf(at)));
    }
    // biggest first, same order the Pg query produces
    out.sort(Comparator.comparingLong(SectionMatch::subtreeBytes).reversed()
        .thenComparing(Comparator.comparingLong(SectionMatch::subtreeFiles).reversed())
        .thenComparing(SectionMatch::hash));
    int from = Math.min(Math.max(q.page(), 0) * Math.max(q.size(), 1), out.size());
    int to = Math.min(from + Math.max(q.size(), 1), out.size());
    return CompletableFuture.completedStage(List.copyOf(out.subList(from, to)));
  }

  /** STRUCTURE is available at once; the other two stay null until the hasher has run AND the rollup has swept. */
  private static byte[] identityOf(SectionQuery q, DataTree.Node node, DataTree.Rollup rolled) {
    return switch (q.match()) {
    case STRUCTURE -> node.structureHash();
    case MERKLE -> rolled == null ? null : rolled.merkleHash();
    case CONTENT -> rolled == null ? null : rolled.merkleContentHash();
    };
  }

  private String nameOf(String itemId) {
    try {
      return this.items.getItem(itemId).toCompletableFuture().get().map(Item::getName).orElse(itemId);
    } catch (Exception e) {
      Thread.currentThread().interrupt();
      return itemId;
    }
  }

  @Override
  public CompletionStage<List<MediumOverlap>> findOverlappingMedia(String itemId) {
    Map<String, DataEntry> mine = this.manifests.getOrDefault(itemId, Map.of());
    long ours = mine.values().stream().filter(e -> e.hash() != null).count();
    if (ours == 0)
      return CompletableFuture.completedStage(List.of());
    byte[] ourRoot = rootMerkle(itemId);
    List<MediumOverlap> out = new ArrayList<>();
    for (Map.Entry<String, Map<String, DataEntry>> medium : this.manifests.entrySet()) {
      if (medium.getKey().equals(itemId))
        continue;
      long shared = 0, sharedBytes = 0, theirs = 0;
      for (DataEntry entry : medium.getValue().values()) {
        if (entry.hash() == null)
          continue;
        theirs++;
        DataEntry same = mine.get(entry.path());
        // same place AND same content — the rule findMirrorsOf had, which did
        // not stop being useful, it stopped being the only one. Two UNHASHED
        // entries at one path are not evidence of anything: same location says
        // nothing about content, which is the whole point of the digest.
        if (same != null && same.hash() != null && same.hashAlgorithm() == entry.hashAlgorithm()
            && same.hash().equals(entry.hash())) {
          shared++;
          sharedBytes += entry.sizeBytes();
        }
      }
      if (shared == 0)
        continue;
      byte[] theirRoot = rootMerkle(medium.getKey());
      boolean identical = ourRoot != null && theirRoot != null && java.util.Arrays.equals(ourRoot, theirRoot);
      out.add(new MediumOverlap(medium.getKey(), nameOf(medium.getKey()), shared, sharedBytes, theirs, ours, identical,
          shared == ours));
    }
    out.sort(Comparator.comparingLong(MediumOverlap::sharedBytes).reversed()
        .thenComparing(Comparator.comparingLong(MediumOverlap::sharedEntries).reversed())
        .thenComparing(MediumOverlap::itemName));
    return CompletableFuture.completedStage(List.copyOf(out));
  }

  /** A medium's whole-tree content identity, or null until it has been hashed and swept. */
  private byte[] rootMerkle(String itemId) {
    DataTree.Rollup root = this.rollups.getOrDefault(itemId, Map.of()).get("");
    return root == null ? null : root.merkleHash();
  }

  // ---- the rollup sweep and the repair index ----------------------------

  /** Recompute this medium's directory rollup from the manifest as it stands. See {@code DataHashing.rollUp}. */
  int rollUp(String itemId) {
    List<DataEntry> entries = manifestOf(itemId);
    if (entries.isEmpty()) {
      this.rollups.remove(itemId);
      return 0;
    }
    Map<String, DataTree.Rollup> rolled = new LinkedHashMap<>();
    for (DataTree.Rollup r : DataTree.roll(HashAlgorithm.SHA256, entries, damageOn(itemId).keySet()))
      rolled.put(r.path(), r);
    this.rollups.put(itemId, rolled);
    return rolled.size();
  }

  /** For every file this medium could not read, the sibling media that hold that path intact. */
  CompletionStage<List<DataHashing.Repair>> findRepairs(String itemId) {
    Map<String, Damage> hurt = damageOn(itemId);
    if (hurt.isEmpty())
      return CompletableFuture.completedStage(List.of());
    List<String> paths = hurt.keySet().stream().sorted().toList();
    CompletionStage<List<DataHashing.Repair>> chain = CompletableFuture.completedStage(new ArrayList<>());
    for (String path : paths) {
      Damage d = hurt.get(path);
      // matched by PATH, because an unreadable file has no content digest —
      // that is what unreadable MEANS, and the path is the only handle left
      List<Map.Entry<String, DataEntry>> elsewhere = new ArrayList<>();
      for (Map.Entry<String, Map<String, DataEntry>> medium : this.manifests.entrySet()) {
        if (medium.getKey().equals(itemId))
          continue;
        DataEntry candidate = medium.getValue().get(path);
        if (candidate != null && candidate.hash() != null)
          elsewhere.add(Map.entry(medium.getKey(), candidate));
      }
      chain = chain.thenCompose(acc -> locate(elsewhere).thenApply(where -> {
        acc.add(new DataHashing.Repair(path, d.reason(), d.attempts(), where));
        return acc;
      }));
    }
    return chain.thenApply(List::copyOf);
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
