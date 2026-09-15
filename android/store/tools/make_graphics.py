#!/usr/bin/env python3
"""Play Store graphics for Keeps. Run:  uv run --with pillow android/store/tools/make_graphics.py

Redraws web/icon.svg in PIL (no SVG rasterizer needed) and writes:
  android/store/graphics/icon-512.png            (Play high-res icon)
  android/store/graphics/feature-graphic-1024x500.png
"""
from __future__ import annotations

import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow required: uv run --with pillow android/store/tools/make_graphics.py")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRAPHICS = os.path.join(ROOT, "graphics")

BG = (27, 28, 30)
INK = (232, 234, 237)
ACCENT = (138, 180, 248)
MUTED = (154, 160, 166)


def draw_mark(draw: ImageDraw.ImageDraw, s: float, ox: float = 0, oy: float = 0) -> None:
    """Note glyph from web/icon.svg, scaled by s, translated by (ox, oy)."""
    # Card outline: rect(156,96,200x320,rx20), stroke 28.
    x0, y0, w, h, r = ox + 156 * s, oy + 96 * s, 200 * s, 320 * s, 20 * s
    draw.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=r,
                           outline=INK, width=max(1, round(28 * s)))
    for y, x1, color in ((176, 316, ACCENT), (240, 316, MUTED), (304, 268, MUTED)):
        draw.line([ox + 196 * s, oy + y * s, ox + x1 * s, oy + y * s], fill=color,
                  width=max(1, round(24 * s)), joint="curve")


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for path, index in (
        ("/System/Library/Fonts/SFNSDisplay.ttf", None),
        ("/System/Library/Fonts/Helvetica.ttc", 0),
        ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", None),
    ):
        try:
            return ImageFont.truetype(path, size, index=index or 0)
        except OSError:
            continue
    return ImageFont.load_default()


def make_icon() -> None:
    img = Image.new("RGB", (512, 512), BG)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, 512, 512], radius=112, fill=BG)
    draw_mark(d, 1.0)
    img.save(os.path.join(GRAPHICS, "icon-512.png"))


def make_feature() -> None:
    img = Image.new("RGB", (1024, 500), BG)
    d = ImageDraw.Draw(img)
    # Glyph (125x200 at 0.625) centered in the 320px tile.
    d.rounded_rectangle([110, 90, 430, 410], radius=70, fill=(36, 37, 38))
    draw_mark(d, 0.625, ox=207.5 - 156 * 0.625, oy=150 - 96 * 0.625)
    d.text((480, 130), "Keeps", font=load_font(120), fill=INK)
    d.text((480, 280), "Fast, offline-first notes", font=load_font(44), fill=MUTED)
    d.text((480, 340), "that sync across devices.", font=load_font(44), fill=MUTED)
    d.line([482, 415, 640, 415], fill=ACCENT, width=6, joint="curve")
    img.save(os.path.join(GRAPHICS, "feature-graphic-1024x500.png"))


if __name__ == "__main__":
    os.makedirs(GRAPHICS, exist_ok=True)
    make_icon()
    make_feature()
    print("wrote icon-512.png + feature-graphic-1024x500.png")
