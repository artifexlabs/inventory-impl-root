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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class UlidTest {

  @Test
  public void testLengthAndAlphabet() {
    String u = Ulid.next();
    assertEquals(Ulid.LENGTH, u.length());
    assertTrue(u.matches("[0-9ABCDEFGHJKMNPQRSTVWXYZ]{26}"), u);
  }

  @Test
  public void testLexicalOrderFollowsTime() {
    assertTrue(Ulid.next(1_000L).compareTo(Ulid.next(2_000L)) < 0);
    assertTrue(Ulid.next(2_000L).compareTo(Ulid.next(3_000_000_000_000L)) < 0);
  }

  @Test
  public void testUniqueness() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 10_000; ++i)
      assertTrue(seen.add(Ulid.next()));
  }
}
