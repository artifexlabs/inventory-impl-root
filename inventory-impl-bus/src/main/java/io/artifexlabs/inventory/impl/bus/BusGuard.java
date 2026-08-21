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
import io.artifexlabs.inventory.api.events.StatusEvent;
import io.artifexlabs.inventory.api.events.StatusPublisher;

import io.vertx.core.json.JsonObject;

/**
 * The single admission checkpoint every worker runs before touching an
 * envelope: the fabric token must match (constant-time), the action must be
 * part of the vocabulary, and the envelope's asserted roles must include the
 * action's required role. Refusals are 400/401/403 before any work happens.
 */
public final class BusGuard {

  /** The {@code source} every StatusEvent from admission control carries. */
  private final static String SOURCE = "bus.guard";

  private final byte[] fabricToken;
  /** Denials reach a human here, not only the gateway's error response (PLAN.md Phase 21). */
  private final StatusPublisher status;

  public BusGuard(String fabricToken) {
    this(fabricToken, StatusPublisher.NOOP);
  }

  public BusGuard(String fabricToken, StatusPublisher status) {
    if (fabricToken == null || fabricToken.isBlank())
      throw new IllegalArgumentException("a fabric token is required");
    this.fabricToken = fabricToken.getBytes(StandardCharsets.UTF_8);
    this.status = status == null ? StatusPublisher.NOOP : status;
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
    if (!MessageDigest.isEqual(this.fabricToken, envelope.token().getBytes(StandardCharsets.UTF_8))) {
      // deliberately NOT reporting the presented token, nor an actor: a bad
      // fabric token means the caller is unauthenticated at the fabric level
      this.status.publish(StatusEvent.error("bus.bad-fabric-token",
          "A request was rejected because it did not carry a valid service token.")
          .source(SOURCE).subject("action", envelope.action())
          .detail("The event-bus fabric token did not match. This is a deployment/configuration fault "
              + "unless something is probing the bus."));
      throw BusServiceException.unauthorized("bad fabric token");
    }
    Optional<String> required = BusActions.requiredRole(envelope.action());
    if (required.isPresent() && !envelope.roles().contains(required.get())) {
      this.status.publish(StatusEvent.warning("bus.forbidden",
          "You do not have permission to perform that action.")
          .source(SOURCE).subject("action", envelope.action()).subject("requiredRole", required.get())
          .actor(envelope.userId())
          .detail("Action " + envelope.action() + " requires the role " + required.get() + "."));
      throw BusServiceException.forbidden("action " + envelope.action() + " requires role " + required.get());
    }
    return envelope;
  }
}
