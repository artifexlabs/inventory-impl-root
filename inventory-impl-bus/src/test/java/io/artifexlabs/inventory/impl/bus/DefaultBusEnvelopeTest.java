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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import io.artifexlabs.inventory.api.bus.BusActions;
import io.artifexlabs.inventory.api.bus.Roles;

import io.vertx.core.json.JsonObject;

/**
 * The envelope's immutability contract: once constructed, nothing an outside
 * holder does — to the object it built from, to what the accessors return, or
 * to the wire form — changes what the envelope says. Combined with the
 * fabric's trust model (cluster membership is the only entry point), an
 * admitted envelope's identity and payload are fixed for its lifetime.
 */
public class DefaultBusEnvelopeTest {

  private static DefaultBusEnvelope envelope(JsonObject data) {
    return new DefaultBusEnvelope(DefaultBusEnvelope.VERSION, "fabric", "user-1", "user@example.com",
        Set.of(Roles.READ, Roles.WRITE), BusActions.ITEMS_CREATE, Optional.of("target-1"), data);
  }

  @Test
  public void identityFieldsAreFinal() {
    // the record is final and every field is final: the identity of an
    // admitted envelope cannot be reassigned, only re-sent as a new envelope
    assertTrue(Modifier.isFinal(DefaultBusEnvelope.class.getModifiers()), "envelope class must be final");
    for (Field f : DefaultBusEnvelope.class.getDeclaredFields())
      if (!Modifier.isStatic(f.getModifiers()))
        assertTrue(Modifier.isFinal(f.getModifiers()), "field must be final: " + f.getName());
  }

  @Test
  public void constructionCopiesThePayload() {
    JsonObject building = new JsonObject().put("name", "crate");
    DefaultBusEnvelope env = envelope(building);
    // the builder keeps mutating its own object after handing it over
    building.put("name", "TAMPERED").put("extra", true);
    assertEquals("crate", env.data().getString("name"));
    assertEquals(1, env.data().size());
  }

  @Test
  public void dataAccessorReturnsACopyEveryTime() {
    DefaultBusEnvelope env = envelope(new JsonObject().put("name", "crate"));
    JsonObject seen = env.data();
    seen.put("name", "TAMPERED").put("injected", "value");
    // the handler's mutation is invisible to every later reader
    assertEquals("crate", env.data().getString("name"));
    assertEquals(1, env.data().size());
  }

  @Test
  public void wireFormDoesNotShareThePayload() {
    DefaultBusEnvelope env = envelope(new JsonObject().put("name", "crate"));
    JsonObject wire = env.toJson();
    wire.getJsonObject("data").put("name", "TAMPERED");
    assertEquals("crate", env.data().getString("name"));
    // and a fresh wire form is untouched by the earlier tampering
    assertEquals("crate", env.toJson().getJsonObject("data").getString("name"));
  }

  @Test
  public void nestedPayloadStructuresAreDeepCopied() {
    JsonObject nested = new JsonObject().put("box", new JsonObject().put("x", 0.5));
    DefaultBusEnvelope env = envelope(nested);
    nested.getJsonObject("box").put("x", 999.0);
    env.data().getJsonObject("box").put("x", 777.0);
    assertEquals(0.5, env.data().getJsonObject("box").getDouble("x"));
  }

  @Test
  public void rolesAreImmutable() {
    DefaultBusEnvelope env = envelope(new JsonObject());
    assertThrows(UnsupportedOperationException.class, () -> env.roles().add(Roles.ADMIN));
    assertEquals(Set.of(Roles.READ, Roles.WRITE), env.roles());
  }

  @Test
  public void wireRoundTripPreservesIdentityAndPayload() {
    DefaultBusEnvelope sent = envelope(new JsonObject().put("name", "crate"));
    DefaultBusEnvelope received = DefaultBusEnvelope.fromJson(sent.toJson());
    assertEquals(sent, received);
    assertEquals("user-1", received.userId());
    assertEquals("user@example.com", received.principal());
  }
}
