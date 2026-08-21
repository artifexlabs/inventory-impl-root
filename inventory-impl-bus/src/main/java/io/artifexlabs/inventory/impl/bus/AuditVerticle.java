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

import java.util.List;

import io.artifexlabs.inventory.api.AuditEvent;
import io.artifexlabs.inventory.api.AuditEventFactory;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;

/**
 * Audit-trail reads over the bus. The global feed demands the admin role
 * (enforced by the guard's action→role registry); per-target history is open
 * to any reader.
 */
/**
 * The public audit service: admission control and routing only. Every operation is performed by the storage layer
 * behind {@code storage} — this verticle holds no backend reference at all (PLAN.md Phase 21, ask 2).
 */
public class AuditVerticle extends ServiceVerticle {

  public AuditVerticle(BusGuard guard) {
    super(BusActions.addressOf(BusActions.AUDIT_RECENT), guard);
    forward(BusActions.AUDIT_RECENT, BusActions.AUDIT_BY_TARGET);
  }
}
