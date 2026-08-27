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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataInfo;
import io.artifexlabs.inventory.api.DataTree;
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.MediaKind;
import io.artifexlabs.inventory.api.Ulid;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.core.json.JsonObject;

/**
 * Postgres-backed {@link DataSystem}: a medium's file listing, stored so that a listing with hundreds of thousands of
 * rows stays cheap.
 *
 * <p>
 * <b>Rows are small because text lives once.</b> Mime types and every path COMPONENT live in dictionary tables
 * ({@code mime_types}, {@code path_elements}); an entry stores the component id SEQUENCE plus a denormalized
 * {@code path_text} for substring search. {@code path_elements} is append-only by contract — a rename creates or looks
 * up the components of the new name and rewrites the sequence, because other entries still point at the old ids and
 * overwriting one would silently repath files nobody touched.
 *
 * <p>
 * <b>Two hashes, both bytes.</b> {@code hash} is the content digest, which answers "which disc has this file?".
 * {@code path_hash} digests the SCOPE-RELATIVE path — the medium's own name is deliberately not part of it — so "the
 * same file in the same place", the mirror question, is an indexed byte compare rather than a path-sequence match.
 *
 * <p>
 * Every mutation runs in ONE transaction that also writes its audit row, exactly like the other Pg systems: a manifest
 * without its audit entry cannot exist.
 */
public class PgDataSystem implements DataSystem {

  private final static String DELETE_SCOPE = "DELETE FROM data_entries WHERE item_id=$1";

  private final static String INSERT_ENTRY = """
      INSERT INTO data_entries
        (item_id, path_ids, path_hash, path_text, size_bytes, hash_alg, hash, mime_id, modified_at, is_archive,
         hash_state)
      VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)""";

  /**
   * Park this medium's completed hashes before the listing is torn down. Without this, re-describing a disc discards
   * weeks of hashing — and via retireArchives, every archive on it too. A temp table rather than a Java map because a
   * large medium is millions of rows: carrying them through the client to put them straight back is the kind of round
   * trip the dictionary encoding exists to avoid.
   */
  /** storeScope recurses for every archive, and each call parks its own scope's hashes. */
  private final static String DROP_CARRIED = "DROP TABLE IF EXISTS carried_hashes";

  private final static String PARK_HASHES = """
      CREATE TEMP TABLE carried_hashes ON COMMIT DROP AS
        SELECT path_hash, size_bytes, modified_at, hash, hash_alg, hashed_at, hash_state
          FROM data_entries WHERE item_id=$1 AND hash_state <> 0""";

  /**
   * Give them back to the rows that describe the SAME file. "Same" is (path, size, mtime): a file whose size or
   * timestamp moved is a different file that happens to share a name, and silently keeping its old digest would be a
   * lie the system could never detect. IS NOT DISTINCT FROM so two NULL mtimes still count as equal.
   */
  private final static String CARRY_HASHES = """
      UPDATE data_entries e
         SET hash = c.hash, hash_alg = c.hash_alg, hashed_at = c.hashed_at, hash_state = c.hash_state
        FROM carried_hashes c
       WHERE e.item_id = $1 AND e.path_hash = c.path_hash
         AND e.size_bytes = c.size_bytes
         AND e.modified_at IS NOT DISTINCT FROM c.modified_at""";

  private final static String DELETE_DIRS = "DELETE FROM data_dirs WHERE item_id=$1";

  private final static String INSERT_DIR = """
      INSERT INTO data_dirs
        (item_id, path_ids, path_hash, path_text, depth, structure_hash,
         subtree_files, subtree_bytes, pending_files)
      VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)""";

  private final static String SELECT_SCOPE = """
      SELECT path_text, size_bytes, hash_alg, hash, modified_at, is_archive,
             (SELECT name FROM mime_types m WHERE m.id = e.mime_id) AS mime_name
        FROM data_entries e
       WHERE item_id=$1 AND ($2 = '' OR lower(path_text) LIKE '%' || $2 || '%')
       ORDER BY path_text OFFSET $3 LIMIT $4""";

  private final static String SUMMARY = """
      SELECT count(*) AS n, coalesce(sum(size_bytes),0) AS bytes,
             count(*) FILTER (WHERE is_archive) AS archives
        FROM data_entries WHERE item_id=$1""";

  private final static String BY_HASH = """
      SELECT e.item_id, e.path_text, e.size_bytes, i.name AS item_name
        FROM data_entries e JOIN items i ON i.id = e.item_id
       WHERE e.hash_alg=$1 AND e.hash=$2
       ORDER BY i.name, e.path_text""";

