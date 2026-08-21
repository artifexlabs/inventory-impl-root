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

import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.Ulid;

import io.vertx.core.json.JsonArray;

/** Token administration over the bus (admin role via the guard). */
/**
 * The public tokens service: admission control and routing only. Every
 * operation is performed by the storage layer behind {@code storage} —
 * this verticle holds no backend reference at all (MORE_VERTX ask 2).
 */
public class TokensVerticle extends ServiceVerticle {

  public TokensVerticle(BusGuard guard) {
    super(BusActions.addressOf(BusActions.TOKENS_FOR_USER), guard);
    forward(BusActions.TOKENS_FOR_USER,
        BusActions.TOKENS_REVOKE);
  }
}
