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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.AuditEvent;
import io.artifexlabs.inventory.api.AuditReader;
import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.DefaultAuditEvent;

import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Postgres audit trail: {@link AuditSink} for standalone events (admin
 * actions — item mutations write their rows inside their own transactions in
 * {@link PgInventorySystem}) and {@link AuditReader} over the whole table.
 *
 * @author mykel
 *
 */
public class PgAudit implements AuditSink, AuditReader {
  private final Pool pool;

  public PgAudit(Pool pool) {
    this.pool = requireNonNull(pool, "pool");
  }

  @Override
  public CompletionStage<Void> record(AuditEvent e) {
    return this.pool
        .preparedQuery("INSERT INTO audit_events (id, ts, principal, action, target_id, details) "
            + "VALUES ($1, $2, $3, $4, $5, $6)")
        .execute(Tuple.of(e.getId(), OffsetDateTime.ofInstant(e.getTimestamp(), ZoneOffset.UTC), e.getPrincipal(),
            e.getAction(), e.getTargetId(), e.getDetails().orElse(null)))
        .replaceWithVoid().subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<AuditEvent>> recent(int limit, int offset) {
    return this.pool
        .preparedQuery("SELECT * FROM audit_events ORDER BY ts DESC, id DESC LIMIT $1 OFFSET $2")
        .execute(Tuple.of(limit, offset)).map(PgAudit::fromRows).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<AuditEvent>> byTarget(String targetId, int limit) {
    return this.pool
        .preparedQuery("SELECT * FROM audit_events WHERE target_id=$1 ORDER BY ts DESC, id DESC LIMIT $2")
        .execute(Tuple.of(targetId, limit)).map(PgAudit::fromRows).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<SequencedEvent>> since(long afterSeq, int limit) {
    return this.pool
        .preparedQuery("SELECT * FROM audit_events WHERE seq > $1 ORDER BY seq LIMIT $2")
        .execute(Tuple.of(afterSeq, limit)).map(rs -> {
          List<SequencedEvent> out = new ArrayList<>();
          for (Row r : rs)
            out.add(new SequencedEvent(r.getLong("seq"), fromRow(r)));
          return out;
        }).subscribeAsCompletionStage();
  }

  private static List<AuditEvent> fromRows(RowSet<Row> rs) {
    List<AuditEvent> out = new ArrayList<>();
    for (Row r : rs)
      out.add(fromRow(r));
    return out;
  }

  private static AuditEvent fromRow(Row r) {
    return new DefaultAuditEvent(r.getString("id"), r.getOffsetDateTime("ts").toInstant(),
        r.getString("principal"), r.getString("action"), r.getString("target_id"),
        r.getValue("details") instanceof io.vertx.core.json.JsonObject j ? j : null);
  }
}
