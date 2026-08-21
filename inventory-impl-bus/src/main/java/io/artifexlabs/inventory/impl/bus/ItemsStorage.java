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
package io.artifexlabs.inventory.impl.bus;

import java.util.List;

import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonArray;

/** Item CRUD and containment over the bus. */
/**
 * Items operations against the backing store (MORE_VERTX ask 2). These are
 * whole units of work, never composable row CRUD: a caller that had to
 * stitch two of them together would lose the transaction the backend
 * guarantees inside one.
 */
final class ItemsStorage {

  private ItemsStorage() {
  }

  static void register(StorageVerticle.Registrar reg, InventorySystem inventory) {
    reg.on(BusActions.ITEMS_LIST, env -> inventory.getAllItems().thenApply(ItemsStorage::serialize));
    reg.on(BusActions.ITEMS_LIST_OF_TYPE,
        env -> inventory.getItemsOfType(Envelopes.requireTarget(env)).thenApply(ItemsStorage::serialize));
    reg.on(BusActions.ITEMS_GET, env -> inventory.getItem(Envelopes.requireTarget(env)).thenApply(o -> o.map(ItemFactory::serialize)
        .orElseThrow(() -> BusServiceException.notFound("no such item"))));
    reg.on(BusActions.ITEMS_CREATE, env -> {
      var creation = DefaultItemCreation.fromJson(env.data());
      return inventory.actingAs(env.principal()).createItem(creation.name(), creation.displayName(), creation.type())
          .thenApply(ItemFactory::serialize);
    });
    reg.on(BusActions.ITEMS_UPDATE, env -> {
      var update = DefaultItemUpdate.fromJson(env.data());
      if (env.targetId().filter(t -> !t.equals(update.itemId())).isPresent())
        throw BusServiceException.badRequest("envelope target does not match update id");
      return inventory.actingAs(env.principal()).updateItem(update.item())
          .thenApply(ok -> Envelopes.okOrNotFound(ok, "no such item", ItemFactory.serialize(update.item())));
    });
    reg.on(BusActions.ITEMS_DELETE,
        env -> inventory.actingAs(env.principal()).deleteItem(Envelopes.requireTarget(env)).thenApply(ok -> Envelopes.okOrNotFound(ok, "no such item", null)));
    reg.on(BusActions.ITEMS_CONTAINER_OF,
        env -> inventory.getContainer(Envelopes.requireTarget(env)).thenApply(o -> o.map(ItemFactory::serialize)
            .orElseThrow(() -> BusServiceException.notFound("item is a root, or unknown"))));
    reg.on(BusActions.ITEMS_COORDINATES, env -> inventory.effectiveCoordinates(Envelopes.requireTarget(env))
        .thenApply(o -> o.map(c -> new io.vertx.core.json.JsonObject().put("latitude", c.latitude())
            .put("longitude", c.longitude()))
            .orElseThrow(() -> BusServiceException.notFound("nothing in the container chain is pinned"))));
    reg.on(BusActions.ITEMS_TAG, env -> {
      var tag = io.artifexlabs.inventory.api.ItemTag.fromJson(env.data());
      return inventory.actingAs(env.principal()).tag(Envelopes.requireTarget(env), tag)
          .thenApply(ok -> Envelopes.okOrNotFound(ok, "no such item", null));
    });
    reg.on(BusActions.ITEMS_UNTAG, env -> {
      String key = env.data().getString("key");
      if (key == null || key.isBlank())
        throw BusServiceException.badRequest("items.untag requires data.key");
      return inventory.actingAs(env.principal()).untag(Envelopes.requireTarget(env), key)
          .thenApply(ok -> Envelopes.okOrNotFound(ok, "no such item or tag", null));
    });
    reg.on(BusActions.ITEMS_FIND_BY_TAG, env -> {
      final io.artifexlabs.inventory.api.TagQuery query;
      try {
        query = io.artifexlabs.inventory.api.TagQuery.fromJson(env.data());
      } catch (IllegalArgumentException e) {
        throw BusServiceException.badRequest(e.getMessage());
      }
      return inventory.findByTag(query).thenApply(ItemsStorage::serialize);
    });
    reg.on(BusActions.ITEMS_IDENTITY_ADD, env -> {
      var identity = parseIdentity(env.data());
      return inventory.actingAs(env.principal()).addIdentity(Envelopes.requireTarget(env), identity)
          .exceptionally(e -> {
            // a marker reused on a second item is a conflict, not a server error
            if (unwrap(e) instanceof IllegalStateException conflict)
              throw BusServiceException.conflict(conflict.getMessage());
            throw sneaky(e);
          })
          .thenApply(ok -> Envelopes.okOrNotFound(ok, "no such item", null));
    });
    reg.on(BusActions.ITEMS_IDENTITY_REMOVE, env -> {
      var identity = parseIdentity(env.data());
      return inventory.actingAs(env.principal()).removeIdentity(Envelopes.requireTarget(env), identity)
          .thenApply(ok -> Envelopes.okOrNotFound(ok, "no such item or identity", null));
    });
    reg.on(BusActions.ITEMS_FIND_BY_IDENTITY, env -> {
      var identity = parseIdentity(env.data());
      return inventory.findByIdentity(identity.kind(), identity.value())
          .thenApply(o -> o.map(ItemFactory::serialize)
              .orElseThrow(() -> BusServiceException.notFound("no item claims that identity")));
    });
    reg.on(BusActions.ITEMS_IDENTITIES_OF,
        env -> inventory.identitiesOf(Envelopes.requireTarget(env)).thenApply(ids -> new JsonArray(
            ids.stream().map(io.artifexlabs.inventory.api.ItemIdentity::toJson).toList())));
    reg.on(BusActions.ITEMS_CONTAIN, env -> {
      var change = DefaultContainmentChange.fromJson(env.data());
      return inventory.actingAs(env.principal()).addToContainer(change.containerId(), change.itemId())
          .thenApply(ok -> Envelopes.okOrNotFound(ok, "container or item unknown", null));
    });
    reg.on(BusActions.ITEMS_UNCONTAIN, env -> {
      var change = DefaultContainmentChange.fromJson(env.data());
      return inventory.actingAs(env.principal()).removeFromContainer(change.containerId(), change.itemId())
          .thenApply(ok -> Envelopes.okOrNotFound(ok, "container or item unknown, or not contained", null));
    });
    reg.on(BusActions.ITEMS_MOVE, env -> {
      var change = DefaultContainmentChange.fromJson(env.data());
      return inventory.actingAs(env.principal()).moveToContainer(change.itemId(), change.containerId())
          .thenApply(ok -> Envelopes.okOrNotFound(ok, "item or target container unknown", null));
    });
    }


  private static Object serialize(List<Item> items) {
    return new JsonArray(items.stream().map(ItemFactory::serialize).toList());
  }

  private static io.artifexlabs.inventory.api.ItemIdentity parseIdentity(io.vertx.core.json.JsonObject data) {
    try {
      return io.artifexlabs.inventory.api.ItemIdentity.fromJson(data);
    } catch (IllegalArgumentException e) {
      throw BusServiceException.badRequest(e.getMessage());
    }
  }

  private static Throwable unwrap(Throwable e) {
    return e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
  }

  /** Rethrow an unexpected failure without wrapping it in a new type. */
  private static RuntimeException sneaky(Throwable e) {
    return e instanceof RuntimeException re ? re : new RuntimeException(e);
  }

}
