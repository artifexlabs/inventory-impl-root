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

import java.time.Instant;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.impl.printer.common.PrintPackets;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.impl.printer.common.QrCodes;
import io.artifexlabs.inventory.api.Ulid;

import io.vertx.core.json.JsonObject;

/**
 * QR rendering and physical label printing over the bus. The QR encodes a
 * scan URL the HTTP tier supplies ({@code data.url}) — public addressing is
 * the gateway's knowledge, not the worker's. Deploy as a worker verticle:
 * QR/AWT composition is CPU work. Prints audit as {@code label.print} with
 * the envelope's principal, matching the audit vocabulary.
 */
public class LabelsVerticle extends ServiceVerticle {

  private final static int DEFAULT_QR_PIXELS = 300;

  /** The item, from storage; storage raises 404 when it is unknown. */
  private CompletionStage<io.artifexlabs.inventory.api.Item> item(
      io.artifexlabs.inventory.api.bus.BusEnvelope env, String id) {
    return storage(env, BusActions.ITEMS_GET, id, null)
        .thenApply(json -> io.artifexlabs.inventory.api.ItemFactory.deserialize((JsonObject) json));
  }

  /** Record an audit fact THROUGH storage — this verticle holds no sink. */
  private CompletionStage<Object> record(io.artifexlabs.inventory.api.bus.BusEnvelope env, String action,
      String targetId, JsonObject details) {
    return storage(env, StorageVerticle.AUDIT_RECORD, targetId,
        io.artifexlabs.inventory.api.AuditEventFactory.serialize(
            new DefaultAuditEvent(Ulid.next(), Instant.now(), env.principal(), action, targetId, details)));
  }

  /** Hand a packet to whichever printer verticle owns the address. */
  private CompletionStage<JsonObject> send(String address, JsonObject packet) {
    java.util.concurrent.CompletableFuture<JsonObject> done = new java.util.concurrent.CompletableFuture<>();
    this.vertx.eventBus().<JsonObject>request(address, packet, reply -> {
      if (reply.succeeded())
        done.complete(reply.result().body());
      else
        done.completeExceptionally(BusServiceException.unavailable("no printer is listening"));
    });
    return done;
  }

  private static boolean accepted(JsonObject ack) {
    return ack != null && Boolean.TRUE.equals(ack.getBoolean("accepted"));
  }

  private static String reason(JsonObject ack) {
    return ack == null ? "the printer did not answer" : ack.getString("reason", "the printer refused the packet");
  }

  public LabelsVerticle(BusGuard guard) {
    super(BusActions.addressOf(BusActions.LABELS_QR), guard);
    on(BusActions.LABELS_QR, env -> {
      String id = requireTarget(env);
      String url = requireUrl(env);
      int size = env.data().getInteger("size", DEFAULT_QR_PIXELS);
      // storage answers 404 itself when the item is unknown
      return item(env, id).thenApply(i -> (Object) new JsonObject().put("png", QrCodes.png(url, size)));
    });
    on(BusActions.LABELS_PRINT, env -> {
      String id = requireTarget(env);
      String url = requireUrl(env);
      String format = env.data().getString("format"); // null = printer default
      String principal = env.principal();
      String actor = env.userId();
      return item(env, id).thenCompose(item -> send(PrintPackets.PRINT, PrintPackets.attribute(
          PrintPackets.label(item, url, format, QrCodes.png(url, DEFAULT_QR_PIXELS)), actor, null))
              .thenCompose(ack -> record(env, "label.print", id,
                      new JsonObject().put("accepted", accepted(ack)))
                  .thenApply(v -> {
                    // acceptance, not completion: TCP 9100 never told us more,
                    // and the outcome arrives on status.events (MORE_VERTX)
                    if (!accepted(ack))
                      throw BusServiceException.unavailable(reason(ack));
                    return (Object) new JsonObject().put("accepted", true);
                  })));
    });
    on(BusActions.LABELS_PRINT_BATCH, env -> {
      // ONE printer job for the whole run, so continuous tape spends a
      // single leader instead of one per label (ongoing item 10). Scan URLs
      // arrive per item because public addressing is the gateway's knowledge.
      io.vertx.core.json.JsonArray ids = env.data().getJsonArray("itemIds");
      if (ids == null || ids.isEmpty())
        throw BusServiceException.badRequest("labels.print-batch requires a non-empty data.itemIds");
      JsonObject urls = env.data().getJsonObject("urls");
      if (urls == null)
        throw BusServiceException.badRequest("labels.print-batch requires data.urls (itemId -> scan url)");
      String format = env.data().getString("format");
      // half cut between labels by default: a strip you tear apart
      boolean halfCut = env.data().getBoolean("halfCut", Boolean.TRUE);
      String principal = env.principal();
      java.util.List<String> wanted = ids.stream().map(String::valueOf).toList();

      String actor = env.userId();
      CompletionStage<java.util.List<JsonObject>> collected =
          java.util.concurrent.CompletableFuture.completedStage(new java.util.ArrayList<>());
      for (String id : wanted) {
        String url = urls.getString(id);
        if (url == null || url.isBlank())
          throw BusServiceException.badRequest("no scan url supplied for item " + id);
        collected = collected.thenCompose(acc -> item(env, id).thenApply(found -> {
          acc.add(PrintPackets.label(found, url, format, QrCodes.png(url, DEFAULT_QR_PIXELS)));
          return acc;
        }));
      }
      return collected.thenCompose(labels -> send(PrintPackets.PRINT_BATCH,
          PrintPackets.attribute(PrintPackets.batch(labels, halfCut), actor, null))
          .thenCompose(ack -> record(env, "label.print-batch", "printer",
                  new JsonObject().put("accepted", accepted(ack)).put("count", labels.size())
                      .put("halfCut", halfCut))
              .thenApply(v -> {
                if (!accepted(ack))
                  throw BusServiceException.unavailable(reason(ack));
                return (Object) new JsonObject().put("accepted", true).put("count", labels.size());
              })));
    });
    on(BusActions.LABELS_FEED, env -> {
      String principal = env.principal();
      return send(PrintPackets.FEED, PrintPackets.attribute(new JsonObject(), env.userId(), null))
          .thenCompose(ack -> record(env, "label.feed", "printer",
                  new JsonObject().put("accepted", accepted(ack)))
              .thenApply(v -> {
                if (!accepted(ack))
                  throw BusServiceException.unavailable(reason(ack));
                return (Object) new JsonObject().put("accepted", true);
              }));
    });
  }

  private static String requireUrl(io.artifexlabs.inventory.api.bus.BusEnvelope env) {
    String url = env.data().getString("url");
    if (url == null || url.isBlank())
      throw BusServiceException.badRequest(env.action() + " requires data.url (the scan URL)");
    return url;
  }
}
