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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads a catalog entry's product image ONCE, at item creation, so the asset survives the external site changing
 * (never hot-linked). Only URLs that came out of our own catalog adapters are ever fetched — client-supplied URLs are
 * never downloaded (SSRF). Failures degrade to "no image": an item without a picture beats no item.
 */
public final class CatalogImages {
  private final static Logger log = LoggerFactory.getLogger(CatalogImages.class);

  /** Catalog product shots are small; anything bigger is not one. */
  final static int MAX_BYTES = 5 * 1024 * 1024;

  private final static HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NORMAL).build();

  private CatalogImages() {
  }

  public record Image(String contentType, byte[] bytes) {
  }

  public static CompletionStage<Optional<Image>> fetch(String url) {
    HttpRequest request;
    try {
      request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
    } catch (RuntimeException e) {
      return java.util.concurrent.CompletableFuture.completedStage(Optional.empty());
    }
    return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).<Optional<Image>>thenApply(response -> {
      if (response.statusCode() != 200 || response.body().length == 0 || response.body().length > MAX_BYTES)
        return Optional.empty();
      String contentType = response.headers().firstValue("Content-Type").orElse("image/jpeg");
      return Optional.of(new Image(contentType, response.body()));
    }).exceptionally(e -> {
      log.debug("Catalog image fetch failed for {}: {}", url, e.toString());
      return Optional.empty();
    });
  }
}
