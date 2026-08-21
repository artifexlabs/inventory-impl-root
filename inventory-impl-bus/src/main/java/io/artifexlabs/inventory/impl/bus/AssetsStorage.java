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

import io.artifexlabs.inventory.api.AssetStore;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Asset storage over the bus. Bytes cross base64 inside the payload — bus
 * messages are fully buffered, accepted for photo-sized assets.
 */
/**
 * Assets operations against the backing store (MORE_VERTX ask 2). These are
 * whole units of work, never composable row CRUD: a caller that had to
 * stitch two of them together would lose the transaction the backend
 * guarantees inside one.
 */
final class AssetsStorage {

  private AssetsStorage() {
  }

  static void register(StorageVerticle.Registrar reg, AssetStore assets) {
    reg.on(BusActions.ASSETS_STORE, env -> {
      var upload = DefaultAssetUpload.fromJson(env.data());
      return assets.actingAs(env.principal())
          .store(upload.itemId(), upload.filename(), upload.contentType(), upload.bytes(),
              upload.coordinates().orElse(null), upload.kind())
          .thenApply(o -> o.map(info -> info.toJson())
              .orElseThrow(() -> BusServiceException.notFound("no such item")));
    });
    reg.on(BusActions.ASSETS_CREATE_ITEM, env -> {
      var req = DefaultPhotoItemRequest.fromJson(env.data());
      return assets.actingAs(env.principal())
          .createItemFromPhoto(req.name(), req.displayName(), req.type(), req.containerId(), req.filename(),
              req.contentType(), req.bytes(), req.coordinates().orElse(null), req.kind())
          .thenApply(o -> o
              .map(made -> new JsonObject()
                  .put("item", io.artifexlabs.inventory.api.ItemFactory.serialize(made.item()))
                  .put("asset", made.asset().toJson()))
              .orElseThrow(() -> BusServiceException.notFound("no such container")));
    });
    reg.on(BusActions.ASSETS_REPLACE, env -> {
      var content = DefaultAssetContent.fromJson(env.data());
      return assets.actingAs(env.principal())
          .replace(Envelopes.requireTarget(env), content.filename(), content.contentType(), content.bytes(),
              content.coordinates().orElse(null))
          .thenApply(o -> o.map(info -> info.toJson())
              .orElseThrow(() -> BusServiceException.notFound("no such asset")));
    });
    reg.on(BusActions.ASSETS_GET, env -> assets.get(Envelopes.requireTarget(env))
        .thenApply(o -> o
            .map(stored -> new JsonObject().put("info", stored.info().toJson()).put("bytes", stored.data()))
            .orElseThrow(() -> BusServiceException.notFound("no such asset"))));
    reg.on(BusActions.ASSETS_LIST_FOR, env -> assets.listFor(Envelopes.requireTarget(env))
        .thenApply(list -> new JsonArray(list.stream().map(i -> i.toJson()).toList())));
    reg.on(BusActions.ASSETS_DELETE, env -> assets.actingAs(env.principal()).delete(Envelopes.requireTarget(env)).thenApply(ok -> {
      if (!ok)
        throw BusServiceException.notFound("no such asset");
      return null;
    }));
    }

}
