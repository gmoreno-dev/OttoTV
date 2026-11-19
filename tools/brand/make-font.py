#!/usr/bin/env python3
"""Writes the launcher's font, tvapp/assets/inter.ttf, from the brand's Inter.

Android can't read WOFF2, so the variable font (weight 100-900, optical size
14-32, Latin) is unpacked to TrueType. The launcher picks weight and optical
size per text, like browsers do with font-optical-sizing: auto.

    python3 tools/brand/make-font.py      (needs fontTools and brotli)
"""
import os

from fontTools.ttLib import TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'art/fonts/inter-latin-opsz-normal.woff2')
OUT = os.path.join(ROOT, 'tvapp/assets/inter.ttf')

font = TTFont(SRC)
font.flavor = None
os.makedirs(os.path.dirname(OUT), exist_ok=True)
font.save(OUT)
print('wrote', os.path.relpath(OUT, ROOT), os.path.getsize(OUT), 'bytes')
