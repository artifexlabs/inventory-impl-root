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

import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.InventoryUser;
import io.artifexlabs.inventory.api.TokenService;
import io.artifexlabs.inventory.api.UserFactory;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.Ulid;
import io.artifexlabs.inventory.api.UserStore;

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
 * Authentication as storage domain operations (PLAN.md Phase 21, ask 2): a login
 * is one whole unit of work over users, tokens and the audit sink — exactly
 * the kind of thing that must NOT be composed from separate messages.
 */
final class AuthStorage {

  private AuthStorage() {
  }

  static void register(StorageVerticle.Registrar reg, UserStore users, TokenService tokens, AuditSink audit, String provision) {
    reg.on(BusActions.AUTH_LOGIN, env -> {
      final DefaultCredentials credentials;
      try {
        credentials = DefaultCredentials.fromJson(env.data());
      } catch (IllegalArgumentException e) {
        throw BusServiceException.unauthorized("invalid credentials");
      }
      return users.authenticate(credentials.email(), credentials.password())
          .thenCompose(o -> o.map(u -> issue(tokens, u))
              .orElseThrow(() -> BusServiceException.unauthorized("invalid credentials")));
    });
    reg.on(BusActions.AUTH_TOKEN, env -> {
      String token = env.data().getString("token");
      if (token == null || token.isBlank())
        throw BusServiceException.unauthorized("missing bearer token");
      return tokens.authenticate(token)
          .thenApply(o -> o.map(u -> (Object) UserFactory.serialize(u))
              .orElseThrow(() -> BusServiceException.unauthorized("unknown or revoked token")));
    });
    reg.on(BusActions.AUTH_REVOKE, env -> {
      String token = env.data().getString("token", "");
      return tokens.revoke(token).thenApply(ok -> new JsonObject().put("revoked", ok));
    });
    reg.on(BusActions.AUTH_EXCHANGE, env -> {
      final DefaultIdentityClaim claim;
      try {
        claim = DefaultIdentityClaim.fromJson(env.data());
      } catch (IllegalArgumentException e) {
        throw BusServiceException.badRequest(e.getMessage());
      }
      if (claim.provider() == null || claim.provider().isBlank() || claim.subject() == null
          || claim.subject().isBlank())
        return byEmail(users, tokens, audit, provision, claim, false);
      return users.findByIdentity(claim.provider(), claim.subject())
          .thenCompose(known -> known.isPresent() ? issue(tokens, known.get())
              : byEmail(users, tokens, audit, provision, claim, true));
    });
    }


  /**
   * Email-keyed path of the exchange: link identity to a matching user, or
   * provision per policy. Mirrors the pre-bus OidcExchangeResource behavior,
   * identity winning over email when both are present.
   */
  private static CompletionStage<Object> byEmail(UserStore users, TokenService tokens, AuditSink audit,
      String provision, DefaultIdentityClaim claim, boolean withIdentity) {
    return users.findByEmail(claim.email()).thenCompose(existing -> {
      if (existing.isPresent())
        return link(users, audit, existing.get(), claim, withIdentity).thenCompose(v -> issue(tokens, existing.get()));
      if (!"auto".equals(provision))
        throw BusServiceException.forbidden("not invited: " + claim.email());
      // auto-provision with an unguessable password: the account is OIDC-only in practice
      return users.ensureUser(claim.email(), claim.displayName(), UUID.randomUUID().toString(), false)
          .thenCompose(u -> link(users, audit, u, claim, withIdentity)
              .thenCompose(x -> audit
                  .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), claim.email(), "user.create", u.getId(),
                      new JsonObject().put("email", claim.email()).put("via", "oidc-auto-provision")
                          .put("provider", claim.provider() == null ? "unknown" : claim.provider())))
                  .thenCompose(v -> issue(tokens, u))));
    });
  }

  private static CompletionStage<Void> link(UserStore users, AuditSink audit, InventoryUser user,
      DefaultIdentityClaim claim, boolean withIdentity) {
    if (!withIdentity)
      return CompletableFuture.completedStage(null);
    return users.linkIdentity(user.getId(), claim.provider(), claim.subject())
        .thenCompose(v -> audit.record(new DefaultAuditEvent(Ulid.next(), Instant.now(), user.getEmail(),
            "user.identity-link", user.getId(), new JsonObject().put("provider", claim.provider()))))
        .thenApply(v -> null);
  }

  private static CompletionStage<Object> issue(TokenService tokens, InventoryUser user) {
    return tokens.issue(user)
        .thenApply(t -> new JsonObject().put("token", t).put("user", UserFactory.serialize(user)));
  }
}
