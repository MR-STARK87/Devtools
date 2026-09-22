"""Bucket server: a LAN drop box shared between this PC and paired phones."""

from __future__ import annotations

import argparse
import datetime
import hashlib
import mimetypes
import re
import shutil
import socket
import sys
import tempfile
import threading
import time
import zipfile
from pathlib import Path

from werkzeug.serving import make_server

from flask import (
    Flask,
    Response,
    g,
    jsonify,
    redirect,
    render_template,
    request,
    send_file,
    url_for,
)

from . import auth
from . import console
from .events import Broker
from .store import Store

MAX_UPLOAD_BYTES = 512 * 1024 * 1024

# How a multi-file batch arrives: "ask" prompts every time, "zip" bundles
# into one .zip item, "separate" stores one item per file. One global value
# (see Store.get_setting): PC and phone share the same bucket, so they share
# the same answer — and the PC page could not be covered by a per-device
# value anyway, since loopback is a synthetic device with no DB row.
BATCH_MODE_KEY = "batch_mode"
BATCH_MODES = ("ask", "zip", "separate")
BATCH_MODE_DEFAULT = "ask"


def create_app(data_dir: Path, origin: str) -> Flask:
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD_BYTES
    app.config["STORE"] = Store(data_dir)
    app.config["BROKER"] = Broker()
    app.config["PAIRING"] = auth.Pairing()
    app.config["ORIGIN"] = origin

    store: Store = app.config["STORE"]
    broker: Broker = app.config["BROKER"]
    pairing: auth.Pairing = app.config["PAIRING"]

    # --- shell ---------------------------------------------------------

    @app.get("/")
    def index():
        device = auth.authenticate()
        return render_template(
            "index.html",
            paired=device is not None,
            # Only the machine itself (loopback) may see the server controls:
            # a paired phone gets the drop box but never the stop/pair chrome.
            local=bool(device and device.get("local")),
        )

    @app.get("/manifest.webmanifest")
    def manifest():
        return send_file(
            Path(app.root_path) / "static" / "manifest.webmanifest",
            mimetype="application/manifest+json",
        )

    @app.get("/sw.js")
    def service_worker():
        # Served from the root so its scope covers the whole origin; a worker
        # under /static/ could only control /static/.
        response = Response(
            render_template("sw.js", version=_asset_version(app)),
            mimetype="text/javascript",
        )
        # Must not be cached: this file is how every other update arrives.
        response.headers["Cache-Control"] = "no-cache"
        return response

    # --- pairing -------------------------------------------------------

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
        code = request.args.get("code", "")
        if not pairing.redeem(code):
            return render_template("paired.html", ok=False), 403
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

    # --- items ---------------------------------------------------------

    @app.get("/api/items")
    @auth.require_device
    def list_items():
        return jsonify({"items": store.list()})

    @app.post("/api/items")
    @auth.require_device
    def create_item():
        origin = "pc" if g.device.get("local") else "phone"
        created = []

        uploads = request.files.getlist("files")
        bundle = request.form.get("bundle") == "zip"
        created.extend(_store_uploads(store, uploads, origin, bundle))

        files = [f for f in uploads if f and f.filename]
        text = request.form.get("text")
        if text is None and not files:
            payload = request.get_json(silent=True) or {}
            text = payload.get("text")
        if text and text.strip():
            # Text always stays its own item, even when the file batch is
            # zipped: bundling answers "what to do with the files", nothing
            # more. Both clients rely on this.
            created.append(store.add_text(text, origin=origin))

        if not created:
            return jsonify({"error": "nothing to add"}), 400

        for item in created:
            broker.publish("item:add", item)
        return jsonify({"items": created}), 201

    @app.get("/api/items/<item_id>/raw")
    @auth.require_device
    def raw_item(item_id: str):
        found = store.blob_for(item_id)
        if found is None:
            body = store.raw_text(item_id)
            if body is None:
                return jsonify({"error": "not found"}), 404
            headers = None
            if request.args.get("download") == "1":
                # Without Content-Disposition the WebView would navigate to
                # the raw text instead of starting a download, so "Save" on
                # a text item would never land in the phone's Downloads.
                item = store.get(item_id)
                name = _safe_download_name(item["name"]) if item else "text"
                headers = {
                    "Content-Disposition": f'attachment; filename="{name}.txt"'
                }
            return Response(
                body,
                mimetype="text/plain; charset=utf-8",
                headers=headers,
            )
        path, mime, name = found
        download = request.args.get("download") == "1"
        return send_file(
            path,
            mimetype=mime,
            as_attachment=download,
            download_name=name,
            conditional=True,
        )

    @app.delete("/api/items/<item_id>")
    @auth.require_device
    def delete_item(item_id: str):
        if not store.delete(item_id):
            return jsonify({"error": "not found"}), 404
        broker.publish("item:remove", {"id": item_id})
        return jsonify({"ok": True})

    @app.post("/api/items/clear")
    @auth.require_device
    def clear_items():
        removed = store.clear()
        broker.publish("item:reset", {"removed": removed})
        return jsonify({"removed": removed})

    # --- batch preference --------------------------------------------

    @app.get("/api/batch-mode")
    @auth.require_device
    def get_batch_mode():
        mode = store.get_setting(BATCH_MODE_KEY, BATCH_MODE_DEFAULT)
        if mode not in BATCH_MODES:
            mode = BATCH_MODE_DEFAULT
        return jsonify({"mode": mode})

    @app.put("/api/batch-mode")
    @auth.require_device
    def put_batch_mode():
        payload = request.get_json(silent=True) or {}
        mode = payload.get("mode")
        if mode not in BATCH_MODES:
            return jsonify({"error": "mode must be one of ask, zip, separate"}), 400
        store.set_setting(BATCH_MODE_KEY, mode)
        return jsonify({"mode": mode})

    # --- android share target ------------------------------------------

    @app.post("/share")
    @auth.require_device
    def share_target():
        """Receives Android's system share sheet POST.

        Redirects rather than returning JSON: this is a top-level navigation
        that opens the PWA, so the response becomes the visible page.
        """
        # The PWA share_target is a bare form POST with no custom fields, so
        # it cannot carry a remembered choice — but a client that knows the
        # mode (the native app reads /api/batch-mode) may still ask for a
        # bundle explicitly.
        created = _store_uploads(
            store,
            request.files.getlist("files"),
            "phone",
            bundle=request.form.get("bundle") == "zip",
        )
        shared_text = " ".join(
            part
            for part in (
                request.form.get("title"),
                request.form.get("text"),
                request.form.get("url"),
            )
            if part
        ).strip()
        if shared_text:
            created.append(store.add_text(shared_text, origin="phone"))
        for item in created:
            broker.publish("item:add", item)
        return redirect(url_for("index", shared=len(created)))

    # --- live stream ---------------------------------------------------

    @app.get("/api/events")
    @auth.require_device
    def events():
        subscriber = broker.subscribe()
        response = Response(
            broker.stream(subscriber),
            mimetype="text/event-stream",
        )
        response.headers["Cache-Control"] = "no-cache"
        response.headers["X-Accel-Buffering"] = "no"
        return response

    @app.get("/api/health")
    def health():
        return jsonify({"ok": True, "origin": app.config["ORIGIN"]})

    @app.post("/api/shutdown")
    @auth.require_local
    def shutdown_server():
        """Graceful stop, used by the Stop control on this PC's page.

        The real server is built with make_server() in main() and stashed on
        app.config["SERVER"]; the headless launcher means there is often no
        console to Ctrl+C, so this is the polite way out. shutdown() is called
        off the request thread (it waits for the serve loop to exit, which
        would deadlock inline) after a beat that lets this response flush
        first. Under the test client there is no server object: no-op.
        """
        server = app.config.get("SERVER")
        if server is None:
            return jsonify({"ok": True, "stopped": False})

        def _stop() -> None:
            time.sleep(0.25)
            server.shutdown()

        threading.Thread(target=_stop, daemon=True).start()
        return jsonify({"ok": True, "stopped": True})

    return app


