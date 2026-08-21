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

import io.artifexlabs.inventory.api.Ulid;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.AssetRegion;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.RegionSystem;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Postgres {@link RegionSystem}. Promotion (item + containment + region link + every audit row) is ONE transaction, so
 * a half-created item or an unlinked audit trail cannot exist. Audit rows mirror what the in-memory path emits through
 * {@code InventorySystem}: item.create, item.contain (when contained), then item.create-from-region.
 *
 * @author mykel
 *
 */
public class PgRegionSystem implements RegionSystem {

  private final static String INSERT_AUDIT = """
      INSERT INTO audit_events (id, ts, principal, action, target_id, details)
      VALUES ($1, $2, $3, $4, $5, $6)""";

  private final static String SELECT_REGION_COLS = "id, asset_id, x, y, w, h, item_id, label, ts";

  private final Pool pool;
  private final String principal;
  private io.artifexlabs.inventory.api.events.EventPublisher events = io.artifexlabs.inventory.api.events.EventPublisher.NOOP;

  public PgRegionSystem(Pool pool, String principal) {
    this.pool = requireNonNull(pool, "pool");
    this.principal = requireNonNull(principal, "principal");
  }

  /** Publish committed facts to this publisher (default: nowhere). */
  public PgRegionSystem withEventPublisher(io.artifexlabs.inventory.api.events.EventPublisher events) {
    this.events = requireNonNull(events, "events");
    return this;
  }

  /** Stateless per-request view: audit rows attribute to the acting principal. */
  @Override
  public PgRegionSystem actingAs(String principal) {
    return new PgRegionSystem(this.pool, principal).withEventPublisher(this.events);
  }

  /** Builds an event, collects it for after-commit publication, and inserts it. */
  private Uni<Void> audit(SqlClient conn, List<io.artifexlabs.inventory.api.AuditEvent> pending, String action,
      String targetId, JsonObject details) {
    io.artifexlabs.inventory.api.AuditEvent e = new io.artifexlabs.inventory.api.DefaultAuditEvent(Ulid.next(),
        Instant.now(), this.principal, action, targetId, details);
    pending.add(e);
    return conn.preparedQuery(INSERT_AUDIT).execute(Tuple.of(e.getId(), odt(e.getTimestamp()), e.getPrincipal(),
        e.getAction(), e.getTargetId(), e.getDetails().orElse(null))).replaceWithVoid();
  }

