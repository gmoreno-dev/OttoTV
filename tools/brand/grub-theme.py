#!/usr/bin/env python3
"""Regenerates the boot menu theme's images and fonts (a development tool:
the outputs are committed, so building the ISO needs neither PIL nor
grub-mkfont).

    python3 tools/brand/grub-theme.py     (needs Pillow, fontTools, brotli, grub-mkfont)

The layout follows the design's 1366x768 boot menu (Bootmenu-768): each
item is a #1D232C pill with 8 px rounded corners, #F2B25C for the chosen
one, Inter SemiBold at 28 px for items and 24 px for the hints. GRUB only
draws images, one font per element and solid colors, so the corners are
9-slice images (name_nw.png ... name_se.png).
"""

import os
import subprocess
import sys
import tempfile

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
THEME = os.path.join(ROOT, "iso", "boot", "grub", "themes", "otto")
FONT = os.path.join(ROOT, "art", "fonts", "inter-latin-wght-normal.woff2")

PANEL = (0x1D, 0x23, 0x2C, 255)
AMBER = (0xF2, 0xB2, 0x5C, 255)
SS = 8  # supersampling for smooth corners


def rounded(radius, color):
    """A (2r+1)-wide rounded square, cut into GRUB's 9 slices."""
    size = 2 * radius + 1
    big = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle(
        (0, 0, size * SS - 1, size * SS - 1), radius=radius * SS, fill=color)
    img = big.resize((size, size), Image.LANCZOS)
    r = radius
    boxes = {
        "nw": (0, 0, r, r), "n": (r, 0, r + 1, r), "ne": (r + 1, 0, size, r),
        "w": (0, r, r, r + 1), "c": (r, r, r + 1, r + 1), "e": (r + 1, r, size, r + 1),
        "sw": (0, r + 1, r, size), "s": (r, r + 1, r + 1, size), "se": (r + 1, r + 1, size, size),
    }
    return {k: img.crop(b) for k, b in boxes.items()}


def write_slices(prefix, radius, color):
    for name, part in rounded(radius, color).items():
        part.save(os.path.join(THEME, "%s_%s.png" % (prefix, name)), optimize=True)


def write_font(size, tmp, metrics=()):
    ttf = os.path.join(tmp, "inter-600.ttf")
    if not os.path.exists(ttf):
        font = TTFont(FONT)
        instancer.instantiateVariableFont(font, {"wght": 600}, inplace=True)
        font.flavor = None
        font.save(ttf)
    out = os.path.join(THEME, "inter-%d.pf2" % size)
    # Latin-1 for Portuguese, the arrows and the middle dot of the hints.
    subprocess.run(
        ["grub-mkfont", "-n", "Otto", "-s", str(size), *metrics,
         "-r", "0x20-0x7E,0xA0-0xFF,0x2190-0x2193", "-o", out, ttf],
        check=True)
    return out


def main():
    os.makedirs(THEME, exist_ok=True)
    # GRUB clips item text to the item's height and draws the pill's corners
    # outside it: 30 px items (the item font's ascent plus descent) with 8 px
    # corners make the design's 46 px pills.
    write_slices("item", 8, PANEL)
    write_slices("select", 8, AMBER)
    with tempfile.TemporaryDirectory() as tmp:
        # Inter's own ascent (28) leaves room for accented capitals the menu
        # doesn't use; 23 fits "í" and "ç" and keeps the text centered.
        print("wrote", os.path.relpath(write_font(28, tmp, ("--asce", "23", "--desc", "7")), ROOT))
        print("wrote", os.path.relpath(write_font(24, tmp), ROOT))
    print("wrote", os.path.relpath(THEME, ROOT) + "/{item,select}_*.png")


if __name__ == "__main__":
    sys.exit(main())
