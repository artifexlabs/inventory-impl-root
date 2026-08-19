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
import io.artifexlabs.inventory.api.LatLong;

public class ExifGpsTest {

  @Test
  public void testExtractsGpsFromSyntheticJpeg() {
    Optional<LatLong> got = ExifGps.extract(GpsJpeg.withGps(35, 30, 0, "N", 97, 15, 0, "W"));
    assertTrue(got.isPresent());
    assertEquals(35.5, got.get().latitude(), 1e-9);
    assertEquals(-97.25, got.get().longitude(), 1e-9);
  }

  @Test
  public void testSouthEastHemispheres() {
    Optional<LatLong> got = ExifGps.extract(GpsJpeg.withGps(12, 0, 30, "S", 45, 45, 0, "E"));
    assertTrue(got.isPresent());
    assertEquals(-(12 + 30 / 3600.0), got.get().latitude(), 1e-9);
    assertEquals(45.75, got.get().longitude(), 1e-9);
  }

  @Test
  public void testNonJpegAndJunkAreEmpty() {
    assertTrue(ExifGps.extract(null).isEmpty());
    assertTrue(ExifGps.extract(new byte[0]).isEmpty());
    assertTrue(ExifGps.extract("not a jpeg at all".getBytes()).isEmpty());
    assertTrue(ExifGps.extract(new byte[] { (byte) 0x89, 'P', 'N', 'G' }).isEmpty());
    // a JPEG with no APP1 at all
    assertTrue(ExifGps.extract(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 }).isEmpty());
    // truncated mid-APP1 must not throw
    byte[] full = GpsJpeg.withGps(1, 2, 3, "N", 4, 5, 6, "E");
    byte[] cut = java.util.Arrays.copyOf(full, full.length / 2);
    assertTrue(ExifGps.extract(cut).isEmpty());
  }
}
