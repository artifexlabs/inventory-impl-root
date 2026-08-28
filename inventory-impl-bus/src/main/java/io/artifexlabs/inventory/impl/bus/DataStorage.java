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

import java.util.List;

import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataHashing;
import io.artifexlabs.inventory.api.DataSystem;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Data-manifest operations against the backing store (PLAN.md Phase 21, ask 2).
 *
 * Replacing a manifest is ONE message on purpose: it deletes the old listing, writes the new one, mints or retires the
 * archive items it implies, and audits — all inside the transaction the backend guarantees. Split into per-file
 * messages it would tear exactly the atomicity that makes a manifest a snapshot.
 */
final class DataStorage {

  private DataStorage() {
  }

  static void register(StorageVerticle.Registrar reg, DataSystem data, DataHashing hashing) {
    reg.on(BusActions.DATA_REPLACE_MANIFEST, env -> {
      String itemId = Envelopes.requireTarget(env);
      JsonArray raw = env.data().getJsonArray("entries", new JsonArray());
      final List<DataEntry> entries;
      try {
        entries = raw.stream().map(JsonObject.class::cast).map(DataEntry::fromJson).toList();
      } catch (RuntimeException bad) {
        // a malformed path or hash is the caller's fault, not a 500
        throw BusServiceException.badRequest("unusable manifest entry: " + bad.getMessage());
      }
      return data.actingAs(env.principal()).replaceManifest(itemId, entries)
          .thenApply(stored -> (Object) new JsonObject().put("entries",
              stored.orElseThrow(() -> BusServiceException.notFound("no such data medium"))));
    });

    reg.on(BusActions.DATA_RENAME_PATH, env -> {
      String itemId = Envelopes.requireTarget(env);
      String from = env.data().getString("from");
      String to = env.data().getString("to");
      if (from == null || to == null)
        throw BusServiceException.badRequest("data.rename-path requires data.from and data.to");
      try {
        return data.actingAs(env.principal()).renamePath(itemId, from, to)
            .thenApply(count -> (Object) new JsonObject().put("entries", count));
      } catch (IllegalArgumentException bad) {
        throw BusServiceException.badRequest(bad.getMessage());
      }
    });

    reg.on(BusActions.DATA_ENTRIES,
        env -> data
            .entriesOf(Envelopes.requireTarget(env), env.data().getString("query"), env.data().getInteger("page", 0),
                env.data().getInteger("size", 100))
            .thenApply(list -> (Object) new JsonArray(list.stream().map(DataEntry::toJson).toList())));

    reg.on(BusActions.DATA_SUMMARY,
        env -> data.summaryOf(Envelopes.requireTarget(env))
            .thenApply(s -> (Object) new JsonObject().put("entryCount", s.entryCount())
                .put("totalBytes", s.totalBytes()).put("archiveCount", s.archiveCount())));

    reg.on(BusActions.DATA_BY_HASH, env -> {
      String name = env.data().getString("algorithm", HashAlgorithm.SHA256.algorithmName());
      HashAlgorithm algorithm = HashAlgorithm.byName(name)
          .orElseThrow(() -> BusServiceException.badRequest("unknown hash algorithm: " + name));
      String hash = env.data().getString("hash");
      if (hash == null || !algorithm.accepts(hash.trim().toLowerCase(java.util.Locale.ROOT)))
        throw BusServiceException.badRequest("not a " + algorithm.algorithmName() + " digest: " + hash);
      return data.findByHash(algorithm, hash).thenApply(DataStorage::locations);
    });

    reg.on(BusActions.DATA_OVERLAP,
        env -> data.findOverlappingMedia(Envelopes.requireTarget(env))
            .thenApply(found -> (Object) new JsonArray(found.stream()
                .map(o -> new JsonObject().put("itemId", o.itemId()).put("itemName", o.itemName())
                    .put("sharedEntries", o.sharedEntries()).put("sharedBytes", o.sharedBytes())
                    .put("theirEntries", o.theirEntries()).put("ourEntries", o.ourEntries())
                    .put("identical", o.identical()).put("contains", o.contains()))
                .toList())));

    // A write, and named as one: it rewrites every directory row the medium
    // owns. Idempotent, so running it twice costs time and changes nothing.
    reg.on(BusActions.DATA_ROLLUP, env -> hashing.actingAs(env.principal()).rollUp(Envelopes.requireTarget(env))
        .thenApply(n -> (Object) new JsonObject().put("directories", n)));

    reg.on(BusActions.DATA_REPAIRS,
        env -> hashing.findRepairs(Envelopes.requireTarget(env))
            .thenApply(found -> (Object) new JsonArray(found.stream()
                .map(r -> new JsonObject().put("path", r.path()).put("reason", r.reason()).put("attempts", r.attempts())
                    .put("recoverable", !r.availableOn().isEmpty()).put("availableOn", locations(r.availableOn())))
                .toList())));

    // The progress bar for a job measured in weeks. Read-only and cheap: the
    // counts come off the manifest itself, which is why the operator can poll
    // it without competing with the worker for the medium.
    reg.on(BusActions.DATA_PROGRESS, env -> hashing.progressOf(Envelopes.requireTarget(env)).thenApply(p -> {
      JsonObject reply = new JsonObject().put("pending", p.pending()).put("claimed", p.claimed()).put("done", p.done())
          .put("unreadable", p.unreadable()).put("pendingBytes", p.pendingBytes()).put("fraction", p.fraction())
          .put("complete", p.complete());
      // said out loud rather than left for the caller to derive: a medium can be
      // finished and NOT intact, and conflating the two hides damage
      return (Object) reply.put("intact", p.complete() && p.unreadable() == 0);
    }));

    // The only data action whose target is OPTIONAL: with one, "where else does
    // this medium's content live"; without one, the whole inventory's duplication.
    reg.on(BusActions.DATA_SECTIONS, env -> {
      JsonObject d = env.data();
      DataSystem.SectionQuery sane = DataSystem.SectionQuery.sane(env.targetId().orElse(null),
          enumOf(DataSystem.Match.class, d.getString("match"), DataSystem.Match.STRUCTURE));
      DataSystem.SectionQuery q = new DataSystem.SectionQuery(sane.itemId(), sane.match(),
          enumOf(DataSystem.Scope.class, d.getString("scope"), sane.scope()), d.getInteger("minFiles", sane.minFiles()),
          d.getLong("minBytes", sane.minBytes()), d.getInteger("minDepth", sane.minDepth()),
          d.getInteger("page", sane.page()), d.getInteger("size", sane.size()));
      return data.findDuplicateSections(q).thenApply(DataStorage::sections);
    });
  }

