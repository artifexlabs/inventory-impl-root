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

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import io.artifexlabs.inventory.api.InventoryUser;
import io.artifexlabs.inventory.api.TokenInfo;
import io.artifexlabs.inventory.api.TokenService;

/**
 * In-memory {@link TokenService} for dev and test profiles. Tokens are ULIDs; {@link #seed(String, InventoryUser)}
 * installs a well-known token (the configured dev token) so local flows work without logging in first. Revocation keeps
 * the record, mirroring the Postgres implementation.
 *
 * @author mykel
 *
 */
public class InMemoryTokenService implements TokenService {
  private record Stored(InventoryUser user, TokenInfo info) {
  }

  private final Map<String, Stored> tokens = new ConcurrentHashMap<>();

  public InMemoryTokenService seed(String token, InventoryUser user) {
    this.tokens.put(token, new Stored(user, new TokenInfo(token, user.getId(), Instant.now(), false)));
    return this;
  }

  @Override
  public CompletionStage<Optional<InventoryUser>> authenticate(String token) {
    Stored s = token == null ? null : this.tokens.get(token);
    return CompletableFuture.completedStage(s == null || s.info().revoked() ? Optional.empty() : Optional.of(s.user()));
  }

  @Override
  public CompletionStage<String> issue(InventoryUser user) {
    String token = Ulid.next();
    this.tokens.put(token, new Stored(user, new TokenInfo(token, user.getId(), Instant.now(), false)));
    return CompletableFuture.completedStage(token);
  }

  @Override
  public CompletionStage<Boolean> revoke(String token) {
    boolean[] did = {
        false
    };
    this.tokens.computeIfPresent(token, (k, s) -> {
      if (s.info().revoked())
        return s;
      did[0] = true;
      return new Stored(s.user(), new TokenInfo(s.info().token(), s.info().userId(), s.info().issuedAt(), true));
    });
    return CompletableFuture.completedStage(did[0]);
  }

  @Override
  public CompletionStage<List<TokenInfo>> tokensFor(String userId) {
    return CompletableFuture.completedStage(this.tokens.values().stream().map(Stored::info)
        .filter(i -> i.userId().equals(userId)).sorted(Comparator.comparing(TokenInfo::issuedAt).reversed()).toList());
  }
}
