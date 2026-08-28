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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.artifexlabs.inventory.impl.tck.DataHashingTck;

import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * The Postgres backend against the hashing kit. What this proves that the in-memory twin cannot is the concurrency: FOR
 * UPDATE SKIP LOCKED, a committed claim rather than a held lock, and a lease that a real clock expires.
 *
 * Isolation is a truncate between tests rather than a fresh container — Liquibase runs once, and the kit only ever asks
 * for an empty store.
 */
@Testcontainers(disabledWithoutDocker = true)
public class PgDataHashingTest extends DataHashingTck {

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
  protected Backends backends() throws Exception {
    // data_entries and the archive items go with the items cascade; the
    // dictionaries are deliberately NOT cleared, so every test also exercises
    // interning against components a previous test already created
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
      c.createStatement().execute("TRUNCATE items, audit_events CASCADE");
    }
    PgInventorySystem items = new PgInventorySystem(pool, "tester@example.com");
    return new Backends(items, new PgDataSystem(pool, items, "tester@example.com"),
        new PgDataHashing(pool, "tester@example.com"));
  }
}
