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

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import io.artifexlabs.inventory.api.events.StatusEvent;
import io.artifexlabs.inventory.api.events.StatusEvents;
import io.artifexlabs.inventory.api.events.StatusPublisher;
import io.artifexlabs.inventory.api.Ulid;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Publishes {@link StatusEvent}s on the Vert.x event bus — the firehose
 * address plus the per-severity address, always {@code publish} (fan-out),
 * never {@code send}. Whether that bus is process-local or clustered is
 * purely Vert.x configuration; this class is identical either way.
 *
 * <p>
 * The event's id and timestamp are stamped HERE, at publication, so identity
 * and time agree with the order consumers actually observe. Failures are
 * swallowed by contract: no user action may fail because its notification
 * could not be delivered — the audit trail remains the record, this is the
 * doorbell.
 */
public class VertxStatusPublisher implements StatusPublisher {
  private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VertxStatusPublisher.class);

  private final Vertx vertx;

  public VertxStatusPublisher(Vertx vertx) {
    this.vertx = requireNonNull(vertx, "vertx");
  }

  @Override
  public void publish(StatusEvent event) {
    try {
      StatusEvent stamped = event.stamped(Ulid.next(), Instant.now());
      JsonObject wire = StatusEvents.toWire(stamped);
      this.vertx.eventBus().publish(StatusEvents.ADDRESS, wire);
      this.vertx.eventBus().publish(StatusEvents.severityAddress(stamped.severity()), wire);
    } catch (RuntimeException e) {
      log.warn("status event {} ({}) not published: {}", event.code(), event.severity(), e.toString());
    }
  }
}
