"""Type text into the PC's focused window (Windows only).

Uses SendInput with KEYEVENTF_UNICODE, which bypasses the keyboard layout
entirely: whatever the phone's voice typing produced arrives verbatim, in any
language, and the clipboard is never touched. Typing goes to whichever window
has focus — that is the whole point of Dictate.

Safety: typing is refused while Shift/Ctrl/Alt/Win is physically held, since
SendInput would apply the modifier (Ctrl+W closes a tab, Alt+F4 closes an
app).
"""

from __future__ import annotations

import ctypes
import threading
import time

INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
VK_BACK = 0x08
_MODIFIER_VKS = (0x10, 0x11, 0x12, 0x5B, 0x5C)  # Shift, Ctrl, Alt, LWin, RWin


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", ctypes.c_ushort),
        ("wScan", ctypes.c_ushort),
        ("dwFlags", ctypes.c_uint),
        ("time", ctypes.c_uint),
        ("dwExtraInfo", ctypes.c_void_p),
    ]


class MOUSEINPUT(ctypes.Structure):
    # Present only to size the union: Windows' INPUT is 40 bytes on x64
    # because the union's largest member is MOUSEINPUT. A keyboard-only union
    # makes sizeof(INPUT) 32, and SendInput rejects every call with
    # ERROR_INVALID_PARAMETER — nothing gets typed.
    _fields_ = [
        ("dx", ctypes.c_long),
        ("dy", ctypes.c_long),
        ("mouseData", ctypes.c_ulong),
        ("dwFlags", ctypes.c_ulong),
        ("time", ctypes.c_ulong),
        ("dwExtraInfo", ctypes.c_void_p),
    ]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", ctypes.c_uint), ("_u", _INPUTUNION)]


def _key(vk: int = 0, scan: int = 0, flags: int = 0) -> INPUT:
    ev = INPUT()
    ev.type = INPUT_KEYBOARD
    ev._u.ki.wVk = vk
    ev._u.ki.wScan = scan
    ev._u.ki.dwFlags = flags
    return ev


def _send(events) -> int:
    arr = (INPUT * len(events))(*events)
    return ctypes.windll.user32.SendInput(len(arr), arr, ctypes.sizeof(INPUT))


class Typer:
    """Serialized typing into whatever window has focus."""

    def __init__(self, per_char_delay: float = 0.002) -> None:
        self.per_char_delay = per_char_delay
        self._lock = threading.Lock()

    def modifiers_down(self) -> bool:
        """True when a modifier is physically held; typing is refused then."""
        for vk in _MODIFIER_VKS:
            if ctypes.windll.user32.GetAsyncKeyState(vk) & 0x8000:
                return True
        return False

    def backspace(self, count: int) -> None:
        if count <= 0:
            return
        with self._lock:
            for _ in range(count):
                _send([_key(vk=VK_BACK), _key(vk=VK_BACK, flags=KEYEVENTF_KEYUP)])
                time.sleep(self.per_char_delay)

    def type_text(self, text: str) -> None:
        if not text:
            return
        with self._lock:
            for ch in text:
                code = ord(ch)
                _send(
                    [
                        _key(scan=code, flags=KEYEVENTF_UNICODE),
                        _key(scan=code, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP),
                    ]
                )
                time.sleep(self.per_char_delay)