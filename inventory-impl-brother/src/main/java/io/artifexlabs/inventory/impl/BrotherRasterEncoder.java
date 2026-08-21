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
import java.io.ByteArrayOutputStream;

/**
 * Stage 2 of the label pipeline: encode a 1-bit label bitmap as Brother P-touch raster commands (PT-P750W dialect, per
 * Brother's Raster Command Reference). One raster line per bitmap COLUMN: the label travels lengthwise through the
 * printer, and each line spans the 128-pin print head. Bitmaps narrower than the head (smaller tapes) are centered
 * across the pins.
 *
 * The command constants below follow the published reference; the physical smoke test against the real P750W is the
 * final authority — adjust here if the hardware disagrees.
 */
public class BrotherRasterEncoder {

  final static int HEAD_PINS = 128;
  final static int BYTES_PER_LINE = HEAD_PINS / 8;
  /** Blank lines in a feed job — the eject margin does the real feeding. */
  final static int FEED_LINES = 8;
  /** ESC i A's page number is 1-99 (Brother Raster Command Reference p.34). */
  final static int MAX_CUT_PAGES = 99;

  /** One label, printed and fed/cut on its own. */
  public byte[] encode(BufferedImage label, int tapeWidthMm) {
    return encodeBatch(java.util.List.of(label), tapeWidthMm);
  }

  /**
   * A whole run as ONE byte stream — the fix for the 2026-08-18 hardware failure (ongoing item 10).
   *
   * Brother raster treats a multi-page run as a single continuous conversation: initialize ONCE, then per page a
   * print-information block (whose "starting page" byte is 0 for the first and 1 for every subsequent page) followed by
   * its raster lines. Intermediate pages end {@code 0x0C} (print, more coming); ONLY the last ends {@code 0x1A} (print
   * with feeding — the run is over).
   *
   * The previous design sent each label as its OWN connection ending {@code 0x0C}, then hung up. The printer was left
   * waiting for a continuation that never came, and the next connection's {@code ESC @} discarded the page still
   * buffered — which lost labels, stranded a page across a power cycle, and eventually faulted the printer outright.
   * The caller MUST therefore write this whole array down ONE connection; that is why chaining is a property of a batch
   * rather than a config flag.
   */
  public byte[] encodeBatch(java.util.List<BufferedImage> labels, int tapeWidthMm) {
    return encodeBatch(labels, tapeWidthMm, false);
  }

  /**
   * {@code halfCutBetween} perforates between labels instead of severing them: the run stays one strip that tears apart
   * by hand, which is what makes a chained run practical to handle (ESC i K bit 2, "Half cut").
   *
   * It pairs with ESC i A, whose DEFAULT is 1 = "cut each label" — so a batch would otherwise take a full cut between
   * every page and give back the leader saving that chaining exists for. Setting it to the run length moves the single
   * full cut to the end of the run.
   */
  public byte[] encodeBatch(java.util.List<BufferedImage> labels, int tapeWidthMm, boolean halfCutBetween) {
    if (labels == null || labels.isEmpty())
      throw new IllegalArgumentException("a batch needs at least one label");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    initialize(out);
    if (labels.size() > 1)
      // one full cut for the whole run; a single label keeps the default (1)
      out.writeBytes(new byte[] {
          0x1B, 0x69, 0x41, (byte) Math.min(labels.size(), MAX_CUT_PAGES)
      });
    for (int i = 0; i < labels.size(); i++) {
      BufferedImage label = labels.get(i);
      int dots = label.getHeight();
      if (dots > HEAD_PINS)
        throw new IllegalArgumentException("label height " + dots + " exceeds " + HEAD_PINS + " pins");
      pageInfo(out, tapeWidthMm, label.getWidth(), i > 0, halfCutBetween);
      rasterLines(out, label, dots);
      // 0x0C keeps the conversation open for the next page; 0x1A ends the run
      out.write(i == labels.size() - 1 ? 0x1A : 0x0C);
    }
    return out.toByteArray();
  }

  /**
   * A blank job that feeds the tape and cuts — the "extend the tape" action, useful on its own to clear the cutter or
   * reclaim the leader.
   */
  public byte[] encodeFeed(int tapeWidthMm) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    initialize(out);
    pageInfo(out, tapeWidthMm, FEED_LINES, false, false);
    for (int x = 0; x < FEED_LINES; x++)
      out.write(0x5A); // 'Z' zero line
    out.write(0x1A); // print with feeding
    return out.toByteArray();
  }

  /** Sent ONCE per connection — repeating ESC @ mid-run is what destroyed pages. */
  private static void initialize(ByteArrayOutputStream out) {
    out.writeBytes(new byte[100]); // invalidate
    out.writeBytes(new byte[] {
        0x1B, 0x40
    }); // initialize
    out.writeBytes(new byte[] {
        0x1B, 0x69, 0x61, 0x01
    }); // switch to raster mode
  }

  /** Per-page header; {@code continuation} sets the "not the starting page" flag. */
  private static void pageInfo(ByteArrayOutputStream out, int tapeWidthMm, int lines, boolean continuation,
      boolean halfCut) {
    // Print information: kind+width flags, laminated media, width mm, length
    // unspecified, raster line count (little endian), starting-page flag,
    // reserved.
    out.writeBytes(new byte[] {
        0x1B, 0x69, 0x7A, 0x06, 0x01, (byte) tapeWidthMm, 0x00, (byte) (lines & 0xFF), (byte) ((lines >> 8) & 0xFF),
        (byte) ((lines >> 16) & 0xFF), (byte) ((lines >> 24) & 0xFF), (byte) (continuation ? 0x01 : 0x00), 0x00
    });
    out.writeBytes(new byte[] {
        0x1B, 0x69, 0x4D, 0x40
    }); // various mode: auto cut
    // ESC i K: bit 3 SET = "no chain printing", which per the spec means
    // feeding and cutting ARE performed after the LAST page — exactly what a
    // finished run wants. (Clearing it suppresses that final feed/cut, which
    // is how a page stayed stranded in the printer on 2026-08-18.) Bit 2 adds
    // the half cut between labels.
    out.writeBytes(new byte[] {
        0x1B, 0x69, 0x4B, (byte) (0x08 | (halfCut ? 0x04 : 0x00))
    });
    out.writeBytes(new byte[] {
        0x1B, 0x69, 0x64, 0x0E, 0x00
    }); // feed margin: 14 dots
    out.writeBytes(new byte[] {
        0x4D, 0x00
    }); // compression: none
  }

  private static void rasterLines(ByteArrayOutputStream out, BufferedImage label, int dots) {
    int pinOffset = (HEAD_PINS - dots) / 2;
    for (int x = 0; x < label.getWidth(); x++) {
      byte[] line = new byte[BYTES_PER_LINE];
      boolean any = false;
      for (int y = 0; y < dots; y++) {
        if ((label.getRGB(x, y) & 0xFF) == 0) { // black pixel
          int pin = pinOffset + y;
          line[pin / 8] |= (byte) (0x80 >> (pin % 8));
          any = true;
        }
      }
      if (any) {
        out.write(0x47); // 'G' raster line
        out.write(BYTES_PER_LINE & 0xFF);
        out.write((BYTES_PER_LINE >> 8) & 0xFF);
        out.writeBytes(line);
      } else {
        out.write(0x5A); // 'Z' zero line
      }
    }
  }
}
