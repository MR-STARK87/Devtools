"""The armed dictation session.

The phone streams the *full* current value of its textarea on every input
event (that is how Gboard exposes live recognition). The server keeps the
last value it typed and diffs the two: the common prefix/suffix is kept, the
middle is backspaced and retyped. Append-only dictation costs a few chars per
event; mid-sentence revisions cost exactly the changed words.

The session is armed with a rolling timeout: every arm/text request extends
it. After 30s of silence the PC refuses to type again, so a lost or stale
phone can never keep injecting keystrokes.
"""

from __future__ import annotations

import threading
import time

ARM_TIMEOUT = 30.0
MAX_TEXT_CHARS = 50000


class Disarmed(Exception):
    pass


class ModifiersHeld(Exception):
    pass


class TooLong(Exception):
    pass


def _split(a: str, b: str) -> tuple[int, int]:
    """Lengths of the common prefix and suffix of a and b (non-overlapping)."""
    prefix = 0
    for x, y in zip(a, b):
        if x != y:
            break
        prefix += 1
    tail_a, tail_b = a[prefix:], b[prefix:]
    suffix = 0
    for x, y in zip(reversed(tail_a), reversed(tail_b)):
        if x != y:
            break
        suffix += 1
    return prefix, suffix


class Session:
    def __init__(self, typer, timeout: float = ARM_TIMEOUT) -> None:
        self.typer = typer
        self.timeout = timeout
        self.buffer = ""
        self.last_seq = 0
        self._armed_until = 0.0
        self._lock = threading.Lock()

    def arm(self) -> float:
        with self._lock:
            self._armed_until = time.time() + self.timeout
            return self._armed_until

    def disarm(self) -> None:
        with self._lock:
            self._armed_until = 0.0
            self.buffer = ""

    def armed(self) -> bool:
        with self._lock:
            return time.time() < self._armed_until

    def state(self) -> dict:
        with self._lock:
            armed = time.time() < self._armed_until
            return {
                "armed": armed,
                "armed_until": self._armed_until if armed else 0.0,
                "buffer_len": len(self.buffer) if armed else 0,
            }

    def type_text(self, text: str, seq: int) -> dict:
        """Type the diff between the session buffer and `text`.

        seq is a client-supplied monotonic timestamp; anything older than the
        last applied text is ignored (guards against out-of-order requests
        arriving after a page reload).
        """
        if len(text) > MAX_TEXT_CHARS:
            raise TooLong
        with self._lock:
            if time.time() >= self._armed_until:
                self.buffer = ""
                raise Disarmed
            if seq <= self.last_seq:
                return {"typed": 0, "backspaced": 0, "stale": True}
            if text == self.buffer:
                self._armed_until = time.time() + self.timeout
                return {"typed": 0, "backspaced": 0, "stale": False}
            if self.typer.modifiers_down():
                raise ModifiersHeld

            prefix, suffix = _split(self.buffer, text)
            back = len(self.buffer) - prefix - suffix
            middle = text[prefix : len(text) - suffix] if suffix else text[prefix:]

            self.typer.backspace(back)
            self.typer.type_text(middle)
            self.buffer = text
            self.last_seq = seq
            self._armed_until = time.time() + self.timeout
            return {"typed": len(middle), "backspaced": back, "stale": False}