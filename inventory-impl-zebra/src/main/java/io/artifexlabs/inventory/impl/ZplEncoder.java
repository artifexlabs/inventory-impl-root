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

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;

/**
 * Stage 2 of the label pipeline, ZPL dialect (the de-facto reference — GK420t and Zebra-class printers): the whole
 * 1-bit label bitmap becomes one {@code ^GFA} graphic field, so the encoder stays format-agnostic exactly like the
 * Brother raster encoder — it rasterizes whatever bitmap arrives.
 *
 * {@code ^LL} declares the format length (the bitmap height): without it the firmware's length tracking has nothing to
 * anchor to and re-syncs by feeding blank labels after the job (observed on the GK420t 2026-08-14 — three wasted
 * labels). Gap sensing still trims at the physical gap. Deliberately omitted: any persistent printer state ({@code ~SD}
 * darkness, {@code ^MT} media type) — calibration belongs to the printer, not every job. Output is plain ASCII,
 * eyeballable via the Labelary renderer during development.
 */
public class ZplEncoder {

  public byte[] encode(BufferedImage label) {
    int width = label.getWidth();
    int height = label.getHeight();
    int bytesPerRow = (width + 7) / 8;
    int total = bytesPerRow * height;

    StringBuilder zpl = new StringBuilder(total * 2 + 64);
    zpl.append("^XA^PW").append(width).append("^LL").append(height).append("^LH0,0");
    zpl.append("^FO0,0^GFA,").append(total).append(',').append(total).append(',').append(bytesPerRow).append(',');
    for (int y = 0; y < height; y++) {
      for (int bx = 0; bx < bytesPerRow; bx++) {
        int b = 0;
        for (int bit = 0; bit < 8; bit++) {
          int x = bx * 8 + bit;
          // 1 = ink; pixels past the right edge stay white
          if (x < width && (label.getRGB(x, y) & 0xFF) == 0)
            b |= 0x80 >> bit;
        }
        zpl.append(HEX[b >> 4]).append(HEX[b & 0x0F]);
      }
    }
    zpl.append("^FS^PQ1^XZ");
    return zpl.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private final static char[] HEX = "0123456789ABCDEF".toCharArray();
}