  /** Same content AND same place as any row of $1, on some other medium. */
  private final static String MIRRORS = """
      SELECT o.item_id, o.path_text, o.size_bytes, i.name AS item_name
        FROM data_entries o
        JOIN data_entries mine
          ON mine.item_id = $1 AND mine.path_hash = o.path_hash
         AND mine.hash_alg = o.hash_alg AND mine.hash = o.hash
        JOIN items i ON i.id = o.item_id
       WHERE o.item_id <> $1
       ORDER BY i.name, o.path_text""";

  /**
   * Subtrees whose identity occurs in more than one PLACE. Grouped in SQL rather than in Java because the answer is
   * "this folder appears N times" — pulling every row back to group them client-side is what made findMirrorsOf a
   * Parallel Seq Scan returning millions of rows to answer a yes/no.
   *
   * <p>
   * $1 picks the column (1 structure, 2 merkle, 3 content-only), $2 the medium or null for the whole inventory, $3 the
   * scope, then the floors. A directory is excluded from matching ITSELF by path_hash, not by item_id — within one
   * medium the same subtree at two paths is exactly what we are hunting.
   */
  private final static String SECTIONS = """
      WITH candidate AS (
        SELECT d.item_id, d.path_text, d.depth, d.subtree_files, d.subtree_bytes,
               CASE $1::int WHEN 1 THEN d.structure_hash WHEN 2 THEN d.merkle_hash
                            ELSE d.merkle_content_hash END AS ident
          FROM data_dirs d
         WHERE d.subtree_files >= $4::bigint AND d.subtree_bytes >= $5::bigint AND d.depth >= $6::int
      ), grouped AS (
        SELECT ident,
               count(*) AS places,
               count(DISTINCT item_id) AS media,
               max(subtree_files) AS files,
               max(subtree_bytes) AS bytes
          FROM candidate WHERE ident IS NOT NULL
         GROUP BY ident
        HAVING count(*) > 1
      )
      SELECT encode(g.ident,'hex') AS ident_hex, g.files, g.bytes,
             c.item_id, c.path_text, c.depth, i.name AS item_name
        FROM grouped g
        JOIN candidate c ON c.ident = g.ident
        JOIN items i ON i.id = c.item_id
       WHERE ($2::varchar IS NULL OR EXISTS (
               SELECT 1 FROM candidate mine WHERE mine.ident = g.ident AND mine.item_id = $2::varchar))
         AND ($3::int = 3
              OR ($3::int = 1 AND g.media > 1)
              OR ($3::int = 2 AND g.places > g.media))
       ORDER BY g.bytes DESC, g.files DESC, encode(g.ident,'hex'), i.name, c.path_text""";

  private final static String RENAME_SELECT = """
      SELECT path_text FROM data_entries
       WHERE item_id=$1 AND (path_text = $2 OR path_text LIKE $3)""";

  private final static String RENAME_ONE = """
      UPDATE data_entries SET path_ids=$3, path_hash=$4, path_text=$5
       WHERE item_id=$1 AND path_text=$2""";

  private final static String INSERT_AUDIT = """
      INSERT INTO audit_events (id, ts, principal, action, target_id, details)
      VALUES ($1, $2, $3, $4, $5, $6)""";

  private final io.vertx.mutiny.sqlclient.Pool pool;
  private final PgInventorySystem items;
  private final String principal;

  public PgDataSystem(io.vertx.mutiny.sqlclient.Pool pool, PgInventorySystem items, String principal) {
    this.pool = requireNonNull(pool, "pool");
    this.items = requireNonNull(items, "items");
    this.principal = requireNonNull(principal, "principal");
  }

  @Override
  public PgDataSystem actingAs(String principal) {
    return new PgDataSystem(this.pool, this.items, principal);
  }

  // ---- writes -----------------------------------------------------------

