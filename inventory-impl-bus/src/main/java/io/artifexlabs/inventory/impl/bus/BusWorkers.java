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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.AssetStore;
import io.artifexlabs.inventory.api.AuditReader;
import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.LabelPrinter;
import io.artifexlabs.inventory.api.RegionSystem;
import io.artifexlabs.inventory.api.TokenService;
import io.artifexlabs.inventory.api.UserStore;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;

/**
 * The full set of bus workers, deployed as one unit. inventory-server hosts this in deployment; the HTTP gateway
 * deploys the identical set in-process for its embedded (single-process dev/test) mode, so the envelope contract is
 * exercised either way.
 */
public final class BusWorkers {

  /** Everything the workers act through, bundled for deployment. */
  public record BackendServices(InventorySystem inventory, AssetStore assets, RegionSystem regions,
      AuditReader auditReader, AuditSink auditSink, LabelPrinter printer, UserStore users, TokenService tokens,
      io.artifexlabs.inventory.api.UpcCatalog catalog) {
  }

  private BusWorkers() {
  }

  /**
   * Deploy every worker on the given Vert.x instance. {@code provision} is the OIDC exchange provisioning policy
   * ({@code invited} or {@code auto}). The labels worker deploys on worker threads: QR/AWT composition is CPU work that
   * must not block an event loop.
   */
  public static CompletionStage<Void> deploy(Vertx vertx, BackendServices s, BusGuard guard, String provision) {
    return deploy(vertx, s, guard, provision, new VertxStatusPublisher(vertx));
  }

  /**
   * As {@link #deploy(Vertx, BackendServices, BusGuard, String)}, with an explicit status channel (the printer verticle
   * reports outcomes there, since its replies are acceptance rather than completion).
   */
  public static CompletionStage<Void> deploy(Vertx vertx, BackendServices s, BusGuard guard, String provision,
      io.artifexlabs.inventory.api.events.StatusPublisher status) {
    DeploymentOptions workerThread = new DeploymentOptions().setThreadingModel(ThreadingModel.WORKER);
    Future<Void> all = Future.all(java.util.List.of(
        // the ONE door to storage; every public verticle forwards here
        vertx.deployVerticle(new StorageVerticle(s, provision)), vertx.deployVerticle(new ItemsVerticle(guard)),
        vertx.deployVerticle(new AssetsVerticle(guard)), vertx.deployVerticle(new RegionsVerticle(guard)),
        vertx.deployVerticle(new AuditVerticle(guard)), vertx.deployVerticle(new LabelsVerticle(guard), workerThread),
        // the printer is reached over the bus now (PLAN.md Phase 21): it composes
        // and rasterizes, which is CPU work that must stay off the event loop
        vertx.deployVerticle(new io.artifexlabs.inventory.impl.printer.common.LabelPrinterVerticle(s.printer(), status,
            s.inventory()::getItem), workerThread),
        // catalog lookups block on external HTTP: keep them off the event loop
        vertx.deployVerticle(new CatalogVerticle(guard, s.catalog()), workerThread),
        vertx.deployVerticle(new UsersVerticle(guard)), vertx.deployVerticle(new TokensVerticle(guard)),
        vertx.deployVerticle(new AuthVerticle(guard)),
        // the status topic's baseline consumer: never write-only (PLAN.md Phase 21)
        vertx.deployVerticle(new StatusLogVerticle()))).mapEmpty();
    CompletableFuture<Void> done = new CompletableFuture<>();
    all.onComplete(r -> {
      if (r.succeeded())
        done.complete(null);
      else
        done.completeExceptionally(r.cause());
    });
    return done;
  }
}
