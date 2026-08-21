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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import io.artifexlabs.inventory.api.AuditEvent;
import io.artifexlabs.inventory.api.AuditReader;
import io.artifexlabs.inventory.api.AuditSink;

/**
 * An {@link AuditSink} that holds events in memory and doubles as the {@link AuditReader} over them. Suitable for tests
 * and dev-mode storage; production uses the transactional Postgres trail.
 *
 * @author mykel
 *
 */
public class InMemoryAuditSink implements AuditSink, AuditReader {
  private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

  @Override
  public CompletionStage<Void> record(AuditEvent event) {
    this.events.add(event);
    return CompletableFuture.completedStage(null);
  }

  public List<AuditEvent> getEvents() {
    return List.copyOf(this.events);
  }

  private java.util.stream.Stream<AuditEvent> newestFirst() {
    return this.events.stream().sorted(java.util.Comparator.comparing(AuditEvent::getTimestamp).reversed());
  }

  @Override
  public CompletionStage<List<AuditEvent>> recent(int limit, int offset) {
    return CompletableFuture.completedStage(newestFirst().skip(offset).limit(limit).toList());
  }

  @Override
  public CompletionStage<List<AuditEvent>> byTarget(String targetId, int limit) {
    return CompletableFuture
        .completedStage(newestFirst().filter(e -> e.getTargetId().equals(targetId)).limit(limit).toList());
  }

  @Override
  public CompletionStage<List<SequencedEvent>> since(long afterSeq, int limit) {
    // the list is append-ordered; a 1-based position stands in for the Pg seq
    List<AuditEvent> snapshot = List.copyOf(this.events);
    List<SequencedEvent> out = new java.util.ArrayList<>();
    for (long s = Math.max(0, afterSeq); s < snapshot.size() && out.size() < limit; s++)
      out.add(new SequencedEvent(s + 1, snapshot.get((int) s)));
    return CompletableFuture.completedStage(out);
  }
}
