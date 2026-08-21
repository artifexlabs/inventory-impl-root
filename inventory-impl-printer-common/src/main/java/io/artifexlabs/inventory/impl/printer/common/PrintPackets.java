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

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.LabelPrinter;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Addresses and wire format for reaching a printer over the event bus
 * (PLAN.md Phase 21, ask 1). A caller sends a PACKET carrying everything the
 * printer needs to produce a label; the printer answers with an
 * ACCEPTANCE, never a completion.
 *
 * <p>
 * <b>Why acceptance.</b> The printers speak JetDirect on TCP 9100, which is
 * unidirectional: "printed" was never knowable — the honest fact is only
 * ever "the printer took the bytes". So the reply says whether the packet
 * was accepted for printing, and the OUTCOME arrives separately as a
 * {@code StatusEvent} on {@code status.events}. That also stops an HTTP
 * request from hanging on a piece of hardware.
 *
 * <p>
 * Packets are SELF-CONTAINED by default (a serialized item snapshot), so a
 * printer can be deployed somewhere storage is not. A packet carrying only
 * {@code itemId} is the documented escape hatch for callers that cannot
 * snapshot; the printer verticle then has to resolve it itself.
 */
public final class PrintPackets {

  /** One label. */
  public final static String PRINT = "printer.print";
  /** A whole run as ONE printer job (chained tape — ongoing item 10). */
  public final static String PRINT_BATCH = "printer.print-batch";
  /** Feed blank media and cut. */
  public final static String FEED = "printer.feed";

  /** Payload schema version carried in the {@code v} field. */
  public final static int VERSION = 1;

  private PrintPackets() {
  }

  /** Everything needed to produce one label, self-contained. */
  public record LabelPacket(Item item, String itemId, String scanUrl, String format, byte[] qrPng) {

    /** The item this packet is about, whether it arrived whole or by id. */
    public String subjectId() {
      return this.item != null ? this.item.getId() : this.itemId;
    }
  }

  /** A packet built from a full item snapshot: nothing else to look up. */
  public final static JsonObject label(Item item, String scanUrl, String format, byte[] qrPng) {
    JsonObject packet = new JsonObject().put("v", VERSION).put("item", ItemFactory.serialize(item));
    return withOptions(packet, scanUrl, format, qrPng);
  }

  /** The escape hatch: only an id, which the printer must resolve itself. */
  public final static JsonObject labelByReference(String itemId, String scanUrl, String format, byte[] qrPng) {
    JsonObject packet = new JsonObject().put("v", VERSION).put("itemId", itemId);
    return withOptions(packet, scanUrl, format, qrPng);
  }

  private static JsonObject withOptions(JsonObject packet, String scanUrl, String format, byte[] qrPng) {
    if (scanUrl != null)
      packet.put("scanUrl", scanUrl);
    if (format != null)
      packet.put("format", format);
    if (qrPng != null)
      packet.put("qrPng", Base64.getEncoder().encodeToString(qrPng));
    return packet;
  }

  /** A run of labels plus how they separate; see {@link LabelPrinter#printBatch}. */
  public final static JsonObject batch(List<JsonObject> labels, boolean halfCutBetween) {
    return new JsonObject().put("v", VERSION).put("labels", new JsonArray(List.copyOf(labels)))
        .put("halfCut", halfCutBetween);
  }

  /** Attribution, so the outcome event can reach the person who asked. */
  public final static JsonObject attribute(JsonObject packet, String actor, String correlationId) {
    if (actor != null)
      packet.put("actor", actor);
    if (correlationId != null)
      packet.put("correlationId", correlationId);
    return packet;
  }

  /** Read one label packet; the item may be absent when sent by reference. */
  public final static LabelPacket readLabel(JsonObject packet) {
    JsonObject raw = packet.getJsonObject("item");
    Item item = raw == null ? null : ItemFactory.deserialize(raw);
    String encoded = packet.getString("qrPng");
    return new LabelPacket(item, packet.getString("itemId"), packet.getString("scanUrl"),
        packet.getString("format"), encoded == null ? null : Base64.getDecoder().decode(encoded));
  }

  /** Read the labels of a batch packet, in order. */
  public final static List<JsonObject> readBatch(JsonObject packet) {
    JsonArray labels = packet.getJsonArray("labels");
    if (labels == null)
      return List.of();
    return labels.stream().map(JsonObject.class::cast).toList();
  }

  public final static Optional<String> actorOf(JsonObject packet) {
    return Optional.ofNullable(packet.getString("actor"));
  }

  public final static Optional<String> correlationOf(JsonObject packet) {
    return Optional.ofNullable(packet.getString("correlationId"));
  }

  /** The reply: the packet was taken for printing (NOT that it printed). */
  public final static JsonObject accepted(int count) {
    return new JsonObject().put("accepted", true).put("count", count);
  }

  /** The reply when the packet itself is unusable — nothing was queued. */
  public final static JsonObject refused(String reason) {
    return new JsonObject().put("accepted", false).put("reason", reason);
  }
}