  @Override
  public CompletionStage<Optional<Integer>> replaceManifest(String itemId, List<DataEntry> entries) {
    List<DataEntry> submitted = entries == null ? List.of() : List.copyOf(entries);
    return this.pool.withTransaction(conn -> holdsData(conn, itemId).flatMap(ok -> {
      if (!ok)
        return Uni.createFrom().item(Optional.<Integer>empty());
      // The medium is being re-described: last description's archive items go.
      //
      // KNOWN GAP (stage 2 owns it): this runs BEFORE storeScope, and deleting an
      // archive item cascade-deletes its data_entries — so an archive's hashes
      // are gone before storeScope can park them, and the replacement archive
      // gets a fresh ULID besides. The medium's OWN hashes survive a re-describe;
      // its archives' do not. Harmless today because nothing hashes inside an
      // archive until the stage-2 scanner exists, and that is the change that
      // should fix it — by parking inner hashes against the archive's PATH in
      // this manifest rather than against an item id that will not survive.
      return retireArchives(conn, itemId).flatMap(v -> storeScope(conn, itemId, submitted)).flatMap(count -> audit(conn,
          "data.replace", itemId, new JsonObject().put("entries", count).put("bytes", totalBytes(submitted))
              .put("archives", (int) submitted.stream().filter(DataEntry::isArchive).count()))
          .map(ignored -> Optional.of(count)));
    })).subscribeAsCompletionStage();
  }

  /** One scope's rows, minting an item per archive and recursing into it. */
  private Uni<Integer> storeScope(SqlConnection conn, String itemId, List<DataEntry> entries) {
    return conn.query(DROP_CARRIED).execute()
        .flatMap(dropped -> conn.preparedQuery(PARK_HASHES).execute(Tuple.of(itemId)))
        .flatMap(parked -> conn.preparedQuery(DELETE_SCOPE).execute(Tuple.of(itemId)))
        .flatMap(deleted -> dictionaries(conn, entries).flatMap(dict -> {
          List<Tuple> rows = new ArrayList<>(entries.size());
          for (DataEntry e : entries)
            rows.add(entryTuple(itemId, e, dict));
          Uni<Integer> inserted = rows.isEmpty() ? Uni.createFrom().item(0)
              : conn.preparedQuery(INSERT_ENTRY).executeBatch(rows).map(r -> entries.size());
          // Directory rows are derived, not supplied: a manifest is flat, and the
          // tree it implies is what makes "is this folder a copy of that one"
          // answerable. Same transaction as the entries — a manifest and its tree
          // disagreeing would be worse than either being absent.
          // hashes come back BEFORE the tree is derived, so pending_files is
          // seeded from what is actually still unhashed rather than assuming a
          // re-described medium starts from zero again
          inserted = inserted.flatMap(count -> conn.preparedQuery(CARRY_HASHES).execute(Tuple.of(itemId))
              .flatMap(carried -> storeDirs(conn, itemId, entries, dict)).map(v -> count));
          return inserted.flatMap(count -> {
            Uni<Integer> chain = Uni.createFrom().item(count);
            for (DataEntry entry : entries) {
              if (!entry.isArchive())
                continue;
              chain = chain.flatMap(running -> mintArchive(conn, itemId, entry).flatMap(
                  archiveId -> storeScope(conn, archiveId, entry.archiveContents()).map(inner -> running + inner)));
            }
            return chain;
          });
        }));
  }

  /**
   * An archive is a file AND a container, so it earns an item of its own — created here rather than through
   * InventorySystem because the whole manifest must land in ONE transaction.
   */
  private Uni<String> mintArchive(SqlConnection conn, String containerId, DataEntry entry) {
    String id = Ulid.next();
    return conn.preparedQuery("""
        INSERT INTO items (id, name, display_name, type, container_id, ts,
                           data_kind, data_mutable, data_archive)
        VALUES ($1, $2, $2, 'archive', $3, $4, $5, false, true)""")
        .execute(Tuple.of(id, entry.fileName(), containerId, OffsetDateTime.now(ZoneOffset.UTC),
            MediaKind.PHYSICAL_MEDIA.name()))
        .flatMap(r -> audit(conn, "item.create", id,
            new JsonObject().put("name", entry.fileName()).put("archiveOf", containerId)))
        .map(v -> id);
  }

  /** Drop the archive items the previous description of this medium made. */
  private Uni<Void> retireArchives(SqlConnection conn, String itemId) {
    return conn.preparedQuery("SELECT id FROM items WHERE container_id=$1 AND data_archive = true")
        .execute(Tuple.of(itemId)).flatMap(rows -> {
          Uni<Void> chain = Uni.createFrom().voidItem();
          for (Row row : rows) {
            String childId = row.getString("id");
            // recurse first: archives inside archives are items too
            chain = chain.flatMap(v -> retireArchives(conn, childId)).flatMap(
                v -> conn.preparedQuery("DELETE FROM items WHERE id=$1").execute(Tuple.of(childId)).replaceWithVoid());
          }
          return chain;
        });
  }

