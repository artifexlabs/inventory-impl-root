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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The ^GFA wire format, pinned exactly on tiny known bitmaps. */
public class ZplEncoderTest {

  private static String encode(BufferedImage image) {
    return new String(new ZplEncoder().encode(image), StandardCharsets.US_ASCII);
  }

  @Test
  public void tinyBitmapEncodesExactly() {
    // 8x2: top-left pixel black in row 0; row 1 all white
    BufferedImage img = new BufferedImage(8, 2, BufferedImage.TYPE_BYTE_BINARY);
    var g = img.createGraphics();
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, 8, 2);
    g.dispose();
    img.setRGB(0, 0, 0x000000);
    assertEquals("^XA^PW8^LL2^LH0,0^FO0,0^GFA,2,2,1,8000^FS^PQ1^XZ", encode(img));
  }

  @Test
  public void widthPadsToWholeBytes() {
    // 10 wide -> 2 bytes/row; rightmost 6 bits of the second byte stay 0
    BufferedImage img = new BufferedImage(10, 1, BufferedImage.TYPE_BYTE_BINARY);
    var g = img.createGraphics();
    g.setColor(java.awt.Color.BLACK);
    g.fillRect(0, 0, 10, 1);
    g.dispose();
    assertEquals("^XA^PW10^LL1^LH0,0^FO0,0^GFA,2,2,2,FFC0^FS^PQ1^XZ", encode(img));
  }

  @Test
  public void fullLabelGeometryIsConsistent() {
    BufferedImage img = new BufferedImage(457, 812, BufferedImage.TYPE_BYTE_BINARY);
    String zpl = encode(img);
    // 457 -> 58 bytes/row; 58*812 = 47096 total
    assertTrue(zpl.startsWith("^XA^PW457^LL812^LH0,0^FO0,0^GFA,47096,47096,58,"));
    assertTrue(zpl.endsWith("^FS^PQ1^XZ"));
    int hexStart = zpl.indexOf("58,") + 3;
    assertEquals(47096 * 2, zpl.indexOf("^FS^PQ1^XZ") - hexStart, "hex payload length");
    // ^LL anchors length tracking (3 blank labels fed without it, 2026-08-14);
    // no persistent printer state ever
    assertTrue(zpl.contains("^LL812"));
    assertFalse(zpl.contains("~SD"));
    assertFalse(zpl.contains("^MT"));
  }
}
