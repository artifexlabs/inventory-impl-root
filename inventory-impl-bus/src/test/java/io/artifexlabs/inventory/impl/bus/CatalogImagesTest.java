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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Catalog image fetching is best-effort by design: a product photo that cannot be retrieved must never fail the item it
 * was decorating. Moved here with CatalogImages when the bus layer became its own module (PLAN.md Phase 21).
 */
public class CatalogImagesTest {

  private static HttpServer stub;
  private static String base;

  @BeforeAll
  public static void serve() throws Exception {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext("/image.jpg", exchange -> {
      byte[] body = new byte[] {
          (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0
      };
      exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    stub.start();
    base = "http://127.0.0.1:" + stub.getAddress().getPort();
  }

  @AfterAll
  public static void stop() {
    stub.stop(0);
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get();
  }

  @Test
  public void imageFetchIsCappedAndBestEffort() throws Exception {
    assertTrue(await(CatalogImages.fetch(base + "/image.jpg")).isPresent());
    assertEquals("image/jpeg", await(CatalogImages.fetch(base + "/image.jpg")).get().contentType());
    assertTrue(await(CatalogImages.fetch("http://127.0.0.1:1/nope.jpg")).isEmpty(),
        "an unreachable host is empty, never an exception");
    assertTrue(await(CatalogImages.fetch("not a url")).isEmpty(), "so is a malformed URL");
  }
}
