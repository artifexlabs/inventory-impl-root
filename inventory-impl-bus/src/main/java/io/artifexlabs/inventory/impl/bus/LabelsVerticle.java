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

import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.DefaultAuditEvent;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.LabelPrinter;
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

  public LabelsVerticle(BusGuard guard, InventorySystem inventory, LabelPrinter printer, AuditSink audit) {
    super(BusActions.addressOf(BusActions.LABELS_QR), guard);
    on(BusActions.LABELS_QR, env -> {
      String id = requireTarget(env);
      String url = requireUrl(env);
      int size = env.data().getInteger("size", DEFAULT_QR_PIXELS);
      return inventory.getItem(id)
          .thenApply(o -> o.map(i -> (Object) new JsonObject().put("png", QrCodes.png(url, size)))
              .orElseThrow(() -> BusServiceException.notFound("no such item")));
    });
    on(BusActions.LABELS_PRINT, env -> {
      String id = requireTarget(env);
      String url = requireUrl(env);
      String format = env.data().getString("format"); // null = printer default
      String principal = env.principal();
      return inventory.getItem(id).thenCompose(o -> o
          .map(item -> printer.printLabel(item, url, QrCodes.png(url, DEFAULT_QR_PIXELS), format)
              .thenCompose(ok -> audit
                  .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), principal, "label.print", id,
                      new JsonObject().put("printed", ok)))
                  .thenApply(v -> {
                    if (!ok)
                      throw BusServiceException.unavailable("printer refused the label");
                    return (Object) new JsonObject().put("printed", true);
                  })))
          .orElseThrow(() -> BusServiceException.notFound("no such item")));
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

      CompletionStage<java.util.List<io.artifexlabs.inventory.api.LabelPrinter.LabelRequest>> collected =
          java.util.concurrent.CompletableFuture.completedStage(new java.util.ArrayList<>());
      for (String id : wanted) {
        String url = urls.getString(id);
        if (url == null || url.isBlank())
          throw BusServiceException.badRequest("no scan url supplied for item " + id);
        collected = collected.thenCompose(acc -> inventory.getItem(id).thenApply(found -> {
          acc.add(new io.artifexlabs.inventory.api.LabelPrinter.LabelRequest(
              found.orElseThrow(() -> BusServiceException.notFound("no such item: " + id)), url,
              QrCodes.png(url, DEFAULT_QR_PIXELS), format));
          return acc;
        }));
      }
      return collected.thenCompose(reqs -> printer.printBatch(reqs, halfCut).thenCompose(ok -> audit
          .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), principal, "label.print-batch",
              "printer", new JsonObject().put("printed", ok).put("count", reqs.size()).put("halfCut", halfCut)))
          .thenApply(v -> {
            if (!ok)
              throw BusServiceException.unavailable("printer refused the batch");
            return (Object) new JsonObject().put("printed", true).put("count", reqs.size());
          })));
    });
    on(BusActions.LABELS_FEED, env -> {
      String principal = env.principal();
      return printer.feed().thenCompose(ok -> audit
          .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), principal, "label.feed", "printer",
              new JsonObject().put("fed", ok)))
          .thenApply(v -> {
            if (!ok)
              throw BusServiceException.unavailable("printer cannot extend the tape");
            return (Object) new JsonObject().put("fed", true);
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
