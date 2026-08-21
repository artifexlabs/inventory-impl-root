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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.ItemTag;
import io.artifexlabs.inventory.api.LatLong;
import io.artifexlabs.inventory.api.TagQuery;

import io.vertx.core.json.JsonObject;

/**
 * A complete in-memory {@link InventorySystem}. The default backend for dev and test profiles; every mutation is
 * recorded to the supplied {@link AuditSink}.
 *
 * Containment is stored the way the database stores it (Phase 15): each item holds its single {@code containerId}, and
 * an item's contents are DERIVED by looking for children. Nothing is nested in storage, so a move is one field write
 * and can never leave the same child in two places.
 *
 * @author mykel
 *
 */
public class InMemoryInventorySystem implements InventorySystem {
  private final ConcurrentHashMap<String, Item> items;
  /** (kind,value) marker -> itemId, exactly like the item_identities table. */
  private final ConcurrentHashMap<io.artifexlabs.inventory.api.ItemIdentity, String> identities;
  private final AuditSink auditSink;
  private final String principal;

  public InMemoryInventorySystem(AuditSink auditSink, String principal) {
    this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), auditSink, principal);
  }

  /** View constructor: shares the store, differs only in attribution. */
  private InMemoryInventorySystem(ConcurrentHashMap<String, Item> items,
      ConcurrentHashMap<io.artifexlabs.inventory.api.ItemIdentity, String> identities, AuditSink auditSink,
      String principal)
  {
    this.items = items;
    this.identities = identities;
    this.auditSink = requireNonNull(auditSink, "auditSink");
    this.principal = requireNonNull(principal, "principal");
  }

  @Override
  public InMemoryInventorySystem actingAs(String principal) {
    return new InMemoryInventorySystem(this.items, this.identities, this.auditSink, principal);
  }

  /** Attach the item's children; absent contents mean "not a container". */
  private Item hydrate(Item item) {
    if (item == null)
      return null;
    Set<Item> children = new LinkedHashSet<>();
    for (Item candidate : this.items.values())
      if (candidate.getContainerId().filter(item.getId()::equals).isPresent())
        children.add(bare(candidate));
    return DefaultItem.builder(item).containedItems(children.isEmpty() ? null : children).build();
  }

  /** Children are returned without their own contents, so trees cannot recurse forever. */
  private static Item bare(Item item) {
    return DefaultItem.builder(item).containedItems(null).build();
  }

  @Override
  public CompletionStage<List<Item>> getAllItems() {
    return CompletableFuture.completedStage(this.items.values().stream().map(this::hydrate).toList());
  }

  @Override
  public CompletionStage<List<Item>> getItemsOfType(String type) {
    return CompletableFuture
        .completedStage(this.items.values().stream().filter(i -> i.getType().equals(type)).map(this::hydrate).toList());
  }

  @Override
  public CompletionStage<Optional<Item>> getItem(String id) {
    return CompletableFuture.completedStage(Optional.ofNullable(hydrate(this.items.get(id))));
  }

  @Override
  public CompletionStage<Item> createItem(String name, String displayName, String type) {
    Item item = DefaultItem.builder().id(Ulid.next()).name(name).displayName(displayName).type(type)
        .timestamp(Instant.now()).build();
    this.items.put(item.getId(), item);
    return audit("item.create", item).thenApply(v -> item);
  }

  @Override
  public CompletionStage<Boolean> updateItem(Item item) {
    // contents are derived, never stored — but containerId IS stored, and the
    // edit forms set it, so an update must pass the same cycle check as
    // addToContainer
    if (item.getContainerId().filter(c -> wouldCycle(c, item.getId())).isPresent())
      return CompletableFuture.completedStage(false);
    Item stored = bare(item);
    boolean replaced = this.items.replace(item.getId(), stored) != null;
    return replaced ? audit("item.update", stored).thenApply(v -> true) : CompletableFuture.completedStage(false);
  }

  @Override
  public CompletionStage<Boolean> deleteItem(String id) {
    Item removed = this.items.remove(id);
    if (removed == null)
      return CompletableFuture.completedStage(false);
    // orphan the children rather than cascade — losing a shelf must not lose its contents
    for (Item child : List.copyOf(this.items.values()))
      if (child.getContainerId().filter(id::equals).isPresent())
        this.items.put(child.getId(), DefaultItem.builder(child).containerId(null).build());
    // markers die with their item (the item_identities FK cascade)
    this.identities.values().removeIf(id::equals);
    return audit("item.delete", removed).thenApply(v -> true);
  }

  @Override
  public CompletionStage<Optional<Item>> getContainer(String itemId) {
    Item item = this.items.get(itemId);
    return CompletableFuture.completedStage(
        item == null ? Optional.empty() : item.getContainerId().map(this.items::get).map(this::hydrate));
  }

  @Override
  public CompletionStage<Boolean> addToContainer(String containerId, String itemId) {
    return reparent(containerId, itemId, "item.contain");
  }

  @Override
  public CompletionStage<Boolean> moveToContainer(String itemId, String targetContainerId) {
    return reparent(targetContainerId, itemId, "item.move");
  }

  /** The single containment write: set the child's parent, refusing cycles. */
  private CompletionStage<Boolean> reparent(String containerId, String itemId, String action) {
    Item child = this.items.get(itemId);
    if (child == null || containerId == null || containerId.equals(itemId) || !this.items.containsKey(containerId))
      return CompletableFuture.completedStage(false);
    if (wouldCycle(containerId, itemId))
      return CompletableFuture.completedStage(false);
    this.items.put(itemId, DefaultItem.builder(child).containerId(containerId).build());
    return audit(action, itemId, new JsonObject().put("containerId", containerId)).thenApply(v -> true);
  }

  /** True when placing item inside container would make item its own ancestor. */
  private boolean wouldCycle(String containerId, String itemId) {
    String walk = containerId;
    for (int guard = 0; walk != null && guard < 1000; guard++) {
      if (walk.equals(itemId))
        return true;
      Item next = this.items.get(walk);
      walk = next == null ? null : next.getContainerId().orElse(null);
    }
    return false;
  }

  @Override
  public CompletionStage<Boolean> removeFromContainer(String containerId, String itemId) {
    Item child = this.items.get(itemId);
    if (child == null || child.getContainerId().filter(containerId::equals).isEmpty())
      return CompletableFuture.completedStage(false);
    this.items.put(itemId, DefaultItem.builder(child).containerId(null).build());
    return audit("item.uncontain", itemId, new JsonObject().put("containerId", containerId)).thenApply(v -> true);
  }

  @Override
  public CompletionStage<Optional<LatLong>> effectiveCoordinates(String itemId) {
    String walk = itemId;
    for (int guard = 0; walk != null && guard < 1000; guard++) {
      Item item = this.items.get(walk);
      if (item == null)
        break;
      Optional<LatLong> own = item.getCoordinates();
      if (own.isPresent())
        return CompletableFuture.completedStage(own);
      walk = item.getContainerId().orElse(null);
    }
    return CompletableFuture.completedStage(Optional.empty());
  }

  @Override
  public CompletionStage<Boolean> tag(String itemId, ItemTag tag) {
    Item item = this.items.get(itemId);
    if (item == null)
      return CompletableFuture.completedStage(false);
    Set<ItemTag> next = new TreeSet<>(item.getTags());
    next.removeIf(t -> t.key().equalsIgnoreCase(tag.key()));
    next.add(tag);
    this.items.put(itemId, DefaultItem.builder(item).tags(next).build());
    return audit("item.tag", itemId, tag.toJson()).thenApply(v -> true);
  }

  @Override
  public CompletionStage<Boolean> untag(String itemId, String key) {
    Item item = this.items.get(itemId);
    if (item == null)
      return CompletableFuture.completedStage(false);
    Set<ItemTag> next = new TreeSet<>(item.getTags());
    if (!next.removeIf(t -> t.key().equalsIgnoreCase(key)))
      return CompletableFuture.completedStage(false);
    this.items.put(itemId, DefaultItem.builder(item).tags(next).build());
    return audit("item.untag", itemId, new JsonObject().put("key", key)).thenApply(v -> true);
  }

  @Override
  public CompletionStage<List<Item>> findByTag(TagQuery query) {
    return CompletableFuture.completedStage(this.items.values().stream()
        .filter(i -> i.getTags().stream().anyMatch(query::matches)).map(this::hydrate).toList());
  }

  @Override
  public CompletionStage<Boolean> addIdentity(String itemId, io.artifexlabs.inventory.api.ItemIdentity identity) {
    if (!this.items.containsKey(itemId))
      return CompletableFuture.completedStage(false);
    String claimed = this.identities.putIfAbsent(identity, itemId);
    if (claimed != null)
      return itemId.equals(claimed) ? CompletableFuture.completedStage(true) // idempotent re-claim
          : CompletableFuture.failedStage(new IllegalStateException(
              "identity " + identity.kind() + ":" + identity.value() + " already claims item " + claimed));
    return audit("item.identity-add", itemId, identity.toJson()).thenApply(v -> true);
  }

  @Override
  public CompletionStage<Boolean> removeIdentity(String itemId, io.artifexlabs.inventory.api.ItemIdentity identity) {
    if (!this.items.containsKey(itemId) || !this.identities.remove(identity, itemId))
      return CompletableFuture.completedStage(false);
    return audit("item.identity-remove", itemId, identity.toJson()).thenApply(v -> true);
  }

  @Override
  public CompletionStage<Optional<Item>> findByIdentity(String kind, String value) {
    String itemId = this.identities.get(new io.artifexlabs.inventory.api.ItemIdentity(kind, value));
    return CompletableFuture.completedStage(Optional.ofNullable(itemId).map(this.items::get).map(this::hydrate));
  }

  @Override
  public CompletionStage<List<io.artifexlabs.inventory.api.ItemIdentity>> identitiesOf(String itemId) {
    return CompletableFuture.completedStage(this.identities.entrySet().stream().filter(e -> e.getValue().equals(itemId))
        .map(java.util.Map.Entry::getKey).sorted().toList());
  }

  private CompletionStage<Void> audit(String action, Item target) {
    return this.auditSink.record(new DefaultAuditEvent(Ulid.next(), Instant.now(), this.principal, action,
        target.getId(), ItemFactory.serialize(target)));
  }

  private CompletionStage<Void> audit(String action, String targetId, JsonObject details) {
    return this.auditSink
        .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), this.principal, action, targetId, details));
  }
}
