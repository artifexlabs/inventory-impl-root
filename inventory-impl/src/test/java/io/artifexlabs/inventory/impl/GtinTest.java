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

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** GTIN canonicalization for identity kind {@code upc}. */
public class GtinTest {

  @Test
  public void everyLengthCanonicalizesToGtin13() {
    // UPC-A (Coca-Cola 12 oz can) pads to the same GTIN-13
    assertEquals(Optional.of("0049000006346"), Gtin.normalize("049000006346"));
    assertEquals(Optional.of("0049000006346"), Gtin.normalize("0049000006346"));
    // the plan's worked example
    assertEquals(Optional.of("0036000291452"), Gtin.normalize("036000291452"));
    assertEquals(Gtin.normalize("036000291452"), Gtin.normalize("0036000291452"),
        "UPC-A and its EAN-13 form are the SAME identity");
    // EAN-13 stays itself
    assertEquals(Optional.of("4006381333931"), Gtin.normalize("4006381333931"));
    // EAN-8 pads with five zeros; the right-aligned check digit survives
    assertEquals(Optional.of("0000096385074"), Gtin.normalize("96385074"));
    // whitespace tolerated
    assertEquals(Optional.of("0049000006346"), Gtin.normalize(" 049000006346 "));
  }

  @Test
  public void garbageIsRefused() {
    assertTrue(Gtin.normalize(null).isEmpty());
    assertTrue(Gtin.normalize("").isEmpty());
    assertTrue(Gtin.normalize("not-digits").isEmpty());
    assertTrue(Gtin.normalize("01ARZ3NDEKTSV4RRFFQ69G5FAV").isEmpty(), "a ULID is not a GTIN");
    assertTrue(Gtin.normalize("1234567").isEmpty(), "wrong length");
    assertTrue(Gtin.normalize("12345678901234").isEmpty(), "wrong length");
    assertTrue(Gtin.normalize("049000006347").isEmpty(), "bad check digit");
    assertTrue(Gtin.normalize("4006381333932").isEmpty(), "bad check digit");
  }
}
