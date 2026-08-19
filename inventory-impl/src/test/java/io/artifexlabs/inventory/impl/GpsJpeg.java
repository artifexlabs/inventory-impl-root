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

import java.io.ByteArrayOutputStream;

/**
 * Builds a minimal-but-valid JPEG whose only content is an EXIF APP1 segment
 * with a GPS IFD (little-endian TIFF). Just enough structure for a GPS
 * extractor to chew on; no pixels.
 */
final class GpsJpeg {

  private GpsJpeg() {
  }

  /** deg/min/sec + hemisphere refs → JPEG bytes carrying exactly that GPS tag set. */
  static byte[] withGps(int latDeg, int latMin, int latSec, String latRef, int lngDeg, int lngMin, int lngSec,
      String lngRef) {
    // TIFF layout (LE): header(8) IFD0(2+12+4=18 @8) GPS-IFD(2+4*12+4=54 @26)
    // lat rationals(24 @80) lng rationals(24 @104) — 128 bytes total
    ByteArrayOutputStream tiff = new ByteArrayOutputStream();
    tiff.write('I');
    tiff.write('I');
    u16(tiff, 42);
    u32(tiff, 8);
    // IFD0: one entry, the GPS IFD pointer
    u16(tiff, 1);
    u16(tiff, 0x8825);
    u16(tiff, 4); // LONG
    u32(tiff, 1);
    u32(tiff, 26);
    u32(tiff, 0); // next IFD
    // GPS IFD: latRef, lat, lngRef, lng
    u16(tiff, 4);
    asciiEntry(tiff, 0x0001, latRef);
    rationalEntry(tiff, 0x0002, 80);
    asciiEntry(tiff, 0x0003, lngRef);
    rationalEntry(tiff, 0x0004, 104);
    u32(tiff, 0); // next IFD
    dms(tiff, latDeg, latMin, latSec);
    dms(tiff, lngDeg, lngMin, lngSec);

    byte[] t = tiff.toByteArray();
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    jpeg.write(0xFF);
    jpeg.write(0xD8); // SOI
    jpeg.write(0xFF);
    jpeg.write(0xE1); // APP1
    int len = 2 + 6 + t.length;
    jpeg.write(len >> 8);
    jpeg.write(len & 0xFF);
    jpeg.writeBytes("Exif".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    jpeg.write(0);
    jpeg.write(0);
    jpeg.writeBytes(t);
    jpeg.write(0xFF);
    jpeg.write(0xD9); // EOI
    return jpeg.toByteArray();
  }

  private static void asciiEntry(ByteArrayOutputStream o, int tag, String value) {
    u16(o, tag);
    u16(o, 2); // ASCII
    u32(o, 2); // one char + NUL, inline
    o.write(value.charAt(0));
    o.write(0);
    o.write(0);
    o.write(0);
  }

  private static void rationalEntry(ByteArrayOutputStream o, int tag, int dataOffset) {
    u16(o, tag);
    u16(o, 5); // RATIONAL
    u32(o, 3);
    u32(o, dataOffset);
  }

  private static void dms(ByteArrayOutputStream o, int deg, int min, int sec) {
    u32(o, deg);
    u32(o, 1);
    u32(o, min);
    u32(o, 1);
    u32(o, sec);
    u32(o, 1);
  }

  private static void u16(ByteArrayOutputStream o, int v) {
    o.write(v & 0xFF);
    o.write((v >> 8) & 0xFF);
  }

  private static void u32(ByteArrayOutputStream o, int v) {
    o.write(v & 0xFF);
    o.write((v >> 8) & 0xFF);
    o.write((v >> 16) & 0xFF);
    o.write((v >> 24) & 0xFF);
  }
}
