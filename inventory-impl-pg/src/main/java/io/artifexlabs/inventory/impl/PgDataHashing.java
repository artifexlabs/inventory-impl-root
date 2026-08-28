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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataHashing;
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.DataTree;
import io.artifexlabs.inventory.api.HashAlgorithm;

import io.smallrye.mutiny.Uni;

import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Postgres-backed {@link DataHashing}: the durable half of a job that runs for weeks.
 *
 * <p>
 * The shape is the projector's consumer pattern applied to a different kind of work — a durable queue in a table, drunk
 * from in committed batches, safe to kill at any point. What differs is that a hasher's unit of work is expensive
 * enough to matter: reading one large file can take minutes, so nothing here holds a transaction across the actual
 * hashing.
 */
public class PgDataHashing implements DataHashing {

  /** hash_state values; 3 is a claim, which exists so a lease can expire. */
  private final static short PENDING = 0;
  private final static short DONE = 1;
  private final static short UNREADABLE = 2;
  private final static short CLAIMED = 3;

  /**
   * Take work without blocking another worker.
   *
   * <p>
   * {@code FOR UPDATE SKIP LOCKED} is the load-bearing clause: two hashers on one medium step over each other's rows
   * rather than queueing, so a second worker doubles throughput instead of waiting. The UPDATE commits immediately —
   * the lease is the claim, not the lock — because the caller is about to spend minutes reading bytes.
   *
   * <p>
   * ORDER BY path_text makes a run depth-first through the tree (decision 5), so a medium finishes in an order a human
   * watching it can recognise, and the partial index on pending rows serves it directly.
   *
   * <p>
   * {@code NOT LIKE '%/'} excludes directory markers. An empty directory is stored as an entry so it can be part of a
   * structure hash, but it describes a PLACE and has no bytes — hand one to a worker and it comes back unreadable,
   * which would report damage on a medium that has none.
   */
  private final static String CLAIM = """
      WITH picked AS (
        SELECT item_id, path_hash FROM data_entries
         WHERE item_id = $1 AND hash_state = 0 AND path_text NOT LIKE '%/'
         ORDER BY path_text
         LIMIT $3
         FOR UPDATE SKIP LOCKED
      )
      UPDATE data_entries e
         SET hash_state = 3, claimed_at = now(), claimed_by = $2
        FROM picked p
       WHERE e.item_id = p.item_id AND e.path_hash = p.path_hash
      RETURNING e.path_text, e.size_bytes, e.modified_at""";

  /**
   * Store the digest only if the row still describes what was claimed. The size and mtime predicates are the guard: a
   * medium re-described mid-run would otherwise take a digest computed against the file it used to hold.
   */
  private final static String COMPLETE = """
      UPDATE data_entries
         SET hash = $5, hash_alg = $4, hashed_at = now(), hash_state = 1,
             claimed_at = NULL, claimed_by = NULL
       WHERE item_id = $1 AND path_text = $2 AND hash_state = 3
         AND size_bytes = $3 AND modified_at IS NOT DISTINCT FROM $6""";

  private final static String MARK_UNREADABLE = """
      UPDATE data_entries SET hash_state = 2, claimed_at = NULL, claimed_by = NULL
       WHERE item_id = $1 AND path_text = $2""";

  /** One row per damaged file, not one per attempt: a permanently bad sector stays a single line. */
  private final static String RECORD_UNREADABLE = """
      INSERT INTO data_unreadable (item_id, path_hash, first_seen, last_attempt, attempts, reason)
      SELECT e.item_id, e.path_hash, now(), now(), 1, $3
        FROM data_entries e WHERE e.item_id = $1 AND e.path_text = $2
      ON CONFLICT (item_id, path_hash) DO UPDATE
         SET last_attempt = now(), attempts = data_unreadable.attempts + 1, reason = EXCLUDED.reason""";

  /** A worker that died holds rows forever unless something gives them back. */
  private final static String RECLAIM = """
      UPDATE data_entries SET hash_state = 0, claimed_at = NULL, claimed_by = NULL
       WHERE item_id = $1 AND hash_state = 3
         AND claimed_at < now() - make_interval(secs => $2::double precision)""";

