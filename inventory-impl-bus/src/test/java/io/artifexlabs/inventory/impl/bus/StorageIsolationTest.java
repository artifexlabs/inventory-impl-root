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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The architectural invariant of PLAN.md Phase 21, ask 2, pinned so it cannot rot:
 * <b>only the storage layer touches storage</b>. A public service verticle
 * that regains an {@code InventorySystem} (or any other backend) has quietly
 * reopened the second door this refactor closed, and no integration test
 * would notice — it would still work, just wrongly.
 */
public class StorageIsolationTest {

  /** Everything that IS the storage mechanism, in api terms. */
  private final static Set<String> BACKENDS = Set.of(
      "io.artifexlabs.inventory.api.InventorySystem",
      "io.artifexlabs.inventory.api.AssetStore",
      "io.artifexlabs.inventory.api.UserStore",
      "io.artifexlabs.inventory.api.TokenService",
      "io.artifexlabs.inventory.api.RegionSystem",
      "io.artifexlabs.inventory.api.AuditReader",
      "io.artifexlabs.inventory.api.AuditSink");

  static Stream<Class<?>> publicVerticles() {
    return Stream.of(ItemsVerticle.class, AssetsVerticle.class, RegionsVerticle.class, AuditVerticle.class,
        UsersVerticle.class, TokensVerticle.class, AuthVerticle.class, LabelsVerticle.class,
        CatalogVerticle.class);
  }

  @ParameterizedTest
  @MethodSource("publicVerticles")
  public void noPublicVerticleCanBeHandedABackend(Class<?> verticle) {
    for (Constructor<?> c : verticle.getConstructors())
      for (Class<?> p : c.getParameterTypes())
        assertTrue(!BACKENDS.contains(p.getName()),
            verticle.getSimpleName() + " takes " + p.getSimpleName()
                + " — storage must be reached over the bus, not held (PLAN.md Phase 21, ask 2)");
  }

  @ParameterizedTest
  @MethodSource("publicVerticles")
  public void noPublicVerticleHoldsABackend(Class<?> verticle) {
    for (Field f : verticle.getDeclaredFields())
      assertTrue(!BACKENDS.contains(f.getType().getName()),
          verticle.getSimpleName() + " keeps a " + f.getType().getSimpleName() + " field");
  }

  @Test
  public void theStorageVerticleIsTheOneThatTakesThem() {
    // the counterpart of the rule: storage really does receive the backends,
    // so the isolation above is not "nobody has them" by accident
    List<Constructor<?>> constructors = List.of(StorageVerticle.class.getConstructors());
    assertEquals(1, constructors.size(), "one way to build the storage layer");
    assertEquals(BusWorkers.BackendServices.class, constructors.get(0).getParameterTypes()[0],
        "storage is constructed FROM the backend bundle");
  }

  @Test
  public void internalStorageActionsAreNamespacedAwayFromThePublicVocabulary() {
    // an internal op must never be nameable by an external caller, whose
    // envelopes are validated against BusActions
    for (String internal : List.of(StorageVerticle.AUDIT_RECORD, StorageVerticle.ASSETS_CREATE_FROM_UPC)) {
      assertTrue(internal.startsWith("storage."), internal + " must be namespaced under storage.");
      assertTrue(!io.artifexlabs.inventory.api.bus.BusActions.known(internal),
          internal + " must NOT be part of the public action vocabulary");
    }
  }
}
