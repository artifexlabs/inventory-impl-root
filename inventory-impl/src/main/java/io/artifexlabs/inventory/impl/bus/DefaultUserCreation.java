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

import io.artifexlabs.inventory.api.bus.UserCreation;

import io.vertx.core.json.JsonObject;

/** Concrete {@link UserCreation}. */
public record DefaultUserCreation(String email, String displayName, String password, boolean admin)
    implements UserCreation {

  public DefaultUserCreation {
    if (email == null || email.isBlank() || password == null || password.isBlank())
      throw new IllegalArgumentException("email and password are required");
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject().put("email", this.email).put("displayName", this.displayName)
        .put("password", this.password).put("admin", this.admin);
  }

  public static DefaultUserCreation fromJson(JsonObject j) {
    return new DefaultUserCreation(j.getString("email"), j.getString("displayName"), j.getString("password"),
        Boolean.TRUE.equals(j.getBoolean("admin")));
  }
}
