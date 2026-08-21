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

import io.artifexlabs.inventory.api.bus.BusEnvelope;

/**
 * Envelope reading shared by the public service verticles and the storage
 * layer behind them — the two halves the PLAN.md Phase 21 split created.
 */
final class Envelopes {

  private Envelopes() {
  }

  /** The target id the action requires; 400 when the envelope lacks one. */
  static String requireTarget(BusEnvelope envelope) {
    return envelope.targetId()
        .orElseThrow(() -> BusServiceException.badRequest(envelope.action() + " requires a targetId"));
  }

  /** The reply when a mutation reports "nothing matched"; 404 rather than a silent false. */
  static Object okOrNotFound(boolean ok, String message, Object reply) {
    if (!ok)
      throw BusServiceException.notFound(message);
    return reply;
  }
}
