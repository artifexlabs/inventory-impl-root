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

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Stage 1 of the label pipeline: compose a 1-bit label bitmap from the item's
 * QR code and text. The bitmap's height is the printable dot count of the
 * target tape (e.g. 128 dots for 24 mm TZe at 180 dpi); width grows with
 * content. Layout: the module-exact QR sits at the left edge (the unprinted
 * leader tape supplies its left quiet zone, the tape margins the top/bottom);
 * the item name and id stack to its right past an in-label quiet gap.
 *
 * Deterministic per platform: logical SANS_SERIF / MONOSPACED fonts with
 * antialiasing off. Golden-file tests pin the exact Linux/DejaVu rendering —
 * the same fonts the production container ships — and run platform-neutral
 * invariants everywhere else.
 */
public class LabelComposer {

  private final static int GAP = 8;
  private final static int MARGIN = 4;

  /**
   * Compose a continuous-tape label. {@code qr} must arrive module-exact
   * (integer dots per module, no embedded margin — see
   * {@code QrCodes.render}) and is drawn at its NATURAL size, vertically
   * centered: an arbitrary rescale emits mixed module widths, which small
   * codes cannot survive (ongoing item 11). {@code quietDots} is the quiet
   * zone the text must keep from the code (4 modules of dots).
   */
  public BufferedImage compose(String name, String id, BufferedImage qr, int quietDots, int heightDots) {
    if (qr.getHeight() > heightDots)
      throw new IllegalArgumentException("QR " + qr.getHeight() + " dots exceeds the " + heightDots + "-dot tape");
    Font nameFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, (int) (heightDots * 0.30)));
    Font idFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(8, (int) (heightDots * 0.14)));

    // Measure text with a throwaway graphics so the final width fits exactly.
    BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D pg = probe.createGraphics();
    int nameWidth = pg.getFontMetrics(nameFont).stringWidth(name);
    int idWidth = pg.getFontMetrics(idFont).stringWidth(id);
    pg.dispose();

    int textX = qr.getWidth() + Math.max(GAP, quietDots);
    int width = textX + Math.max(nameWidth, idWidth) + MARGIN;
    BufferedImage label = new BufferedImage(width, heightDots, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D g = graphics(label);
    try {
      g.drawImage(qr, 0, (heightDots - qr.getHeight()) / 2, null);
      g.setFont(nameFont);
      g.drawString(name, textX, (int) (heightDots * 0.44));
      g.setFont(idFont);
      g.drawString(id, textX, (int) (heightDots * 0.80));
    } finally {
      g.dispose();
    }
    return label;
  }

  /**
   * QR-only continuous-tape label for narrow media (12/9 mm), where text
   * would compute to unreadable sizes: just the module-exact code, vertically
   * centered, with a small trailing margin — the tape leader and margins
   * supply the quiet zone (ongoing item 11).
   */
  public BufferedImage composeQrOnly(BufferedImage qr, int heightDots) {
    if (qr.getHeight() > heightDots)
      throw new IllegalArgumentException("QR " + qr.getHeight() + " dots exceeds the " + heightDots + "-dot tape");
    BufferedImage label = new BufferedImage(qr.getWidth() + MARGIN, heightDots, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D g = graphics(label);
    try {
      g.drawImage(qr, 0, (heightDots - qr.getHeight()) / 2, null);
    } finally {
      g.dispose();
    }
    return label;
  }

  // --- die-cut formats (fixed width × height; Zebra-class media) ----------
  // The composer owns formats as pure layout functions; encoders stay
  // format-agnostic, so these work on any printer whose media fits.

  /**
   * {@code standard} die-cut format (2.25×1.25 in → 457×254 @203 dpi).
   * Unlike the continuous-tape layout (whose width grows with content), this
   * canvas is FIXED — every element is sized to fit or ellipsized, never
   * clipped by the raster edge. QR at 1 in on the left (≈5 dots/module for a
   * v3 code — ample); the text column carries name, the full id split
   * across two mono lines (a ULID must never truncate — it IS the label),
   * type/qty, location when known, and the printed-on date.
   */
  public BufferedImage composeStandard(String name, String id, String type, Long quantity, String locationName,
      boolean heavy, String expiresOn, boolean expirationAbsolute, String printedOn, BufferedImage qr, int width,
      int height) {
    BufferedImage label = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D g = graphics(label);
    try {
      int qrSize = 200;
      g.drawImage(qr, MARGIN, (height - qrSize) / 2, qrSize, qrSize, null);
      int textX = MARGIN + qrSize + GAP;
      int textWidth = width - textX - 2 * GAP; // real breathing room at the die-cut edge

      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
      g.drawString(fit(g, name, textWidth), textX, 34);
      if (heavy) {
        // the strip under the QR is always empty — the mark can never
        // collide with the field stack (it did, at a fixed 220 baseline,
        // when qty+loc+expiry pushed the stack down; caught 2026-08-15)
        Font heavyFont = new Font(Font.SANS_SERIF, Font.BOLD, 22);
        g.setFont(heavyFont);
        int markWidth = g.getFontMetrics(heavyFont).stringWidth(HEAVY_MARK);
        g.drawString(HEAVY_MARK, MARGIN + Math.max(0, (qrSize - markWidth) / 2), height - 6);
      }
      g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 20));
      // 13 + 13 chars at mono-20 (~12-dot advance) fits the column with margin
      g.drawString(id.length() > 13 ? id.substring(0, 13) : id, textX, 64);
      if (id.length() > 13)
        g.drawString(id.substring(13), textX, 88);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
      // one field per line: fit() may ellipsize a long value, but it must
      // never be able to swallow a DIFFERENT field packed onto the same line
      g.drawString(fit(g, "Type: " + type, textWidth), textX, 122);
      int fieldY = 150;
      if (quantity != null) {
        g.drawString("Qty: " + quantity, textX, fieldY);
        fieldY += 28;
      }
      if (locationName != null && !locationName.isBlank()) {
        g.drawString(fit(g, "Loc: " + locationName, textWidth), textX, fieldY);
        fieldY += 28;
      }
      if (expiresOn != null)
        g.drawString(fit(g, expiryLine(expiresOn, expirationAbsolute), textWidth), textX, fieldY);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
      g.drawString(fit(g, "Printed " + printedOn, textWidth), textX, height - 12);
    } finally {
      g.dispose();
    }
    return label;
  }

  /**
   * {@code large} die-cut format (2.25×4 in → 457×812 @203 dpi): MORE
   * information and a BIGGER code — the QR centered up top at nearly full
   * width (scans from across a room), then name, id, and the fields the item
   * carries (type, quantity, location name, wrapped description) with the
   * printed-on date at the bottom. {@code locationName} and {@code printedOn}
   * are passed in so the layout stays a pure function.
   */
  public BufferedImage composeLarge(String name, String id, String type, Long quantity, String locationName,
      boolean heavy, String expiresOn, boolean expirationAbsolute, String description, String printedOn,
      BufferedImage qr, int width, int height) {
    BufferedImage label = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D g = graphics(label);
    try {
      int qrSize = width - 2 * MARGIN - 42; // ≈411 dots (2 in) on the 457 canvas
      g.drawImage(qr, (width - qrSize) / 2, MARGIN, qrSize, qrSize, null);

      int y = MARGIN + qrSize + 40;
      Font nameFont = new Font(Font.SANS_SERIF, Font.BOLD, 44);
      g.setFont(nameFont);
      g.drawString(fit(g, name, width - 2 * MARGIN), MARGIN, y);
      y += 40;
      g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 28));
      g.drawString(id, MARGIN, y);
      y += 38;
      Font fieldFont = new Font(Font.SANS_SERIF, Font.PLAIN, 28);
      g.setFont(fieldFont);
      // one field per line — fit() must never swallow a different field
      g.drawString(fit(g, "Type: " + type, width - 2 * MARGIN), MARGIN, y);
      y += 38;
      if (quantity != null) {
        g.drawString("Qty: " + quantity, MARGIN, y);
        y += 38;
      }
      if (locationName != null && !locationName.isBlank()) {
        g.drawString(fit(g, "Location: " + locationName, width - 2 * MARGIN), MARGIN, y);
        y += 38;
      }
      if (expiresOn != null) {
        g.drawString(fit(g, expiryLine(expiresOn, expirationAbsolute), width - 2 * MARGIN), MARGIN, y);
        y += 38;
      }
      if (heavy) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        g.drawString(HEAVY_MARK, MARGIN, y);
        y += 40;
        g.setFont(fieldFont);
      }
      if (description != null && !description.isBlank())
        for (String line : wrap(g.getFontMetrics(fieldFont), description, width - 2 * MARGIN, 4)) {
          g.drawString(line, MARGIN, y);
          y += 34;
        }
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
      g.drawString("Printed " + printedOn, MARGIN, height - 10);
    } finally {
      g.dispose();
    }
    return label;
  }

  /**
   * {@code x-large} die-cut format (4×4 in → 812×812 @203 dpi): the full
   * 4-inch head width. A ~2.5 in QR centered up top (room-distance scanning
   * with dots to spare), then name, the full id on ONE mono line (the wide
   * canvas ends the standard format's two-line split), type and quantity
   * sharing a fixed-column line, container name, expiry, the HEAVY mark, and
   * the printed-on footer. Description/tags/coordinates stay 2x-large-only.
   */
  public BufferedImage composeXLarge(String name, String id, String type, Long quantity, String locationName,
      boolean heavy, String expiresOn, boolean expirationAbsolute, String printedOn, BufferedImage qr, int width,
      int height) {
    BufferedImage label = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D g = graphics(label);
    try {
      int qrSize = 508; // 2.5 in
      g.drawImage(qr, (width - qrSize) / 2, MARGIN, qrSize, qrSize, null);

      int y = MARGIN + qrSize + 56;
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 52));
      g.drawString(fit(g, name, width - 2 * MARGIN), MARGIN, y);
      y += 40;
      g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 30));
      g.drawString(id, MARGIN, y);
      y += 40;
      Font fieldFont = new Font(Font.SANS_SERIF, Font.PLAIN, 32);
      g.setFont(fieldFont);
      // type and quantity share a line as fixed columns — each fits() only
      // within its own column, so neither can ever swallow the other
      int qtyX = 580;
      g.drawString(fit(g, "Type: " + type, qtyX - MARGIN - GAP), MARGIN, y);
      if (quantity != null)
        g.drawString(fit(g, "Qty: " + quantity, width - qtyX - MARGIN), qtyX, y);
      y += 40;
      if (locationName != null && !locationName.isBlank()) {
        g.drawString(fit(g, "Location: " + locationName, width - 2 * MARGIN), MARGIN, y);
        y += 40;
      }
      if (expiresOn != null) {
        g.drawString(fit(g, expiryLine(expiresOn, expirationAbsolute), width - 2 * MARGIN), MARGIN, y);
        y += 40;
      }
      if (heavy) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
        g.drawString(HEAVY_MARK, MARGIN, y + 8);
      }
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
      g.drawString("Printed " + printedOn, MARGIN, height - 10);
    } finally {
      g.dispose();
    }
    return label;
  }

  /**
   * {@code 2x-large} die-cut format (4×6.5 in → 812×1320 @203 dpi),
   * shipping-label size: everything x-large carries plus the rest of what the
   * item knows — own coordinates, tags, and the wrapped description — with
   * the QR pushed to a full 3 in. {@code coordinates} and {@code tags} arrive
   * pre-rendered so the layout stays a pure function.
   */
  public BufferedImage compose2xLarge(String name, String id, String type, Long quantity, String locationName,
      boolean heavy, String expiresOn, boolean expirationAbsolute, String coordinates, String tags,
      String description, String printedOn, BufferedImage qr, int width, int height) {
    BufferedImage label = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D g = graphics(label);
    try {
      int qrSize = 609; // 3 in
      g.drawImage(qr, (width - qrSize) / 2, MARGIN, qrSize, qrSize, null);

      int y = MARGIN + qrSize + 60;
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 56));
      g.drawString(fit(g, name, width - 2 * MARGIN), MARGIN, y);
      y += 44;
      g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 32));
      g.drawString(id, MARGIN, y);
      y += 42;
      Font fieldFont = new Font(Font.SANS_SERIF, Font.PLAIN, 32);
      g.setFont(fieldFont);
      if (coordinates != null && !coordinates.isBlank()) {
        g.drawString(fit(g, coordinates, width - 2 * MARGIN), MARGIN, y);
        y += 42;
      }
      int qtyX = 580;
      g.drawString(fit(g, "Type: " + type, qtyX - MARGIN - GAP), MARGIN, y);
      if (quantity != null)
        g.drawString(fit(g, "Qty: " + quantity, width - qtyX - MARGIN), qtyX, y);
      y += 42;
      if (locationName != null && !locationName.isBlank()) {
        g.drawString(fit(g, "Location: " + locationName, width - 2 * MARGIN), MARGIN, y);
        y += 42;
      }
      if (expiresOn != null) {
        g.drawString(fit(g, expiryLine(expiresOn, expirationAbsolute), width - 2 * MARGIN), MARGIN, y);
        y += 42;
      }
      if (heavy) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
        g.drawString(HEAVY_MARK, MARGIN, y + 8);
        y += 52;
        g.setFont(fieldFont);
      }
      Font smallFont = new Font(Font.SANS_SERIF, Font.PLAIN, 30);
      g.setFont(smallFont);
      if (tags != null && !tags.isBlank())
        for (String line : wrap(g.getFontMetrics(smallFont), "Tags: " + tags, width - 2 * MARGIN, 3)) {
          g.drawString(line, MARGIN, y);
          y += 38;
        }
      if (description != null && !description.isBlank())
        for (String line : wrap(g.getFontMetrics(smallFont), description, width - 2 * MARGIN, 6)) {
          g.drawString(line, MARGIN, y);
          y += 36;
        }
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
      g.drawString("Printed " + printedOn, MARGIN, height - 12);
    } finally {
      g.dispose();
    }
    return label;
  }

  /** Loud on purpose: a two-person lift must be readable across a room. */
  private final static String HEAVY_MARK = "** HEAVY **";

  /** "!" marks an absolute expiry — a hard stop, not a recommendation. */
  private static String expiryLine(String expiresOn, boolean absolute) {
    return (absolute ? "EXPIRES! " : "Expires ") + expiresOn;
  }

  private static Graphics2D graphics(BufferedImage label) {
    Graphics2D g = label.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, label.getWidth(), label.getHeight());
    g.setColor(java.awt.Color.BLACK);
    return g;
  }

  /** Truncate with an ellipsis when the text will not fit the given width. */
  private static String fit(Graphics2D g, String text, int maxWidth) {
    var fm = g.getFontMetrics();
    if (fm.stringWidth(text) <= maxWidth)
      return text;
    String out = text;
    while (out.length() > 1 && fm.stringWidth(out + "…") > maxWidth)
      out = out.substring(0, out.length() - 1);
    return out + "…";
  }

  /** Greedy word wrap to at most {@code maxLines}; the last line gets an ellipsis if truncated. */
  private static java.util.List<String> wrap(java.awt.FontMetrics fm, String text, int maxWidth, int maxLines) {
    java.util.List<String> lines = new java.util.ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : text.split("\\s+")) {
      String candidate = line.isEmpty() ? word : line + " " + word;
      if (fm.stringWidth(candidate) <= maxWidth || line.isEmpty()) {
        // an over-wide single word stays on its own line (raster clips it)
        line = new StringBuilder(candidate);
        continue;
      }
      lines.add(line.toString());
      line = new StringBuilder(word);
      if (lines.size() == maxLines) {
        String last = lines.get(maxLines - 1);
        lines.set(maxLines - 1, last.length() > 1 ? last.substring(0, last.length() - 1) + "…" : last);
        return lines;
      }
    }
    if (!line.isEmpty())
      lines.add(line.toString());
    return lines;
  }
}