  /** Case-insensitive enum lookup that refuses an unknown name rather than silently defaulting. */
  private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
    if (name == null || name.isBlank())
      return fallback;
    for (E value : type.getEnumConstants())
      if (value.name().equalsIgnoreCase(name.trim()))
        return value;
    throw BusServiceException.badRequest("unknown " + type.getSimpleName().toLowerCase(java.util.Locale.ROOT) + ": "
        + name + " (expected one of " + java.util.Arrays.toString(type.getEnumConstants()) + ")");
  }

  /**
   * Grouped by identity, not flattened. "This folder appears three times" is the answer; the list of places is the
   * evidence, and flattening it would make the caller re-group to read it.
   */
  private static Object sections(List<DataSystem.SectionMatch> found) {
    return new JsonArray(
        found.stream()
            .map(m -> new JsonObject().put("hash", m.hash()).put("subtreeFiles", m.subtreeFiles())
                .put("subtreeBytes", m.subtreeBytes()).put("places", m.locations().size()).put("locations",
                    new JsonArray(m.locations().stream().map(l -> new JsonObject().put("itemId", l.itemId())
                        .put("itemName", l.itemName()).put("path", l.path()).put("depth", l.depth())).toList())))
            .toList());
  }

  private static JsonArray locations(List<DataSystem.DataLocation> found) {
    return new JsonArray(found.stream().map(l -> new JsonObject().put("itemId", l.itemId())
        .put("itemName", l.itemName()).put("path", l.path()).put("sizeBytes", l.sizeBytes())).toList());
  }
}
