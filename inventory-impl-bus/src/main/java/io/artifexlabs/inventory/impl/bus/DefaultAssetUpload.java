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

import io.artifexlabs.inventory.api.LatLong;
import io.artifexlabs.inventory.api.bus.AssetUpload;

import io.vertx.core.json.JsonObject;

/** Concrete {@link AssetUpload}; bytes ride the envelope base64. */
public record DefaultAssetUpload(String itemId, String filename, String contentType, byte[] bytes,
    Optional<LatLong> coordinates, String kind) implements AssetUpload {

  public DefaultAssetUpload {
    if (itemId == null || itemId.isBlank())
      throw new IllegalArgumentException("an upload names its item");
    bytes = bytes == null ? new byte[0] : bytes;
    coordinates = coordinates == null ? Optional.empty() : coordinates;
  }

  /** Default-kind upload. */
  public DefaultAssetUpload(String itemId, String filename, String contentType, byte[] bytes,
      Optional<LatLong> coordinates) {
    this(itemId, filename, contentType, bytes, coordinates, null);
  }

  @Override
  public JsonObject toJson() {
    JsonObject j = new JsonObject().put("itemId", this.itemId).put("filename", this.filename)
        .put("contentType", this.contentType).put("bytes", this.bytes);
    this.coordinates.ifPresent(c -> j.put("latitude", c.latitude()).put("longitude", c.longitude()));
    if (this.kind != null)
      j.put("kind", this.kind);
    return j;
  }

  public static DefaultAssetUpload fromJson(JsonObject j) {
    Optional<LatLong> coords = j.containsKey("latitude") && j.containsKey("longitude")
        ? Optional.of(new LatLong(j.getDouble("latitude"), j.getDouble("longitude")))
        : Optional.empty();
    return new DefaultAssetUpload(j.getString("itemId"), j.getString("filename"), j.getString("contentType"),
        j.getBinary("bytes"), coords, j.getString("kind"));
  }
}
