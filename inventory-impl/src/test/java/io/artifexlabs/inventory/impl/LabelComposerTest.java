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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Platform-neutral invariants everywhere; the exact golden-file comparison is
 * Linux-only (the devcontainer/CI/production fonts — DejaVu — differ from
 * macOS glyphs). Regenerate the golden INSIDE the devcontainer with:
 *   mvn -pl inventory-impl test -Dtest=LabelComposerTest -Dlabel.golden.update=true
 */
public class LabelComposerTest {

  private final LabelComposer composer = new LabelComposer();

  /** A deterministic stand-in QR: 8x8 checkerboard of 4-dot modules. */
  private static BufferedImage fakeQr() {
    return fakeQrExact(8, 4);
  }

  /** A module-exact stand-in: {@code modules}² checkerboard at {@code dpm} dots per module. */
  private static BufferedImage fakeQrExact(int modules, int dpm) {
    int size = modules * dpm;
    BufferedImage qr = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++)
        qr.setRGB(x, y, ((x / dpm + y / dpm) % 2 == 0) ? 0x000000 : 0xFFFFFF);
    return qr;
  }

  @Test
  public void geometryAndQrRegionAreExact() {
    // the QR arrives module-exact (8 modules × 16 dots) and is drawn at its
    // NATURAL size — the composer must never rescale it (ongoing item 11)
    BufferedImage label = this.composer.compose("toolbox", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        fakeQrExact(8, 16), 8, 128);
    assertEquals(128, label.getHeight());
    assertTrue(label.getWidth() > 128, "text must extend the label beyond the QR");
    // QR occupies the left 128x128: sample module centers.
    for (int my = 0; my < 8; my++)
      for (int mx = 0; mx < 8; mx++) {
        boolean expectBlack = (mx + my) % 2 == 0;
        int px = mx * 16 + 8, py = my * 16 + 8;
        assertEquals(expectBlack, (label.getRGB(px, py) & 0xFF) == 0,
            "module (" + mx + "," + my + ") at " + px + "," + py);
      }
    // Text area contains ink.
    int dark = 0;
    for (int y = 0; y < 128; y++)
      for (int x = 136; x < label.getWidth(); x++)
        if ((label.getRGB(x, y) & 0xFF) == 0)
          dark++;
    assertTrue(dark > 50, "expected rendered text pixels, got " + dark);
  }

  @Test
  public void smallerQrIsCenteredVerticallyNotRescaled() {
    // a 58-dot code (29 modules × 2) on the 70-dot 12mm tape: centered, exact
    BufferedImage label = this.composer.compose("x", "y", fakeQrExact(29, 2), 8, 70);
    assertEquals(70, label.getHeight());
    assertEquals(0, ink(label, 0, 0, 58, 6), "tape margin above the code stays white");
    assertTrue(ink(label, 0, 6, 58, 64) > 500, "code centered in the printable band");
  }

  @Test
  public void quietZoneKeepsTextOffTheCode() {
    BufferedImage label = this.composer.compose("x", "y", fakeQrExact(8, 12), 48, 128);
    // 96-dot QR, quiet 48 -> text starts at 144; the gap column stays white
    assertEquals(0, ink(label, 96, 0, 144, 128), "quiet zone between code and text");
  }

  @Test
  public void qrOnlyLayoutForNarrowTapes() {
    BufferedImage label = this.composer.composeQrOnly(fakeQrExact(25, 2), 70);
    assertEquals(70, label.getHeight());
    assertEquals(54, label.getWidth(), "code plus the trailing margin, nothing else");
    assertEquals(0, ink(label, 0, 0, 50, 10), "centered: top band white");
    assertTrue(ink(label, 0, 10, 50, 60) > 400, "the code itself");
  }

  @Test
  public void oversizeQrIsRejectedNotClipped() {
    assertThrows(IllegalArgumentException.class,
        () -> this.composer.compose("x", "y", fakeQrExact(29, 3), 8, 70));
    assertThrows(IllegalArgumentException.class,
        () -> this.composer.composeQrOnly(fakeQrExact(29, 3), 70));
  }

  @Test
  public void goldenFileMatchesOnLinux() throws Exception {
    BufferedImage label = this.composer.compose("toolbox", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        fakeQrExact(8, 16), 8, 128);
    assertOrUpdateGolden(label, "golden-toolbox-24mm.png");
  }

  /**
   * Linux-gated exact-pixel comparison against src/test/resources/labels/{name},
   * or regeneration of that file under -Dlabel.golden.update=true.
   */
  private void assertOrUpdateGolden(BufferedImage label, String name) throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"),
        "golden rendering is pinned to the Linux/DejaVu fonts the containers ship");
    if (Boolean.getBoolean("label.golden.update")) {
      Path golden = Path.of("src/test/resources/labels/" + name);
      Files.createDirectories(golden.getParent());
      ByteArrayOutputStream rendered = new ByteArrayOutputStream();
      ImageIO.write(label, "PNG", rendered);
      Files.write(golden, rendered.toByteArray());
      return;
    }
    try (InputStream in = getClass().getResourceAsStream("/labels/" + name)) {
      Assumptions.assumeTrue(in != null, "golden not yet generated (run with -Dlabel.golden.update=true)");
      BufferedImage expected = ImageIO.read(in);
      assertEquals(expected.getWidth(), label.getWidth(), "golden width");
      assertEquals(expected.getHeight(), label.getHeight(), "golden height");
      for (int y = 0; y < expected.getHeight(); y++)
        for (int x = 0; x < expected.getWidth(); x++)
          assertEquals(expected.getRGB(x, y), label.getRGB(x, y), "pixel " + x + "," + y);
    }
  }

  // --- die-cut formats (Zebra 203 dpi media) -----------------------------

  private static int ink(BufferedImage img, int x0, int y0, int x1, int y1) {
    int dark = 0;
    for (int y = y0; y < y1; y++)
      for (int x = x0; x < x1; x++)
        if ((img.getRGB(x, y) & 0xFF) == 0)
          dark++;
    return dark;
  }

  @Test
  public void standardDieCutLayout() {
    BufferedImage label = this.composer.composeStandard("Standard Verify", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "test-fixture", 2L, "garage shelf 3", false, null, false, "2026-08-14", fakeQr(), 457, 254);
    assertEquals(457, label.getWidth());
    assertEquals(254, label.getHeight());
    assertTrue(ink(label, 4, 27, 204, 227) > 1000, "1-in QR on the left");
    assertTrue(ink(label, 212, 0, 457, 100) > 100, "name + id lines");
    assertTrue(ink(label, 212, 100, 457, 190) > 100, "type/qty/location lines");
    assertTrue(ink(label, 212, 220, 457, 254) > 20, "printed-on footer");
    // the fixed canvas must never clip: the right edge column stays white
    assertEquals(0, ink(label, 452, 0, 457, 254), "no ink bleeding off the right edge");
  }

  @Test
  public void standardQuantityGetsItsOwnLineEvenWithALongType() {
    // regression: type+qty once shared a fitted line, and a long type
    // ellipsized the quantity clean off the label
    BufferedImage without = this.composer.composeStandard("x", "y", "test-fixture", null, null, false, null, false,
        "2026-08-14", fakeQr(), 457, 254);
    BufferedImage with = this.composer.composeStandard("x", "y", "test-fixture", 1L, null, false, null, false,
        "2026-08-14", fakeQr(), 457, 254);
    assertTrue(ink(with, 212, 130, 457, 165) > ink(without, 212, 130, 457, 165) + 50,
        "the quantity line must render ink of its own");
  }

  @Test
  public void largeDieCutLayout() {
    BufferedImage label = this.composer.composeLarge("toolbox", "01ARZ3NDEKTSV4RRFFQ69G5FAV", "tool", 3L,
        "garage", false, null, false,
        "A long description that certainly needs to wrap across more than one rendered line on the label",
        "2026-08-14", fakeQr(), 457, 812);
    assertEquals(457, label.getWidth());
    assertEquals(812, label.getHeight());
    assertTrue(ink(label, 23, 4, 434, 415) > 5000, "big QR up top");
    assertTrue(ink(label, 0, 430, 457, 780) > 300, "name/id/fields block");
    assertTrue(ink(label, 0, 780, 457, 812) > 20, "printed-on footer");
    // nothing prints in the top corners beside the centered QR
    assertEquals(0, ink(label, 0, 0, 20, 20), "top-left stays white");
  }

  @Test
  public void largeDieCutLocationLineAddsInk() {
    BufferedImage without = this.composer.composeLarge("toolbox", "id", "tool", null, null, false, null, false, null,
        "2026-08-14", fakeQr(), 457, 812);
    BufferedImage with = this.composer.composeLarge("toolbox", "id", "tool", null, "garage shelf 3", false, null, false, null,
        "2026-08-14", fakeQr(), 457, 812);
    assertTrue(ink(with, 0, 430, 457, 780) > ink(without, 0, 430, 457, 780) + 100,
        "the location line must render ink the location-less label lacks");
  }

  @Test
  public void largeDieCutTolerantOfMissingOptionals() {
    BufferedImage label = this.composer.composeLarge("x", "y", "_", null, null, false, null, false, null, "2026-08-14",
        fakeQr(), 457, 812);
    assertEquals(812, label.getHeight());
    assertTrue(ink(label, 0, 430, 457, 812) > 50, "still renders name/id/footer");
  }

  @Test
  public void heavyMarkAndExpiryAddInkOnBothLayouts() {
    BufferedImage plainStd = this.composer.composeStandard("x", "y", "t", null, null, false, null, false,
        "2026-08-14", fakeQr(), 457, 254);
    BufferedImage heavyStd = this.composer.composeStandard("x", "y", "t", null, null, true, "2027-01-01", true,
        "2026-08-14", fakeQr(), 457, 254);
    assertTrue(ink(heavyStd, 212, 0, 457, 254) > ink(plainStd, 212, 0, 457, 254) + 100,
        "standard: HEAVY mark + EXPIRES! line must render ink");

    BufferedImage plainLg = this.composer.composeLarge("x", "y", "t", null, null, false, null, false, null,
        "2026-08-14", fakeQr(), 457, 812);
    BufferedImage heavyLg = this.composer.composeLarge("x", "y", "t", null, null, true, "2027-01-01", false, null,
        "2026-08-14", fakeQr(), 457, 812);
    assertTrue(ink(heavyLg, 0, 430, 457, 812) > ink(plainLg, 0, 430, 457, 812) + 200,
        "large: HEAVY mark + Expires line must render ink");
  }

  @Test
  public void xLargeDieCutLayout() {
    BufferedImage label = this.composer.composeXLarge("Wide Toolbox", "01ARZ3NDEKTSV4RRFFQ69G5FAV", "tool", 3L,
        "garage shelf 3", false, null, false, "2026-08-15", fakeQr(), 812, 812);
    assertEquals(812, label.getWidth());
    assertEquals(812, label.getHeight());
    // 508-dot QR centered: x 152..660, y 4..512
    assertTrue(ink(label, 172, 24, 640, 492) > 5000, "2.5-in QR centered up top");
    assertTrue(ink(label, 0, 520, 812, 790) > 300, "name/id/fields block");
    assertTrue(ink(label, 0, 790, 812, 812) > 20, "printed-on footer");
    assertEquals(0, ink(label, 0, 0, 140, 140), "top-left stays white beside the centered QR");
    assertEquals(0, ink(label, 808, 0, 812, 812), "no ink bleeding off the right edge");
  }

  @Test
  public void xLargeIdFitsOnOneMonoLine() {
    // the wide canvas exists to end the standard format's two-line id split:
    // the full 26-char ULID must render as a single line with margin to spare
    BufferedImage label = this.composer.composeXLarge("x", "01ARZ3NDEKTSV4RRFFQ69G5FAV", "t", null, null, false,
        null, false, "2026-08-15", fakeQr(), 812, 812);
    assertTrue(ink(label, 0, 576, 812, 616) > 200, "id line renders");
    assertEquals(0, ink(label, 750, 576, 812, 616), "id ends well before the right edge");
  }

  @Test
  public void xLargeTolerantOfMissingOptionals() {
    BufferedImage label = this.composer.composeXLarge("x", "y", "_", null, null, false, null, false, "2026-08-15",
        fakeQr(), 812, 812);
    assertEquals(812, label.getHeight());
    assertTrue(ink(label, 0, 520, 812, 812) > 50, "still renders name/id/footer");
  }

  @Test
  public void twoXLargeDieCutLayout() {
    BufferedImage label = this.composer.compose2xLarge("Shipping Crate", "01ARZ3NDEKTSV4RRFFQ69G5FAV", "container",
        2L, "garage", true, "2027-06-30", true, "@ 34.12345, -86.54321", "scuba, color=orange",
        "A long description that certainly needs to wrap across more than one rendered line on the label",
        "2026-08-15", fakeQr(), 812, 1320);
    assertEquals(812, label.getWidth());
    assertEquals(1320, label.getHeight());
    // 609-dot QR centered: x 101..710, y 4..613
    assertTrue(ink(label, 121, 24, 690, 590) > 7000, "3-in QR centered up top");
    assertTrue(ink(label, 0, 620, 812, 1280) > 800, "name/id/coords/fields/tags/description block");
    assertTrue(ink(label, 0, 1290, 812, 1320) > 20, "printed-on footer");
    assertEquals(0, ink(label, 0, 0, 90, 90), "top-left stays white beside the centered QR");
    assertEquals(0, ink(label, 808, 0, 812, 1320), "no ink bleeding off the right edge");
  }

  @Test
  public void twoXLargeCoordinatesAndTagsAddInk() {
    BufferedImage without = this.composer.compose2xLarge("x", "y", "t", null, null, false, null, false, null, null,
        null, "2026-08-15", fakeQr(), 812, 1320);
    BufferedImage with = this.composer.compose2xLarge("x", "y", "t", null, null, false, null, false,
        "@ 34.12345, -86.54321", "scuba, color=orange", null, "2026-08-15", fakeQr(), 812, 1320);
    assertTrue(ink(with, 0, 620, 812, 1280) > ink(without, 0, 620, 812, 1280) + 200,
        "coordinates + tags lines must render ink of their own");
  }

  @Test
  public void twoXLargeTolerantOfMissingOptionals() {
    BufferedImage label = this.composer.compose2xLarge("x", "y", "_", null, null, false, null, false, null, null,
        null, "2026-08-15", fakeQr(), 812, 1320);
    assertEquals(1320, label.getHeight());
    assertTrue(ink(label, 0, 620, 812, 1320) > 50, "still renders name/id/footer");
  }

  @Test
  public void heavyMarkAddsInkOnTheWideLayouts() {
    BufferedImage plainXl = this.composer.composeXLarge("x", "y", "t", null, null, false, null, false, "2026-08-15",
        fakeQr(), 812, 812);
    BufferedImage heavyXl = this.composer.composeXLarge("x", "y", "t", null, null, true, "2027-01-01", true,
        "2026-08-15", fakeQr(), 812, 812);
    assertTrue(ink(heavyXl, 0, 520, 812, 812) > ink(plainXl, 0, 520, 812, 812) + 200,
        "x-large: HEAVY mark + EXPIRES! line must render ink");

    BufferedImage plain2x = this.composer.compose2xLarge("x", "y", "t", null, null, false, null, false, null, null,
        null, "2026-08-15", fakeQr(), 812, 1320);
    BufferedImage heavy2x = this.composer.compose2xLarge("x", "y", "t", null, null, true, "2027-01-01", false, null,
        null, null, "2026-08-15", fakeQr(), 812, 1320);
    assertTrue(ink(heavy2x, 0, 620, 812, 1320) > ink(plain2x, 0, 620, 812, 1320) + 200,
        "2x-large: HEAVY mark + Expires line must render ink");
  }

  @Test
  public void xLargeDieCutGoldenMatchesOnLinux() throws Exception {
    // every field populated, heavy + absolute expiry — the fullest layout
    BufferedImage label = this.composer.composeXLarge("XLarge Golden", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "test-fixture", 3L, "garage shelf 3", true, "2027-06-30", true, "2026-08-15", fakeQr(), 812, 812);
    assertOrUpdateGolden(label, "golden-x-large-812x812.png");
  }

  @Test
  public void twoXLargeDieCutGoldenMatchesOnLinux() throws Exception {
    BufferedImage label = this.composer.compose2xLarge("2xLarge Golden", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "test-fixture", 3L, "garage shelf 3", true, "2027-06-30", true, "@ 34.12345, -86.54321",
        "scuba, color=orange, fragile",
        "A long description that certainly needs to wrap across more than one rendered line on the label",
        "2026-08-15", fakeQr(), 812, 1320);
    assertOrUpdateGolden(label, "golden-2x-large-812x1320.png");
  }

  @Test
  public void standardDieCutGoldenMatchesOnLinux() throws Exception {
    // every field populated, heavy + absolute expiry — the fullest layout
    BufferedImage label = this.composer.composeStandard("Standard Golden", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "test-fixture", 2L, "garage shelf 3", true, "2027-06-30", true, "2026-08-15", fakeQr(), 457, 254);
    assertOrUpdateGolden(label, "golden-standard-457x254.png");
  }

  @Test
  public void largeDieCutGoldenMatchesOnLinux() throws Exception {
    BufferedImage label = this.composer.composeLarge("Large Golden", "01ARZ3NDEKTSV4RRFFQ69G5FAV", "tool", 3L,
        "garage shelf 3", true, "2027-06-30", false,
        "A long description that certainly needs to wrap across more than one rendered line on the label",
        "2026-08-15", fakeQr(), 457, 812);
    assertOrUpdateGolden(label, "golden-large-457x812.png");
  }

  @Test
  public void standardWithEveryFieldNeverOverlaps() {
    // regression: the fixed-position HEAVY mark collided with the expiry
    // line when qty+loc+expiry pushed the field stack down
    BufferedImage label = this.composer.composeStandard("Phase 15 Check", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "test-fixture", 3L, "garage shelf 3", true, "2027-06-30", true, "2026-08-15", fakeQr(), 457, 254);
    // the heavy mark lives under the QR now: ink below y=230 on the left
    assertTrue(ink(label, 4, 230, 204, 254) > 50, "HEAVY mark under the QR");
    // scan the text column for rows where two glyph bands merge: every
    // 4-row window must stay under the density a single 30px line produces
    for (int y = 100; y < 250; y += 2) {
      int band = ink(label, 212, y, 457, y + 4);
      assertTrue(band < 900, "suspicious ink density at y=" + y + " (" + band + ") — overlapping lines?");
    }
  }
}
