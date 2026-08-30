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

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * The QR image the bus SERVES — {@code labels.qr}, and the PNG that rides in a print packet — as opposed to the
 * module-exact matrix a printer RENDERS ({@code QrCodes} in printer-common). The two were one class until 2026-08-30;
 * splitting them is what lets the printer layer stand alone: this is the only thing the bus ever needed from it, and a
 * served image keeps its quiet-zone margin where a printed one does not, so they were never the same function.
 */
public final class QrImages {

  private QrImages() {
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
}
