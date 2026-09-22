"""Dictate server: phone voice typing typed into the PC's focused window.

The phone does no speech recognition of its own — Gboard's voice typing
(by far the most accurate recognizer a phone carries) streams words into a
textarea, and Dictate relays each revision to this server, which types the
diff into whatever window has focus on the PC.

Pairing and trust model mirror Bucket (see bucket/auth.py): localhost is
trusted implicitly, a paired phone authenticates via the bucket_token cookie,
and only the PC can mint pairing codes. The service worker is deliberately
absent — there is nothing to cache and no install-to-home-screen path.
"""

from __future__ import annotations

import argparse
import socket
from pathlib import Path

from flask import Flask, jsonify, redirect, render_template, request, url_for

from bucket import auth
from bucket.store import Store

from .session import Disarmed, ModifiersHeld, Session, TooLong
from .typer import Typer

from . import rambler as rambler_mod

DEFAULT_PORT = 8766


def create_app(data_dir: Path, origin: str, typer: Typer | None = None) -> Flask:
    app = Flask(__name__)
    app.config["STORE"] = Store(data_dir)
    app.config["PAIRING"] = auth.Pairing()
    app.config["SESSION"] = Session(typer or Typer())
    app.config["ORIGIN"] = origin

    store: Store = app.config["STORE"]
    pairing: auth.Pairing = app.config["PAIRING"]
    session: Session = app.config["SESSION"]

    # --- shell ---------------------------------------------------------

    @app.after_request
    def no_cache_static(response):
        # The WebView wrapper is the only client surface and there is no
        # service worker to version assets; a stale HTML/CSS/JS copy would
        # strand UI updates on the phone. Revalidate the shell and the static
        # files every time.
        if request.path == "/" or request.path.startswith("/static/"):
            response.headers["Cache-Control"] = "no-cache"
        return response

    @app.get("/")
    def index():
        paired = auth.authenticate() is not None
        return render_template("index.html", paired=paired)

    # --- pairing (same shape as Bucket) -------------------------------

    @app.post("/api/pairing/code")
    @auth.require_local
    def create_pairing_code():
        code = pairing.issue()
        return jsonify(
            {
                "code": code.code,
                "expires_at": code.expires_at,
                "url": f"{app.config['ORIGIN']}/pair?code={code.code}",
            }
        )

    @app.get("/api/pairing/code")
    @auth.require_local
    def peek_pairing_code():
        code = pairing.current()
        if code is None:
            return jsonify({"code": None})
        return jsonify(
            {
                "code": code.code,
                "expires_at": code.expires_at,
                "url": f"{app.config['ORIGIN']}/pair?code={code.code}",
            }
        )

    @app.get("/pair")
    def pair_via_link():
        if not pairing.redeem(request.args.get("code", "")):
            return jsonify({"error": "invalid or expired code"}), 403
        token = auth.pair_device(store, label=_describe_client())
        response = redirect(url_for("index"))
        _set_token_cookie(response, token)
        return response

    @app.post("/api/pair")
    def pair_via_api():
        payload = request.get_json(silent=True) or {}
        if not pairing.redeem(str(payload.get("code", ""))):
            return jsonify({"error": "invalid or expired code"}), 403
        token = auth.pair_device(store, label=_describe_client())
        response = jsonify({"ok": True})
        _set_token_cookie(response, token)
        return response

    @app.get("/api/devices")
    @auth.require_local
    def list_devices():
        return jsonify({"devices": store.list_devices()})

    @app.delete("/api/devices/<device_id>")
    @auth.require_local
    def revoke_device(device_id: str):
        if not store.delete_device(device_id):
            return jsonify({"error": "not found"}), 404
        return jsonify({"ok": True})

    # --- dictation -----------------------------------------------------

    @app.post("/api/arm")
    @auth.require_device
    def arm():
        return jsonify({"armed": True, "armed_until": session.arm()})

    @app.post("/api/disarm")
    @auth.require_device
    def disarm():
        session.disarm()
        return jsonify({"armed": False})

    @app.get("/api/status")
    @auth.require_device
    def status():
        state = session.state()
        try:
            cfg = rambler_mod.get_config()
            state["rambler_available"] = cfg["configured"]
            state["rambler_model"] = cfg["model"]
        except Exception:
            state["rambler_available"] = False
        return jsonify(state)

    @app.get("/api/rambler")
    @auth.require_device
    def rambler_status():
        cfg = rambler_mod.get_config()
        return jsonify(
            {
                "available": cfg["configured"],
                "model": cfg["model"],
                "host": cfg["host"],
            }
        )

    @app.post("/api/text")
    @auth.require_device
    def text():
        payload = request.get_json(silent=True) or {}
        value = payload.get("text")
        if not isinstance(value, str):
            return jsonify({"error": "text required"}), 400
        want_rambler = bool(payload.get("rambler"))
        seq_val = int(payload.get("seq", 0))

        # Rambler path: clean via Ollama Cloud before diff-typing.
        # Never hold the session lock during the network call.
        cleaned = None
        if want_rambler:
            if not value.strip():
                # Empty clear should still clear the PC even in rambler mode
                pass
            elif not rambler_mod.is_configured():
                return jsonify({"error": "rambler not configured — set OLLAMA_API_KEY in .env on the PC"}), 503
            else:
                try:
                    # Log for terminal visibility — user can see cleaning happened
                    print(f"  [rambler] cleaning {len(value)} chars via {rambler_mod.get_config()['model']} ...")
                    cleaned = rambler_mod.clean_text(value)
                    print(f"  [rambler] -> {len(cleaned)} chars: {cleaned[:120]!r}")
                except Exception as exc:
                    print(f"  [rambler] failed: {exc}")
                    # Surface cloud errors to the phone so the user knows why it didn't type
                    return jsonify({"error": f"rambler: {exc}"}), 502
            # Use cleaned text for typing (if cleaning succeeded or input was empty whitespace)
            if cleaned is not None:
                value = cleaned

        try:
            result = session.type_text(value, seq_val)
        except Disarmed:
            return jsonify({"error": "disarmed"}), 409
        except ModifiersHeld:
            return jsonify({"error": "modifiers"}), 400
        except TooLong:
            return jsonify({"error": "too long"}), 400
        result["ok"] = True
        result["armed_until"] = session.state()["armed_until"]
        if cleaned is not None:
            result["rambler"] = True
            result["cleaned"] = cleaned
        return jsonify(result)

    @app.get("/api/health")
    def health():
        try:
            rambler_available = rambler_mod.is_configured()
        except Exception:
            rambler_available = False
        return jsonify(
            {"ok": True, "origin": app.config["ORIGIN"], "armed": session.armed(), "rambler_available": rambler_available}
        )

    return app


