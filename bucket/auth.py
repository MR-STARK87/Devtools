"""Device pairing and request authentication.

Pairing: the PC mints a short one-time code shown as a QR. The phone posts it
back and receives a long-lived token, stored as an HttpOnly cookie. Only the
token's hash is persisted, so a stolen database yields nothing replayable.

The cookie matters beyond convenience: <img> tags, downloads, and the SSE
stream all authenticate themselves without JavaScript attaching headers.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import threading
import time
from dataclasses import dataclass
from functools import wraps

from flask import current_app, g, jsonify, request

COOKIE_NAME = "bucket_token"
COOKIE_MAX_AGE = 60 * 60 * 24 * 365
PAIRING_TTL = 120.0


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


@dataclass
class PairingCode:
    code: str
    expires_at: float


class Pairing:
    """Holds at most one live pairing code; issuing a new one voids the old."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._current: PairingCode | None = None

    def issue(self) -> PairingCode:
        code = f"{secrets.randbelow(10**6):06d}"
        pc = PairingCode(code=code, expires_at=time.time() + PAIRING_TTL)
        with self._lock:
            self._current = pc
        return pc

    def current(self) -> PairingCode | None:
        with self._lock:
            pc = self._current
            if pc and pc.expires_at < time.time():
                self._current = None
                return None
            return pc

    def redeem(self, submitted: str) -> bool:
        """Consume the code if it matches. Compared in constant time, and
        single-use so a code cannot be brute-forced across attempts."""
        with self._lock:
            pc = self._current
            if pc is None or pc.expires_at < time.time():
                self._current = None
                return False
            if not hmac.compare_digest(pc.code, submitted.strip()):
                return False
            self._current = None
            return True


def pair_device(store, label: str) -> str:
    token = secrets.token_urlsafe(32)
    store.add_device(label=label or "device", token_hash=hash_token(token))
    return token


def _token_from_request() -> str | None:
    cookie = request.cookies.get(COOKIE_NAME)
    if cookie:
        return cookie
    header = request.headers.get("Authorization", "")
    if header.startswith("Bearer "):
        return header[7:]
    return None


def authenticate() -> dict | None:
    """Resolve the caller. Localhost is trusted implicitly — it is the PC
    running the server, and it already has filesystem access to everything."""
    if request.remote_addr in ("127.0.0.1", "::1"):
        return {"id": "local", "label": "this pc", "local": True}
    token = _token_from_request()
    if not token:
        return None
    device = current_app.config["STORE"].device_by_token_hash(hash_token(token))
    if device is None:
        return None
    current_app.config["STORE"].touch_device(device["id"])
    return device


def require_device(view):
    @wraps(view)
    def wrapper(*args, **kwargs):
        device = authenticate()
        if device is None:
            return jsonify({"error": "unpaired"}), 401
        g.device = device
        return view(*args, **kwargs)

    return wrapper


def require_local(view):
    """Guard actions that only the PC may take, e.g. minting pairing codes.

    Without this, a paired phone could pair further devices, and a single
    compromised token would become permanent access.
    """

    @wraps(view)
    def wrapper(*args, **kwargs):
        if request.remote_addr not in ("127.0.0.1", "::1"):
            return jsonify({"error": "local only"}), 403
        return view(*args, **kwargs)

    return wrapper
