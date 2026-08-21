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

import io.artifexlabs.inventory.api.bus.Credentials;

import io.vertx.core.json.JsonObject;

/** Concrete {@link Credentials}. */
public record DefaultCredentials(String email, String password) implements Credentials {

  public DefaultCredentials {
    if (email == null || password == null)
      throw new IllegalArgumentException("credentials carry email and password");
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject().put("email", this.email).put("password", this.password);
  }

  public static DefaultCredentials fromJson(JsonObject j) {
    return new DefaultCredentials(j.getString("email"), j.getString("password"));
  }
}
