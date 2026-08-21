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

import java.util.Optional;

import io.artifexlabs.inventory.api.InventoryConstants;
import io.artifexlabs.inventory.api.LatLong;
import io.artifexlabs.inventory.api.bus.AssetContent;
import io.artifexlabs.inventory.api.bus.BusActions;

import io.vertx.core.json.JsonObject;

/**
 * Payload of {@link BusActions#ASSETS_CREATE_ITEM}: a picture that IS a
 * thing. Carries the item-to-be (name/displayName/type, optional containing
 * item) alongside the {@link AssetContent} to attach; bytes ride the envelope
 * base64.
 */
public record DefaultPhotoItemRequest(String name, String displayName, String type, String containerId,
    String filename, String contentType, byte[] bytes, Optional<LatLong> coordinates, String kind)
    implements AssetContent {

  public DefaultPhotoItemRequest {
    if (name == null || name.isBlank())
      throw new IllegalArgumentException("the created item needs a name");
    if (type == null || type.isBlank())
      type = InventoryConstants.DEFAULT_TYPE;
    bytes = bytes == null ? new byte[0] : bytes;
    coordinates = coordinates == null ? Optional.empty() : coordinates;
  }

  @Override
  public JsonObject toJson() {
    JsonObject j = new JsonObject().put("name", this.name).put("type", this.type)
        .put("filename", this.filename).put("contentType", this.contentType).put("bytes", this.bytes);
    if (this.displayName != null)
      j.put("displayName", this.displayName);
    if (this.containerId != null)
      j.put("containerId", this.containerId);
    this.coordinates.ifPresent(c -> j.put("latitude", c.latitude()).put("longitude", c.longitude()));
    if (this.kind != null)
      j.put("kind", this.kind);
    return j;
  }

  public static DefaultPhotoItemRequest fromJson(JsonObject j) {
    Optional<LatLong> coords = j.containsKey("latitude") && j.containsKey("longitude")
        ? Optional.of(new LatLong(j.getDouble("latitude"), j.getDouble("longitude")))
        : Optional.empty();
    return new DefaultPhotoItemRequest(j.getString("name"), j.getString("displayName"), j.getString("type"),
        j.getString("containerId"), j.getString("filename"), j.getString("contentType"), j.getBinary("bytes"),
        coords, j.getString("kind"));
  }
}
