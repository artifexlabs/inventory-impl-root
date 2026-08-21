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

import io.artifexlabs.inventory.api.AuditEvent;
import io.artifexlabs.inventory.api.events.EventPublisher;
import io.artifexlabs.inventory.api.events.InventoryEvents;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Publishes committed facts on the Vert.x event bus — the firehose address plus the per-category address, always
 * {@code publish} (fan-out), never {@code send}. Whether that bus is process-local or clustered is purely Vert.x
 * configuration; this class is identical either way. Failures are swallowed by contract: the audit table is the log of
 * record, consumers reconcile from it, and a mutation must never fail because a notification could not be delivered.
 */
public class VertxEventPublisher implements EventPublisher {
  private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VertxEventPublisher.class);

  private final Vertx vertx;

  public VertxEventPublisher(Vertx vertx) {
    this.vertx = requireNonNull(vertx, "vertx");
  }

  @Override
  public void publish(AuditEvent event) {
    try {
      JsonObject wire = InventoryEvents.toWire(event);
      this.vertx.eventBus().publish(InventoryEvents.ADDRESS, wire);
      this.vertx.eventBus().publish(InventoryEvents.categoryAddress(event.getAction()), wire);
    } catch (RuntimeException e) {
      log.warn("event {} ({}) not published: {}", event.getId(), event.getAction(), e.toString());
    }
  }
}