VERSIONED_ASSETS = ("app.css", "app.js")


def _asset_version(app: Flask) -> str:
    """Short hash of the cached assets plus the page markup.

    Recomputed per request: this runs on a laptop serving one user, and the
    cost is a few file reads. In exchange, editing a file and reloading is
    enough to push the change to an installed phone.
    """
    digest = hashlib.sha256()
    static = Path(app.root_path) / "static"
    for name in VERSIONED_ASSETS:
        digest.update((static / name).read_bytes())
    digest.update((Path(app.root_path) / "templates" / "index.html").read_bytes())
    return digest.hexdigest()[:12]


def _set_token_cookie(response, token: str) -> None:
    # Secure is set only for HTTPS origins: on a plain-HTTP LAN origin a
    # Secure cookie is silently dropped, which would break pairing entirely.
    https = current_origin_is_https()
    response.set_cookie(
        auth.COOKIE_NAME,
        token,
        max_age=auth.COOKIE_MAX_AGE,
        httponly=True,
        samesite="Lax",
        secure=https,
        path="/",
    )


def current_origin_is_https() -> bool:
    from flask import current_app

    return str(current_app.config.get("ORIGIN", "")).startswith("https://")


def _describe_client() -> str:
    agent = request.headers.get("User-Agent", "")
    for marker in ("Android", "iPhone", "iPad", "Macintosh", "Windows", "Linux"):
        if marker in agent:
            return f"{marker} ({request.remote_addr})"
    return request.remote_addr or "unknown"


def _guess_mime(filename: str) -> str:
    return mimetypes.guess_type(filename)[0] or "application/octet-stream"


def _safe_download_name(name: str) -> str:
    """Turn an item name into something Android/Windows will write: no path
    separators, no reserved characters, no leading/trailing dots."""
    return re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name).strip(" .")


