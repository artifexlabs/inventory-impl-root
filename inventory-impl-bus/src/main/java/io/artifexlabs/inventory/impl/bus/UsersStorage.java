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

import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.UserFactory;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.Ulid;
import io.artifexlabs.inventory.api.UserStore;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * User administration over the bus (admin role via the guard). Every mutation
 * writes its own audit entry with the envelope's principal as the actor. The
 * self-delete refusal keys on the envelope's acting userId.
 */
/**
 * Users operations against the backing store (PLAN.md Phase 21, ask 2). These are whole units of work, never composable
 * row CRUD: a caller that had to stitch two of them together would lose the transaction the backend guarantees inside
 * one.
 */
final class UsersStorage {

  private UsersStorage() {
  }

  static void register(StorageVerticle.Registrar reg, UserStore users, AuditSink audit) {
    reg.on(BusActions.USERS_LIST,
        env -> users.list().thenApply(list -> new JsonArray(list.stream().map(UserFactory::serialize).toList())));
    reg.on(BusActions.USERS_CREATE, env -> {
      final DefaultUserCreation creation;
      try {
        creation = DefaultUserCreation.fromJson(env.data());
      } catch (IllegalArgumentException e) {
        throw BusServiceException.badRequest(e.getMessage());
      }
      return users.ensureUser(creation.email(), creation.displayName(), creation.password(), creation.admin())
          .thenCompose(
              u -> record(audit, env.principal(), "user.create", u.getId(), new JsonObject().put("email", u.getEmail()))
                  .thenApply(v -> UserFactory.serialize(u)));
    });
    reg.on(BusActions.USERS_DELETE, env -> {
      String id = Envelopes.requireTarget(env);
      if (id.equals(env.userId()))
        return CompletableFuture.failedStage(BusServiceException.conflict("cannot delete yourself"));
      return users.delete(id).thenCompose(ok -> {
        if (!ok)
          throw BusServiceException.notFound("no such user");
        return record(audit, env.principal(), "user.delete", id, null).thenApply(v -> null);
      });
    });
    reg.on(BusActions.USERS_SET_ADMIN, env -> {
      var change = DefaultAdminChange.fromJson(env.data());
      return users.setAdmin(change.userId(), change.admin())
          .thenCompose(o -> o
              .map(u -> record(audit, env.principal(), "user.set-admin", change.userId(),
                  new JsonObject().put("admin", change.admin())).thenApply(v -> (Object) UserFactory.serialize(u)))
              .orElseThrow(() -> BusServiceException.notFound("no such user")));
    });
  }

  private static java.util.concurrent.CompletionStage<Void> record(AuditSink audit, String principal, String action,
      String targetId, JsonObject details) {
    return audit.record(new DefaultAuditEvent(Ulid.next(), Instant.now(), principal, action, targetId, details));
  }
}