  /**
   * Everything the rollup folds, in path order. Read back through {@link io.artifexlabs.inventory.api.DataTree} rather
   * than folded in SQL, and that is not a convenience: two backends computing "the same" Merkle slightly differently is
   * a divergence no test catches until two media that ARE copies fail to match — and by then the wrong digests are in a
   * database. One implementation, in the api, called by both.
   */
  private final static String MANIFEST = """
      SELECT path_text, size_bytes, hash_alg, hash, hash_state FROM data_entries
       WHERE item_id = $1 ORDER BY path_text""";

  private final static String APPLY_ROLLUP = """
      UPDATE data_dirs
         SET merkle_hash = $3, merkle_content_hash = $4, unreadable_hash = $5,
             pending_files = $6, unreadable_files = $7,
             hash_alg = CASE WHEN $3::bytea IS NULL THEN hash_alg ELSE $8::smallint END
       WHERE item_id = $1 AND path_text = $2""";

  /**
   * Every file this medium could not read, and every sibling medium holding that path intact.
   *
   * <p>
   * A LEFT JOIN on purpose: a damaged file with nowhere to recover it from is the most important row in the answer, and
   * an inner join would silently drop exactly those.
   */
  private final static String REPAIRS = """
      WITH damaged AS (
        SELECT e.path_hash, e.path_text, u.reason, u.attempts
          FROM data_entries e
          LEFT JOIN data_unreadable u ON u.item_id = e.item_id AND u.path_hash = e.path_hash
         WHERE e.item_id = $1 AND e.hash_state = 2
      )
      SELECT d.path_text, d.reason, coalesce(d.attempts, 1) AS attempts,
             o.item_id, o.path_text AS their_path, o.size_bytes, i.name AS item_name
        FROM damaged d
        LEFT JOIN data_entries o
               ON o.path_hash = d.path_hash AND o.item_id <> $1 AND o.hash_state = 1
        LEFT JOIN items i ON i.id = o.item_id
       ORDER BY d.path_text, i.name""";

  private final static String PROGRESS = """
      SELECT count(*) FILTER (WHERE hash_state = 0) AS pending,
             count(*) FILTER (WHERE hash_state = 3) AS claimed,
             count(*) FILTER (WHERE hash_state = 1) AS done,
             count(*) FILTER (WHERE hash_state = 2) AS unreadable,
             coalesce(sum(size_bytes) FILTER (WHERE hash_state IN (0, 3)), 0) AS pending_bytes
        FROM data_entries WHERE item_id = $1 AND path_text NOT LIKE '%/'""";

  private final io.vertx.mutiny.sqlclient.Pool pool;
  private final String principal;

  public PgDataHashing(io.vertx.mutiny.sqlclient.Pool pool, String principal) {
    this.pool = requireNonNull(pool, "pool");
    this.principal = requireNonNull(principal, "principal");
  }

  @Override
  public PgDataHashing actingAs(String principal) {
    return new PgDataHashing(this.pool, principal);
  }

  @Override
  public CompletionStage<List<PendingFile>> claim(String itemId, String worker, int limit) {
    if (limit <= 0)
      return java.util.concurrent.CompletableFuture.completedStage(List.of());
    return this.pool.withTransaction(conn -> conn.preparedQuery(CLAIM).execute(Tuple.of(itemId, worker, limit)))
        .map(PgDataHashing::pendingFiles).subscribeAsCompletionStage();
  }

  private static List<PendingFile> pendingFiles(RowSet<Row> rows) {
    List<PendingFile> out = new ArrayList<>();
    for (Row r : rows) {
      OffsetDateTime m = r.getOffsetDateTime("modified_at");
      out.add(new PendingFile(r.getString("path_text"), r.getLong("size_bytes"), m == null ? null : m.toInstant()));
    }
    return List.copyOf(out);
  }