def _set_token_cookie(response, token: str) -> None:
    # Secure only for HTTPS origins: on a plain-HTTP LAN origin a Secure
    # cookie is silently dropped and pairing would break (same rule as Bucket).
    response.set_cookie(
        auth.COOKIE_NAME,
        token,
        max_age=auth.COOKIE_MAX_AGE,
        httponly=True,
        samesite="Lax",
        secure=_origin_https(),
        path="/",
    )


def _origin_https() -> bool:
    from flask import current_app

    return str(current_app.config.get("ORIGIN", "")).startswith("https://")


def _describe_client() -> str:
    agent = request.headers.get("User-Agent", "")
    for marker in ("Android", "iPhone", "iPad", "Macintosh", "Windows", "Linux"):
        if marker in agent:
            return f"{marker} ({request.remote_addr})"
    return request.remote_addr or "unknown"


def detect_lan_ip() -> str:
    """Best-effort local address (identical trick to bucket/server.py)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Dictate: phone voice typing on the PC")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "dictate-data",
    )
    parser.add_argument(
        "--origin",
        default=None,
        help="Public origin for pairing links, e.g. https://laptop.ts.net",
    )
    args = parser.parse_args()

    lan_ip = detect_lan_ip()
    origin = args.origin or f"http://{lan_ip}:{args.port}"
    app = create_app(args.data_dir, origin)

    code = app.config["PAIRING"].issue()
    pair_url = f"{origin}/pair?code={code.code}"
    _print_banner(origin, args.port, pair_url, code.code)

    app.run(host=args.host, port=args.port, threaded=True, debug=False)


def _print_banner(origin: str, port: int, pair_url: str, code: str) -> None:
    # Surface rambler config at startup so missing key is obvious
    try:
        rc = rambler_mod.get_config()
        if rc["configured"]:
            print(f"  Rambler mode: enabled via Ollama Cloud ({rc['model']} @ {rc['host']})")
        else:
            print(f"  Rambler mode: disabled — set OLLAMA_API_KEY in .env to enable (model {rc['model']})")
    except Exception:
        pass
    print()
    print("  Dictate is running")
    print(f"  On this PC     http://localhost:{port}")
    print(f"  On your phone  {origin}")
    print()
    print(f"  Pair with code {code}   (valid 2 minutes)")
    print(f"  or scan:       {pair_url}")
    print()
    try:
        import qrcode

        qr = qrcode.QRCode(border=1)
        qr.add_data(pair_url)
        qr.print_ascii(invert=True)
    except Exception as exc:
        print(f"  (no QR: {type(exc).__name__}: {exc})")
        print(f"  Open this on the phone instead: {pair_url}")
    print()
    print("  WARNING: whatever the phone sends is typed into the window that")
    print("  currently has focus on this PC. The PC disarms after 30s of")
    print("  silence, but keep Dictate open only while you are using it.")
    print()


if __name__ == "__main__":
    main()