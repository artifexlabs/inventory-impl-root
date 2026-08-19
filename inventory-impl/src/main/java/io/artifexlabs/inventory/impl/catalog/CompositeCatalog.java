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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.CatalogEntry;
import io.artifexlabs.inventory.api.UpcCatalog;

/**
 * Ordered catalog sources, first hit wins. SEQUENTIAL by design: the
 * fallback (UPCitemdb trial) is rate-limited, so it is only asked when the
 * open-data sources miss.
 */
public class CompositeCatalog implements UpcCatalog {

  private final List<UpcCatalog> sources;

  public CompositeCatalog(List<UpcCatalog> sources) {
    this.sources = List.copyOf(sources);
  }

  @Override
  public CompletionStage<Optional<CatalogEntry>> lookup(String gtin13) {
    return trySource(gtin13, 0);
  }

  private CompletionStage<Optional<CatalogEntry>> trySource(String gtin13, int index) {
    if (index >= this.sources.size())
      return CompletableFuture.completedStage(Optional.empty());
    return this.sources.get(index).lookup(gtin13)
        .thenCompose(found -> found.isPresent() ? CompletableFuture.completedStage(found)
            : trySource(gtin13, index + 1));
  }
}
