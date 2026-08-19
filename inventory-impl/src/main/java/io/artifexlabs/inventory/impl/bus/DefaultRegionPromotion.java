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

import io.artifexlabs.inventory.api.bus.RegionPromotion;

import io.vertx.core.json.JsonObject;

/** Concrete {@link RegionPromotion}. */
public record DefaultRegionPromotion(String regionId, String name, String type, String containerId)
    implements RegionPromotion {

  public DefaultRegionPromotion {
    if (regionId == null || regionId.isBlank())
      throw new IllegalArgumentException("a promotion names its region");
  }

  @Override
  public JsonObject toJson() {
    JsonObject j = new JsonObject().put("regionId", this.regionId).put("name", this.name).put("type", this.type);
    if (this.containerId != null)
      j.put("containerId", this.containerId);
    return j;
  }

  public static DefaultRegionPromotion fromJson(JsonObject j) {
    return new DefaultRegionPromotion(j.getString("regionId"), j.getString("name"), j.getString("type"),
        j.getString("containerId"));
  }
}