def _store_uploads(store: Store, uploads, origin: str, bundle: bool) -> list:
    """Store one request's file parts, bundling them into a single zip item
    when asked and when there is actually a batch (a lone file with the flag
    set is stored as-is — no pointless one-file archives)."""
    files = [u for u in uploads if u and u.filename]
    if bundle and len(files) > 1:
        return [store_bundle(store, files, origin)]
    created = []
    for upload in files:
        created.append(
            store.add_blob(
                upload.stream,
                name=upload.filename,
                mime=upload.mimetype or _guess_mime(upload.filename),
                origin=origin,
            )
        )
    return created


def store_bundle(store: Store, uploads, origin: str) -> dict:
    """Zip a batch of uploads into one item.

    Entries stream straight from the request into a temp zip next to the
    blobs (same filesystem, no extra disk seek), and only then does the
    finished archive go through add_blob — so the blob-before-row contract
    holds and a crash mid-zip leaves an unlisted .tmp, never a dangling row.
    The temp file is always unlinked afterwards, so "clear all" keeps its
    guarantee of an empty blobs dir.
    """
    taken: set[str] = set()
    tmp = tempfile.NamedTemporaryFile(
        dir=store.blobs, prefix="bundle-", suffix=".tmp", delete=False
    )
    tmp.close()
    try:
        with zipfile.ZipFile(tmp.name, "w", zipfile.ZIP_DEFLATED) as archive:
            for upload in uploads:
                entry = _unique_entry_name(upload.filename, taken)
                taken.add(entry)
                with archive.open(entry, "w") as dest:
                    shutil.copyfileobj(upload.stream, dest, length=1024 * 256)
        with open(tmp.name, "rb") as fh:
            return store.add_blob(
                fh, name=_bundle_name(), mime="application/zip", origin=origin
            )
    finally:
        Path(tmp.name).unlink(missing_ok=True)


def _bundle_name() -> str:
    return datetime.datetime.now().strftime("bucket-%Y%m%d-%H%M%S.zip")


def _unique_entry_name(filename: str, taken: set[str]) -> str:
    """A zip-local name: client paths flattened, duplicates numbered the same
    way the app's own download path numbers files (`name (1).ext`)."""
    base = (filename or "").replace("\\", "/").split("/")[-1].strip() or "file"
    if base not in taken:
        return base
    stem, dot, ext = base.rpartition(".")
    if not dot:
        stem, ext = base, ""
    n = 1
    while True:
        candidate = f"{stem} ({n}){dot + ext if dot else ''}"
        if candidate not in taken:
            return candidate
        n += 1


def detect_lan_ip() -> str:
    """Best-effort local address. Opening a UDP socket to a public address
    makes the OS pick the interface it would actually route through, without
    sending a packet."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Bucket: LAN drop box")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data",
    )
    parser.add_argument(
        "--origin",
        default=None,
        help="Public origin for pairing links, e.g. https://laptop.ts.net",
    )
    parser.add_argument(
        "--log-file",
        type=Path,
        default=None,
        help="Write console output and request logs to this file instead of "
        "the terminal. Auto-enabled when there is no console at all "
        "(pythonw, the headless launcher).",
    )
    args = parser.parse_args()

    log_path = args.log_file
    if log_path is None and (sys.stdout is None or sys.stderr is None):
        # Started with pythonw: no console exists, so default to a log file
        # next to the data dir. The pairing code and request history then
        # survive in server.log instead of vanishing with the window.
        log_path = Path(__file__).resolve().parent.parent / "server.log"
    if log_path is not None:
        _redirect_stdio(log_path)

    console.setup()

    lan_ip = detect_lan_ip()
    origin = args.origin or f"http://{lan_ip}:{args.port}"
    app = create_app(args.data_dir, origin)

    code = app.config["PAIRING"].issue()
    pair_url = f"{origin}/pair?code={code.code}"
    console.banner(origin, args.port, pair_url, code.code, https=origin.startswith("https://"))
    console.configure_logging()

    # Serve via make_server rather than app.run so we hold the server object:
    # /api/shutdown needs it to stop cleanly, and werkzeug 3.x no longer
    # exposes a shutdown hook through the request environ.
    server = make_server(
        args.host,
        args.port,
        app,
        threaded=True,
        request_handler=console.RequestHandler,
    )
    app.config["SERVER"] = server
    server.log_startup()
    server.serve_forever()  # returns on Ctrl+C or /api/shutdown
    console.bye()


def _redirect_stdio(path: Path) -> None:
    """Point stdout/stderr at an append-only log file.

    Runs when the server has no console (pythonw under the headless launcher)
    or --log-file was passed. console.setup() then sees non-TTY streams and
    falls back to the plain banner; werkzeug's request logs land in the same
    file because configure_logging() builds its StreamHandler after this.
    """
    stream = open(path, "a", encoding="utf-8", errors="replace", buffering=1)
    sys.stdout = stream
    sys.stderr = stream


if __name__ == "__main__":
    main()
