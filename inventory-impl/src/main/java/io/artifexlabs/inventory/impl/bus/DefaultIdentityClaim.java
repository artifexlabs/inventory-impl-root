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

import io.artifexlabs.inventory.api.bus.IdentityClaim;

import io.vertx.core.json.JsonObject;

/** Concrete {@link IdentityClaim}. */
public record DefaultIdentityClaim(String email, String displayName, String provider, String subject)
    implements IdentityClaim {

  public DefaultIdentityClaim {
    if (email == null || email.isBlank())
      throw new IllegalArgumentException("an identity claim carries the email");
  }

  @Override
  public JsonObject toJson() {
    JsonObject j = new JsonObject().put("email", this.email);
    if (this.displayName != null)
      j.put("displayName", this.displayName);
    if (this.provider != null)
      j.put("provider", this.provider);
    if (this.subject != null)
      j.put("subject", this.subject);
    return j;
  }

  public static DefaultIdentityClaim fromJson(JsonObject j) {
    return new DefaultIdentityClaim(j.getString("email"), j.getString("displayName"), j.getString("provider"),
        j.getString("subject"));
  }
}
