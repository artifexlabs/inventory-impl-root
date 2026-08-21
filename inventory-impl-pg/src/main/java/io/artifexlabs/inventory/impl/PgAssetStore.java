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

import io.artifexlabs.inventory.api.AssetInfo;
import io.artifexlabs.inventory.api.AssetStore;

import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Postgres {@link AssetStore}: bytes live in the {@code assets} table (bytea) so backup/restore and transactional
 * integrity stay one-datastore problems. The item FK cascades, so deleting an item deletes its assets.
 *
 * @author mykel
 *
 */
public class PgAssetStore implements AssetStore {
  private final Pool pool;
  private final String principal;
  private io.artifexlabs.inventory.api.events.EventPublisher events = io.artifexlabs.inventory.api.events.EventPublisher.NOOP;

  public PgAssetStore(Pool pool, String principal) {
    this.pool = requireNonNull(pool, "pool");
    this.principal = requireNonNull(principal, "principal");
  }

  /** Publish committed facts to this publisher (default: nowhere). */
  public PgAssetStore withEventPublisher(io.artifexlabs.inventory.api.events.EventPublisher events) {
    this.events = requireNonNull(events, "events");
    return this;
  }

  /** Stateless per-request view: audit rows attribute to the acting principal. */
  @Override
  public PgAssetStore actingAs(String principal) {
    return new PgAssetStore(this.pool, principal).withEventPublisher(this.events);
  }

  private io.artifexlabs.inventory.api.AuditEvent event(String action, AssetInfo info) {
    return new io.artifexlabs.inventory.api.DefaultAuditEvent(Ulid.next(), Instant.now(), this.principal, action,
        info.itemId(), new JsonObject().put("assetId", info.id()).put("filename", info.filename()));
  }

  @Override
  public CompletionStage<Optional<AssetInfo>> store(String itemId, String filename, String contentType, byte[] data,
      io.artifexlabs.inventory.api.LatLong explicitCoordinates, String kind) {
    io.artifexlabs.inventory.api.LatLong coords = explicitCoordinates != null ? explicitCoordinates
        : ExifGps.extract(data).orElse(null);
    Instant now = Instant.now();
    AssetInfo info = new AssetInfo(Ulid.next(), itemId, filename, contentType, data.length, now, now, coords, kind);
    io.artifexlabs.inventory.api.AuditEvent attached = event("asset.attach", info);
    return this.events.announce(this.pool
        .withTransaction(conn -> conn.preparedQuery("SELECT 1 FROM items WHERE id=$1").execute(Tuple.of(itemId))
            .flatMap(rs -> !rs.iterator().hasNext() ? Uni.createFrom().item(Optional.<AssetInfo>empty())
                : insertAsset(conn, info, data).map(v -> Optional.of(info)).call(v -> audit(conn, attached))))
        .subscribeAsCompletionStage(), attached);
  }

  /** The one INSERT every attach path shares. */
  private static Uni<Void> insertAsset(SqlClient conn, AssetInfo info, byte[] data) {
    io.artifexlabs.inventory.api.LatLong coords = info.coordinates();
    return conn
        .preparedQuery(
            """
                INSERT INTO assets (id, item_id, filename, content_type, size_bytes, data, attached_at, updated_at, latitude, longitude, kind)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)""")
        .execute(Tuple.from(new Object[] {
            info.id(), info.itemId(), info.filename(), info.contentType(), info.sizeBytes(), Buffer.buffer(data),
            OffsetDateTime.ofInstant(info.attachedAt(), ZoneOffset.UTC),
            OffsetDateTime.ofInstant(info.updatedAt(), ZoneOffset.UTC), coords == null ? null : coords.latitude(),
            coords == null ? null : coords.longitude(), info.kind()
        })).replaceWithVoid();
  }

