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

import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.TokenService;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.Ulid;

import io.vertx.core.json.JsonArray;

/** Token administration over the bus (admin role via the guard). */
public class TokensVerticle extends ServiceVerticle {

  public TokensVerticle(BusGuard guard, TokenService tokens, AuditSink audit) {
    super(BusActions.addressOf(BusActions.TOKENS_FOR_USER), guard);
    on(BusActions.TOKENS_FOR_USER, env -> tokens.tokensFor(requireTarget(env))
        .thenApply(list -> new JsonArray(list.stream().map(t -> t.toJson()).toList())));
    on(BusActions.TOKENS_REVOKE, env -> {
      String token = requireTarget(env);
      return tokens.revoke(token).thenCompose(ok -> {
        if (!ok)
          throw BusServiceException.notFound("no such token");
        return audit
            .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), env.principal(), "token.revoke", token, null))
            .thenApply(v -> null);
      });
    });
  }
}
