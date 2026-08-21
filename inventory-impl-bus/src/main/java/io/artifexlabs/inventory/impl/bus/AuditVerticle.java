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
import io.artifexlabs.inventory.api.AuditReader;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;

/**
 * Audit-trail reads over the bus. The global feed demands the admin role
 * (enforced by the guard's action→role registry); per-target history is open
 * to any reader.
 */
public class AuditVerticle extends ServiceVerticle {

  private final static int MAX_LIMIT = 200;

  public AuditVerticle(BusGuard guard, AuditReader audit) {
    super(BusActions.addressOf(BusActions.AUDIT_RECENT), guard);
    on(BusActions.AUDIT_RECENT, env -> audit
        .recent(clamp(env.data().getInteger("limit", 50)), Math.max(0, env.data().getInteger("offset", 0)))
        .thenApply(AuditVerticle::serialize));
    on(BusActions.AUDIT_BY_TARGET,
        env -> audit.byTarget(requireTarget(env), clamp(env.data().getInteger("limit", 50)))
            .thenApply(AuditVerticle::serialize));
  }

  private static int clamp(int limit) {
    return Math.max(1, Math.min(limit, MAX_LIMIT));
  }

  private static Object serialize(List<AuditEvent> events) {
    return new JsonArray(events.stream().map(AuditEventFactory::serialize).toList());
  }
}