  @Override
  public CompletionStage<Optional<PhotoItem>> createItemFromPhoto(String name, String displayName, String type,
      String containerId, String filename, String contentType, byte[] data,
      io.artifexlabs.inventory.api.LatLong explicitCoordinates, String kind) {
    io.artifexlabs.inventory.api.LatLong coords = explicitCoordinates != null ? explicitCoordinates
        : ExifGps.extract(data).orElse(null);
    Instant now = Instant.now();
    io.artifexlabs.inventory.api.Item item = io.artifexlabs.inventory.api.DefaultItem.builder().id(Ulid.next())
        .name(name).displayName(displayName).type(type).timestamp(now).coordinates(coords).containerId(containerId)
        .build();
    AssetInfo info = new AssetInfo(Ulid.next(), item.getId(), filename, contentType, data.length, now, now, coords,
        kind);
    List<io.artifexlabs.inventory.api.AuditEvent> pending = new ArrayList<>();
    return this.events.announceAll(this.pool.withTransaction(conn -> {
      Uni<Boolean> containerOk = containerId == null ? Uni.createFrom().item(true)
          : conn.preparedQuery("SELECT 1 FROM items WHERE id=$1").execute(Tuple.of(containerId))
              .map(rs -> rs.iterator().hasNext());
      return containerOk.flatMap(ok -> !ok ? Uni.createFrom().item(Optional.<PhotoItem>empty()) : conn.preparedQuery("""
          INSERT INTO items (id, name, display_name, type, ts, latitude, longitude, container_id)
          VALUES ($1, $2, $3, $4, $5, $6, $7, $8)""").execute(Tuple.from(new Object[] {
          item.getId(), item.getName(), displayName, item.getType(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
          coords == null ? null : coords.latitude(), coords == null ? null : coords.longitude(), containerId
      })).flatMap(v -> audit(conn, pending, "item.create", item.getId(),
          io.artifexlabs.inventory.api.ItemFactory.serialize(item)))
          .flatMap(v -> containerId == null ? Uni.createFrom().voidItem()
              : audit(conn, pending, "item.contain", item.getId(), new JsonObject().put("containerId", containerId)))
          .flatMap(v -> insertAsset(conn, info, data))
          .flatMap(v -> audit(conn, pending, "asset.attach", item.getId(),
              new JsonObject().put("assetId", info.id()).put("filename", info.filename())))
          .map(v -> Optional.of(new PhotoItem(item, info))));
    }).subscribeAsCompletionStage(), pending);
  }

  @Override
  public CompletionStage<Optional<PhotoItem>> createItemFromUpc(io.artifexlabs.inventory.api.UpcItemCreation spec,
      String imageFilename, String imageContentType, byte[] imageBytes) {
    Instant now = Instant.now();
    var builder = io.artifexlabs.inventory.api.DefaultItem.builder().id(Ulid.next()).name(spec.name())
        .displayName(spec.displayName()).type(spec.type()).timestamp(now).description(spec.description())
        .containerId(spec.containerId());
    if (spec.weightGrams() != null)
      builder.weight(new io.artifexlabs.inventory.api.Weight(spec.weightGrams()));
    io.artifexlabs.inventory.api.Item item = builder.build();
    AssetInfo info = imageBytes == null ? null
        : new AssetInfo(Ulid.next(), item.getId(), imageFilename, imageContentType, imageBytes.length, now, now, null,
            AssetInfo.KIND_PHOTO);
    List<io.artifexlabs.inventory.api.AuditEvent> pending = new ArrayList<>();
    return this.events.announceAll(this.pool.withTransaction(conn -> {
      Uni<Boolean> containerOk = spec.containerId() == null ? Uni.createFrom().item(true)
          : conn.preparedQuery("SELECT 1 FROM items WHERE id=$1").execute(Tuple.of(spec.containerId()))
              .map(rs -> rs.iterator().hasNext());
      return containerOk.flatMap(ok -> !ok ? Uni.createFrom().item(Optional.<PhotoItem>empty()) : conn.preparedQuery("""
          INSERT INTO items (id, name, display_name, type, ts, description, weight_grams, container_id)
          VALUES ($1, $2, $3, $4, $5, $6, $7, $8)""").execute(Tuple.from(new Object[] {
          item.getId(), item.getName(), spec.displayName(), item.getType(),
          OffsetDateTime.ofInstant(now, ZoneOffset.UTC), spec.description(), spec.weightGrams(), spec.containerId()
      })).flatMap(v -> audit(conn, pending, "item.create", item.getId(),
          io.artifexlabs.inventory.api.ItemFactory.serialize(item)))
          .flatMap(v -> spec.containerId() == null ? Uni.createFrom().voidItem()
              : audit(conn, pending, "item.contain", item.getId(),
                  new JsonObject().put("containerId", spec.containerId())))
          // the atomic claim: a conflicting marker fails the WHOLE creation
          .flatMap(v -> conn.preparedQuery("""
              INSERT INTO item_identities (kind, value, item_id) VALUES ('upc', $1, $2)
              ON CONFLICT (kind, value) DO NOTHING""").execute(Tuple.of(spec.gtin13(), item.getId())))
          .flatMap(rs -> rs.rowCount() == 1
              ? audit(conn, pending, "item.identity-add", item.getId(),
                  new io.artifexlabs.inventory.api.ItemIdentity("upc", spec.gtin13()).toJson())
              : Uni.createFrom().<Void>failure(
                  new IllegalStateException("identity upc:" + spec.gtin13() + " already claims another item")))
          .flatMap(v -> insertTags(conn, pending, item.getId(), spec.tags()))
          .flatMap(v -> info == null ? Uni.createFrom().voidItem()
              : insertAsset(conn, info, imageBytes).flatMap(x -> audit(conn, pending, "asset.attach", item.getId(),
                  new JsonObject().put("assetId", info.id()).put("filename", info.filename()))))
          .map(v -> Optional.of(new PhotoItem(item, info))));
    }).subscribeAsCompletionStage(), pending);
  }

  private Uni<Void> insertTags(SqlClient conn, List<io.artifexlabs.inventory.api.AuditEvent> pending, String itemId,
      List<io.artifexlabs.inventory.api.ItemTag> tags) {
    Uni<Void> flow = Uni.createFrom().voidItem();
    for (var tag : tags)
      flow = flow
          .flatMap(v -> conn.preparedQuery("INSERT INTO item_tags (item_id, tag_key, tag_value) VALUES ($1, $2, $3)")
              .execute(Tuple.of(itemId, tag.key(), tag.value())))
          .flatMap(v -> audit(conn, pending, "item.tag", itemId, tag.toJson()));
    return flow;
  }

  /** Builds an event, collects it for after-commit publication, and inserts it. */
  private Uni<Void> audit(SqlClient conn, List<io.artifexlabs.inventory.api.AuditEvent> pending, String action,
      String targetId, JsonObject details) {
    io.artifexlabs.inventory.api.AuditEvent e = new io.artifexlabs.inventory.api.DefaultAuditEvent(Ulid.next(),
        Instant.now(), this.principal, action, targetId, details);
    pending.add(e);
    return audit(conn, e);
  }

  @Override
  public CompletionStage<Optional<AssetInfo>> replace(String assetId, String filename, String contentType, byte[] data,
      io.artifexlabs.inventory.api.LatLong explicitCoordinates) {
    io.artifexlabs.inventory.api.LatLong coords = explicitCoordinates != null ? explicitCoordinates
        : ExifGps.extract(data).orElse(null);
    Instant now = Instant.now();
    // the superseded version lands in asset_archive IN THE SAME TRANSACTION;
    // the audit event carries only a reference — the audit log is the replay
    // feed every consumer pages, so blobs must never ride it
    return this.pool.withTransaction(
        conn -> conn.preparedQuery("SELECT * FROM assets WHERE id=$1").execute(Tuple.of(assetId)).flatMap(rs -> {
          if (!rs.iterator().hasNext())
            return Uni.createFrom().item(Optional.<AssetInfo>empty());
          Row old = rs.iterator().next();
          AssetInfo previous = infoFromRow(old);
          byte[] previousBytes = old.getBuffer("data").getDelegate().getBytes();
          AssetInfo next = previous.revised(filename, contentType, data.length, now, coords);
          String archiveId = Ulid.next();
          String auditEventId = Ulid.next();
          io.vertx.core.json.JsonObject details = new io.vertx.core.json.JsonObject().put("replaced", previous.toJson())
              .put("archiveId", archiveId).put("current", next.toJson());
          io.artifexlabs.inventory.api.AuditEvent replaced = new io.artifexlabs.inventory.api.DefaultAuditEvent(
              auditEventId, now, this.principal, "asset.replace", assetId, details);
          return conn.preparedQuery("""
              INSERT INTO asset_archive (id, asset_id, item_id, filename, content_type, size_bytes, data,
                latitude, longitude, attached_at, updated_at, archived_at, audit_event_id)
              VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)""").execute(Tuple.from(new Object[] {
              archiveId, assetId, previous.itemId(), previous.filename(), previous.contentType(), previous.sizeBytes(),
              Buffer.buffer(previousBytes), previous.coordinates() == null ? null : previous.coordinates().latitude(),
              previous.coordinates() == null ? null : previous.coordinates().longitude(),
              OffsetDateTime.ofInstant(previous.attachedAt(), ZoneOffset.UTC),
              OffsetDateTime.ofInstant(previous.updatedAt(), ZoneOffset.UTC),
              OffsetDateTime.ofInstant(now, ZoneOffset.UTC), auditEventId
          })).flatMap(v -> conn.preparedQuery("""
              UPDATE assets SET filename=$2, content_type=$3, size_bytes=$4, data=$5, updated_at=$6,
                latitude=$7, longitude=$8 WHERE id=$1""").execute(Tuple.from(new Object[] {
              assetId, filename, contentType, (long) data.length, Buffer.buffer(data),
              OffsetDateTime.ofInstant(now, ZoneOffset.UTC), coords == null ? null : coords.latitude(),
              coords == null ? null : coords.longitude()
          }))).flatMap(v -> audit(conn, replaced)).map(v -> Optional.of(next))
              .invoke(() -> this.events.publish(replaced));
        })).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Optional<StoredAsset>> get(String assetId) {
    return this.pool.preparedQuery("SELECT * FROM assets WHERE id=$1").execute(Tuple.of(assetId)).map(rs -> {
      for (Row r : rs)
        return Optional.of(new StoredAsset(infoFromRow(r), r.getBuffer("data").getDelegate().getBytes()));
      return Optional.<StoredAsset>empty();
    }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<AssetInfo>> listFor(String itemId) {
    return this.pool.preparedQuery(
        "SELECT id, item_id, filename, content_type, size_bytes, attached_at, updated_at, latitude, longitude, kind "
            + "FROM assets WHERE item_id=$1 ORDER BY attached_at")
        .execute(Tuple.of(itemId)).map(rs -> {
          List<AssetInfo> out = new ArrayList<>();
          for (Row r : rs)
            out.add(infoFromRow(r));
          return out;
        }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Boolean> delete(String assetId) {
    // the event's details (owning item, filename) are only known inside the
    // transaction, so capture it there and publish it after commit
    java.util.concurrent.atomic.AtomicReference<io.artifexlabs.inventory.api.AuditEvent> deleted = new java.util.concurrent.atomic.AtomicReference<>();
    return this.pool
        .withTransaction(conn -> conn.preparedQuery("DELETE FROM assets WHERE id=$1 RETURNING item_id, filename")
            .execute(Tuple.of(assetId)).flatMap(rs -> {
              for (Row r : rs) {
                io.artifexlabs.inventory.api.AuditEvent e = event("asset.delete",
                    new AssetInfo(assetId, r.getString("item_id"), r.getString("filename"), "n/a", 0, Instant.now()));
                deleted.set(e);
                return audit(conn, e).map(v -> true);
              }
              return Uni.createFrom().item(false);
            }))
        .subscribeAsCompletionStage().whenComplete((ok, t) -> {
          if (t == null && Boolean.TRUE.equals(ok) && deleted.get() != null)
            this.events.publish(deleted.get());
        });
  }

  /** Inserts the exact event that will be published after commit — same id. */
  private Uni<Void> audit(SqlClient conn, io.artifexlabs.inventory.api.AuditEvent e) {
    return conn
        .preparedQuery("INSERT INTO audit_events (id, ts, principal, action, target_id, details) "
            + "VALUES ($1, $2, $3, $4, $5, $6)")
        .execute(Tuple.of(e.getId(), OffsetDateTime.ofInstant(e.getTimestamp(), ZoneOffset.UTC), e.getPrincipal(),
            e.getAction(), e.getTargetId(), e.getDetails().orElse(null)))
        .replaceWithVoid();
  }

  private static AssetInfo infoFromRow(Row r) {
    Double lat = r.getDouble("latitude");
    Double lng = r.getDouble("longitude");
    return new AssetInfo(r.getString("id"), r.getString("item_id"), r.getString("filename"),
        r.getString("content_type"), r.getLong("size_bytes"), r.getOffsetDateTime("attached_at").toInstant(),
        r.getOffsetDateTime("updated_at").toInstant(),
        lat != null && lng != null ? new io.artifexlabs.inventory.api.LatLong(lat, lng) : null, r.getString("kind"));
  }
}
