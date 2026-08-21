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
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;

/** Spatial annotation over the bus: boxes on pictures and their promotion. */
/**
 * The public regions service: admission control and routing only. Every operation is performed by the storage layer
 * behind {@code storage} — this verticle holds no backend reference at all (PLAN.md Phase 21, ask 2).
 */
public class RegionsVerticle extends ServiceVerticle {

  public RegionsVerticle(BusGuard guard) {
    super(BusActions.addressOf(BusActions.REGIONS_LIST), guard);
    forward(BusActions.REGIONS_LIST, BusActions.REGIONS_CREATE, BusActions.REGIONS_DELETE,
        BusActions.REGIONS_CREATE_ITEM, BusActions.REGIONS_MAKE_ITEM);
  }
}
