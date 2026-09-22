"""Terminal chrome for Bucket: banner, pairing QR, and request logs.

Everything here is hand-rolled ANSI (24-bit color, half-block glyphs for
the QR) — deliberately no `rich`/`colorama` dependency, so the venv stays
at flask+qrcode and the output pipeline is fully ours. The only OS-specific
bit is enabling VT processing on Windows conhost, the same trick colorama
does without the import.

When stdout is not a terminal (piped, redirected, run from a daemon), or
`NO_COLOR` is set / `TERM=dumb`, everything degrades to the plain text
banner — ANSI escapes in a file would be worse than useless, and the old
`print_ascii(invert=True)` would crash with a UnicodeEncodeError under the
cp1252 pipe encoding on Windows.
"""

from __future__ import annotations

import logging
import os
import re
import shutil
import sys
import time
from typing import Optional, Sequence, Tuple

from werkzeug.serving import WSGIRequestHandler

# --- palette (the terminal's own amber/cyan identity, tuned for a dark
# shell; the web UI is deliberately monochrome since the rework) ---------
AMBER = (255, 184, 84)
CYAN = (94, 224, 255)
INK = (41, 47, 60)       # panel backdrop
INK_SOFT = (56, 64, 82)  # QR "light" cells, so dark cells glow instead of float
DIM = (110, 118, 138)    # muted labels
BRIGHT = (228, 235, 245) # request paths
OK = (122, 224, 168)     # 2xx
WARN = (240, 178, 90)    # 3xx
ERR = (255, 107, 107)    # 4xx/5xx

_RESET = "\x1b[0m"
_BOLD = "\x1b[1m"
_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")

enabled = False


# --- low-level paint ---------------------------------------------------


def _fg(color: Tuple[int, int, int]) -> str:
    return f"\x1b[38;2;{color[0]};{color[1]};{color[2]}m"


def _bg(color: Tuple[int, int, int]) -> str:
    return f"\x1b[48;2;{color[0]};{color[1]};{color[2]}m"


def _paint(text: str, color: Optional[Tuple[int, int, int]], bold: bool = False) -> str:
    if not enabled or color is None:
        return text
    return _fg(color) + (_BOLD if bold else "") + text


def _vis(text: str) -> int:
    """Visible width of a possibly-ANSI string (escape codes are invisible)."""
    return len(_ANSI_RE.sub("", text))


def _enable_vt() -> None:
    """Turn on ANSI processing for Windows conhost.

    NT 10+ ships VT support behind a flag; the Windows Terminal has it on
    by default, but a double-clicked run.cmd lands in legacy conhost.
    Same mechanism colorama uses, ~6 lines instead of a dependency.
    """
    if os.name != "nt":
        return
    try:
        import ctypes

        kernel32 = ctypes.windll.kernel32
        handle = kernel32.GetStdHandle(-11)  # STD_OUTPUT_HANDLE
        mode = ctypes.c_uint32()
        if kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            kernel32.SetConsoleMode(handle, mode.value | 0x0004)
    except Exception:
        pass


def setup(color: Optional[bool] = None) -> bool:
    """Decide fancy output. `color=None` (the server's call) auto-detects:
    on only when stdout is a real terminal and NO_COLOR/TERM=dumb aren't set.
    Tests pass True to force the painted path regardless of the pipe."""
    global enabled
    if color is None:
        color = (
            sys.stdout.isatty()
            and "NO_COLOR" not in os.environ
            and os.environ.get("TERM") != "dumb"
        )
    if color:
        _enable_vt()
        try:
            # Half-block QR glyphs are beyond the cp1252 pipe encoding; a
            # real console accepts anything via WriteConsoleW anyway.
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    enabled = color
    return enabled


# --- banner ------------------------------------------------------------


# BUCKET in block glyphs, built letter-by-letter so the columns line up.
# "BUCK" renders amber, "ET" cyan — the terminal's brand colors, echoing the
# two-origin rail of the pre-rework UI (amber PC, cyan phones). _LOGO_SPLIT
# is where E begins.
_B = ["██████╗", "██╔══██╗", "██████╔╝", "██╔══██╗", "██████╔╝"]
_U = ["██╗ ██╗", "██║ ██║", "██║ ██║", "██║ ██║", "╚███╔╝"]
_C = [" ██████╗", "██╔════╝", "██║     ", "██║     ", "╚██████╗"]
_K = ["██╗  ██╗", "██║ ██╔╝", "█████╔╝", "██╔═██╗", "██║  ██╗"]
_E = ["███████╗", "██╔════╝", "█████╗ ", "██╔══╝ ", "███████╗"]
_T = ["████████╗", "╚══██╔══╝", "   ██║  ", "   ██║  ", "   ██║  "]

