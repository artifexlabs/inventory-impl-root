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

import java.security.SecureRandom;

/**
 * ULID generation: 26 Crockford base32 characters, 48-bit millisecond timestamp
 * prefix + 80 bits of randomness. Lexically sortable by creation time and small
 * enough for a compact QR code.
 *
 * @author mykel
 *
 */
public final class Ulid {
  private final static char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private final static SecureRandom RANDOM = new SecureRandom();
  public final static int LENGTH = 26;

  private Ulid() {
  }

  public static String next() {
    return next(System.currentTimeMillis());
  }

  static String next(long timeMillis) {
    char[] c = new char[LENGTH];
    long t = timeMillis;
    for (int i = 9; i >= 0; --i) {
      c[i] = ALPHABET[(int) (t & 0x1F)];
      t >>>= 5;
    }
    for (int i = 10; i < LENGTH; ++i)
      c[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    return new String(c);
  }
}
