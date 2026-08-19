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

import java.util.Optional;

/**
 * GTIN normalization for identity kind {@code upc}: UPC-A (12), EAN-13 (13),
 * and EAN-8 (8) canonicalize to <b>GTIN-13</b> by leading-zero padding (the
 * GS1 check digit is right-aligned, so padding never changes it). Clients
 * expand UPC-E to UPC-A BEFORE sending — an 8-digit payload is ambiguous
 * between EAN-8 and UPC-E, and only the scanner knows its symbology; the
 * server treats 8 digits as EAN-8. Pure functions, no state.
 */
public final class Gtin {

  private Gtin() {
  }

  /**
   * Canonical GTIN-13 for a scanned code, empty when the input is not a
   * plausible GTIN (wrong length, non-digits, bad check digit).
   */
  public static Optional<String> normalize(String raw) {
    if (raw == null)
      return Optional.empty();
    String digits = raw.trim();
    if (!digits.chars().allMatch(Character::isDigit))
      return Optional.empty();
    if (digits.length() != 8 && digits.length() != 12 && digits.length() != 13)
      return Optional.empty();
    String padded = "0".repeat(13 - digits.length()) + digits;
    return checkDigitValid(padded) ? Optional.of(padded) : Optional.empty();
  }

  /** GS1 check digit: weights 1/3 alternating from the RIGHT, mod 10. */
  static boolean checkDigitValid(String gtin13) {
    int sum = 0;
    for (int i = 0; i < 12; i++) {
      int digit = gtin13.charAt(i) - '0';
      // position from the right (excluding the check digit) decides the weight
      sum += digit * ((12 - i) % 2 == 1 ? 3 : 1);
    }
    int expected = (10 - sum % 10) % 10;
    return expected == gtin13.charAt(12) - '0';
  }
}
