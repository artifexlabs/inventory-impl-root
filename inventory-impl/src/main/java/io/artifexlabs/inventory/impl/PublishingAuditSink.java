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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.AuditEvent;
import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.events.EventPublisher;

/**
 * Decorates an {@link AuditSink} so every recorded event is also published as a domain fact once the record completes.
 * This covers the paths that flow through the sink — the in-memory systems and the resource-layer standalone events
 * ({@code label.print}, {@code user.*}, {@code
 * token.revoke} via {@code PgAudit}, now in inventory-impl-pg) — while the Pg domain systems, whose audit rows are
 * written inside their own transactions, publish after commit themselves.
 */
public class PublishingAuditSink implements AuditSink {

  private final AuditSink delegate;
  private final EventPublisher events;

  public PublishingAuditSink(AuditSink delegate, EventPublisher events) {
    this.delegate = requireNonNull(delegate, "delegate");
    this.events = requireNonNull(events, "events");
  }

  @Override
  public CompletionStage<Void> record(AuditEvent event) {
    return this.events.announce(this.delegate.record(event), event);
  }
}
