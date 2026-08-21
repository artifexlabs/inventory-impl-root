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

import io.artifexlabs.inventory.api.events.StatusEvent;
import io.artifexlabs.inventory.api.events.StatusEvents;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;

/**
 * The baseline consumer of the {@code status.events} topic: every status event also reaches the operator's log, at the
 * severity it claims.
 *
 * <p>
 * This exists so the topic is never write-only — until the SSE gateway lands (PLAN.md Phase 21), this verticle is the
 * ONLY consumer, and after it lands this remains the record for anyone reading logs. It deliberately duplicates what
 * the emitting component already logged locally: the point is that one subscription shows every emitter's trouble in
 * one place, in a single machine-greppable format.
 */
public class StatusLogVerticle extends AbstractVerticle {
  private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StatusLogVerticle.class);

  @Override
  public void start() {
    this.vertx.eventBus().<JsonObject>consumer(StatusEvents.ADDRESS, message -> {
      try {
        StatusEvent event = StatusEvents.fromWire(message.body());
        String line = "status {} [{}] {} — {} (subject={}, actor={})";
        Object[] args = {
            event.severity(), event.code(), event.source(), event.message(), event.subject(),
            event.actorId().orElse("-")
        };
        switch (event.severity()) {
        case ERROR -> log.error(line, args);
        case WARNING -> log.warn(line, args);
        case INFO -> log.info(line, args);
        }
      } catch (RuntimeException e) {
        log.warn("undecodable status event on {}: {}", StatusEvents.ADDRESS, e.toString());
      }
    });
  }
}
