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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.bus.BusEnvelope;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

/**
 * The single door to storage (PLAN.md Phase 21, ask 2). Every read and every write of the backing store passes through
 * this verticle; no other verticle holds an {@code InventorySystem}, {@code AssetStore}, {@code UserStore},
 * {@code TokenService}, {@code RegionSystem}, {@code AuditReader} or {@code AuditSink} reference at all, which is what
 * makes the storage mechanism replaceable from the outside.
 *
 * <p>
 * <b>The operations are DOMAIN operations, never row-level CRUD.</b> That is the load-bearing constraint: bus messages
 * cannot share a database transaction, so anything a caller would have to compose from two messages would tear the
 * atomicity the Postgres backend guarantees in-process. Every action registered here is one whole unit of work — which
 * is exactly the shape {@code BusActions} already had, and why this was tractable.
 *
 * <p>
 * This address is INTERNAL and deliberately unguarded: admission (fabric token and role) already happened at the public
 * service verticle that forwarded the envelope. Exposing it would bypass that check, so it must never be reachable from
 * outside the bus — which the deployment already ensures, because bus membership is access (VERTICLES.md).
 */
public class StorageVerticle extends AbstractVerticle {

  /** The internal address every storage operation arrives on. */
  public final static String ADDRESS = "storage";

  /** Handles one storage operation; the resolved value becomes the reply. */
  @FunctionalInterface
  public interface StorageHandler {
    CompletionStage<Object> handle(BusEnvelope envelope);
  }

  /** How a domain registrar contributes its operations. */
  @FunctionalInterface
  public interface Registrar {
    void on(String action, StorageHandler handler);
  }

  /**
   * Internal-only operations with no public {@code BusActions} counterpart: an orchestrating verticle needs them, but
   * no external caller may name them. They are namespaced so they can never collide with the public vocabulary.
   */
  public final static String AUDIT_RECORD = "storage.audit.record";
  /** Create an item (plus optional catalog image) from a UPC spec, atomically. */
  public final static String ASSETS_CREATE_FROM_UPC = "storage.assets.create-from-upc";

  private final Map<String, StorageHandler> handlers = new HashMap<>();

  public StorageVerticle(BusWorkers.BackendServices services, String provision) {
    ItemsStorage.register(this::on, services.inventory());
    AssetsStorage.register(this::on, services.assets());
    RegionsStorage.register(this::on, services.regions());
    AuditStorage.register(this::on, services.auditReader());
    UsersStorage.register(this::on, services.users(), services.auditSink());
    TokensStorage.register(this::on, services.tokens(), services.auditSink());
    AuthStorage.register(this::on, services.users(), services.tokens(), services.auditSink(), provision);
    // one transactional unit: the item and its catalog image are created
    // together or not at all, which is exactly why this cannot be composed
    // by a caller out of two messages
    on(ASSETS_CREATE_FROM_UPC, env -> {
      JsonObject d = env.data();
      JsonObject spec = d.getJsonObject("spec");
      var creation = new io.artifexlabs.inventory.api.UpcItemCreation(spec.getString("gtin13"), spec.getString("name"),
          spec.getString("displayName"), spec.getString("type"), spec.getString("description"),
          spec.getDouble("weightGrams"), spec.getString("containerId"),
          spec.getJsonArray("tags", new io.vertx.core.json.JsonArray()).stream()
              .map(io.vertx.core.json.JsonObject.class::cast).map(io.artifexlabs.inventory.api.ItemTag::fromJson)
              .toList());
      return services.assets().actingAs(env.principal())
          .createItemFromUpc(creation, d.getString("filename"), d.getString("contentType"), d.getBinary("bytes"))
          .thenApply(o -> o.map(made -> {
            JsonObject reply = new JsonObject().put("item",
                io.artifexlabs.inventory.api.ItemFactory.serialize(made.item()));
            if (made.asset() != null)
              reply.put("asset", made.asset().toJson());
            return (Object) reply;
          }).orElseThrow(() -> BusServiceException.notFound("no such container"))).exceptionally(e -> {
            // the backend signals "this marker is already claimed" with an
            // IllegalStateException; that meaning must be translated HERE,
            // because a bus reply only carries a code (it used to be mapped
            // in CatalogVerticle, which no longer sees the exception)
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IllegalStateException conflict)
              throw BusServiceException.conflict(conflict.getMessage());
            if (cause instanceof BusServiceException bse)
              throw bse;
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
          });
    });
    // the audit sink is storage too: an orchestrator records through here
    // rather than holding the sink itself
    on(AUDIT_RECORD,
        env -> services.auditSink().record(io.artifexlabs.inventory.api.AuditEventFactory.deserialize(env.data()))
            .thenApply(v -> (Object) new JsonObject().put("recorded", true)));
  }

  final void on(String action, StorageHandler handler) {
    this.handlers.put(action, handler);
  }

  /** Whether this storage layer can serve an action — used by the forwarders. */
  public final boolean handles(String action) {
    return this.handlers.containsKey(action);
  }

  @Override
  public void start(Promise<Void> started) {
    this.vertx.eventBus().<JsonObject>consumer(ADDRESS, this::dispatch).completionHandler(started);
  }

  private void dispatch(Message<JsonObject> message) {
    final BusEnvelope envelope;
    try {
      envelope = DefaultBusEnvelope.fromJson(message.body());
    } catch (RuntimeException e) {
      message.fail(400, "malformed storage envelope: " + e.getMessage());
      return;
    }
    StorageHandler handler = this.handlers.get(envelope.action());
    if (handler == null) {
      message.fail(400, "storage does not handle " + envelope.action());
      return;
    }
    try {
      handler.handle(envelope).whenComplete((result, error) -> {
        if (error == null)
          message.reply(result);
        else
          fail(message, error);
      });
    } catch (RuntimeException e) {
      fail(message, e);
    }
  }

  private static void fail(Message<JsonObject> message, Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    if (cause instanceof BusServiceException bse)
      message.fail(bse.code(), bse.getMessage());
    else
      message.fail(500, String.valueOf(cause.getMessage()));
  }
}