  @Override
  public CompletionStage<Integer> renamePath(String itemId, String fromPath, String toPath) {
    String from = DataEntry.normalizePath(fromPath);
    String to = DataEntry.normalizePath(toPath);
    return this.pool.withTransaction(
        conn -> conn.preparedQuery(RENAME_SELECT).execute(Tuple.of(itemId, from, from + "/%")).flatMap(rows -> {
          List<String> affected = new ArrayList<>();
          for (Row r : rows)
            affected.add(r.getString("path_text"));
          if (affected.isEmpty())
            return Uni.createFrom().item(0);
          // every new path's components must exist before any row moves
          Set<String> newElements = new LinkedHashSet<>();
          for (String old : affected)
            newElements.addAll(List.of(repath(old, from, to).split("/")));
          return intern(conn, "path_elements", newElements).flatMap(ids -> {
            Uni<Integer> chain = Uni.createFrom().item(0);
            for (String old : affected) {
              String moved = repath(old, from, to);
              chain = chain.flatMap(running -> conn.preparedQuery(RENAME_ONE)
                  .execute(Tuple.of(itemId, old, pathIds(moved, ids), digest(moved), moved)).map(r -> running + 1));
            }
            return chain.flatMap(count -> audit(conn, "data.rename", itemId,
                new JsonObject().put("from", from).put("to", to).put("entries", count)).map(v -> count));
          });
        })).subscribeAsCompletionStage();
  }

  // ---- reads ------------------------------------------------------------

