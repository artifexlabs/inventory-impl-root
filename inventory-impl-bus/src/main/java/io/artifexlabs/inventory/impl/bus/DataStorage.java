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

  static void register(StorageVerticle.Registrar reg, DataSystem data) {
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

    reg.on(BusActions.DATA_MIRRORS,
        env -> data.findMirrorsOf(Envelopes.requireTarget(env)).thenApply(DataStorage::locations));
  }

  private static Object locations(List<DataSystem.DataLocation> found) {
    return new JsonArray(found.stream().map(l -> new JsonObject().put("itemId", l.itemId())
        .put("itemName", l.itemName()).put("path", l.path()).put("sizeBytes", l.sizeBytes())).toList());
  }
}
