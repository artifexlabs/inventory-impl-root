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

import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Asset storage over the bus. Bytes cross base64 inside the payload — bus
 * messages are fully buffered, accepted for photo-sized assets.
 */
/**
 * The public assets service: admission control and routing only. Every
 * operation is performed by the storage layer behind {@code storage} —
 * this verticle holds no backend reference at all (PLAN.md Phase 21, ask 2).
 */
public class AssetsVerticle extends ServiceVerticle {

  public AssetsVerticle(BusGuard guard) {
    super(BusActions.addressOf(BusActions.ASSETS_STORE), guard);
    forward(BusActions.ASSETS_STORE,
        BusActions.ASSETS_CREATE_ITEM,
        BusActions.ASSETS_REPLACE,
        BusActions.ASSETS_GET,
        BusActions.ASSETS_LIST_FOR,
        BusActions.ASSETS_DELETE);
  }
}
