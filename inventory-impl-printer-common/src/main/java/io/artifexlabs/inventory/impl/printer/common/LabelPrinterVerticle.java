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
package io.artifexlabs.inventory.impl.printer.common;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.LabelPrinter;
import io.artifexlabs.inventory.api.events.StatusEvent;
import io.artifexlabs.inventory.api.events.StatusPublisher;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

/**
 * The printer as a bus participant (PLAN.md Phase 21, ask 1): callers send a
 * {@link PrintPackets} packet to {@code printer.*} instead of holding a
 * {@link LabelPrinter} reference, which is what lets a printer live in a
 * different process — or eventually on a print-station node — without any
 * caller knowing.
 *
 * <p>
 * <b>Replies are ACCEPTANCE, not completion.</b> The packet is validated and
 * queued, the sender is released immediately, and the outcome follows as a
 * {@link StatusEvent}: {@code printer.printed} on success, and whatever
 * refusal the driver already publishes on failure. This is the honest
 * contract for a unidirectional TCP-9100 printer — completion was never
 * observable — and it keeps an HTTP request from waiting on hardware.
 *
 * <p>
 * This class is vendor-agnostic: the vendor lives in the injected
 * {@link LabelPrinter}, which is where the Brother/Zebra difference already
 * was. One verticle per deployed printer.
 */
public class LabelPrinterVerticle extends AbstractVerticle {
  private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LabelPrinterVerticle.class);

  private final LabelPrinter printer;
  private final StatusPublisher status;
  private final Function<String, CompletionStage<Optional<Item>>> lookup;

  public LabelPrinterVerticle(LabelPrinter printer, StatusPublisher status) {
    this(printer, status, null);
  }

  /**
   * {@code lookup} backs the by-reference escape hatch — a packet carrying
   * only an item id. Null means this printer accepts self-contained packets
   * only, which is the deployable-anywhere case.
   */
  public LabelPrinterVerticle(LabelPrinter printer, StatusPublisher status,
      Function<String, CompletionStage<Optional<Item>>> lookup) {
    this.printer = requireNonNull(printer, "printer");
    this.status = status == null ? StatusPublisher.NOOP : status;
    this.lookup = lookup;
  }

  @Override
  public void start() {
    this.vertx.eventBus().<JsonObject>consumer(PrintPackets.PRINT, this::onPrint);
    this.vertx.eventBus().<JsonObject>consumer(PrintPackets.PRINT_BATCH, this::onPrintBatch);
    this.vertx.eventBus().<JsonObject>consumer(PrintPackets.FEED, this::onFeed);
  }

  private void onPrint(Message<JsonObject> message) {
    JsonObject packet = message.body();
    if (packet == null) {
      message.reply(PrintPackets.refused("empty print packet"));
      return;
    }
    String actor = PrintPackets.actorOf(packet).orElse(null);
    String correlation = PrintPackets.correlationOf(packet).orElse(null);
    resolve(PrintPackets.readLabel(packet)).whenComplete((request, failure) -> {
      if (failure != null || request == null) {
        message.reply(PrintPackets.refused(failure == null ? "no such item" : failure.toString()));
        return;
      }
      // accepted: the sender is free, the outcome follows as an event
      message.reply(PrintPackets.accepted(1));
      this.printer.printLabel(request.item(), request.scanUrl(), request.qrPng(), request.format())
          .whenComplete((printed, thrown) -> report(printed, thrown, request.item().getId(), 1, actor, correlation));
    });
  }

  private void onPrintBatch(Message<JsonObject> message) {
    JsonObject packet = message.body();
    List<JsonObject> labels = packet == null ? List.of() : PrintPackets.readBatch(packet);
    if (labels.isEmpty()) {
      message.reply(PrintPackets.refused("empty batch packet"));
      return;
    }
    boolean halfCut = packet.getBoolean("halfCut", Boolean.TRUE);
    String actor = PrintPackets.actorOf(packet).orElse(null);
    String correlation = PrintPackets.correlationOf(packet).orElse(null);

    CompletionStage<List<LabelPrinter.LabelRequest>> collected =
        java.util.concurrent.CompletableFuture.completedStage(new ArrayList<>());
    for (JsonObject label : labels)
      collected = collected.thenCompose(acc -> resolve(PrintPackets.readLabel(label)).thenApply(r -> {
        acc.add(r);
        return acc;
      }));

    collected.whenComplete((requests, failure) -> {
      if (failure != null) {
        message.reply(PrintPackets.refused(failure.toString()));
        return;
      }
      message.reply(PrintPackets.accepted(requests.size()));
      this.printer.printBatch(requests, halfCut)
          .whenComplete((printed, thrown) -> report(printed, thrown, "printer", requests.size(), actor, correlation));
    });
  }

  private void onFeed(Message<JsonObject> message) {
    message.reply(PrintPackets.accepted(0));
    JsonObject packet = message.body();
    String actor = packet == null ? null : PrintPackets.actorOf(packet).orElse(null);
    this.printer.feed().whenComplete((fed, thrown) -> {
      if (thrown == null && Boolean.TRUE.equals(fed))
        this.status.publish(StatusEvent.info("printer.fed", "The tape was fed and cut.")
            .source("printer").actor(actor));
      // failures already publish their own event from the driver
    });
  }

  /** Turn a packet into a printable request, fetching the item if sent by reference. */
  private CompletionStage<LabelPrinter.LabelRequest> resolve(PrintPackets.LabelPacket packet) {
    if (packet.item() != null)
      return java.util.concurrent.CompletableFuture.completedStage(
          new LabelPrinter.LabelRequest(packet.item(), packet.scanUrl(), packet.qrPng(), packet.format()));
    if (this.lookup == null || packet.itemId() == null)
      return java.util.concurrent.CompletableFuture.failedStage(
          new IllegalArgumentException("packet carries no item and this printer cannot resolve one"));
    return this.lookup.apply(packet.itemId())
        .thenApply(found -> new LabelPrinter.LabelRequest(
            found.orElseThrow(() -> new IllegalArgumentException("no such item: " + packet.itemId())),
            packet.scanUrl(), packet.qrPng(), packet.format()));
  }

  /**
   * The outcome, after the fact. Success is announced here; every failure
   * mode already publishes its own specific refusal from the driver, so this
   * only backstops the ones that arrive as a bare false or an exception.
   */
  private void report(Boolean printed, Throwable thrown, String subjectId, int count, String actor,
      String correlation) {
    if (thrown == null && Boolean.TRUE.equals(printed)) {
      this.status.publish(StatusEvent.info("printer.printed",
          count == 1 ? "The label was sent to the printer." : "The label run was sent to the printer.")
          .source("printer").subject("itemId", subjectId).subject("count", String.valueOf(count))
          .actor(actor).correlationId(correlation));
      return;
    }
    log.warn("print did not succeed for {} ({} labels): {}", subjectId, count,
        thrown == null ? "refused" : thrown.toString());
    if (thrown != null)
      this.status.publish(StatusEvent.error("printer.print-failed", "The label could not be printed.")
          .source("printer").subject("itemId", subjectId).actor(actor).correlationId(correlation)
          .detail(thrown.toString()));
  }
}
