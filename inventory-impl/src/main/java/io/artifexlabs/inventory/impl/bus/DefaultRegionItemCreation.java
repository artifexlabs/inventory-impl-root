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

import io.artifexlabs.inventory.api.bus.RegionBox;
import io.artifexlabs.inventory.api.bus.RegionItemCreation;

import io.vertx.core.json.JsonObject;

/** Concrete {@link RegionItemCreation}. */
public record DefaultRegionItemCreation(RegionBox box, String name, String type, String containerId)
    implements RegionItemCreation {

  public DefaultRegionItemCreation {
    if (box == null)
      throw new IllegalArgumentException("region item creation carries its box");
  }

  @Override
  public JsonObject toJson() {
    JsonObject j = new JsonObject().put("box", this.box.toJson()).put("name", this.name).put("type", this.type);
    if (this.containerId != null)
      j.put("containerId", this.containerId);
    return j;
  }

  public static DefaultRegionItemCreation fromJson(JsonObject j) {
    return new DefaultRegionItemCreation(DefaultRegionBox.fromJson(j.getJsonObject("box")), j.getString("name"),
        j.getString("type"), j.getString("containerId"));
  }
}
