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
package io.artifexlabs.inventory.impl.printer.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * ULID ids were chosen for compact QR codes; this renders them. Pure function,
 * no state. Lives in impl so the label worker renders where the printing
 * happens; the HTTP tier asks over the bus.
 */
public final class QrCodes {

  private QrCodes() {
  }

  public static byte[] png(String text, int sizePixels) {
    try {
      BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePixels, sizePixels);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      MatrixToImageWriter.writeToStream(matrix, "PNG", out);
      return out.toByteArray();
    } catch (WriterException | IOException e) {
      throw new RuntimeException("QR generation failed for: " + text, e);
    }
  }

  /**
   * The bare module grid — margin 0, no scaling — for printers that render
   * module-exact (ongoing item 11: the printed white tape supplies the quiet
   * zone, worth 8 modules of width). {@code ecc} tunes robustness against
   * payload size: L keeps a URL at version 3 (29 modules); a bare 26-char
   * ULID is Crockford base32 and encodes ALPHANUMERIC, fitting version 2
   * (25 modules) even at Q.
   */
  public static BitMatrix bareMatrix(String text, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel ecc) {
    try {
      var hints = new java.util.EnumMap<com.google.zxing.EncodeHintType, Object>(
          com.google.zxing.EncodeHintType.class);
      hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
      hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, ecc);
      return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints);
    } catch (WriterException e) {
      throw new RuntimeException("QR generation failed for: " + text, e);
    }
  }

  /** Render a module grid at an exact integer scale: modules × dotsPerModule square. */
  public static java.awt.image.BufferedImage render(BitMatrix modules, int dotsPerModule) {
    int size = modules.getWidth() * dotsPerModule;
    var img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_BYTE_BINARY);
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++)
        img.setRGB(x, y, modules.get(x / dotsPerModule, y / dotsPerModule) ? 0x000000 : 0xFFFFFF);
    return img;
  }
}
