"""Storage layer: SQLite index over a blob directory."""

from __future__ import annotations

import shutil
import sqlite3
import threading
import time
import uuid
from pathlib import Path

MAX_ITEMS = 200
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024

SCHEMA = """
CREATE TABLE IF NOT EXISTS items (
    id          TEXT PRIMARY KEY,
    kind        TEXT NOT NULL CHECK (kind IN ('text', 'image', 'file')),
    name        TEXT NOT NULL,
    mime        TEXT NOT NULL,
    size        INTEGER NOT NULL,
    body        TEXT,
    blob_path   TEXT,
    origin      TEXT NOT NULL,
    created_at  REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS items_created_at ON items (created_at DESC);

CREATE TABLE IF NOT EXISTS devices (
    id          TEXT PRIMARY KEY,
    label       TEXT NOT NULL,
    token_hash  TEXT NOT NULL UNIQUE,
    created_at  REAL NOT NULL,
    last_seen   REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS devices_token_hash ON devices (token_hash);

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


class Store:
    """Thread-safe store. One connection guarded by a lock.

    Flask serves requests from multiple threads, and SSE streams hold a
    request open for hours, so a per-request connection would leak. A single
    serialized connection is plenty for one user on a LAN.
    """

    def __init__(self, root: Path) -> None:
        self.root = root
        self.blobs = root / "blobs"
        self.blobs.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._db = sqlite3.connect(root / "bucket.db", check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._lock:
            self._db.executescript(SCHEMA)
            self._db.commit()

    # --- items ---------------------------------------------------------

    def add_text(self, body: str, origin: str) -> dict:
        preview = body.strip().splitlines()[0] if body.strip() else "empty"
        return self._insert(
            kind="text",
            name=preview[:80] or "text",
            mime="text/plain",
            size=len(body.encode("utf-8")),
            body=body,
            blob_path=None,
            origin=origin,
        )

    def add_blob(self, stream, name: str, mime: str, origin: str) -> dict:
        """Persist an uploaded stream, then index it.

        The blob is written before the row exists so a crash mid-write leaves
        an orphan file rather than a row pointing at nothing.
        """
        item_id = uuid.uuid4().hex
        suffix = Path(name).suffix[:16]
        path = self.blobs / f"{item_id}{suffix}"
        with path.open("wb") as fh:
            shutil.copyfileobj(stream, fh, length=1024 * 256)
        size = path.stat().st_size
        kind = "image" if mime.startswith("image/") else "file"
        return self._insert(
            kind=kind,
            name=name,
            mime=mime,
            size=size,
            body=None,
            blob_path=path.name,
            origin=origin,
            item_id=item_id,
        )

    def _insert(
        self,
        *,
        kind: str,
        name: str,
        mime: str,
        size: int,
        body: str | None,
        blob_path: str | None,
        origin: str,
        item_id: str | None = None,
    ) -> dict:
        item_id = item_id or uuid.uuid4().hex
        row = {
            "id": item_id,
            "kind": kind,
            "name": name,
            "mime": mime,
            "size": size,
            "body": body,
            "blob_path": blob_path,
            "origin": origin,
            "created_at": time.time(),
        }
        with self._lock:
            self._db.execute(
                "INSERT INTO items (id, kind, name, mime, size, body, blob_path,"
                " origin, created_at) VALUES (:id, :kind, :name, :mime, :size,"
                " :body, :blob_path, :origin, :created_at)",
                row,
            )
            self._db.commit()
        self._enforce_retention()
        return self.get(item_id)

    def list(self, limit: int = 100) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM items ORDER BY created_at DESC LIMIT ?", (limit,)
            ).fetchall()
        return [self._public(r) for r in rows]

    def get(self, item_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM items WHERE id = ?", (item_id,)
            ).fetchone()
        return self._public(row) if row else None

    def raw_text(self, item_id: str) -> str | None:
        """Full, untruncated body. `get` returns the capped preview shape, so
        anything that serves real content must come through here."""
        with self._lock:
            row = self._db.execute(
                "SELECT body FROM items WHERE id = ?", (item_id,)
            ).fetchone()
        if row is None:
            return None
        return row["body"] or ""

    def blob_for(self, item_id: str) -> tuple[Path, str, str] | None:
        with self._lock:
            row = self._db.execute(
                "SELECT blob_path, mime, name FROM items WHERE id = ?", (item_id,)
            ).fetchone()
        if row is None or not row["blob_path"]:
            return None
        path = self.blobs / row["blob_path"]
        if not path.exists():
            return None
        return path, row["mime"], row["name"]

    def delete(self, item_id: str) -> bool:
        with self._lock:
            row = self._db.execute(
                "SELECT blob_path FROM items WHERE id = ?", (item_id,)
            ).fetchone()
            if row is None:
                return False
            self._db.execute("DELETE FROM items WHERE id = ?", (item_id,))
            self._db.commit()
        self._unlink_blob(row["blob_path"])
        return True

    def clear(self) -> int:
        with self._lock:
            rows = self._db.execute("SELECT blob_path FROM items").fetchall()
            count = self._db.execute("SELECT COUNT(*) c FROM items").fetchone()["c"]
            self._db.execute("DELETE FROM items")
            self._db.commit()
        for row in rows:
            self._unlink_blob(row["blob_path"])
        return count

    def _enforce_retention(self) -> None:
        """Drop oldest items past the count cap or the byte budget."""
        with self._lock:
            rows = self._db.execute(
                "SELECT id, blob_path, size FROM items ORDER BY created_at DESC"
            ).fetchall()

        doomed, running = [], 0
        for index, row in enumerate(rows):
            running += row["size"]
            if index >= MAX_ITEMS or running > MAX_TOTAL_BYTES:
                doomed.append(row)

        if not doomed:
            return
        with self._lock:
            self._db.executemany(
                "DELETE FROM items WHERE id = ?", [(r["id"],) for r in doomed]
            )
            self._db.commit()
        for row in doomed:
            self._unlink_blob(row["blob_path"])

    def _unlink_blob(self, blob_path: str | None) -> None:
        if not blob_path:
            return
        (self.blobs / blob_path).unlink(missing_ok=True)

    @staticmethod
    def _public(row: sqlite3.Row) -> dict:
        """Shape a row for the client. Text bodies are capped so a huge paste
        does not bloat every list response; the full body stays downloadable."""
        body = row["body"]
        truncated = False
        if body is not None and len(body) > 4096:
            body, truncated = body[:4096], True
        return {
            "id": row["id"],
            "kind": row["kind"],
            "name": row["name"],
            "mime": row["mime"],
            "size": row["size"],
            "body": body,
            "truncated": truncated,
            "origin": row["origin"],
            "created_at": row["created_at"],
        }

    # --- devices -------------------------------------------------------

    def add_device(self, label: str, token_hash: str) -> str:
        device_id = uuid.uuid4().hex
        now = time.time()
        with self._lock:
            self._db.execute(
                "INSERT INTO devices (id, label, token_hash, created_at, last_seen)"
                " VALUES (?, ?, ?, ?, ?)",
                (device_id, label, token_hash, now, now),
            )
            self._db.commit()
        return device_id

    def device_by_token_hash(self, token_hash: str) -> dict | None:
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM devices WHERE token_hash = ?", (token_hash,)
            ).fetchone()
        return dict(row) if row else None

    def touch_device(self, device_id: str) -> None:
        with self._lock:
            self._db.execute(
                "UPDATE devices SET last_seen = ? WHERE id = ?",
                (time.time(), device_id),
            )
            self._db.commit()

    def list_devices(self) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT id, label, created_at, last_seen FROM devices"
                " ORDER BY created_at"
            ).fetchall()
        return [dict(r) for r in rows]

    def delete_device(self, device_id: str) -> bool:
        with self._lock:
            cur = self._db.execute("DELETE FROM devices WHERE id = ?", (device_id,))
            self._db.commit()
        return cur.rowcount > 0

    # --- settings ----------------------------------------------------

    def get_setting(self, key: str, default: str | None = None) -> str | None:
        """Tiny key/value store for account-wide preferences (batch_mode).
        Single-user LAN box, so one global value is enough — no per-device
        table, which could not cover the PC page anyway (loopback is a
        synthetic device with no DB row)."""
        with self._lock:
            row = self._db.execute(
                "SELECT value FROM settings WHERE key = ?", (key,)
            ).fetchone()
        return row["value"] if row else default

    def set_setting(self, key: str, value: str) -> None:
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
                (key, value),
            )
            self._db.commit()
