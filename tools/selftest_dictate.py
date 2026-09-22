"""Selftest for Dictate: walks the whole API through Flask's test client.

Runs against a fresh dictate-data-test dir (wiped at start) and a recording
fake typer, so no keystrokes are ever simulated. The phone is impersonated
from a non-localhost REMOTE_ADDR, which is what exercises the real trust
model: the pairing code is minted locally, then claimed over the network.

Usage:
    .venv\\Scripts\\python.exe tools\\selftest_dictate.py
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from flask.testing import FlaskClient  # noqa: E402

from dictate.server import create_app  # noqa: E402

DATA = Path(__file__).resolve().parent.parent / "dictate-data-test"
PHONE_IP = "192.168.1.42"


class Client:
    """A test client that always reports a fixed REMOTE_ADDR."""

    def __init__(self, app, remote_addr: str) -> None:
        self._c = app.test_client()
        self._remote = remote_addr

    def _open(self, *args, **kwargs):
        kwargs.setdefault("environ_overrides", {})["REMOTE_ADDR"] = self._remote
        return self._c.open(*args, **kwargs)

    def get(self, *a, **kw):
        return self._open(*a, method="GET", **kw)

    def post(self, *a, **kw):
        return self._open(*a, method="POST", **kw)

    def delete(self, *a, **kw):
        return self._open(*a, method="DELETE", **kw)


class FakeTyper:
    """Records what the real Typer would have sent to Windows."""

    def __init__(self) -> None:
        self.backspaces = 0
        self.typed = ""
        self.modifiers = False

    def modifiers_down(self) -> bool:
        return self.modifiers

    def backspace(self, count: int) -> None:
        self.backspaces += count

    def type_text(self, text: str) -> None:
        self.typed += text


class Selftest:
    def __init__(self) -> None:
        if DATA.exists():
            shutil.rmtree(DATA)
        self.typer = FakeTyper()
        self.app = create_app(DATA, "http://192.168.1.99:8766", typer=self.typer)
        self.app.test_client_class = None
        self.client = Client(self.app, "127.0.0.1")
        self._phone = Client(self.app, PHONE_IP)
        self.passed = 0
        self.failed = 0

    def check(self, label: str, condition: bool, detail: str = "") -> None:
        if condition:
            self.passed += 1
            print(f"  ok  {label}")
        else:
            self.failed += 1
            print(f"FAIL  {label}  {detail}")

    def phone(self):
        """The one phone client, so its pairing cookie persists."""
        return self._phone

    def run(self) -> None:
        print("Dictate selftest")

        # --- shell & health ------------------------------------------
        r = self.client.get("/")
        self.check("index renders", r.status_code == 200, r.status_code)
        r = self.client.get("/api/health")
        self.check("health ok", r.get_json()["ok"] and r.get_json()["origin"])

        # --- unpaired phone is locked out ----------------------------
        r = self.phone().get("/api/status")
        self.check("unpaired status -> 401", r.status_code == 401, r.status_code)
        r = self.phone().get("/api/health")
        self.check("health is public", r.status_code == 200)

        # --- pairing code is local-only ------------------------------
        r = self.phone().post("/api/pairing/code")
        self.check("phone cannot mint codes", r.status_code == 403, r.status_code)
        r = self.client.post("/api/pairing/code")
        code = r.get_json()["code"]
        self.check("pc mints a code", r.status_code == 200 and len(code) == 6)

        # --- claim it from the LAN -----------------------------------
        r = self.phone().post("/api/pair", json={"code": "000000"})
        self.check("wrong code rejected", r.status_code == 403, r.status_code)
        r = self.phone().post("/api/pair", json={"code": code})
        cookie = r.headers.get("Set-Cookie", "")
        self.check(
            "right code pairs the phone",
            r.status_code == 200
            and "bucket_token" in cookie
            and "Secure" not in cookie,
            r.status_code,
        )
        self.check("code is single-use", self.client.get("/api/pairing/code").get_json()["code"] is None)

        # --- dictation: arm -> type diffs ----------------------------
        p = self.phone()
        r = p.post("/api/arm")
        self.check("arm", r.status_code == 200 and r.get_json()["armed"])
        r = p.get("/api/status")
        self.check("status armed", r.get_json()["armed"])

        seq = [1000]

        def send(text):
            seq[0] += 1
            return p.post("/api/text", json={"text": text, "seq": seq[0]})

        r = send("hello")
        body = r.get_json()
        self.check(
            "first text types", r.status_code == 200
            and body["typed"] == 5 and body["backspaced"] == 0,
            body,
        )
        r = send("hello world")
        body = r.get_json()
        self.check(
            "append types only the new part",
            body["typed"] == 6 and body["backspaced"] == 0,
            body,
        )
        r = send("hello universe")
        body = r.get_json()
        self.check(
            "mid revision backspaces then retypes",
            body["backspaced"] == 5 and body["typed"] == 8,
            body,
        )
        r = send("hello uni")
        body = r.get_json()
        self.check("shrink backspaces the tail", body["backspaced"] == 5 and body["typed"] == 0, body)
        r = send("")
        body = r.get_json()
        self.check("clearing the field clears the PC", body["backspaced"] == 9, body)

        # --- stale/out-of-order requests are ignored -----------------
        before = len(self.typer.typed)
        r = p.post("/api/text", json={"text": "replay", "seq": seq[0] - 1})
        self.check(
            "stale seq ignored",
            r.status_code == 200 and r.get_json()["stale"] and len(self.typer.typed) == before,
        )

        # --- modifier key on the PC blocks typing --------------------
        self.typer.modifiers = True
        r = send("blocked")
        self.check("modifier guard -> 400", r.status_code == 400 and r.get_json()["error"] == "modifiers", r.status_code)
        self.typer.modifiers = False
        before = len(self.typer.typed)
        r = send("unblocked")
        self.check("typing resumes", r.status_code == 200 and len(self.typer.typed) > before)

        # --- too long ------------------------------------------------
        r = p.post("/api/text", json={"text": "x" * 60000, "seq": seq[0] + 1})
        self.check("oversized text -> 400", r.status_code == 400, r.status_code)

        # --- disarm stops everything ---------------------------------
        r = p.post("/api/disarm")
        self.check("disarm", r.status_code == 200 and not r.get_json()["armed"])
        r = send("after disarm")
        self.check("text after disarm -> 409", r.status_code == 409, r.status_code)

        # --- unpaired new device stays locked out --------------------
        other = Client(self.app, "192.168.1.77")
        r = other.post("/api/text", json={"text": "sneak", "seq": seq[0] + 2})
        self.check("unpaired cannot type", r.status_code == 401, r.status_code)

        # --- device revocation ---------------------------------------
        r = self.client.get("/api/devices")
        devices = r.get_json()["devices"]
        self.check("device listed", len(devices) == 1, devices)
        device_id = devices[0]["id"]
        r = self.client.delete(f"/api/devices/{device_id}")
        self.check("revoke device", r.status_code == 200)
        r = p.get("/api/status")
        self.check("revoked phone -> 401", r.status_code == 401, r.status_code)
        r = self.phone().get("/api/status")
        self.check("revoked token stays dead", r.status_code == 401, r.status_code)

        print(f"\n{self.passed} passed, {self.failed} failed")
        sys.exit(1 if self.failed else 0)


if __name__ == "__main__":
    Selftest().run()