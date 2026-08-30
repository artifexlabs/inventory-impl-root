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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.artifexlabs.inventory.api.DefaultItem;
import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.LabelPrinter;
import io.artifexlabs.inventory.api.bus.PrintPackets;
import io.artifexlabs.inventory.api.events.StatusEvent;
import io.artifexlabs.inventory.api.events.StatusPublisher;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * The attribution thread, end to end through the printer verticle: a packet says who asked and which request asked, and
 * the outcome event — published long after the acceptance reply — still says both.
 *
 * That is the whole point of the correlation id. Because TCP 9100 is unidirectional, the reply can only ever be an
 * acceptance; the fact a user actually cares about (it printed, it refused) arrives later on {@code status.events}, and
 * without attribution it can only be shown to an administrator. With it, the event reaches the person who asked.
 */
public class LabelPrinterVerticleTest {

  private final static String ACTOR = "01ALICE";
  private final static String REQUEST = "01REQUESTID";

  /** Remembers what was published, from whichever thread publishes it. */
  private final static class Captured implements StatusPublisher {
    private final List<StatusEvent> events = new CopyOnWriteArrayList<>();
    private final CompletableFuture<StatusEvent> first = new CompletableFuture<>();

    @Override
    public void publish(StatusEvent event) {
      this.events.add(event);
      this.first.complete(event);
    }

    private StatusEvent awaitFirst() throws Exception {
      return this.first.get(5, TimeUnit.SECONDS);
    }
  }

  /** A printer that does what the test asks: succeed, refuse, or throw. */
  private final static class StubPrinter implements LabelPrinter {
    private final Boolean answer;
    private final RuntimeException failure;

    private StubPrinter(Boolean answer, RuntimeException failure) {
      this.answer = answer;
      this.failure = failure;
    }

    @Override
    public CompletionStage<Boolean> printLabel(Item item, byte[] qrPng) {
      if (this.failure != null)
        return CompletableFuture.failedStage(this.failure);
      return CompletableFuture.completedStage(this.answer);
    }

    @Override
    public CompletionStage<Boolean> feed() {
      if (this.failure != null)
        return CompletableFuture.failedStage(this.failure);
      return CompletableFuture.completedStage(this.answer);
    }
  }

  private Vertx vertx;
  private Captured status;

  @BeforeEach
  public void setUp() {
    this.vertx = Vertx.vertx();
    this.status = new Captured();
  }

  @AfterEach
  public void tearDown() throws Exception {
    this.vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private void deploy(LabelPrinter printer) throws Exception {
    this.vertx.deployVerticle(new LabelPrinterVerticle(printer, this.status)).toCompletionStage().toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
  }

  private static Item item() {
    return DefaultItem.builder().id("01ARZ3NDEKTSV4RRFFQ69G5FAV").name("crate").timestamp(Instant.now()).build();
  }

  private JsonObject send(String address, JsonObject packet) throws Exception {
    return (JsonObject) this.vertx.eventBus().request(address, packet).toCompletionStage().toCompletableFuture()
        .get(5, TimeUnit.SECONDS).body();
  }

  @Test
  public void aFailedPrintReportsWhoAskedAndWhichRequest() throws Exception {
    deploy(new StubPrinter(null, new IllegalStateException("tape jam")));
    JsonObject packet = PrintPackets
        .attribute(PrintPackets.label(item(), "http://host/i/01ARZ3NDEKTSV4RRFFQ69G5FAV", null, null), ACTOR, REQUEST);

    JsonObject ack = send(PrintPackets.PRINT, packet);
    // acceptance comes back immediately: the hardware has not been heard from
    assertTrue(ack.getBoolean("accepted"), "the packet is accepted before the printer answers");

    StatusEvent outcome = this.status.awaitFirst();
    assertEquals("printer.print-failed", outcome.code());
    assertEquals(ACTOR, outcome.actorId().orElse(null), "the failure names who asked");
    assertEquals(REQUEST, outcome.correlation().orElse(null), "and which request asked");
  }

  @Test
  public void aSuccessfulPrintCarriesTheSameThread() throws Exception {
    deploy(new StubPrinter(Boolean.TRUE, null));
    JsonObject packet = PrintPackets
        .attribute(PrintPackets.label(item(), "http://host/i/01ARZ3NDEKTSV4RRFFQ69G5FAV", null, null), ACTOR, REQUEST);

    send(PrintPackets.PRINT, packet);

    StatusEvent outcome = this.status.awaitFirst();
    assertEquals("printer.printed", outcome.code());
    assertEquals(ACTOR, outcome.actorId().orElse(null));
    assertEquals(REQUEST, outcome.correlation().orElse(null));
  }

  @Test
  public void feedingTheTapeIsAttributedToo() throws Exception {
    deploy(new StubPrinter(Boolean.TRUE, null));

    send(PrintPackets.FEED, PrintPackets.attribute(new JsonObject(), ACTOR, REQUEST));

    StatusEvent fed = this.status.awaitFirst();
    assertEquals("printer.fed", fed.code());
    assertEquals(ACTOR, fed.actorId().orElse(null));
    assertEquals(REQUEST, fed.correlation().orElse(null), "feed outcomes correlate like any other print");
  }

  @Test
  public void anUnattributedPacketStillWorksAndStaysUnattributed() throws Exception {
    // a printer reached by something that has no request behind it (a future
    // print station, a maintenance tool) must still print; its outcome simply
    // routes to admins, which is what an absent actor means to the SSE scoping
    deploy(new StubPrinter(Boolean.TRUE, null));

    send(PrintPackets.PRINT, PrintPackets.label(item(), "http://host/i/01ARZ3NDEKTSV4RRFFQ69G5FAV", null, null));

    StatusEvent outcome = this.status.awaitFirst();
    assertEquals("printer.printed", outcome.code());
    assertTrue(outcome.actorId().isEmpty(), "no actor was claimed");
    assertTrue(outcome.correlation().isEmpty(), "and no correlation was invented");
  }
}