  @Override
  public CompletionStage<List<DataEntry>> entriesOf(String itemId, String query, int page, int size) {
    int limit = Math.max(1, size);
    int offset = Math.max(0, page) * limit;
    String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    return this.pool.withConnection(conn -> conn.preparedQuery(SELECT_SCOPE)
        .execute(Tuple.of(itemId, needle, offset, limit)).map(PgDataSystem::readEntries)).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<ManifestSummary> summaryOf(String itemId) {
    return this.pool.withConnection(conn -> conn.preparedQuery(SUMMARY).execute(Tuple.of(itemId)).map(rows -> {
      Row r = rows.iterator().next();
      int n = (int) r.getLong("n").longValue();
      return n == 0 ? ManifestSummary.EMPTY
          : new ManifestSummary(n, r.getLong("bytes"), (int) r.getLong("archives").longValue());
    })).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<DataLocation>> findByHash(HashAlgorithm algorithm, String hash) {
    byte[] wanted = hexToBytes(hash);
    if (wanted == null)
      return java.util.concurrent.CompletableFuture.completedStage(List.of());
    return this.pool.withConnection(conn -> conn.preparedQuery(BY_HASH)
        .execute(Tuple.of((short) algorithm.id(), wanted)).map(PgDataSystem::readLocations))
        .subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<SectionMatch>> findDuplicateSections(SectionQuery q) {
    short column = switch (q.match()) {
    case STRUCTURE -> 1;
    case MERKLE -> 2;
    case CONTENT -> 3;
    };
    short scope = switch (q.scope()) {
    case ACROSS_MEDIA -> 1;
    case WITHIN_MEDIUM -> 2;
    case BOTH -> 3;
    };
    return this.pool
        .withConnection(
            conn -> conn.preparedQuery(SECTIONS)
                .execute(Tuple.of(column, q.itemId(), scope, (long) Math.max(q.minFiles(), 0),
                    Math.max(q.minBytes(), 0L), q.minDepth())))
        .map(rows -> groupSections(rows, q)).subscribeAsCompletionStage();
  }

  /** Fold the flat join back into one entry per identity, preserving the SQL ordering. */
  private static List<SectionMatch> groupSections(RowSet<Row> rows, SectionQuery q) {
    Map<String, List<SectionLocation>> places = new LinkedHashMap<>();
    Map<String, long[]> sizes = new LinkedHashMap<>();
    for (Row r : rows) {
      String ident = r.getString("ident_hex");
      places.computeIfAbsent(ident, k -> new ArrayList<>()).add(new SectionLocation(r.getString("item_id"),
          r.getString("item_name"), r.getString("path_text"), r.getInteger("depth")));
      sizes.computeIfAbsent(ident, k -> new long[] {
          r.getLong("files"), r.getLong("bytes")
      });
    }
    List<SectionMatch> out = new ArrayList<>();
    for (Map.Entry<String, List<SectionLocation>> e : places.entrySet()) {
      long[] sz = sizes.get(e.getKey());
      out.add(new SectionMatch(e.getKey(), sz[0], sz[1], List.copyOf(e.getValue())));
    }
    int from = Math.min(Math.max(q.page(), 0) * Math.max(q.size(), 1), out.size());
    int to = Math.min(from + Math.max(q.size(), 1), out.size());
    return List.copyOf(out.subList(from, to));
  }

  @Override
  public CompletionStage<List<DataLocation>> findMirrorsOf(String itemId) {
    return this.pool
        .withConnection(conn -> conn.preparedQuery(MIRRORS).execute(Tuple.of(itemId)).map(PgDataSystem::readLocations))
        .subscribeAsCompletionStage();
  }

  // ---- dictionaries -----------------------------------------------------

  /** Intern every path component and mime type these entries need, once. */
  private Uni<Dictionaries> dictionaries(SqlConnection conn, List<DataEntry> entries) {
    Set<String> elements = new LinkedHashSet<>();
    Set<String> mimes = new LinkedHashSet<>();
    for (DataEntry e : entries) {
      elements.addAll(e.pathElements());
      e.mime().ifPresent(mimes::add);
    }
    return intern(conn, "path_elements", elements)
        .flatMap(paths -> intern(conn, "mime_types", mimes).map(m -> new Dictionaries(paths, m)));
  }

  /**
   * Look up or create each name, returning name → id. Append-only: an existing row is REUSED, never rewritten, because
   * other entries already point at its id.
   */
  /**
   * Intern a whole vocabulary in TWO round trips: one array insert, one array lookup.
   *
   * <p>
   * This used to build a flatMap chain with one link per name, each doing its own INSERT and SELECT. With a handful of
   * test paths that was invisible; with a real manifest it is fatal twice over — 80,000 paths carry roughly 100,000
   * distinct components, which meant a 100,000-deep Uni chain (a StackOverflowError on subscribe) and 200,000
   * sequential round trips (a load that never finished). Found by running an actual medium through it rather than a
   * fixture.
   *
   * <p>
   * ON CONFLICT DO NOTHING keeps the append-only contract: a component already present keeps its id, because other
   * entries' {@code path_ids} still point at it and reassigning one would silently repath files nobody touched.
   */
  private Uni<Map<String, Long>> intern(SqlConnection conn, String table, Set<String> names) {
    Map<String, Long> ids = new LinkedHashMap<>();
    if (names.isEmpty())
      return Uni.createFrom().item(ids);
    String[] all = names.toArray(new String[0]);
    return conn
        .preparedQuery("INSERT INTO " + table + " (name) SELECT unnest($1::text[]) ON CONFLICT (name) DO NOTHING")
        .execute(Tuple.of(all)).flatMap(inserted -> conn
            .preparedQuery("SELECT name, id FROM " + table + " WHERE name = ANY($1::text[])").execute(Tuple.of(all)))
        .map(rows -> {
          for (Row r : rows)
            ids.put(r.getString("name"), r.getLong("id"));
          return ids;
        });
  }

  private record Dictionaries(Map<String, Long> pathElements, Map<String, Long> mimeTypes) {
  }

  // ---- row mapping ------------------------------------------------------

  /** hash_state: 0 pending, 1 done, 2 unreadable. An unhashed entry is normal, not an error. */
  private final static short HASH_PENDING = 0;
  private final static short HASH_DONE = 1;

  /**
   * Replace this medium's directory rows from the manifest it was just given. {@code pending_files} is seeded to the
   * subtree's file count — every file starts unhashed — and from then on ONLY the bottom-up sweep writes it. An
   * incremental counter would cost an update per ancestor per file (~15 on the measured tree, 781M at 50M files) and
   * would make the root a row every hasher contends on.
   */
  private Uni<Void> storeDirs(SqlConnection conn, String itemId, List<DataEntry> entries, Dictionaries dict) {
    return conn.preparedQuery(DELETE_DIRS).execute(Tuple.of(itemId)).flatMap(gone -> {
      List<DataTree.Node> nodes = DataTree.build(HashAlgorithm.SHA256, entries);
      if (nodes.isEmpty())
        return Uni.createFrom().voidItem();
      List<Tuple> rows = new ArrayList<>(nodes.size());
      for (DataTree.Node n : nodes)
        rows.add(Tuple.from(new Object[] {
            itemId, pathIds(n.path(), dict.pathElements()), digest(n.path()), n.path(), n.depth(), n.structureHash(),
            n.subtreeFiles(), n.subtreeBytes(), n.subtreeFiles()
        }));
      return conn.preparedQuery(INSERT_DIR).executeBatch(rows).replaceWithVoid();
    });
  }

  private static Tuple entryTuple(String itemId, DataEntry e, Dictionaries dict) {
    Long mimeId = e.mime().map(dict.mimeTypes()::get).orElse(null);
    // a manifest may arrive before anything is hashed (find describes a tree in
    // minutes; hashing it takes weeks), so hash/hash_alg are null together and
    // hash_state records which of the two situations this row is in
    boolean hashed = e.hash() != null;
    return Tuple.from(new Object[] {
        itemId, pathIds(e.path(), dict.pathElements()), digest(e.path()), e.path(), e.sizeBytes(),
        hashed ? (short) e.hashAlgorithm().id() : null, hashed ? hexToBytes(e.hash()) : null, mimeId,
        e.modified().map(i -> OffsetDateTime.ofInstant(i, ZoneOffset.UTC)).orElse(null), e.isArchive(),
        hashed ? HASH_DONE : HASH_PENDING
    });
  }

  private static Long[] pathIds(String path, Map<String, Long> elements) {
    // the medium root has no components at all; "".split("/") yields [""] , which
    // would intern an empty component and store {NULL} in a bigint[]
    if (path.isEmpty())
      return new Long[0];
    String[] parts = path.split("/");
    Long[] ids = new Long[parts.length];
    for (int i = 0; i < parts.length; i++)
      ids[i] = elements.get(parts[i]);
    return ids;
  }

  private static List<DataEntry> readEntries(RowSet<Row> rows) {
    List<DataEntry> out = new ArrayList<>();
    for (Row r : rows) {
      // hash and hash_alg are null together until the async hasher reaches this
      // file — the normal state of a freshly described medium, not a bad row
      Short algId = r.get(Short.class, "hash_alg");
      Buffer digest = r.getBuffer("hash");
      HashAlgorithm algorithm = algId == null ? null
          : HashAlgorithm.byId(algId.intValue()).orElse(HashAlgorithm.SHA256);
      OffsetDateTime modified = r.getOffsetDateTime("modified_at");
      // archiveContents is NOT rehydrated here: a nested scope is its own
      // item with its own manifest, reachable by asking for that item
      out.add(new DataEntry(r.getString("path_text"), r.getLong("size_bytes"), algorithm,
          digest == null ? null : bytesToHex(digest.getBytes()), r.getString("mime_name"),
          modified == null ? null : modified.toInstant(), List.of()));
    }
    return List.copyOf(out);
  }

  private static List<DataLocation> readLocations(RowSet<Row> rows) {
    List<DataLocation> out = new ArrayList<>();
    for (Row r : rows)
      out.add(new DataLocation(r.getString("item_id"), r.getString("item_name"), r.getString("path_text"),
          r.getLong("size_bytes")));
    return List.copyOf(out);
  }

  // ---- helpers ----------------------------------------------------------

  private Uni<Boolean> holdsData(SqlConnection conn, String itemId) {
    return conn.preparedQuery("SELECT data_kind FROM items WHERE id=$1").execute(Tuple.of(itemId)).map(rows -> {
      if (!rows.iterator().hasNext())
        return false;
      String kind = rows.iterator().next().getString("data_kind");
      return kind != null && new DataInfo(MediaKind.valueOf(kind), false, false).holdsData();
    });
  }

  private Uni<Void> audit(SqlConnection conn, String action, String targetId, JsonObject details) {
    return conn.preparedQuery(INSERT_AUDIT)
        .execute(
            Tuple.of(Ulid.next(), OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                this.principal, action, targetId, details))
        .replaceWithVoid();
  }

  private static String repath(String path, String from, String to) {
    return path.equals(from) ? to : to + path.substring(from.length());
  }

  private static long totalBytes(List<DataEntry> entries) {
    long total = 0L;
    for (DataEntry e : entries)
      total += e.sizeBytes();
    return total;
  }

  /** The scope-relative path as bytes: the mirror question's indexed key. */
  private static byte[] digest(String path) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by every JVM", impossible);
    }
  }

  private static byte[] hexToBytes(String hex) {
    if (hex == null || hex.length() % 2 != 0)
      return null;
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = Character.digit(hex.charAt(i * 2), 16);
      int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
      if (hi < 0 || lo < 0)
        return null;
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes)
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    return sb.toString();
  }

  /** Unused today; kept so an Instant-typed caller has an obvious path. */
  static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
