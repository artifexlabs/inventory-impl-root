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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

/** Exact-byte assertions for the P750W raster dialect. */
public class BrotherRasterEncoderTest {

  private final BrotherRasterEncoder encoder = new BrotherRasterEncoder();

  private static BufferedImage image(int width, int height) {
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
    var g = img.createGraphics();
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, width, height);
    g.dispose();
    return img;
  }

  /** Full single-label job: one-time init + one page. */
  private static byte[] preamble(int lines, int tapeMm) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[100]);
    out.writeBytes(new byte[] { 0x1B, 0x40 });
    out.writeBytes(new byte[] { 0x1B, 0x69, 0x61, 0x01 });
    out.writeBytes(pageInfo(lines, tapeMm, false));
    return out.toByteArray();
  }

  /** Per-page header; continuation sets the "not the starting page" flag. */
  private static byte[] pageInfo(int lines, int tapeMm, boolean continuation) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] { 0x1B, 0x69, 0x7A, 0x06, 0x01, (byte) tapeMm, 0x00, (byte) lines, 0x00,
        0x00, 0x00, (byte) (continuation ? 0x01 : 0x00), 0x00 });
    out.writeBytes(new byte[] { 0x1B, 0x69, 0x4D, 0x40 });
    out.writeBytes(new byte[] { 0x1B, 0x69, 0x4B, 0x08 });
    out.writeBytes(new byte[] { 0x1B, 0x69, 0x64, 0x0E, 0x00 });
    out.writeBytes(new byte[] { 0x4D, 0x00 });
    return out.toByteArray();
  }

  @Test
  public void blankLabelIsAllZeroLines() {
    BufferedImage img = image(3, 128);
    byte[] encoded = this.encoder.encode(img, 24);

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.writeBytes(preamble(3, 24));
    expected.writeBytes(new byte[] { 0x5A, 0x5A, 0x5A, 0x1A });
    assertArrayEquals(expected.toByteArray(), encoded);
  }

  @Test
  public void blackPixelsSetTheRightPinBits() {
    BufferedImage img = image(2, 128);
    img.setRGB(0, 0, 0x000000);    // column 0, top pin -> byte 0, bit 0x80
    img.setRGB(0, 127, 0x000000);  // column 0, bottom pin -> byte 15, bit 0x01
    img.setRGB(1, 9, 0x000000);    // column 1, pin 9 -> byte 1, bit 0x40

    byte[] encoded = this.encoder.encode(img, 24);

    byte[] line0 = new byte[16];
    line0[0] = (byte) 0x80;
    line0[15] = 0x01;
    byte[] line1 = new byte[16];
    line1[1] = 0x40;

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.writeBytes(preamble(2, 24));
    expected.writeBytes(new byte[] { 0x47, 0x10, 0x00 });
    expected.writeBytes(line0);
    expected.writeBytes(new byte[] { 0x47, 0x10, 0x00 });
    expected.writeBytes(line1);
    expected.write(0x1A);
    assertArrayEquals(expected.toByteArray(), encoded);
  }

  @Test
  public void narrowTapeIsCenteredAcrossTheHead() {
    BufferedImage img = image(1, 70); // 12mm tape: 70 dots, offset (128-70)/2 = 29
    img.setRGB(0, 0, 0x000000);       // top of the 70 -> pin 29 -> byte 3, bit 7-(29%8)=0x04
    byte[] encoded = this.encoder.encode(img, 12);
    // find the raster line: preamble length then 'G'
    int at = preamble(1, 12).length;
    assertEquals(0x47, encoded[at] & 0xFF);
    assertEquals(0x04, encoded[at + 3 + 3] & 0xFF, "pin 29 -> byte index 3, bit 0x04");
  }

  @Test
  public void tallerThanHeadIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> this.encoder.encode(image(1, 129), 24));
  }

  @Test
  public void aBatchIsOneStreamWithOnlyTheLastPageEndingTheJob() {
    // the 2026-08-18 hardware bug: pages must ride ONE conversation, with
    // intermediate pages ending 0x0C and only the final one ending 0x1A
    byte[] encoded = this.encoder.encodeBatch(java.util.List.of(image(2, 128), image(3, 128)), 24);

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.writeBytes(new byte[100]);                       // invalidate ONCE
    expected.writeBytes(new byte[] { 0x1B, 0x40 });           // initialize ONCE
    expected.writeBytes(new byte[] { 0x1B, 0x69, 0x61, 0x01 });
    expected.writeBytes(new byte[] { 0x1B, 0x69, 0x41, 0x02 }); // cut once per 2-label run
    expected.writeBytes(pageInfo(2, 24, false));              // page 1: starting page
    expected.writeBytes(new byte[] { 0x5A, 0x5A, 0x0C });     // ...more pages follow
    expected.writeBytes(pageInfo(3, 24, true));               // page 2: continuation flag
    expected.writeBytes(new byte[] { 0x5A, 0x5A, 0x5A, 0x1A }); // ...run complete
    assertArrayEquals(expected.toByteArray(), encoded);
  }

  @Test
  public void halfCutSetsBit2AndKeepsTheFinalFeedCutBit() {
    // Brother Raster Command Reference p.33: ESC i K bit 2 = half cut,
    // bit 3 = "no chain printing" (feed+cut AFTER the last page). A run
    // wants both: perforations between, one real cut at the end.
    byte[] plain = this.encoder.encodeBatch(java.util.List.of(image(1, 128), image(1, 128)), 24, false);
    byte[] half = this.encoder.encodeBatch(java.util.List.of(image(1, 128), image(1, 128)), 24, true);
    assertEquals(0x08, advancedModeByte(plain), "full cut between: bit 3 only");
    assertEquals(0x0C, advancedModeByte(half), "half cut between: bits 2 and 3");
    assertEquals(0x1A, half[half.length - 1] & 0xFF, "the run still ends with feed+cut");
  }

  @Test
  public void aMultiLabelRunTakesItsFullCutOnlyAtTheEnd() {
    // ESC i A defaults to 1 = "cut each label", which would sever every page
    // and give back the leader saving; the run length moves it to the end
    byte[] three = this.encoder.encodeBatch(
        java.util.List.of(image(1, 128), image(1, 128), image(1, 128)), 24, true);
    assertEquals(3, cutEachPages(three), "ESC i A must carry the run length");
    // a single label keeps the default and sends no ESC i A at all
    assertEquals(-1, cutEachPages(this.encoder.encodeBatch(java.util.List.of(image(1, 128)), 24, true)));
  }

  /** The n1 of the first ESC i K (1B 69 4B). */
  private static int advancedModeByte(byte[] job) {
    for (int i = 0; i + 3 < job.length; i++)
      if ((job[i] & 0xFF) == 0x1B && (job[i + 1] & 0xFF) == 0x69 && (job[i + 2] & 0xFF) == 0x4B)
        return job[i + 3] & 0xFF;
    return -1;
  }

  /** The n of ESC i A (1B 69 41), or -1 when the command is absent. */
  private static int cutEachPages(byte[] job) {
    for (int i = 0; i + 3 < job.length; i++)
      if ((job[i] & 0xFF) == 0x1B && (job[i + 1] & 0xFF) == 0x69 && (job[i + 2] & 0xFF) == 0x41)
        return job[i + 3] & 0xFF;
    return -1;
  }

  @Test
  public void theInitializeSequenceAppearsExactlyOncePerBatch() {
    // repeating ESC @ mid-run is precisely what discarded buffered pages
    byte[] encoded = this.encoder.encodeBatch(
        java.util.List.of(image(1, 128), image(1, 128), image(1, 128)), 24);
    int occurrences = 0;
    for (int i = 0; i + 1 < encoded.length; i++)
      if ((encoded[i] & 0xFF) == 0x1B && (encoded[i + 1] & 0xFF) == 0x40)
        occurrences++;
    assertEquals(1, occurrences, "ESC @ must appear once, not once per page");
  }

  @Test
  public void aSingleLabelIsJustABatchOfOne() {
    assertArrayEquals(this.encoder.encodeBatch(java.util.List.of(image(3, 128)), 24),
        this.encoder.encode(image(3, 128), 24));
  }

  @Test
  public void anEmptyBatchIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> this.encoder.encodeBatch(java.util.List.of(), 24));
  }

  @Test
  public void feedJobIsBlankAndFeeds() {
    byte[] encoded = this.encoder.encodeFeed(24);

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.writeBytes(preamble(BrotherRasterEncoder.FEED_LINES, 24));
    for (int i = 0; i < BrotherRasterEncoder.FEED_LINES; i++)
      expected.write(0x5A);
    expected.write(0x1A); // print WITH feeding: this cut ends the chain run
    assertArrayEquals(expected.toByteArray(), encoded);
  }
}
