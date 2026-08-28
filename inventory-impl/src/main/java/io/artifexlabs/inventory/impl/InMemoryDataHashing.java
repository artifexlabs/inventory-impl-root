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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataHashing;
import io.artifexlabs.inventory.api.HashAlgorithm;

/**
 * In-memory {@link DataHashing}: the behavioural reference the Postgres implementation must match.
 *
 * <p>
 * The hash state lives HERE rather than on {@link DataEntry}, because a claim is not part of what a manifest says about
 * a file — it is what a worker is currently doing about one. Keeping it separate is also what lets the two backends be
 * compared: Postgres stores the same thing in columns, and neither shape leaks through the interface.
 *
 * <p>
 * Claims are single-threaded here by {@code synchronized}, which is the in-memory equivalent of what
 * {@code FOR UPDATE SKIP LOCKED} buys the Pg backend — two workers never receive the same file.
 */
public class InMemoryDataHashing implements DataHashing {

  /** What a worker is doing about one file. Absent means pending. */
  private record Claim(String worker, Instant at) {
  }

  private final InMemoryDataSystem data;
  /** itemId -> (path -> claim) */
  private final ConcurrentHashMap<String, Map<String, Claim>> claims;
  private final String principal;

  public InMemoryDataHashing(InMemoryDataSystem data, String principal) {
    this(data, new ConcurrentHashMap<>(), principal);
  }

  private InMemoryDataHashing(InMemoryDataSystem data, ConcurrentHashMap<String, Map<String, Claim>> claims,
      String principal)
  {
    this.data = requireNonNull(data, "data");
    this.claims = claims;
    this.principal = requireNonNull(principal, "principal");
  }

  @Override
  public InMemoryDataHashing actingAs(String principal) {
    return new InMemoryDataHashing(this.data, this.claims, principal);
  }

  /**
   * Damage lives on the manifest side, not here — {@code hash_state = 2} is a column on {@code data_entries} in
   * Postgres, so the tree already knows which of its files could not be read. Anywhere else and the rollup could not
   * tell damage from work still to do.
   */
  private Map<String, InMemoryDataSystem.Damage> damagedOn(String itemId) {
    return this.data.damageOn(itemId);
  }

  @Override
  public synchronized CompletionStage<List<PendingFile>> claim(String itemId, String worker, int limit) {
    if (limit <= 0)
      return CompletableFuture.completedStage(List.of());
    Map<String, Claim> held = this.claims.computeIfAbsent(itemId, k -> new java.util.LinkedHashMap<>());
    Map<String, InMemoryDataSystem.Damage> bad = damagedOn(itemId);
    List<PendingFile> taken = new ArrayList<>();
    // path order, so a run walks the tree depth-first exactly as the Pg
    // ORDER BY path_text does
    for (DataEntry e : sortedEntries(itemId)) {
      if (taken.size() >= limit)
        break;
      if (e.hash() != null || held.containsKey(e.path()) || bad.containsKey(e.path()) || e.isDirectory())
        continue;
      held.put(e.path(), new Claim(worker, Instant.now()));
      taken.add(new PendingFile(e.path(), e.sizeBytes(), e.modified().orElse(null)));
    }
    return CompletableFuture.completedStage(List.copyOf(taken));
  }

  @Override
  public synchronized CompletionStage<Boolean> complete(String itemId, PendingFile claimed, HashAlgorithm algorithm,
      String hash) {
    Map<String, Claim> held = this.claims.getOrDefault(itemId, Map.of());
    if (!held.containsKey(claimed.path()))
      return CompletableFuture.completedStage(false);
    DataEntry current = this.data.entryAt(itemId, claimed.path());
    // the medium may have been re-described since the claim; a digest computed
    // against the file it USED to hold must not land on the row
    if (current == null || current.sizeBytes() != claimed.sizeBytes()
        || !Objects.equals(current.modified().orElse(null), claimed.modifiedAt()))
      return CompletableFuture.completedStage(false);
    this.data.applyHash(itemId, claimed.path(), algorithm, hash);
    this.claims.get(itemId).remove(claimed.path());
    return CompletableFuture.completedStage(true);
  }

  @Override
  public synchronized CompletionStage<Void> unreadable(String itemId, String path, String reason) {
    this.data.recordDamage(itemId, path, reason);
    Map<String, Claim> held = this.claims.get(itemId);
    if (held != null)
      held.remove(path);
    return CompletableFuture.completedStage(null);
  }

  @Override
  public synchronized CompletionStage<Integer> reclaimStale(String itemId, long leaseSeconds) {
    Map<String, Claim> held = this.claims.get(itemId);
    if (held == null)
      return CompletableFuture.completedStage(0);
    Instant cutoff = Instant.now().minusSeconds(leaseSeconds);
    List<String> stale = held.entrySet().stream().filter(e -> e.getValue().at().isBefore(cutoff)).map(Map.Entry::getKey)
        .toList();
    stale.forEach(held::remove);
    return CompletableFuture.completedStage(stale.size());
  }

  @Override
  public synchronized CompletionStage<Progress> progressOf(String itemId) {
    Map<String, Claim> held = this.claims.getOrDefault(itemId, Map.of());
    Map<String, InMemoryDataSystem.Damage> bad = damagedOn(itemId);
    long pending = 0, claimed = 0, done = 0, unreadable = 0, pendingBytes = 0;
    for (DataEntry e : sortedEntries(itemId)) {
      if (e.isDirectory())
        continue;
      if (bad.containsKey(e.path()))
        unreadable++;
      else if (e.hash() != null)
        done++;
      else if (held.containsKey(e.path())) {
        claimed++;
        pendingBytes += e.sizeBytes();
      } else {
        pending++;
        pendingBytes += e.sizeBytes();
      }
    }
    return CompletableFuture.completedStage(new Progress(pending, claimed, done, unreadable, pendingBytes));
  }

  @Override
  public synchronized CompletionStage<Integer> rollUp(String itemId) {
    return CompletableFuture.completedStage(this.data.rollUp(itemId));
  }

  @Override
  public CompletionStage<List<Repair>> findRepairs(String itemId) {
    return this.data.findRepairs(itemId);
  }

  private List<DataEntry> sortedEntries(String itemId) {
    return this.data.manifestOf(itemId).stream().sorted(Comparator.comparing(DataEntry::path)).toList();
  }
}
