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

import io.artifexlabs.inventory.api.LatLong;

/**
 * Minimal GPS-tag EXIF parser: JPEG APP1 → TIFF header → IFD0 → GPS IFD → lat/long rationals. Deliberately
 * dependency-free and reflection-free (native image stays clean — the alternative, metadata-extractor, was passed over
 * for exactly that reason, per PLAN Phase 8). JPEG only; best-effort by design — any structural surprise returns empty,
 * never throws. Mobile clients pass coordinates explicitly and don't depend on this surviving.
 *
 * @author mykel
 *
 */
public final class ExifGps {

  private static final int GPS_IFD_POINTER = 0x8825;
  private static final int GPS_LATITUDE_REF = 0x0001;
  private static final int GPS_LATITUDE = 0x0002;
  private static final int GPS_LONGITUDE_REF = 0x0003;
  private static final int GPS_LONGITUDE = 0x0004;

  private ExifGps() {
  }

  /** Extract capture coordinates from JPEG bytes; empty on anything else. */
  public static Optional<LatLong> extract(byte[] data) {
    try {
      return doExtract(data);
    } catch (RuntimeException anyStructuralSurprise) {
      return Optional.empty();
    }
  }

  private static Optional<LatLong> doExtract(byte[] d) {
    if (d == null || d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8)
      return Optional.empty(); // not a JPEG (SOI)
    int p = 2;
    while (p + 4 <= d.length && (d[p] & 0xFF) == 0xFF) {
      int marker = d[p + 1] & 0xFF;
      if (marker == 0xDA || marker == 0xD9)
        break; // image data / end: no EXIF ahead
      int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
      if (len < 2 || p + 2 + len > d.length)
        return Optional.empty();
      if (marker == 0xE1 && len >= 8 && d[p + 4] == 'E' && d[p + 5] == 'x' && d[p + 6] == 'i' && d[p + 7] == 'f'
          && d[p + 8] == 0 && d[p + 9] == 0)
        return fromTiff(d, p + 10, len - 8);
      p += 2 + len;
    }
    return Optional.empty();
  }

  private static Optional<LatLong> fromTiff(byte[] d, int tiff, int tiffLen) {
    if (tiffLen < 8)
      return Optional.empty();
    boolean le;
    if (d[tiff] == 'I' && d[tiff + 1] == 'I')
      le = true;
    else if (d[tiff] == 'M' && d[tiff + 1] == 'M')
      le = false;
    else
      return Optional.empty();
    if (u16(d, tiff + 2, le) != 42)
      return Optional.empty();
    long ifd0 = u32(d, tiff + 4, le);
    long gpsIfd = findTagValue(d, tiff, tiffLen, (int) ifd0, le, GPS_IFD_POINTER);
    if (gpsIfd < 0)
      return Optional.empty();

    Double lat = null, lng = null;
    String latRef = null, lngRef = null;
    int entries = u16(d, tiff + (int) gpsIfd, le);
    for (int i = 0; i < entries; i++) {
      int e = tiff + (int) gpsIfd + 2 + i * 12;
      if (e + 12 > tiff + tiffLen)
        return Optional.empty();
      int tag = u16(d, e, le);
      int type = u16(d, e + 2, le);
      long count = u32(d, e + 4, le);
      switch (tag) {
      case GPS_LATITUDE_REF -> latRef = asciiValue(d, tiff, e, le, count);
      case GPS_LONGITUDE_REF -> lngRef = asciiValue(d, tiff, e, le, count);
      case GPS_LATITUDE -> lat = dmsValue(d, tiff, e, le, type, count);
      case GPS_LONGITUDE -> lng = dmsValue(d, tiff, e, le, type, count);
      default -> {
        /* not a location tag */ }
      }
    }
    if (lat == null || lng == null || latRef == null || lngRef == null)
      return Optional.empty();
    double la = "S".equals(latRef) ? -lat : lat;
    double lo = "W".equals(lngRef) ? -lng : lng;
    if (la < -90 || la > 90 || lo < -180 || lo > 180)
      return Optional.empty();
    return Optional.of(new LatLong(la, lo));
  }

  /** Scan one IFD for a LONG tag's value; -1 when absent. */
  private static long findTagValue(byte[] d, int tiff, int tiffLen, int ifd, boolean le, int wanted) {
    if (ifd < 0 || tiff + ifd + 2 > tiff + tiffLen)
      return -1;
    int entries = u16(d, tiff + ifd, le);
    for (int i = 0; i < entries; i++) {
      int e = tiff + ifd + 2 + i * 12;
      if (e + 12 > tiff + tiffLen)
        return -1;
      if (u16(d, e, le) == wanted)
        return u32(d, e + 8, le);
    }
    return -1;
  }

  /** ASCII tag (count<=4 fits inline; longer values live at an offset). */
  private static String asciiValue(byte[] d, int tiff, int entry, boolean le, long count) {
    int at = count <= 4 ? entry + 8 : tiff + (int) u32(d, entry + 8, le);
    int n = (int) count;
    while (n > 0 && d[at + n - 1] == 0)
      n--;
    return new String(d, at, n, java.nio.charset.StandardCharsets.US_ASCII);
  }

  /** Three RATIONALs (deg, min, sec) → decimal degrees. */
  private static Double dmsValue(byte[] d, int tiff, int entry, boolean le, int type, long count) {
    if (type != 5 || count != 3)
      return null;
    int at = tiff + (int) u32(d, entry + 8, le);
    double deg = rational(d, at, le);
    double min = rational(d, at + 8, le);
    double sec = rational(d, at + 16, le);
    return deg + min / 60.0 + sec / 3600.0;
  }

  private static double rational(byte[] d, int at, boolean le) {
    long num = u32(d, at, le);
    long den = u32(d, at + 4, le);
    return den == 0 ? 0 : (double) num / den;
  }

  private static int u16(byte[] d, int at, boolean le) {
    int a = d[at] & 0xFF, b = d[at + 1] & 0xFF;
    return le ? (b << 8) | a : (a << 8) | b;
  }

  private static long u32(byte[] d, int at, boolean le) {
    long a = d[at] & 0xFF, b = d[at + 1] & 0xFF, c = d[at + 2] & 0xFF, e = d[at + 3] & 0xFF;
    return le ? (e << 24) | (c << 16) | (b << 8) | a : (a << 24) | (b << 16) | (c << 8) | e;
  }
}
