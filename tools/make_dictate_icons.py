"""Generate the Dictate Android launcher icons — Quiet Sheet rework.

Palette is warm paper on ink, glyph is mic + cursor underline (voice → cursor).
The cursor is a distinct horizontal bar below the mic base, not part of the mic body —
it reads as “typing” even at 48dp. SDF union keeps edges crisp with AA.

Usage:
    python tools/make_dictate_icons.py
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools.make_icons import (  # noqa: E402
    ANDROID,
    RES,
    clamp01,
    mix,
    png,
    rrect,
)

# --- warm paper palette — sync with dictate/static/app.css --ground/--ink ---
INK = (0x12, 0x14, 0x17)
INK_LT = (0x1E, 0x22, 0x26)
PAPER = (0xFD, 0xFC, 0xF8)
PAPER_LT = (0xFF, 0xFF, 0xFF)
PAPER_DK = (0xC9, 0xC1, 0xB1)

# --- mic + cursor (voice → cursor) — (cx, cy, hw, hh, r) ---
BODY = (0.50, 0.34, 0.12, 0.155, 0.12)
BALL = (0.50, 0.515, 0.145, 0.145, 0.145)
STEM = (0.50, 0.69, 0.026, 0.065, 0.026)
BASE = (0.50, 0.765, 0.095, 0.016, 0.012)
CURSOR = (0.50, 0.875, 0.14, 0.014, 0.006)
TOP = 0.16


def glyph_sdf(gx: float, gy: float) -> float:
    return min(
        rrect(gx, gy, *BODY),
        rrect(gx, gy, *BALL),
        rrect(gx, gy, *STEM),
        rrect(gx, gy, *BASE),
        rrect(gx, gy, *CURSOR),
    )


def shade(x: int, y: int, size: int, inset: float):
    u, v = x / size, y / size
    span = 1.0 - 2.0 * inset
    gx, gy = (u - inset) / span, (v - inset) / span
    px = 1.0 / size / span

    def cov(d: float) -> float:
        return clamp01(0.5 - d / px)

    # tile — ink with subtle vertical gradient + vignette
    color = mix(INK, INK_LT, clamp01(1.5 * (1.0 - v)))
    dv = math.hypot(u - 0.5, v - 0.5)
    color = mix(color, INK, clamp01((dv - 0.36) / 0.28) * 0.55)
    color = mix(color, PAPER, clamp01(1.0 - u) * clamp01(1.0 - v) * 0.03)
    glow = math.exp(-(((gx - 0.5) ** 2) * 2.2 + ((gy - 0.5) ** 2) * 2.8))
    color = mix(color, PAPER, 0.08 * glow)

    # soft drop shadow of glyph
    sh = cov(glyph_sdf(gx, gy + 0.035) + 0.018) * 0.45 * clamp01((gy - (TOP + 0.035)) / 0.03)
    color = mix(color, (0, 0, 0), sh)

    g = cov(glyph_sdf(gx, gy))
    if g > 0:
        t = clamp01((gy - 0.16) / 0.72)
        col = mix(PAPER_LT, PAPER, 0.30 + 0.70 * t)
        col = mix(col, PAPER_DK, 0.50 * clamp01((t - 0.45) / 0.40))
        # gentle gloss on mic head
        gloss = clamp01(1.0 - abs(gx - 0.44) / 0.11) * clamp01((0.40 - gy) / 0.16)
        col = mix(col, PAPER_LT, 0.45 * gloss)
        # cursor is slightly warmer to read as typing
        if gy > 0.86:
            col = mix(col, PAPER_LT, 0.12)
        color = mix(color, col, g)

    return color


def main() -> None:
    for folder, (size, inset) in ANDROID.items():
        path = RES.parent.parent / "android-dictate" / "res" / folder / "ic_launcher.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        png(path, size, lambda x, y, s, ins=inset: shade(x, y, s, ins))
        print(f"wrote android-dictate/res/{folder}/ic_launcher.png")


if __name__ == "__main__":
    main()
