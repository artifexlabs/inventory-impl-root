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

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;

/** Item CRUD and containment over the bus. */
/**
 * The public items service: admission control and routing only. Every operation is performed by the storage layer
 * behind {@code storage} — this verticle holds no backend reference at all (PLAN.md Phase 21, ask 2).
 */
public class ItemsVerticle extends ServiceVerticle {

  public ItemsVerticle(BusGuard guard) {
    super(BusActions.addressOf(BusActions.ITEMS_LIST), guard);
    forward(BusActions.ITEMS_LIST, BusActions.ITEMS_LIST_OF_TYPE, BusActions.ITEMS_GET, BusActions.ITEMS_CREATE,
        BusActions.ITEMS_UPDATE, BusActions.ITEMS_DELETE, BusActions.ITEMS_CONTAINER_OF, BusActions.ITEMS_COORDINATES,
        BusActions.ITEMS_TAG, BusActions.ITEMS_UNTAG, BusActions.ITEMS_FIND_BY_TAG, BusActions.ITEMS_IDENTITY_ADD,
        BusActions.ITEMS_IDENTITY_REMOVE, BusActions.ITEMS_FIND_BY_IDENTITY, BusActions.ITEMS_IDENTITIES_OF,
        BusActions.ITEMS_CONTAIN, BusActions.ITEMS_UNCONTAIN, BusActions.ITEMS_MOVE);
  }
}
