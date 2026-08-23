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
package io.artifexlabs.inventory.impl;

import io.artifexlabs.inventory.impl.tck.DataSystemTck;

/**
 * The in-memory backend against the parity kit. A fresh store per test comes free: new objects.
 */
public class InMemoryDataSystemTest extends DataSystemTck {

  @Override
  protected Backends backends() {
    InMemoryAuditSink audit = new InMemoryAuditSink();
    InMemoryInventorySystem items = new InMemoryInventorySystem(audit, "tester@example.com");
    return new Backends(items, new InMemoryDataSystem(items, audit, "tester@example.com"), audit);
  }
}