  @Override
  public CompletionStage<Boolean> complete(String itemId, PendingFile claimed, HashAlgorithm algorithm, String hash) {
    OffsetDateTime modified = claimed.modifiedAt() == null ? null
        : OffsetDateTime.ofInstant(claimed.modifiedAt(), ZoneOffset.UTC);
    return this.pool
        .withTransaction(conn -> conn.preparedQuery(COMPLETE).execute(
            Tuple.of(itemId, claimed.path(), claimed.sizeBytes(), (short) algorithm.id(), hexToBytes(hash), modified)))
        .map(r -> r.rowCount() == 1).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Void> unreadable(String itemId, String path, String reason) {
    // the entry and the repair index move together or not at all: a file marked
    // unreadable with nothing recorded about WHY is a dead end for whoever has
    // to go and fix it
    return this.pool
        .withTransaction(conn -> conn.preparedQuery(RECORD_UNREADABLE).execute(Tuple.of(itemId, path, reason))
            .flatMap(v -> conn.preparedQuery(MARK_UNREADABLE).execute(Tuple.of(itemId, path))))
        .replaceWithVoid().subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Integer> reclaimStale(String itemId, long leaseSeconds) {
    return this.pool
        .withTransaction(conn -> conn.preparedQuery(RECLAIM).execute(Tuple.of(itemId, (double) leaseSeconds)))
        .map(RowSet::rowCount).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Progress> progressOf(String itemId) {
    return this.pool.withConnection(conn -> conn.preparedQuery(PROGRESS).execute(Tuple.of(itemId))).map(rows -> {
      Row r = rows.iterator().next();
      return new Progress(r.getLong("pending"), r.getLong("claimed"), r.getLong("done"), r.getLong("unreadable"),
          r.getLong("pending_bytes"));
    }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Integer> rollUp(String itemId) {
    return this.pool.withTransaction(conn -> conn.preparedQuery(MANIFEST).execute(Tuple.of(itemId)).flatMap(rows -> {
      List<DataEntry> entries = new ArrayList<>();
      Set<String> damaged = new java.util.LinkedHashSet<>();
      for (Row r : rows) {
        String path = r.getString("path_text");
        byte[] hash = r.getBuffer("hash") == null ? null : r.getBuffer("hash").getBytes();
        Short alg = r.getShort("hash_alg");
        HashAlgorithm algorithm = alg == null ? null : HashAlgorithm.byId(alg).orElse(null);
        if (r.getShort("hash_state") == UNREADABLE)
          damaged.add(path);
        // mime and mtime are irrelevant to a rollup, so they are not fetched:
        // this query runs over every row of a medium and the columns it does not
        // ask for are the ones it does not pay for
        entries.add(new DataEntry(path, r.getLong("size_bytes"), hash == null ? null : algorithm,
            hash == null ? null : HexFormat.of().formatHex(hash), null, null, List.of()));
      }
      List<DataTree.Rollup> rolled = DataTree.roll(HashAlgorithm.SHA256, entries, damaged);
      if (rolled.isEmpty())
        return Uni.createFrom().item(0);
      List<Tuple> updates = new ArrayList<>(rolled.size());
      for (DataTree.Rollup d : rolled)
        updates.add(Tuple.from(new Object[] {
            itemId, d.path(), d.merkleHash(), d.merkleContentHash(), d.unreadableHash(), d.pendingFiles(),
            d.unreadableFiles(), (short) HashAlgorithm.SHA256.id()
        }));
      return conn.preparedQuery(APPLY_ROLLUP).executeBatch(updates).map(v -> rolled.size());
    })).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<Repair>> findRepairs(String itemId) {
    return this.pool.withConnection(conn -> conn.preparedQuery(REPAIRS).execute(Tuple.of(itemId))).map(rows -> {
      // one row per (damaged file x sibling that has it), folded back into one
      // Repair per damaged file, preserving the SQL ordering
      Map<String, List<DataSystem.DataLocation>> where = new LinkedHashMap<>();
      Map<String, Object[]> detail = new LinkedHashMap<>();
      for (Row r : rows) {
        String path = r.getString("path_text");
        where.computeIfAbsent(path, k -> new ArrayList<>());
        detail.computeIfAbsent(path, k -> new Object[] {
            r.getString("reason"), r.getInteger("attempts")
        });
        if (r.getString("item_id") != null)
          where.get(path).add(new DataSystem.DataLocation(r.getString("item_id"), r.getString("item_name"),
              r.getString("their_path"), r.getLong("size_bytes")));
      }
      List<Repair> out = new ArrayList<>();
      for (Map.Entry<String, List<DataSystem.DataLocation>> e : where.entrySet()) {
        Object[] d = detail.get(e.getKey());
        out.add(new Repair(e.getKey(), (String) d[0], (Integer) d[1], List.copyOf(e.getValue())));
      }
      return List.copyOf(out);
    }).subscribeAsCompletionStage();
  }

  private static byte[] hexToBytes(String hex) {
    int n = hex.length() / 2;
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++)
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    return out;
  }
}
