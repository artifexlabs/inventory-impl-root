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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.AuditEvent;
import io.artifexlabs.inventory.api.DataInfo;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.Dimensions;
import io.artifexlabs.inventory.api.Expiration;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.ItemTag;
import io.artifexlabs.inventory.api.LatLong;
import io.artifexlabs.inventory.api.MediaKind;
import io.artifexlabs.inventory.api.ParValues;
import io.artifexlabs.inventory.api.TagQuery;
import io.artifexlabs.inventory.api.Weight;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Postgres-backed {@link InventorySystem}. Every mutation runs in a single
 * transaction that also writes the audit trail row, so a change without its
 * audit entry cannot exist. Schema is managed by the Liquibase changelogs in
 * this module ({@code db/changelog-master.yaml}).
 *
 * Containment is a TREE (Phase 15): {@code items.container_id} is a single
 * self-reference, so re-parenting is one field write and a child cannot be in
 * two places. Contents are materialized one level deep on read — contained
 * items appear with their own scalar fields but not their own children.
 * Ancestry questions (effective coordinates, cycle refusal) are answered by
 * recursive CTEs, which keeps the walk in the database instead of round-
 * tripping per level.
 *
 * @author mykel
 *
 */
public class PgInventorySystem implements InventorySystem {

  private final static String UPDATE_ITEM = """
      UPDATE items SET name=$2, display_name=$3, type=$4, description=$5, container_id=$6,
        data_kind=$7, data_mutable=$8, data_archive=$9, quantity=$10, weight_grams=$11,
        length_cm=$12, width_cm=$13, height_cm=$14, ts=$15, min_on_hand=$16, max_on_hand=$17,
        latitude=$18, longitude=$19, heavy=$20, expires_at=$21, expiration_absolute=$22 WHERE id=$1""";

  private final static String INSERT_AUDIT = """
      INSERT INTO audit_events (id, ts, principal, action, target_id, details)
      VALUES ($1, $2, $3, $4, $5, $6)""";

  /** Nearest pinned ancestor, self first: the item's effective coordinates. */
  private final static String EFFECTIVE_COORDINATES = """
      WITH RECURSIVE chain AS (
        SELECT id, container_id, latitude, longitude, 0 AS depth FROM items WHERE id=$1
        UNION ALL
        SELECT i.id, i.container_id, i.latitude, i.longitude, c.depth+1
          FROM items i JOIN chain c ON i.id = c.container_id WHERE c.depth < 100
      )
      SELECT latitude, longitude FROM chain
       WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY depth LIMIT 1""";

  /** Is $2 an ancestor of (or equal to) $1? Then $1 cannot contain $2. */
  private final static String IS_ANCESTOR = """
      WITH RECURSIVE up AS (
        SELECT id, container_id, 0 AS depth FROM items WHERE id=$1
        UNION ALL
        SELECT i.id, i.container_id, u.depth+1
          FROM items i JOIN up u ON i.id = u.container_id WHERE u.depth < 100
      )
      SELECT count(*) AS c FROM up WHERE id=$2""";

  private final Pool pool;
  private final String principal;
  private io.artifexlabs.inventory.api.events.EventPublisher events =
      io.artifexlabs.inventory.api.events.EventPublisher.NOOP;

  public PgInventorySystem(Pool pool, String principal) {
    this.pool = requireNonNull(pool, "pool");
    this.principal = requireNonNull(principal, "principal");
  }

  /** Publish committed facts to this publisher (default: nowhere). */
  public PgInventorySystem withEventPublisher(io.artifexlabs.inventory.api.events.EventPublisher events) {
    this.events = requireNonNull(events, "events");
    return this;
  }

  /** Stateless per-request view: audit rows attribute to the acting principal. */
  @Override
  public PgInventorySystem actingAs(String principal) {
    return new PgInventorySystem(this.pool, principal).withEventPublisher(this.events);
  }

  private AuditEvent event(String action, String targetId, JsonObject details) {
    return new DefaultAuditEvent(Ulid.next(), Instant.now(), this.principal, action, targetId, details);
  }

