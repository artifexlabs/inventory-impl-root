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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.artifexlabs.inventory.api.AuditEvent;
import io.artifexlabs.inventory.api.DataEntry;
import io.artifexlabs.inventory.api.DataInfo;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.HashAlgorithm;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.MediaKind;
import io.artifexlabs.inventory.api.events.EventPublisher;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * The data domain publishes on the fact plane like every other store — and it publishes the SAME fact the audit row
 * holds.
 *
 * <p>
 * The in-memory twin got this for free (it audits through the decorated sink); Postgres writes its audit row inside the
 * transaction and has to announce by hand afterwards. Until it did, one operation emitted a fact on one backend and
 * nothing on the other — a divergence the parity kit cannot see, because the kit does not test emission. This test is
 * the emission half, on the backend that had it wrong.
 */
@Testcontainers(disabledWithoutDocker = true)
public class PgDataFactsTest {

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

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** Records what was published, and when relative to the operation completing. */
  private static class Recording implements EventPublisher {
    final List<AuditEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(AuditEvent event) {
      this.published.add(event);
    }
  }

  private static String medium(PgInventorySystem items, String name) throws Exception {
    Item created = await(items.createItem(name, name, "disc"));
    await(items.updateItem(DefaultItem.builder(created)
        .dataInfo(new DataInfo(MediaKind.PHYSICAL_MEDIA, false, false, "shelf-1")).build()));
    return created.getId();
  }

  private static List<String> auditIds(String action) throws Exception {
    return await(pool.preparedQuery("SELECT id FROM audit_events WHERE action = $1 ORDER BY seq")
        .execute(io.vertx.mutiny.sqlclient.Tuple.of(action)).map(rows -> {
          List<String> ids = new java.util.ArrayList<>();
          for (Row r : rows)
            ids.add(r.getString("id"));
          return ids;
        }).subscribeAsCompletionStage());
  }

  @Test
  public void aManifestReplaceIsPublishedAfterCommitWithTheAuditRowsOwnId() throws Exception {
    Recording bus = new Recording();
    PgInventorySystem items = new PgInventorySystem(pool, "facts-test");
    PgDataSystem data = new PgDataSystem(pool, items, "facts-test").withEventPublisher(bus);
    String disc = medium(items, "facts-disc");

    Optional<Integer> stored = await(data.replaceManifest(disc, List.of(DataEntry.of("docs/a.txt", 100L,
        HashAlgorithm.SHA256, "aaaa000000000000000000000000000000000000000000000000000000000001"))));

    assertEquals(Optional.of(1), stored);
    List<AuditEvent> replaced = bus.published.stream().filter(e -> e.getAction().equals("data.replace")).toList();
    assertEquals(1, replaced.size(), "one operation, one fact");
    assertEquals(disc, replaced.get(0).getTargetId());
    assertEquals(auditIds("data.replace"), List.of(replaced.get(0).getId()),
        "the published fact IS the audit row — same id — or a consumer's dedupe-by-id is meaningless");
    assertEquals(1, replaced.get(0).getDetails().orElseThrow().getInteger("entries"),
        "counts, not rows: the payload stays small");
  }

  @Test
  public void anArchiveMintedByAManifestPublishesItsItemCreateToo() throws Exception {
    Recording bus = new Recording();
    PgInventorySystem items = new PgInventorySystem(pool, "facts-test");
    PgDataSystem data = new PgDataSystem(pool, items, "facts-test").withEventPublisher(bus);
    String disc = medium(items, "facts-archive-disc");
    DataEntry zip = new DataEntry("backups/2019.zip", 4096L, null, null, "application/zip", null,
        List.of(DataEntry.of("notes.txt", 10L, HashAlgorithm.SHA256,
            "bbbb000000000000000000000000000000000000000000000000000000000002")));

    await(data.replaceManifest(disc, List.of(zip)));

    List<String> actions = bus.published.stream().map(AuditEvent::getAction).sorted().toList();
    assertEquals(List.of("data.replace", "item.create"), actions,
        "the archive became an item inside the same transaction; both facts ride out together");
  }

  @Test
  public void aRefusedOperationPublishesNothing() throws Exception {
    Recording bus = new Recording();
    PgInventorySystem items = new PgInventorySystem(pool, "facts-test");
    PgDataSystem data = new PgDataSystem(pool, items, "facts-test").withEventPublisher(bus);
    // an item that does NOT hold data: replaceManifest answers empty, and audits nothing
    Item plain = await(items.createItem("facts-crate", "facts-crate", "crate"));

    assertEquals(Optional.empty(), await(data.replaceManifest(plain.getId(), List.of(DataEntry.of("a.txt", 1L,
        HashAlgorithm.SHA256, "cccc000000000000000000000000000000000000000000000000000000000003")))));

    assertTrue(bus.published.isEmpty(), "no row, no fact");
  }

  @Test
  public void aRenameIsPublishedAndActingAsKeepsThePublisher() throws Exception {
    Recording bus = new Recording();
    PgInventorySystem items = new PgInventorySystem(pool, "facts-test");
    PgDataSystem data = new PgDataSystem(pool, items, "facts-test").withEventPublisher(bus);
    String disc = medium(items, "facts-rename-disc");
    await(data.replaceManifest(disc, List.of(DataEntry.of("old/a.txt", 100L, HashAlgorithm.SHA256,
        "dddd000000000000000000000000000000000000000000000000000000000004"))));
    bus.published.clear();

    assertEquals(1, await(data.actingAs("someone-else").renamePath(disc, "old", "new")));

    List<AuditEvent> renamed = bus.published.stream().filter(e -> e.getAction().equals("data.rename")).toList();
    assertEquals(1, renamed.size());
    assertEquals("someone-else", renamed.get(0).getPrincipal(), "a per-request view attributes AND still publishes");
    assertEquals(auditIds("data.rename"), List.of(renamed.get(0).getId()));
  }
}
