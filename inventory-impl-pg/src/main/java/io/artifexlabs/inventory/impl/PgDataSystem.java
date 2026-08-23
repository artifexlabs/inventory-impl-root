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
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.MediaKind;
import io.artifexlabs.inventory.api.Ulid;

import io.smallrye.mutiny.Uni;
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
        (item_id, path_ids, path_hash, path_text, size_bytes, hash_alg, hash, mime_id, modified_at, is_archive)
      VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)""";

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
      // the medium is being re-described: last description's archive items go
      return retireArchives(conn, itemId).flatMap(v -> storeScope(conn, itemId, submitted)).flatMap(count -> audit(conn,
          "data.replace", itemId, new JsonObject().put("entries", count).put("bytes", totalBytes(submitted))
              .put("archives", (int) submitted.stream().filter(DataEntry::isArchive).count()))
          .map(ignored -> Optional.of(count)));
    })).subscribeAsCompletionStage();
  }

  /** One scope's rows, minting an item per archive and recursing into it. */
  private Uni<Integer> storeScope(SqlConnection conn, String itemId, List<DataEntry> entries) {
    return conn.preparedQuery(DELETE_SCOPE).execute(Tuple.of(itemId))
        .flatMap(deleted -> dictionaries(conn, entries).flatMap(dict -> {
          List<Tuple> rows = new ArrayList<>(entries.size());
          for (DataEntry e : entries)
            rows.add(entryTuple(itemId, e, dict));
          Uni<Integer> inserted = rows.isEmpty() ? Uni.createFrom().item(0)
              : conn.preparedQuery(INSERT_ENTRY).executeBatch(rows).map(r -> entries.size());
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
  private Uni<Map<String, Long>> intern(SqlConnection conn, String table, Set<String> names) {
    Map<String, Long> ids = new LinkedHashMap<>();
    if (names.isEmpty())
      return Uni.createFrom().item(ids);
    Uni<Void> chain = Uni.createFrom().voidItem();
    for (String name : names)
      chain = chain
          .flatMap(v -> conn.preparedQuery("INSERT INTO " + table + " (name) VALUES ($1) ON CONFLICT (name) DO NOTHING")
              .execute(Tuple.of(name))
              .flatMap(
                  ignored -> conn.preparedQuery("SELECT id FROM " + table + " WHERE name=$1").execute(Tuple.of(name)))
              .map(rows -> {
                ids.put(name, rows.iterator().next().getLong("id"));
                return (Void) null;
              }));
    return chain.map(v -> ids);
  }

  private record Dictionaries(Map<String, Long> pathElements, Map<String, Long> mimeTypes) {
  }

  // ---- row mapping ------------------------------------------------------

  private static Tuple entryTuple(String itemId, DataEntry e, Dictionaries dict) {
    Long mimeId = e.mime().map(dict.mimeTypes()::get).orElse(null);
    return Tuple.from(new Object[] {
        itemId, pathIds(e.path(), dict.pathElements()), digest(e.path()), e.path(), e.sizeBytes(),
        (short) e.hashAlgorithm().id(), hexToBytes(e.hash()), mimeId,
        e.modified().map(i -> OffsetDateTime.ofInstant(i, ZoneOffset.UTC)).orElse(null), e.isArchive()
    });
  }

  private static Long[] pathIds(String path, Map<String, Long> elements) {
    String[] parts = path.split("/");
    Long[] ids = new Long[parts.length];
    for (int i = 0; i < parts.length; i++)
      ids[i] = elements.get(parts[i]);
    return ids;
  }

  private static List<DataEntry> readEntries(RowSet<Row> rows) {
    List<DataEntry> out = new ArrayList<>();
    for (Row r : rows) {
      HashAlgorithm algorithm = HashAlgorithm.byId(r.get(Short.class, "hash_alg").intValue())
          .orElse(HashAlgorithm.SHA256);
      OffsetDateTime modified = r.getOffsetDateTime("modified_at");
      // archiveContents is NOT rehydrated here: a nested scope is its own
      // item with its own manifest, reachable by asking for that item
      out.add(new DataEntry(r.getString("path_text"), r.getLong("size_bytes"), algorithm,
          bytesToHex(r.getBuffer("hash").getBytes()), r.getString("mime_name"),
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
