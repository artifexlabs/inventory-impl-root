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

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.artifexlabs.inventory.api.bus.BusEnvelope;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Concrete {@link BusEnvelope}; the wire shape both ends agree on.
 *
 * Immutable by construction: every field is final (record), the role set is
 * defensively copied, and the mutable {@link JsonObject} payload is deep-
 * copied at the boundaries — on construction, on {@link #data()} access, and
 * into {@link #toJson()} — so nothing holding a reference before or after the
 * envelope exists can alter what the envelope says. Within the fabric's trust
 * model (cluster membership is the only entry point), an admitted envelope's
 * identity and payload are therefore fixed for its lifetime. The extra copies
 * are an accepted cost of that guarantee.
 */
public record DefaultBusEnvelope(int version, String token, String userId, String principal, Set<String> roles,
    String action, Optional<String> targetId, JsonObject data) implements BusEnvelope {

  /** Envelope schema version this build writes. */
  public final static int VERSION = 1;

  public DefaultBusEnvelope {
    if (token == null || token.isBlank())
      throw new IllegalArgumentException("envelope requires the fabric token");
    if (action == null || action.isBlank())
      throw new IllegalArgumentException("envelope requires an action");
    if (userId == null)
      userId = "";
    if (principal == null || principal.isBlank())
      throw new IllegalArgumentException("envelope requires the acting principal");
    roles = Set.copyOf(roles == null ? Set.of() : roles);
    targetId = targetId == null ? Optional.empty() : targetId;
    data = data == null ? new JsonObject() : data.copy();
  }

  /** Defensive copy: mutating the returned object never alters the envelope. */
  @Override
  public JsonObject data() {
    return this.data.copy();
  }

  @Override
  public JsonObject toJson() {
    JsonObject j = new JsonObject().put("v", this.version).put("token", this.token).put("userId", this.userId)
        .put("principal", this.principal).put("roles", new JsonArray(this.roles.stream().sorted().toList()))
        .put("action", this.action).put("data", this.data.copy());
    this.targetId.ifPresent(t -> j.put("targetId", t));
    return j;
  }

  public static DefaultBusEnvelope fromJson(JsonObject j) {
    Set<String> roles = j.getJsonArray("roles", new JsonArray()).stream().map(String::valueOf)
        .collect(Collectors.toSet());
    return new DefaultBusEnvelope(j.getInteger("v", 0), j.getString("token"), j.getString("userId", ""),
        j.getString("principal"), roles, j.getString("action"),
        Optional.ofNullable(j.getString("targetId")), j.getJsonObject("data", new JsonObject()));
  }
}
