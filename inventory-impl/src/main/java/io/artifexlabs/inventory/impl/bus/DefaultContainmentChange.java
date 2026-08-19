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

import io.artifexlabs.inventory.api.bus.ContainmentChange;

import io.vertx.core.json.JsonObject;

/** Concrete {@link ContainmentChange}. */
public record DefaultContainmentChange(String containerId, String itemId) implements ContainmentChange {

  public DefaultContainmentChange {
    if (containerId == null || itemId == null)
      throw new IllegalArgumentException("a containment change names both container and item");
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject().put("containerId", this.containerId).put("itemId", this.itemId);
  }

  public static DefaultContainmentChange fromJson(JsonObject j) {
    return new DefaultContainmentChange(j.getString("containerId"), j.getString("itemId"));
  }
}