  @Override
  public CompletionStage<List<AssetRegion>> listRegions(String assetId) {
    return this.pool.preparedQuery("SELECT " + SELECT_REGION_COLS + " FROM asset_regions WHERE asset_id=$1 ORDER BY id")
        .execute(Tuple.of(assetId)).map(rs -> {
          List<AssetRegion> out = new ArrayList<>();
          for (Row r : rs)
            out.add(fromRow(r));
          return out;
        }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Optional<AssetRegion>> createRegion(String assetId, double x, double y, double w, double h,
      String label) {
    AssetRegion region = new AssetRegion(Ulid.next(), assetId, x, y, w, h, null, label, Instant.now());
    List<io.artifexlabs.inventory.api.AuditEvent> pending = new ArrayList<>();
    return this.events.announceAll(this.pool.withTransaction(conn -> assetOwner(conn, assetId)
        .flatMap(owner -> owner.isEmpty() ? Uni.createFrom().item(Optional.<AssetRegion>empty())
            : insertRegion(conn, region)
                .flatMap(v -> audit(conn, pending, "region.create", owner.get(),
                    new JsonObject().put("assetId", assetId).put("regionId", region.id())))
                .map(v -> Optional.of(region))))
        .subscribeAsCompletionStage(), pending);
  }

  @Override
  public CompletionStage<Boolean> deleteRegion(String regionId) {
    List<io.artifexlabs.inventory.api.AuditEvent> pending = new ArrayList<>();
    return this.events.announceAll(
        this.pool.withTransaction(conn -> conn.preparedQuery("DELETE FROM asset_regions WHERE id=$1 RETURNING asset_id")
            .execute(Tuple.of(regionId)).flatMap(rs -> {
              for (Row r : rs) {
                String assetId = r.getString("asset_id");
                return assetOwner(conn, assetId).flatMap(owner -> audit(conn, pending, "region.delete",
                    owner.orElse(assetId), new JsonObject().put("assetId", assetId).put("regionId", regionId)))
                    .map(v -> true);
              }
              return Uni.createFrom().item(false);
            })).subscribeAsCompletionStage(),
        pending);
  }

  @Override
  public CompletionStage<Optional<Item>> createItemFromRegion(String assetId, double x, double y, double w, double h,
      String name, String type, String containerId) {
    AssetRegion region = new AssetRegion(Ulid.next(), assetId, x, y, w, h, null, name, Instant.now());
    List<io.artifexlabs.inventory.api.AuditEvent> pending = new ArrayList<>();
    return this.events
        .announceAll(
            this.pool
                .withTransaction(conn -> assetOwner(conn, assetId)
                    .flatMap(owner -> owner.isEmpty() ? Uni.createFrom().item(Optional.<Item>empty())
                        : insertRegion(conn, region)
                            .flatMap(v -> promote(conn, pending, region, name, type, containerId))))
                .subscribeAsCompletionStage(),
            pending);
  }

  @Override
  public CompletionStage<Optional<Item>> makeItemFromRegion(String regionId, String name, String type,
      String containerId) {
    List<io.artifexlabs.inventory.api.AuditEvent> pending = new ArrayList<>();
    return this.events.announceAll(this.pool.withTransaction(conn -> conn
        .preparedQuery("SELECT " + SELECT_REGION_COLS + " FROM asset_regions WHERE id=$1 AND item_id IS NULL")
        .execute(Tuple.of(regionId)).flatMap(rs -> {
          for (Row r : rs)
            return promote(conn, pending, fromRow(r), name, type, containerId);
          return Uni.createFrom().item(Optional.<Item>empty());
        })).subscribeAsCompletionStage(), pending);
  }

  /** Item insert + optional containment + region link + audits, on one conn. */
  private Uni<Optional<Item>> promote(SqlClient conn, List<io.artifexlabs.inventory.api.AuditEvent> pending,
      AssetRegion region, String name, String type, String containerId) {
    Item item = DefaultItem.builder().id(Ulid.next()).name(name).type(type).timestamp(Instant.now()).build();
    Uni<Boolean> containerOk = containerId == null ? Uni.createFrom().item(true)
        : conn.preparedQuery("SELECT 1 FROM items WHERE id=$1").execute(Tuple.of(containerId))
            .map(rs -> rs.iterator().hasNext());
    return containerOk
        .flatMap(ok -> !ok ? Uni.createFrom().item(Optional.<Item>empty())
            : conn.preparedQuery("INSERT INTO items (id, name, display_name, type, ts) VALUES ($1, $2, $3, $4, $5)")
                .execute(Tuple.of(item.getId(), item.getName(), null, item.getType(), odt(item.getTimestamp())))
                .flatMap(v -> audit(conn, pending, "item.create", item.getId(), ItemFactory.serialize(item)))
                .flatMap(v -> containerId == null ? Uni.createFrom().voidItem()
                    : conn.preparedQuery("UPDATE items SET container_id=$1 WHERE id=$2")
                        .execute(Tuple.of(containerId, item.getId()))
                        .flatMap(x -> audit(conn, pending, "item.contain", item.getId(),
                            new JsonObject().put("containerId", containerId))))
                .flatMap(v -> conn.preparedQuery("UPDATE asset_regions SET item_id=$2, label=$3 WHERE id=$1")
                    .execute(Tuple.of(region.id(), item.getId(), name)))
                .flatMap(v -> audit(conn, pending, "item.create-from-region", item.getId(), new JsonObject()
                    .put("assetId", region.assetId()).put("regionId", region.id()).put("containerId", containerId)))
                .map(v -> Optional.of(item)));
  }

  private static Uni<Void> insertRegion(SqlClient conn, AssetRegion r) {
    return conn.preparedQuery("""
        INSERT INTO asset_regions (id, asset_id, x, y, w, h, item_id, label, ts)
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)""").execute(Tuple.from(new Object[] {
        r.id(), r.assetId(), r.x(), r.y(), r.w(), r.h(), r.itemId(), r.label(), odt(r.timestamp())
    })).replaceWithVoid();
  }

  /** The item owning an asset — the audit target for region activity. */
  private static Uni<Optional<String>> assetOwner(SqlClient conn, String assetId) {
    return conn.preparedQuery("SELECT item_id FROM assets WHERE id=$1").execute(Tuple.of(assetId)).map(rs -> {
      for (Row r : rs)
        return Optional.of(r.getString("item_id"));
      return Optional.empty();
    });
  }

  private static AssetRegion fromRow(Row r) {
    return new AssetRegion(r.getString("id"), r.getString("asset_id"), r.getDouble("x"), r.getDouble("y"),
        r.getDouble("w"), r.getDouble("h"), r.getString("item_id"), r.getString("label"),
        r.getOffsetDateTime("ts").toInstant());
  }

  private static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
