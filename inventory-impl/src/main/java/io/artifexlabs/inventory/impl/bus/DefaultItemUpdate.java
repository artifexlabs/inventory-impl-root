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

import io.artifexlabs.inventory.api.Item;
import io.artifexlabs.inventory.api.ItemFactory;
import io.artifexlabs.inventory.api.bus.ItemUpdate;

import io.vertx.core.json.JsonObject;

/** Concrete {@link ItemUpdate}: identifier + full replacement state. */
public record DefaultItemUpdate(String itemId, Item item) implements ItemUpdate {

  public DefaultItemUpdate {
    if (itemId == null || item == null)
      throw new IllegalArgumentException("an update names its item and carries its state");
    if (!itemId.equals(item.getId()))
      throw new IllegalArgumentException("update id " + itemId + " does not match item state id " + item.getId());
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject().put("itemId", this.itemId).put("item", ItemFactory.serialize(this.item));
  }

  public static DefaultItemUpdate fromJson(JsonObject j) {
    return new DefaultItemUpdate(j.getString("itemId"), ItemFactory.deserialize(j.getJsonObject("item")));
  }
}
