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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.bus.BusEnvelope;

import io.vertx.core.json.JsonObject;

/**
 * The single admission checkpoint every worker runs before touching an
 * envelope: the fabric token must match (constant-time), the action must be
 * part of the vocabulary, and the envelope's asserted roles must include the
 * action's required role. Refusals are 400/401/403 before any work happens.
 */
public final class BusGuard {

  private final byte[] fabricToken;

  public BusGuard(String fabricToken) {
    if (fabricToken == null || fabricToken.isBlank())
      throw new IllegalArgumentException("a fabric token is required");
    this.fabricToken = fabricToken.getBytes(StandardCharsets.UTF_8);
  }

  /** Validate and admit a raw message body; throws {@link BusServiceException}. */
  public BusEnvelope admit(JsonObject body) {
    if (body == null)
      throw BusServiceException.badRequest("empty message body");
    final BusEnvelope envelope;
    try {
      envelope = DefaultBusEnvelope.fromJson(body);
    } catch (RuntimeException e) {
      throw BusServiceException.badRequest("malformed envelope: " + e.getMessage());
    }
    if (!BusActions.known(envelope.action()))
      throw BusServiceException.badRequest("unknown bus action: " + envelope.action());
    if (!MessageDigest.isEqual(this.fabricToken, envelope.token().getBytes(StandardCharsets.UTF_8)))
      throw BusServiceException.unauthorized("bad fabric token");
    Optional<String> required = BusActions.requiredRole(envelope.action());
    if (required.isPresent() && !envelope.roles().contains(required.get()))
      throw BusServiceException.forbidden("action " + envelope.action() + " requires role " + required.get());
    return envelope;
  }
}
