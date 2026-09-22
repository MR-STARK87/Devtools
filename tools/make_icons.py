"""Generate the PWA and Android launcher icons with no image library.

Writes minimal PNGs by hand: a near-black tile with a white grotesk letter B.
Keeping this dependency-free means `pip install` stays at two packages, and
the icons are reproducible if you tweak the palette later.

The two core palette constants are the design tokens; keep them in sync with
the CSS variables in `app.css` (`GROUND` ↔ `--bg`, `INK` ↔ `--ink`). The
derived *_LT / *_DK shades are icon-only lighting and shading and do not
appear in the web UI.

Usage:
    python tools/make_icons.py            write all PNGs
    python tools/make_icons.py --preview  also print a terminal preview
"""

from __future__ import annotations

import math
import struct
import sys
import zlib
from pathlib import Path

# --- design tokens (keep in sync with app.css) ---
GROUND = (0x0B, 0x0B, 0x0C)  # --bg
INK = (0xF2, 0xF2, 0xF3)     # --ink

# --- icon-only shading shades ---
GROUND_LT = (0x1A, 0x1A, 0x1E)
INK_LT = (0xFF, 0xFF, 0xFF)
INK_DK = (0xB8, 0xB8, 0xBF)

STATIC = Path(__file__).resolve().parent.parent / "bucket" / "static"
RES = Path(__file__).resolve().parent.parent / "android" / "res"

# density -> (pixel size, glyph inset). Launcher icons get a bit more padding
# than the PWA ones because launchers mask them into circles/squircles.
ANDROID = {
    "mipmap-mdpi": (48, 0.16),
    "mipmap-hdpi": (72, 0.16),
    "mipmap-xhdpi": (96, 0.16),
    "mipmap-xxhdpi": (144, 0.16),
    "mipmap-xxxhdpi": (192, 0.16),
}


def clamp01(v: float) -> float:
    return 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)


def mix(a, b, t: float):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def png(path: Path, size: int, pixels) -> None:
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # filter type 0 for each scanline
        for x in range(size):
            raw.extend(pixels(x, y, size))

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def rrect(gx: float, gy: float, cx: float, cy: float, hw: float, hh: float,
          r: float) -> float:
    """Signed distance to a rounded rectangle; <=0 is inside."""
    qx = abs(gx - cx) - (hw - r)
    qy = abs(gy - cy) - (hh - r)
    ax, ay = max(qx, 0.0), max(qy, 0.0)
    return math.hypot(ax, ay) + min(max(qx, qy), 0.0) - r


# --- the letterform -------------------------------------------------------
# A grotesk B built from primitives: a rounded stem, two D-shaped bowls (the
# right edge of each is a semicircle), and the counters subtracted. The upper
# bowl is a hair smaller than the lower one, as in the typeface.
STEM = (0.355, 0.50, 0.045, 0.38, 0.045)
BOWL_UP = (0.485, 0.31, 0.175, 0.19, 0.175)
BOWL_LO = (0.50, 0.69, 0.19, 0.20, 0.19)
COUNT_UP = (0.485, 0.31, 0.085, 0.10, 0.085)
COUNT_LO = (0.50, 0.69, 0.10, 0.10, 0.10)
BAR = (0.50, 0.495, 0.19, 0.04, 0.04)
TOP, BOTTOM = 0.12, 0.89
SCALE = 1.12  # letter is drawn 12% larger than the design grid


def _s(v: float) -> float:
    """Scale a coordinate around the glyph-box center."""
    return 0.5 + (v - 0.5) * SCALE


TOP2, BOTTOM2 = _s(TOP), _s(BOTTOM)


def letter_sdf(gx: float, gy: float) -> float:
    outer = min(
        rrect(gx, gy, *STEM),
        rrect(gx, gy, *BOWL_UP),
        rrect(gx, gy, *BOWL_LO),
        rrect(gx, gy, *BAR),
    )
    counter = min(
        rrect(gx, gy, *COUNT_UP),
        rrect(gx, gy, *COUNT_LO),
    )
    return max(outer, -counter)


def shade(x: int, y: int, size: int, inset: float):
    """One pixel of the icon, as a tuple of RGB bytes.

    Geometry lives in a normalized glyph box `inset`-inset from the canvas
    edge; every edge is anti-aliased per-pixel. The tile is near-black with
    a soft vignette; the letter is white with a gentle vertical shade and a
    drop shadow under it. Monochrome since the UI rework — the terminal
    keeps its amber/cyan, the icons match the app.
    """
    u, v = x / size, y / size
    span = 1.0 - 2.0 * inset
    gx, gy = (u - inset) / span, (v - inset) / span
    gx2, gy2 = _s(gx), _s(gy)  # letter-space coords (slightly larger)
    px = 1.0 / size / span

    def cov(d: float) -> float:
        """Coverage from a signed distance: <=0 inside, ~1px soft transition."""
        return clamp01(0.5 - d / px)

    # --- background tile ------------------------------------------------
    color = mix(GROUND, GROUND_LT, clamp01(1.6 * (1.0 - v)))
    dv = math.hypot(u - 0.5, v - 0.5)
    color = mix(color, (0, 0, 0), clamp01((dv - 0.35) / 0.28) * 0.6)

    # --- drop shadow (offset down, softer edge, never above the cap line) --
    sh = cov(letter_sdf(gx2, gy2 + 0.05) + 0.02) * 0.5 * clamp01((gy2 - (TOP2 + 0.05)) / 0.03)
    color = mix(color, (0, 0, 0), sh)

    # --- the letter ------------------------------------------------------
    letter = cov(letter_sdf(gx2, gy2))
    if letter > 0:
        t = clamp01((gy2 - TOP2) / (BOTTOM2 - TOP2))
        col = mix(INK_LT, INK, 0.45 * t)
        col = mix(col, INK_DK, 0.5 * clamp01((t - 0.5) / 0.4))
        # a soft gloss sweeping down the upper-left of the letter
        gloss = clamp01(1.0 - abs(gx2 - 0.48) / 0.15) * clamp01((0.34 - gy2) / 0.16)
        col = mix(col, INK_LT, 0.5 * gloss)
        color = mix(color, col, letter)

    return color


def glyph(inset: float):
    def pixel(x: int, y: int, size: int):
        return shade(x, y, size, inset)

    return pixel


def ascii_preview() -> None:
    ramp = " .:-=+*#%@"
    print()
    for y in range(40):
        row = ""
        for x in range(40):
            r, g, b = shade(x, y, 40, 0.10)
            lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            row += ramp[min(9, int(lum / 256 * 10))]
        print(row)
    print()


def main() -> None:
    STATIC.mkdir(parents=True, exist_ok=True)
    png(STATIC / "icon-192.png", 192, glyph(0.10))
    png(STATIC / "icon-512.png", 512, glyph(0.10))
    # Maskable icons get cropped to a circle by Android, so pull the glyph in
    # and keep the tile full-bleed to survive the crop.
    png(STATIC / "icon-maskable.png", 512, glyph(0.22))

    for folder, (size, inset) in ANDROID.items():
        png(RES / folder / "ic_launcher.png", size, glyph(inset))

    for name in (
        "icon-192.png",
        "icon-512.png",
        "icon-maskable.png",
        *[f"android/res/{d}/ic_launcher.png" for d in ANDROID],
    ):
        print(f"wrote {name}")

    if "--preview" in sys.argv:
        ascii_preview()


if __name__ == "__main__":
    main()