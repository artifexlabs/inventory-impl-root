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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.InventoryUser;
import io.artifexlabs.inventory.api.UserFactory;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.Ulid;

import io.vertx.core.json.JsonObject;

/**
 * The authentication service: credential login, bearer-token resolution,
 * revocation, and the trusted OIDC identity exchange. These are the pre-auth
 * actions — the guard admits them on the fabric token alone, because they run
 * before an acting user exists. The HTTP gateway's own authentication filter
 * is this verticle's caller: user identity is stored here, so resolving a
 * presented token is bus work like everything else.
 *
 * Exchange provisioning policy ({@code invited} or {@code auto}) is the
 * worker's configuration — the domain decides who may become a user.
 */
/**
 * The public auth service: admission control and routing only; the login and token operations happen in the storage
 * layer (PLAN.md Phase 21, ask 2).
 */
public class AuthVerticle extends ServiceVerticle {

  public AuthVerticle(BusGuard guard) {
    super(BusActions.addressOf(BusActions.AUTH_LOGIN), guard);
    forward(BusActions.AUTH_LOGIN, BusActions.AUTH_TOKEN, BusActions.AUTH_REVOKE, BusActions.AUTH_EXCHANGE);
  }
}
