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

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.UserFactory;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.Ulid;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * User administration over the bus (admin role via the guard). Every mutation
 * writes its own audit entry with the envelope's principal as the actor. The
 * self-delete refusal keys on the envelope's acting userId.
 */
/**
 * The public users service: admission control and routing only. Every operation is performed by the storage layer
 * behind {@code storage} — this verticle holds no backend reference at all (PLAN.md Phase 21, ask 2).
 */
public class UsersVerticle extends ServiceVerticle {

  public UsersVerticle(BusGuard guard) {
    super(BusActions.addressOf(BusActions.USERS_LIST), guard);
    forward(BusActions.USERS_LIST, BusActions.USERS_CREATE, BusActions.USERS_DELETE, BusActions.USERS_SET_ADMIN);
  }
}
