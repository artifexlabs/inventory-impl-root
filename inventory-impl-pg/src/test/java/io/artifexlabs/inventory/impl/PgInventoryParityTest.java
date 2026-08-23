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

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.artifexlabs.inventory.api.AuditEvent;
import io.artifexlabs.inventory.impl.tck.InventorySystemTck;

import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Postgres against the SAME parity kit the in-memory twin runs.
 *
 * This is what the kit is for: the containment tree, cycle refusal, marker claims, and audit-per-mutation are asserted
 * once and proven twice, so a behavior cannot drift between backends without a red build. Postgres-only facts —
 * recursive-CTE ancestry, atomic claim via {@code ON CONFLICT}, sequence paging under concurrent writers — stay in
 * {@link PgInventorySystemTest}, because they are not parity claims.
 */
@Testcontainers(disabledWithoutDocker = true)
public class PgInventoryParityTest extends InventorySystemTck {

  @Container
  private final static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static PgPool pool;

  @BeforeAll
  public static void startSchema() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
      new Liquibase("db/changelog-master.yaml", new ClassLoaderResourceAccessor(),
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c))).update("");
    }
    PgConnectOptions opts = new PgConnectOptions().setHost(PG.getHost()).setPort(PG.getMappedPort(5432))
        .setDatabase(PG.getDatabaseName()).setUser(PG.getUsername()).setPassword(PG.getPassword());
    pool = PgPool.pool(opts, new PoolOptions().setMaxSize(4));
  }

  @AfterAll
  public static void stop() {
    if (pool != null)
      pool.closeAndAwait();
  }

  @Override
  protected Backend backend() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
      c.createStatement().execute("TRUNCATE items, audit_events CASCADE");
    }
    PgAudit audit = new PgAudit(pool);
    return new Backend(new PgInventorySystem(pool, PRINCIPAL), () -> recent(audit));
  }

  /** The kit asks "what was audited?"; here that is a query, in commit order. */
  private static List<AuditEvent> recent(PgAudit audit) {
    try {
      List<AuditEvent> newestFirst = audit.recent(200, 0).toCompletableFuture().get();
      return newestFirst.reversed();
    } catch (Exception e) {
      throw new IllegalStateException("could not read the audit trail", e);
    }
  }
}
