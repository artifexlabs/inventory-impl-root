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

import io.artifexlabs.inventory.api.Ulid;

import static java.util.Objects.requireNonNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.DefaultInventoryUser;
import io.artifexlabs.inventory.api.InventoryUser;
import io.artifexlabs.inventory.api.TokenService;

import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Postgres {@link TokenService} over the {@code api_tokens} table. Tokens are ULIDs; revocation is a flag so the trail
 * of issued tokens survives.
 *
 * @author mykel
 *
 */
public class PgTokenService implements TokenService {
  private final Pool pool;

  public PgTokenService(Pool pool) {
    this.pool = requireNonNull(pool, "pool");
  }

  @Override
  public CompletionStage<Optional<InventoryUser>> authenticate(String token) {
    return this.pool.preparedQuery("""
        SELECT u.id, u.email, u.display_name, u.admin FROM api_tokens t
        JOIN users u ON u.id = t.user_id WHERE t.token=$1 AND NOT t.revoked""")
        .execute(Tuple.of(token == null ? "" : token)).map(rs -> {
          for (Row r : rs)
            return Optional.<InventoryUser>of(new DefaultInventoryUser(r.getString("id"), r.getString("email"),
                r.getString("display_name"), Boolean.TRUE.equals(r.getBoolean("admin"))));
          return Optional.<InventoryUser>empty();
        }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<String> issue(InventoryUser user) {
    String token = Ulid.next();
    return this.pool
        .preparedQuery("INSERT INTO api_tokens (token, user_id, issued_at, revoked) VALUES ($1, $2, $3, false)")
        .execute(Tuple.of(token, user.getId(), OffsetDateTime.now(ZoneOffset.UTC))).map(v -> token)
        .subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Boolean> revoke(String token) {
    return this.pool.preparedQuery("UPDATE api_tokens SET revoked=true WHERE token=$1 AND NOT revoked")
        .execute(Tuple.of(token)).map(rs -> rs.rowCount() > 0).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<java.util.List<io.artifexlabs.inventory.api.TokenInfo>> tokensFor(String userId) {
    return this.pool
        .preparedQuery(
            "SELECT token, user_id, issued_at, revoked FROM api_tokens WHERE user_id=$1 ORDER BY issued_at DESC")
        .execute(Tuple.of(userId)).map(rs -> {
          java.util.List<io.artifexlabs.inventory.api.TokenInfo> out = new java.util.ArrayList<>();
          for (Row r : rs)
            out.add(new io.artifexlabs.inventory.api.TokenInfo(r.getString("token"), r.getString("user_id"),
                r.getOffsetDateTime("issued_at").toInstant(), Boolean.TRUE.equals(r.getBoolean("revoked"))));
          return out;
        }).subscribeAsCompletionStage();
  }
}
