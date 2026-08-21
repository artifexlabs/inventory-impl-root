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

import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.RegionSystem;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;

/** Spatial annotation over the bus: boxes on pictures and their promotion. */
/**
 * Regions operations against the backing store (PLAN.md Phase 21, ask 2). These are whole units of work, never
 * composable row CRUD: a caller that had to stitch two of them together would lose the transaction the backend
 * guarantees inside one.
 */
final class RegionsStorage {

  private RegionsStorage() {
  }

  static void register(StorageVerticle.Registrar reg, RegionSystem regions) {
    reg.on(BusActions.REGIONS_LIST, env -> regions.listRegions(Envelopes.requireTarget(env))
        .thenApply(list -> new JsonArray(list.stream().map(r -> r.toJson()).toList())));
    reg.on(BusActions.REGIONS_CREATE, env -> {
      var box = DefaultRegionBox.fromJson(env.data());
      return regions.actingAs(env.principal())
          .createRegion(box.assetId(), box.x(), box.y(), box.w(), box.h(), box.label())
          .thenApply(o -> o.map(r -> r.toJson()).orElseThrow(() -> BusServiceException.notFound("no such asset")));
    });
    reg.on(BusActions.REGIONS_DELETE,
        env -> regions.actingAs(env.principal()).deleteRegion(Envelopes.requireTarget(env)).thenApply(ok -> {
          if (!ok)
            throw BusServiceException.notFound("no such region");
          return null;
        }));
    reg.on(BusActions.REGIONS_CREATE_ITEM, env -> {
      var creation = DefaultRegionItemCreation.fromJson(env.data());
      var box = creation.box();
      return regions.actingAs(env.principal())
          .createItemFromRegion(box.assetId(), box.x(), box.y(), box.w(), box.h(), creation.name(), creation.type(),
              creation.containerId())
          .thenApply(o -> o.map(ItemFactory::serialize)
              .orElseThrow(() -> BusServiceException.notFound("no such asset or container")));
    });
    reg.on(BusActions.REGIONS_MAKE_ITEM, env -> {
      var promotion = DefaultRegionPromotion.fromJson(env.data());
      return regions.actingAs(env.principal())
          .makeItemFromRegion(promotion.regionId(), promotion.name(), promotion.type(), promotion.containerId())
          .thenApply(o -> o.map(ItemFactory::serialize)
              .orElseThrow(() -> BusServiceException.notFound("region unknown or already linked")));
    });
  }

}
