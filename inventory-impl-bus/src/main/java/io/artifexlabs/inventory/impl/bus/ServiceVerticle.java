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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.bus.BusEnvelope;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

/**
 * Base of every bus worker: consumes one service address, runs every message through the {@link BusGuard} (fabric token
 * + role), dispatches to the handler registered for the envelope's action, and replies with the result — or fails the
 * message with an HTTP-aligned code. One instance per service; handlers complete asynchronously through the domain
 * services.
 */
public abstract class ServiceVerticle extends AbstractVerticle {

  /** Handles one admitted envelope; the resolved value becomes the reply. */
  @FunctionalInterface
  protected interface ActionHandler {
    CompletionStage<Object> handle(BusEnvelope envelope);
  }

  private final String address;
  private final BusGuard guard;
  private final Map<String, ActionHandler> handlers = new HashMap<>();

  protected ServiceVerticle(String address, BusGuard guard) {
    this.address = address;
    this.guard = guard;
  }

  /** Register the handler for an action; the action must route to this address. */
  protected final void on(String action, ActionHandler handler) {
    if (!BusActions.addressOf(action).equals(this.address))
      throw new IllegalArgumentException(action + " does not belong to " + this.address);
    this.handlers.put(action, handler);
  }

  @Override
  public void start(Promise<Void> started) {
    this.vertx.eventBus().<JsonObject>consumer(this.address, this::dispatch).completionHandler(started);
  }

  private void dispatch(Message<JsonObject> message) {
    final BusEnvelope envelope;
    try {
      envelope = this.guard.admit(message.body());
    } catch (BusServiceException refused) {
      message.fail(refused.code(), refused.getMessage());
      return;
    }
    ActionHandler handler = this.handlers.get(envelope.action());
    if (handler == null) {
      message.fail(400, this.address + " does not handle " + envelope.action());
      return;
    }
    try {
      handler.handle(envelope).whenComplete((result, error) -> {
        if (error == null)
          message.reply(result);
        else
          fail(message, error);
      });
    } catch (RuntimeException e) {
      fail(message, e);
    }
  }

  private static void fail(Message<JsonObject> message, Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    if (cause instanceof BusServiceException bse)
      message.fail(bse.code(), bse.getMessage());
    else
      message.fail(500, String.valueOf(cause.getMessage()));
  }

  /** The target id the action requires; 400 when the envelope lacks one. */
  protected final static String requireTarget(BusEnvelope envelope) {
    return Envelopes.requireTarget(envelope);
  }

  /**
   * Hand this envelope to the storage layer unchanged (PLAN.md Phase 21, ask 2). Admission already happened here, so
   * the storage address is internal and unguarded; forwarding the envelope verbatim keeps the acting user and the
   * operation intact all the way to the backing store.
   */
  protected final CompletionStage<Object> storage(BusEnvelope envelope) {
    return storage(envelope.toJson());
  }

  /** Ask storage for a DIFFERENT operation on behalf of the same caller. */
  protected final CompletionStage<Object> storage(BusEnvelope envelope, String action, String targetId,
      JsonObject data) {
    return storage(new DefaultBusEnvelope(envelope.version(), envelope.token(), envelope.userId(), envelope.principal(),
        envelope.roles(), action, java.util.Optional.ofNullable(targetId), data == null ? new JsonObject() : data)
        .toJson());
  }

  private CompletionStage<Object> storage(JsonObject envelope) {
    java.util.concurrent.CompletableFuture<Object> done = new java.util.concurrent.CompletableFuture<>();
    this.vertx.eventBus().<Object>request(StorageVerticle.ADDRESS, envelope, reply -> {
      if (reply.succeeded()) {
        done.complete(reply.result().body());
        return;
      }
      Throwable cause = reply.cause();
      if (cause instanceof io.vertx.core.eventbus.ReplyException re
          && re.failureType() == io.vertx.core.eventbus.ReplyFailure.RECIPIENT_FAILURE)
        done.completeExceptionally(new BusServiceException(re.failureCode(), re.getMessage()));
      else
        done.completeExceptionally(BusServiceException.unavailable("storage is not answering"));
    });
    return done;
  }

  /** Register actions this verticle only guards and passes through to storage. */
  protected final void forward(String... actions) {
    for (String action : actions)
      on(action, this::storage);
  }
}