  @Override
  public CompletionStage<List<Item>> getAllItems() {
    return loadAll(this.pool).map(m -> List.copyOf(m.values())).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<Item>> getItemsOfType(String type) {
    return loadAll(this.pool).map(m -> m.values().stream().filter(i -> i.getType().equals(type)).toList())
        .subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Optional<Item>> getItem(String id) {
    return loadAll(this.pool).map(m -> Optional.ofNullable(m.get(id))).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Item> createItem(String name, String displayName, String type) {
    Item item = DefaultItem.builder().id(Ulid.next()).name(name).displayName(displayName).type(type)
        .timestamp(Instant.now()).build();
    AuditEvent created = event("item.create", item.getId(), ItemFactory.serialize(item));
    return this.events.announce(this.pool.withTransaction(conn -> conn
        .preparedQuery("INSERT INTO items (id, name, display_name, type, ts) VALUES ($1, $2, $3, $4, $5)")
        .execute(Tuple.of(item.getId(), item.getName(), displayName, item.getType(), odt(item.getTimestamp())))
        .flatMap(v -> audit(conn, created)).map(v -> item)).subscribeAsCompletionStage(), created);
  }

  @Override
  public CompletionStage<Boolean> updateItem(Item item) {
    AuditEvent updated = event("item.update", item.getId(), ItemFactory.serialize(item));
    // an update may re-parent (the edit forms set containerId), so it must
    // pass the same cycle check as addToContainer — otherwise an edit could
    // make an item its own ancestor
    String container = item.getContainerId().orElse(null);
    return this.events.announce(this.pool.withTransaction(conn -> (container == null
        ? Uni.createFrom().item(false)
        : wouldCycle(conn, container, item.getId()))
        .flatMap(cycle -> cycle ? Uni.createFrom().item(false)
            : conn.preparedQuery(UPDATE_ITEM).execute(updateTuple(item))
                .flatMap(rs -> rs.rowCount() == 0 ? Uni.createFrom().item(false)
                    : replaceTags(conn, item).flatMap(v -> audit(conn, updated)).map(v -> true))))
        .subscribeAsCompletionStage(), updated);
  }

  @Override
  public CompletionStage<Boolean> deleteItem(String id) {
    AuditEvent deleted = event("item.delete", id, null);
    // children are orphaned, not cascaded (fk_items_container is ON DELETE SET
    // NULL): removing a shelf must not delete what was on it
    return this.events.announce(this.pool
        .withTransaction(conn -> conn.preparedQuery("DELETE FROM items WHERE id=$1").execute(Tuple.of(id))
            .flatMap(rs -> rs.rowCount() == 0 ? Uni.createFrom().item(false)
                : audit(conn, deleted).map(v -> true)))
        .subscribeAsCompletionStage(), deleted);
  }

  @Override
  public CompletionStage<Optional<Item>> getContainer(String itemId) {
    return loadAll(this.pool).map(m -> Optional.ofNullable(m.get(itemId))
        .flatMap(Item::getContainerId).map(m::get)).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Boolean> addToContainer(String containerId, String itemId) {
    return reparent(containerId, itemId, "item.contain");
  }

  @Override
  public CompletionStage<Boolean> moveToContainer(String itemId, String targetContainerId) {
    return reparent(targetContainerId, itemId, "item.move");
  }

  /** The single containment write; refuses unknown ids, self, and cycles. */
  private CompletionStage<Boolean> reparent(String containerId, String itemId, String action) {
    if (containerId == null || containerId.equals(itemId))
      return CompletableFuture.completedStage(false);
    AuditEvent moved = event(action, itemId, new JsonObject().put("containerId", containerId));
    return this.events.announce(this.pool.withTransaction(conn -> bothExist(conn, containerId, itemId)
        .flatMap(ok -> !ok ? Uni.createFrom().item(false)
            : wouldCycle(conn, containerId, itemId).flatMap(cycle -> cycle ? Uni.createFrom().item(false)
                : conn.preparedQuery("UPDATE items SET container_id=$1 WHERE id=$2")
                    .execute(Tuple.of(containerId, itemId))
                    .flatMap(v -> audit(conn, moved)).map(v -> true))))
        .subscribeAsCompletionStage(), moved);
  }

  @Override
  public CompletionStage<Boolean> removeFromContainer(String containerId, String itemId) {
    AuditEvent uncontained = event("item.uncontain", itemId, new JsonObject().put("containerId", containerId));
    return this.events.announce(this.pool.withTransaction(conn -> conn
        .preparedQuery("UPDATE items SET container_id=NULL WHERE id=$1 AND container_id=$2")
        .execute(Tuple.of(itemId, containerId))
        .flatMap(rs -> rs.rowCount() == 0 ? Uni.createFrom().item(false)
            : audit(conn, uncontained).map(v -> true)))
        .subscribeAsCompletionStage(), uncontained);
  }

  @Override
  public CompletionStage<Optional<LatLong>> effectiveCoordinates(String itemId) {
    return this.pool.preparedQuery(EFFECTIVE_COORDINATES).execute(Tuple.of(itemId)).map(rs -> {
      if (rs.rowCount() == 0)
        return Optional.<LatLong>empty();
      Row r = rs.iterator().next();
      return Optional.of(new LatLong(r.getDouble("latitude"), r.getDouble("longitude")));
    }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<Boolean> tag(String itemId, ItemTag tag) {
    AuditEvent tagged = event("item.tag", itemId, tag.toJson());
    return this.events.announce(this.pool.withTransaction(conn -> exists(conn, itemId).flatMap(ok -> !ok
        ? Uni.createFrom().item(false)
        : conn.preparedQuery("""
            INSERT INTO item_tags (item_id, tag_key, tag_value) VALUES ($1, $2, $3)
            ON CONFLICT (item_id, tag_key) DO UPDATE SET tag_value = EXCLUDED.tag_value""")
            .execute(Tuple.of(itemId, tag.key(), tag.value()))
            .flatMap(v -> audit(conn, tagged)).map(v -> true)))
        .subscribeAsCompletionStage(), tagged);
  }

  @Override
  public CompletionStage<Boolean> untag(String itemId, String key) {
    AuditEvent untagged = event("item.untag", itemId, new JsonObject().put("key", key));
    return this.events.announce(this.pool.withTransaction(conn -> conn
        .preparedQuery("DELETE FROM item_tags WHERE item_id=$1 AND lower(tag_key)=lower($2)")
        .execute(Tuple.of(itemId, key))
        .flatMap(rs -> rs.rowCount() == 0 ? Uni.createFrom().item(false)
            : audit(conn, untagged).map(v -> true)))
        .subscribeAsCompletionStage(), untagged);
  }

  @Override
  public CompletionStage<List<Item>> findByTag(TagQuery query) {
    // matching semantics live in TagQuery so both backends agree exactly;
    // the database narrows by key existence only when the mode is EXACT
    return loadAll(this.pool)
        .map(m -> m.values().stream().filter(i -> i.getTags().stream().anyMatch(query::matches)).toList())
        .subscribeAsCompletionStage();
  }

  /** addIdentity outcomes; null (unknown item) never announces. */
  private enum Claim {
    ADDED, IDEMPOTENT
  }

  @Override
  public CompletionStage<Boolean> addIdentity(String itemId, io.artifexlabs.inventory.api.ItemIdentity identity) {
    AuditEvent added = event("item.identity-add", itemId, identity.toJson());
    // the INSERT is the atomic claim; the follow-up SELECT only explains a
    // conflict, so no concurrent claimer can slip between check and claim
    CompletionStage<Claim> tx = this.pool.withTransaction(conn -> exists(conn, itemId).flatMap(ok -> !ok
        ? Uni.createFrom().nullItem()
        : conn.preparedQuery("""
            INSERT INTO item_identities (kind, value, item_id) VALUES ($1, $2, $3)
            ON CONFLICT (kind, value) DO NOTHING""")
            .execute(Tuple.of(identity.kind(), identity.value(), itemId))
            .flatMap(rs -> rs.rowCount() == 1 ? audit(conn, added).map(v -> Claim.ADDED)
                : conn.preparedQuery("SELECT item_id FROM item_identities WHERE kind=$1 AND value=$2")
                    .execute(Tuple.of(identity.kind(), identity.value()))
                    .flatMap(cur -> {
                      String claimed = cur.iterator().next().getString("item_id");
                      return claimed.equals(itemId) ? Uni.createFrom().item(Claim.IDEMPOTENT)
                          : Uni.createFrom().failure(new IllegalStateException("identity " + identity.kind()
                              + ":" + identity.value() + " already claims item " + claimed));
                    }))))
        .subscribeAsCompletionStage();
    // only a real claim wrote an audit row, so only it announces
    return tx.thenApply(claim -> {
      if (claim == Claim.ADDED)
        this.events.publish(added);
      return claim != null;
    });
  }

  @Override
  public CompletionStage<Boolean> removeIdentity(String itemId, io.artifexlabs.inventory.api.ItemIdentity identity) {
    AuditEvent removed = event("item.identity-remove", itemId, identity.toJson());
    return this.events.announce(this.pool.withTransaction(conn -> conn
        .preparedQuery("DELETE FROM item_identities WHERE kind=$1 AND value=$2 AND item_id=$3")
        .execute(Tuple.of(identity.kind(), identity.value(), itemId))
        .flatMap(rs -> rs.rowCount() == 0 ? Uni.createFrom().item(false)
            : audit(conn, removed).map(v -> true)))
        .subscribeAsCompletionStage(), removed);
  }

  @Override
  public CompletionStage<Optional<Item>> findByIdentity(String kind, String value) {
    // normalize exactly as storage did (kind lowercased, both trimmed)
    var identity = new io.artifexlabs.inventory.api.ItemIdentity(kind, value);
    return this.pool.preparedQuery("SELECT item_id FROM item_identities WHERE kind=$1 AND value=$2")
        .execute(Tuple.of(identity.kind(), identity.value()))
        .flatMap(rs -> {
          var it = rs.iterator();
          if (!it.hasNext())
            return Uni.createFrom().item(Optional.<Item>empty());
          String itemId = it.next().getString("item_id");
          return loadAll(this.pool).map(m -> Optional.ofNullable(m.get(itemId)));
        }).subscribeAsCompletionStage();
  }

  @Override
  public CompletionStage<List<io.artifexlabs.inventory.api.ItemIdentity>> identitiesOf(String itemId) {
    return this.pool.preparedQuery("SELECT kind, value FROM item_identities WHERE item_id=$1 ORDER BY kind, value")
        .execute(Tuple.of(itemId))
        .map(rs -> {
          List<io.artifexlabs.inventory.api.ItemIdentity> out = new java.util.ArrayList<>();
          rs.forEach(row -> out
              .add(new io.artifexlabs.inventory.api.ItemIdentity(row.getString("kind"), row.getString("value"))));
          return List.copyOf(out);
        }).subscribeAsCompletionStage();
  }

  private static Uni<Boolean> exists(SqlClient conn, String id) {
    return conn.preparedQuery("SELECT count(*) AS c FROM items WHERE id=$1").execute(Tuple.of(id))
        .map(rs -> rs.iterator().next().getLong("c") == 1L);
  }

  private static Uni<Boolean> bothExist(SqlClient conn, String idA, String idB) {
    return conn.preparedQuery("SELECT count(*) AS c FROM items WHERE id=$1 OR id=$2").execute(Tuple.of(idA, idB))
        .map(rs -> rs.iterator().next().getLong("c") == 2L);
  }

  /** True when itemId is already an ancestor of containerId (or is it). */
  private static Uni<Boolean> wouldCycle(SqlClient conn, String containerId, String itemId) {
    return conn.preparedQuery(IS_ANCESTOR).execute(Tuple.of(containerId, itemId))
        .map(rs -> rs.iterator().next().getLong("c") > 0L);
  }

  /** Inserts the exact event that will be published after commit — same id. */
  private Uni<Void> audit(SqlClient conn, AuditEvent e) {
    return conn.preparedQuery(INSERT_AUDIT)
        .execute(Tuple.of(e.getId(), odt(e.getTimestamp()), e.getPrincipal(), e.getAction(), e.getTargetId(),
            e.getDetails().orElse(null)))
        .replaceWithVoid();
  }

  private Uni<Void> replaceTags(SqlClient conn, Item item) {
    Uni<Void> cleared = conn.preparedQuery("DELETE FROM item_tags WHERE item_id=$1")
        .execute(Tuple.of(item.getId())).replaceWithVoid();
    Set<ItemTag> tags = item.getTags();
    if (tags.isEmpty())
      return cleared;
    List<Tuple> rows = tags.stream().map(t -> Tuple.of(item.getId(), t.key(), t.value())).toList();
    return cleared.flatMap(v -> conn
        .preparedQuery("INSERT INTO item_tags (item_id, tag_key, tag_value) VALUES ($1, $2, $3)")
        .executeBatch(rows).replaceWithVoid());
  }

  private Uni<Map<String, Item>> loadAll(SqlClient conn) {
    return conn.query("SELECT * FROM items").execute()
        .flatMap(items -> conn.query("SELECT item_id, tag_key, tag_value FROM item_tags").execute()
            .map(tags -> assemble(items, tags)));
  }

  /** Flat rows in; one-level-deep containment and tags out. */
  private static Map<String, Item> assemble(RowSet<Row> itemRows, RowSet<Row> tagRows) {
    Map<String, Set<ItemTag>> tagsByItem = new HashMap<>();
    for (Row r : tagRows)
      tagsByItem.computeIfAbsent(r.getString("item_id"), k -> new TreeSet<>())
          .add(new ItemTag(r.getString("tag_key"), r.getString("tag_value")));

    Map<String, Item> flat = new HashMap<>();
    for (Row r : itemRows) {
      Item i = fromRow(r, tagsByItem.getOrDefault(r.getString("id"), Set.of()));
      flat.put(i.getId(), i);
    }
    // derive contents from each child's container_id — storage holds one direction
    Map<String, Set<Item>> children = new HashMap<>();
    for (Item i : flat.values())
      i.getContainerId().ifPresent(parent -> children.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(i));

    Map<String, Item> out = new HashMap<>(flat);
    children.forEach((parentId, kids) -> {
      Item parent = flat.get(parentId);
      if (parent != null)
        out.put(parentId, DefaultItem.builder(parent).containedItems(kids).build());
    });
    return out;
  }

  private static Item fromRow(Row r, Set<ItemTag> tags) {
    DefaultItem.Builder b = DefaultItem.builder().id(r.getString("id")).name(r.getString("name"))
        .displayName(r.getString("display_name")).type(r.getString("type"))
        .timestamp(r.getOffsetDateTime("ts").toInstant()).description(r.getString("description"))
        .containerId(r.getString("container_id")).heavy(Boolean.TRUE.equals(r.getBoolean("heavy")))
        .tags(tags);
    Double lat = r.getDouble("latitude");
    Double lng = r.getDouble("longitude");
    if (lat != null && lng != null)
      b.coordinates(new LatLong(lat, lng));
    OffsetDateTime expires = r.getOffsetDateTime("expires_at");
    if (expires != null)
      b.expiration(new Expiration(expires.toInstant(), Boolean.TRUE.equals(r.getBoolean("expiration_absolute"))));
    String kind = r.getString("data_kind");
    if (kind != null)
      b.dataInfo(new DataInfo(MediaKind.valueOf(kind), Boolean.TRUE.equals(r.getBoolean("data_mutable")),
          Boolean.TRUE.equals(r.getBoolean("data_archive"))));
    Long quantity = r.getLong("quantity");
    if (quantity != null)
      b.quantity(quantity);
    Double grams = r.getDouble("weight_grams");
    if (grams != null)
      b.weight(new Weight(grams));
    Long minOnHand = r.getLong("min_on_hand");
    Long maxOnHand = r.getLong("max_on_hand");
    if (minOnHand != null && maxOnHand != null)
      b.parValues(new ParValues(minOnHand, maxOnHand));
    Double l = r.getDouble("length_cm");
    Double w = r.getDouble("width_cm");
    Double h = r.getDouble("height_cm");
    if (l != null && w != null && h != null)
      b.dimensions(new Dimensions(l, w, h));
    return b.build();
  }

  private static Tuple updateTuple(Item i) {
    DataInfo di = i.getDataInfo().orElse(null);
    Dimensions dims = i.getDimensions().orElse(null);
    LatLong coords = i.getCoordinates().orElse(null);
    Expiration exp = i.getExpiration().orElse(null);
    return Tuple.from(new Object[] { i.getId(), i.getName(), i.getDisplayName().orElse(null), i.getType(),
        i.getDescription().orElse(null), i.getContainerId().orElse(null), di == null ? null : di.kind().name(),
        di == null ? null : di.mutable(), di == null ? null : di.archive(), i.getQuantity().orElse(null),
        i.getWeight().map(Weight::grams).orElse(null), dims == null ? null : dims.lengthCm(),
        dims == null ? null : dims.widthCm(), dims == null ? null : dims.heightCm(), odt(i.getTimestamp()),
        i.getParValues().map(ParValues::minOnHand).orElse(null),
        i.getParValues().map(ParValues::maxOnHand).orElse(null),
        coords == null ? null : coords.latitude(), coords == null ? null : coords.longitude(), i.isHeavy(),
        exp == null ? null : odt(exp.when()), exp != null && exp.absolute() });
  }

  private static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
