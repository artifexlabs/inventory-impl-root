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
package io.artifexlabs.inventory.impl;

import io.artifexlabs.inventory.api.UserStore;

import io.artifexlabs.inventory.api.Ulid;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.DefaultInventoryUser;
import io.artifexlabs.inventory.api.InventoryUser;

import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Postgres {@link UserStore} over the {@code users} table.
 *
 * @author mykel
 *
 */
public class PgUserStore implements UserStore {
  private final Pool pool;

  public PgUserStore(Pool pool) {
    this.pool = requireNonNull(pool, "pool");
  }

  @Override
  public CompletionStage<Optional<InventoryUser>> authenticate(String email, String password) {
    return this.pool.preparedQuery("SELECT id, email, display_name, admin, password_hash FROM users WHERE email=$1")
        .execute(Tuple.of(email)).map(rs -> {
          for (Row r : rs)
            if (Passwords.verify(password, r.getString("password_hash")))
              return Optional.<InventoryUser>of(fromRow(r));
          return Optional.<InventoryUser>empty();
        }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<InventoryUser> ensureUser(String email, String displayName, String password, boolean admin) {
    return this.pool.withTransaction(conn -> conn
        .preparedQuery("""
            INSERT INTO users (id, email, display_name, admin, password_hash)
            VALUES ($1, $2, $3, $4, $5) ON CONFLICT (email) DO NOTHING""")
        .execute(Tuple.of(Ulid.next(), email, displayName, admin, Passwords.hash(password)))
        .flatMap(v -> conn.preparedQuery("SELECT id, email, display_name, admin FROM users WHERE email=$1")
            .execute(Tuple.of(email)))
        .map(rs -> (InventoryUser) fromRow(rs.iterator().next()))).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<java.util.List<InventoryUser>> list() {
    return this.pool.query("SELECT id, email, display_name, admin FROM users ORDER BY email").execute().map(rs -> {
      java.util.List<InventoryUser> out = new java.util.ArrayList<>();
      for (Row r : rs)
        out.add(fromRow(r));
      return out;
    }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Boolean> delete(String id) {
    return this.pool.preparedQuery("DELETE FROM users WHERE id=$1").execute(Tuple.of(id))
        .map(rs -> rs.rowCount() > 0).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Optional<InventoryUser>> setAdmin(String id, boolean admin) {
    return this.pool
        .preparedQuery("UPDATE users SET admin=$2 WHERE id=$1 RETURNING id, email, display_name, admin")
        .execute(Tuple.of(id, admin)).map(rs -> {
          for (Row r : rs)
            return Optional.<InventoryUser>of(fromRow(r));
          return Optional.<InventoryUser>empty();
        }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Optional<InventoryUser>> findByEmail(String email) {
    return this.pool.preparedQuery("SELECT id, email, display_name, admin FROM users WHERE lower(email)=lower($1)")
        .execute(Tuple.of(email)).map(rs -> {
          for (Row r : rs)
            return Optional.<InventoryUser>of(fromRow(r));
          return Optional.<InventoryUser>empty();
        }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Optional<InventoryUser>> findByIdentity(String provider, String subject) {
    return this.pool.preparedQuery("""
        SELECT u.id, u.email, u.display_name, u.admin FROM users u
        JOIN user_identities i ON i.user_id = u.id
        WHERE i.provider=$1 AND i.subject=$2""").execute(Tuple.of(provider, subject)).map(rs -> {
          for (Row r : rs)
            return Optional.<InventoryUser>of(fromRow(r));
          return Optional.<InventoryUser>empty();
        }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Void> linkIdentity(String userId, String provider, String subject) {
    return this.pool.preparedQuery("""
        INSERT INTO user_identities (provider, subject, user_id)
        VALUES ($1, $2, $3) ON CONFLICT (provider, subject) DO NOTHING""")
        .execute(Tuple.of(provider, subject, userId)).map(rs -> (Void) null).subscribeAsCompletionStage();
  }

  private static DefaultInventoryUser fromRow(Row r) {
    return new DefaultInventoryUser(r.getString("id"), r.getString("email"), r.getString("display_name"),
        Boolean.TRUE.equals(r.getBoolean("admin")));
  }
}
