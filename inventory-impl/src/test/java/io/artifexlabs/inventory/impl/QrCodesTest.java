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

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/** Pins the payload-tier arithmetic from ongoing item 11. */
public class QrCodesTest {

  private final static String ULID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

  @Test
  public void payloadTiersLandOnThePlannedVersions() {
    // a ~50-byte deep link forces version 3 = 29 modules (byte mode, ECC L)
    assertEquals(29, QrCodes.bareMatrix("http://localhost:8081/i/" + ULID, ErrorCorrectionLevel.L).getWidth());
    // a bare ULID is Crockford base32 -> ALPHANUMERIC mode, fitting
    // version 2 = 25 modules even at ECC Q
    assertEquals(25, QrCodes.bareMatrix(ULID, ErrorCorrectionLevel.Q).getWidth());
  }

  @Test
  public void bareMatrixHasNoMargin() {
    BitMatrix m = QrCodes.bareMatrix(ULID, ErrorCorrectionLevel.Q);
    // the finder pattern's outer ring starts AT the edge when margin is 0
    assertEquals(true, m.get(0, 0), "top-left finder corner at the very edge");
    assertEquals(true, m.get(m.getWidth() - 1, 0), "top-right finder corner at the very edge");
  }

  @Test
  public void renderIsModuleExact() {
    BitMatrix m = QrCodes.bareMatrix(ULID, ErrorCorrectionLevel.Q);
    BufferedImage img = QrCodes.render(m, 2);
    assertEquals(m.getWidth() * 2, img.getWidth());
    assertEquals(m.getHeight() * 2, img.getHeight());
    // every module is a uniform dpm×dpm block — no mixed widths anywhere
    for (int my = 0; my < m.getHeight(); my++)
      for (int mx = 0; mx < m.getWidth(); mx++) {
        int expected = m.get(mx, my) ? 0 : 0xFF;
        for (int dy = 0; dy < 2; dy++)
          for (int dx = 0; dx < 2; dx++)
            assertEquals(expected, img.getRGB(mx * 2 + dx, my * 2 + dy) & 0xFF,
                "module (" + mx + "," + my + ") pixel (" + dx + "," + dy + ")");
      }
  }
}
