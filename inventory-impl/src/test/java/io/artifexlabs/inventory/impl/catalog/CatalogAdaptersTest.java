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
package io.artifexlabs.inventory.impl.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.artifexlabs.inventory.api.CatalogEntry;
import io.artifexlabs.inventory.api.UpcCatalog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Adapters against a LOCAL stub server serving canned catalog JSON — the network is never touched. Pins the field
 * mapping, the flavor fallback order, and the degrade-to-miss failure modes.
 */
public class CatalogAdaptersTest {

  private final static String GTIN = "0049000006346";
  private static HttpServer stub;
  private static String base;

  @BeforeAll
  public static void start() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // an Open-Facts-shaped flavor that KNOWS the code
    stub.createContext("/hit/api/v2/product/", ex -> respond(ex, 200, """
        {"status":1,"product":{"product_name":"Cola Can","brands":"CocaCola",
         "generic_name":"Carbonated soft drink","categories":"en:beverages, en:carbonated-drinks",
         "product_quantity":355,"product_quantity_unit":"g",
         "image_url":"%s/image.jpg"}}""".formatted("http://127.0.0.1:" + portOf())));
    // a flavor that answers 200 with status 0 (the OFF "not found" shape)
    stub.createContext("/miss/api/v2/product/", ex -> respond(ex, 200, "{\"status\":0}"));
    // a flavor that 404s outright
    stub.createContext("/gone/api/v2/product/", ex -> respond(ex, 404, "{}"));
    // UPCitemdb shapes
    stub.createContext("/updb/lookup", ex -> respond(ex, 200, """
        {"code":"OK","items":[{"title":"20V Drill","brand":"DeWalt",
         "description":"Cordless drill driver","category":"Home Improvement > Tools > Drills",
         "weight":"3.6 pounds","images":["http://127.0.0.1:%d/image.jpg"]}]}""".formatted(portOf())));
    stub.createContext("/updb429/lookup", ex -> respond(ex, 429, "{\"code\":\"TOO_FAST\"}"));
    stub.createContext("/image.jpg", ex -> {
      byte[] bytes = new byte[] {
          (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3
      };
      ex.getResponseHeaders().add("Content-Type", "image/jpeg");
      ex.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(bytes);
      }
    });
    stub.start();
    base = "http://127.0.0.1:" + portOf();
  }

  private static int portOf() {
    return stub.getAddress().getPort();
  }

  private static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  @AfterAll
  public static void stop() {
    stub.stop(0);
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get();
  }

  @Test
  public void openFactsMapsTheFields() throws Exception {
    CatalogEntry entry = await(new OpenFactsCatalog(List.of(base + "/hit")).lookup(GTIN)).get();
    assertEquals("Cola Can", entry.name());
    assertEquals("CocaCola", entry.brand());
    assertEquals("Carbonated soft drink", entry.description());
    assertEquals("carbonated-drinks", entry.category(), "leaf category, language prefix stripped");
    assertEquals(355.0, entry.weightGrams(), 1e-9);
    assertEquals(base + "/hit/product/" + GTIN, entry.sourceUrl(), "the stable outside link");
    assertTrue(entry.imageUrl().endsWith("/image.jpg"));
  }

  @Test
  public void flavorFallbackFindsTheLaterFlavor() throws Exception {
    var catalog = new OpenFactsCatalog(List.of(base + "/gone", base + "/miss", base + "/hit"));
    assertEquals("Cola Can", await(catalog.lookup(GTIN)).get().name());
    var allMiss = new OpenFactsCatalog(List.of(base + "/gone", base + "/miss"));
    assertTrue(await(allMiss.lookup(GTIN)).isEmpty());
  }

  @Test
  public void upcItemDbMapsTheFields() throws Exception {
    CatalogEntry entry = await(new UpcItemDbCatalog(base + "/updb").lookup(GTIN)).get();
    assertEquals("20V Drill", entry.name());
    assertEquals("DeWalt", entry.brand());
    assertEquals("Drills", entry.category(), "leaf of the category path");
    assertEquals(3.6 * 453.59237, entry.weightGrams(), 1e-6, "pounds parsed to grams");
    assertEquals("https://www.upcitemdb.com/upc/" + GTIN, entry.sourceUrl());
  }

  @Test
  public void rateLimitDegradesToAMiss() throws Exception {
    assertTrue(await(new UpcItemDbCatalog(base + "/updb429").lookup(GTIN)).isEmpty(),
        "429 must read as not-found, never as an error");
  }

  @Test
  public void unreachableSourceDegradesToAMiss() throws Exception {
    assertTrue(await(new UpcItemDbCatalog("http://127.0.0.1:1").lookup(GTIN)).isEmpty());
    assertTrue(await(new OpenFactsCatalog(List.of("http://127.0.0.1:1")).lookup(GTIN)).isEmpty());
  }

  @Test
  public void compositeAsksInOrderAndStopsAtTheFirstHit() throws Exception {
    var counted = new java.util.concurrent.atomic.AtomicInteger();
    UpcCatalog counting = gtin -> {
      counted.incrementAndGet();
      return CompletableFuture.completedStage(Optional.empty());
    };
    var composite = new CompositeCatalog(List.of(counting, new OpenFactsCatalog(List.of(base + "/hit")), counting));
    assertEquals("Cola Can", await(composite.lookup(GTIN)).get().name());
    assertEquals(1, counted.get(), "sources AFTER the hit are never asked (rate limits are precious)");
  }
}