_LOGO = [" ".join(row) for row in zip(_B, _U, _C, _K, _E, _T)]
_LOGO_SPLIT = 33


def _inner_width() -> int:
    cols = shutil.get_terminal_size((80, 24)).columns
    return max(min(cols - 4, 78), 50)


def _emit_top(inner: int) -> None:
    print("  " + _paint("╔", DIM) + "═" * inner + _paint("╗", DIM) + _RESET)


def _emit_bottom(inner: int) -> None:
    print("  " + _paint("╚", DIM) + "═" * inner + _paint("╝", DIM) + _RESET)


def _emit_blank(inner: int) -> None:
    print("  " + _paint("║", DIM) + " " * inner + _paint("║", DIM) + _RESET)


def _emit_row(inner: int, segs: Sequence[Tuple[str, Optional[Tuple[int, int, int]], bool]]) -> None:
    """One panel row; segs are (text, color, bold) and the row is centered."""
    pad = inner - sum(_vis(t) for t, _, _ in segs)
    left = pad // 2
    body = " " * left + "".join(_paint(t, c, b) + _RESET for t, c, b in segs) + " " * (pad - left)
    print("  " + _paint("║", DIM) + body + _paint("║", DIM) + _RESET)


def _qr_lines(url: str, inner: int) -> Optional[Sequence[str]]:
    """Render the pairing URL as a QR made of half-block glyphs.

    A module is 2x2 terminal cells (▀▄ stack) so the code is square and
    crisp. Dark cells are amber on a slate backdrop; light cells are just
    the slate — the code reads as a glowing block instead of the washed-out
    band print_ascii(invert=True) produces on a dark console.
    """
    try:
        import qrcode
    except ImportError:
        return None
    qr = qrcode.QRCode(border=2)
    qr.add_data(url)
    qr.make(fit=True)
    matrix = qr.get_matrix()
    n = len(matrix)
    scale = min(2, max(1, (inner - 2) // n))
    fg, bg = _fg(AMBER), _bg(INK_SOFT)
    lines = []
    for r in range(0, n, 2):
        top = matrix[r]
        bottom = matrix[r + 1] if r + 1 < n else [False] * n
        cells = []
        for c in range(n):
            t, b = top[c], bottom[c]
            if t and b:
                ch = "\u2588"  # █ both halves
            elif t:
                ch = "\u2580"  # ▀ top half (foreground), bottom = background
            elif b:
                ch = "\u2584"  # ▄ top = background, bottom half (foreground)
            else:
                ch = " "
            cells.append(ch * scale)
        lines.append(fg + bg + "".join(cells) + _RESET)
    return lines


def banner(origin: str, port: int, pair_url: str, code: str, https: bool) -> None:
    if not enabled:
        _plain_banner(origin, port, pair_url, code, https)
        return

    inner = _inner_width()
    qr = _qr_lines(pair_url, inner)

    print()
    _emit_top(inner)
    for line in _LOGO:
        _emit_row(inner, [(line[:_LOGO_SPLIT], AMBER, False), (line[_LOGO_SPLIT:], CYAN, False)])
    _emit_row(inner, [("a LAN drop box  ·  nothing ever leaves this machine", DIM, False)])
    _emit_blank(inner)
    if qr is None:
        _emit_row(inner, [("pair with code", DIM, False), ("   " + code, AMBER, True)])
        _emit_row(inner, [("or open", DIM, False), ("   " + pair_url, BRIGHT, False)])
    else:
        _emit_row(inner, [("SCAN TO PAIR", DIM, True)])
        _emit_blank(inner)
        for line in qr:
            _emit_row(inner, [(line, None, False)])
        _emit_blank(inner)
        _emit_row(inner, [("pair with code", DIM, False), ("   " + code, AMBER, True), ("   ·  valid 2 minutes", DIM, False)])
    _emit_blank(inner)
    _emit_row(inner, [("this PC", DIM, False), ("     http://localhost:" + str(port), AMBER, False)])
    _emit_row(inner, [("phones", DIM, False), ("       " + origin, CYAN, False)])
    _emit_blank(inner)
    _emit_row(inner, [("press Ctrl+C to quit", DIM, False)])
    _emit_bottom(inner)
    if not https:
        print()
        print(
            "  "
            + _paint("plain HTTP", WARN, True)
            + _RESET
            + _paint(" — install-to-homescreen and the Android share sheet need HTTPS (see README, Tailscale step).", DIM)
        )
    print()


def _plain_banner(origin: str, port: int, pair_url: str, code: str, https: bool) -> None:
    """Non-TTY fallback (pipes, redirects, logs): same facts, no paint."""
    print()
    print("  Bucket is running")
    print(f"  On this PC     http://localhost:{port}")
    print(f"  On your phone  {origin}")
    print()
    print(f"  Pair with code {code}   (valid 2 minutes)")
    print(f"  or open:       {pair_url}")
    print()
    if not https:
        print("  Note: plain HTTP. Install-to-homescreen and the Android share")
        print("  sheet need HTTPS — see README for the Tailscale step.")
    print()


def bye() -> None:
    """Printed after the server loop ends (Ctrl+C)."""
    print()
    if not enabled:
        print("  Bucket stopped. All data stays on this machine.")
        return
    print(
        "  "
        + _paint("Bucket stopped.", AMBER, True)
        + _RESET
        + _paint("  all data stays on this machine.", DIM)
        + _RESET
    )


# --- request logs ------------------------------------------------------


_REQUEST_RE = re.compile(
    r'(?P<head>\S+ - - \[[^\]]+\]) "(?P<method>[A-Z]+) (?P<path>\S+)(?: [^"]+)?" '
    r"(?P<code>\d{3}) (?P<size>[\w-]+)(?: (?P<ms>\d+ms))?"
)


class _RequestFormatter(logging.Formatter):
    """Colors the request line: method cyan, path bright, status by class,
    everything else dim. Non-request lines (e.g. " * Running on ...") are
    dimmed whole rather than mangled."""

    def format(self, record):
        msg = record.getMessage()
        if not enabled:
            # Not a terminal (piped, or redirected to server.log by the
            # headless launcher): werkzeug pre-colors a few of its own lines
            # ("Running on", dev-server warning) before we ever see them, and
            # raw escape codes in a file are worse than useless.
            msg = _ANSI_RE.sub("", msg)
        m = _REQUEST_RE.match(msg)
        if m is None:
            # Plain path: nothing to wrap, so no reset either.
            return msg if not enabled else _paint(msg, DIM) + _RESET
        code = int(m.group("code"))
        if code < 300:
            color = OK
        elif code < 400:
            color = WARN
        else:
            color = ERR
        parts = [
            _paint(m.group("head"), DIM) + _RESET,
            _paint(m.group("method"), CYAN) + _RESET,
            _paint(m.group("path"), BRIGHT) + _RESET,
            _paint(m.group("code"), color, True) + _RESET,
            _paint(m.group("size"), DIM) + _RESET,
            _paint(m.group("ms") or "", DIM) + _RESET,
        ]
        joined = " ".join(parts).rstrip()
        return joined if enabled else _ANSI_RE.sub("", joined).rstrip()


def configure_logging() -> None:
    """Replace werkzeug's stock handler with the painted one. Called in
    main() before the server starts so werkzeug's lazy default handler never
    installs."""
    logger = logging.getLogger("werkzeug")
    logger.setLevel(logging.INFO)
    logger.handlers.clear()
    handler = logging.StreamHandler()
    handler.setFormatter(_RequestFormatter())
    logger.addHandler(handler)


class RequestHandler(WSGIRequestHandler):
    """The dev server's per-connection handler, tagged with a duration so
    the log shows how slow (or snappy) each request was. main() passes this
    to make_server()."""

    def handle(self) -> None:
        self._started = time.monotonic()
        super().handle()

    def log_request(self, code="-", size="-") -> None:
        elapsed_ms = (time.monotonic() - self._started) * 1000
        self.log("info", '"%s" %s %s %dms', self.requestline, code, size, round(elapsed_ms))
