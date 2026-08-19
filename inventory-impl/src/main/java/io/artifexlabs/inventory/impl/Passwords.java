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

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * BCrypt password hashing shared by the user stores.
 *
 * @author mykel
 *
 */
public final class Passwords {
  private final static int COST = 10;

  private Passwords() {
  }

  public static String hash(String password) {
    return BCrypt.withDefaults().hashToString(COST, password.toCharArray());
  }

  public static boolean verify(String password, String hash) {
    return hash != null && BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
  }
}
