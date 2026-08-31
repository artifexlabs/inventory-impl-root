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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemIdentity;
import io.artifexlabs.inventory.api.Ulid;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * The tenancy RESERVATION (PLAN.md Phase 27), proven at the schema it landed in: the zero-ULID default inventory is
 * seeded, every item falls into it without any code knowing, and a physical marker claims per INVENTORY, not globally —
 * two inventories may each own the banana UPC, while within one inventory a second claim still fails loudly.
 *
 * <p>
 * Nothing here exercises enforcement — there is none yet, on purpose. This test is the proof that reserving the columns
 * changed no observable single-tenant behaviour while making the multi-tenant shape expressible.
 */
@Testcontainers(disabledWithoutDocker = true)
public class PgTenancyReservationTest {

  /** The seeded default inventory: the zero ULID, chosen so single-tenant installs never think about it. */
  private static final String DEFAULT_INVENTORY = "00000000000000000000000000";

  @Container
  private final static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static PgPool pool;
  private static PgInventorySystem items;

  @BeforeAll
  public static void startSchema() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
      new Liquibase("db/changelog-master.yaml", new ClassLoaderResourceAccessor(),
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c))).update("");
    }
    PgConnectOptions opts = new PgConnectOptions().setHost(PG.getHost()).setPort(PG.getMappedPort(5432))
        .setDatabase(PG.getDatabaseName()).setUser(PG.getUsername()).setPassword(PG.getPassword());
    pool = PgPool.pool(opts, new PoolOptions().setMaxSize(4));
    items = new PgInventorySystem(pool, "tenancy-test");
  }

  @AfterAll
  public static void stop() {
    if (pool != null)
      pool.closeAndAwait();
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** First column of the first row, stringified — enough for ids, names, and counts. */
  private static String scalar(String sql, Object... params) throws Exception {
    return await(pool.preparedQuery(sql).execute(Tuple.from(params)).map(rs -> {
      var it = rs.iterator();
      return it.hasNext() ? String.valueOf(it.next().getValue(0)) : null;
    }).subscribeAsCompletionStage());
  }

  @Test
  public void theDefaultInventoryIsSeededAndEveryItemFallsIntoIt() throws Exception {
    Item crate = await(items.createItem("tenancy-crate", "tenancy-crate", "crate"));

    assertEquals("default", scalar("SELECT name FROM inventories WHERE id=$1", DEFAULT_INVENTORY),
        "the changeset seeds the zero-ULID inventory");
    assertEquals(DEFAULT_INVENTORY, scalar("SELECT inventory_id FROM items WHERE id=$1", crate.getId()),
        "createItem knows nothing about inventories and still lands in the default");
  }

  @Test
  public void aMarkerClaimsPerInventoryNotGlobally() throws Exception {
    Item mine = await(items.createItem("tenancy-mine", "tenancy-mine", "disc"));
    assertTrue(await(items.addIdentity(mine.getId(), new ItemIdentity("upc", "0012345678905"))));

    // a neighbour inventory and its item, written directly: no management
    // surface exists yet, and that is exactly what a reservation is
    String neighbour = Ulid.next();
    String theirs = Ulid.next();
    await(pool.preparedQuery("INSERT INTO inventories (id, name, ts) VALUES ($1, 'neighbour', now())")
        .execute(Tuple.of(neighbour)).subscribeAsCompletionStage());
    await(pool.preparedQuery("INSERT INTO items (id, name, ts, inventory_id) VALUES ($1, 'their-disc', now(), $2)")
        .execute(Tuple.of(theirs, neighbour)).subscribeAsCompletionStage());
    await(pool.preparedQuery("INSERT INTO item_identities (kind, value, item_id, inventory_id) VALUES ($1, $2, $3, $4)")
        .execute(Tuple.of("upc", "0012345678905", theirs, neighbour)).subscribeAsCompletionStage());

    assertEquals("2", scalar("SELECT count(*) FROM item_identities WHERE kind='upc' AND value=$1", "0012345678905"),
        "both inventories own the banana UPC — the PK is (inventory_id, kind, value)");
  }

  @Test
  public void withinOneInventoryASecondClaimStillFailsLoudly() throws Exception {
    Item first = await(items.createItem("tenancy-first", "tenancy-first", "disc"));
    Item second = await(items.createItem("tenancy-second", "tenancy-second", "disc"));
    assertTrue(await(items.addIdentity(first.getId(), new ItemIdentity("nfc-uid", "04:AA:BB:CC"))));
    assertTrue(await(items.addIdentity(first.getId(), new ItemIdentity("nfc-uid", "04:AA:BB:CC"))),
        "re-claiming your own marker stays idempotent");

    ExecutionException refused = assertThrows(ExecutionException.class,
        () -> await(items.addIdentity(second.getId(), new ItemIdentity("nfc-uid", "04:AA:BB:CC"))));
    assertInstanceOf(IllegalStateException.class, refused.getCause(),
        "a marker reused within an inventory is a mistake to surface, exactly as before the reservation");
  }
}
