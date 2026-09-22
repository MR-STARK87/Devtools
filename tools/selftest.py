"""End-to-end exercise of the bucket server against Flask's test client."""

from __future__ import annotations

import io
import json
import shutil
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from bucket.server import create_app  # noqa: E402
from bucket import store as store_mod  # noqa: E402

DATA = ROOT / "data_test"

PASS, FAIL = [], []


def check(name: str, ok: bool, detail: str = "") -> None:
    (PASS if ok else FAIL).append(name)
    print(f"  [{'ok' if ok else 'FAIL'}] {name}" + (f"  -> {detail}" if detail and not ok else ""))


def main() -> int:
    if DATA.exists():
        shutil.rmtree(DATA)

    app = create_app(DATA, "http://127.0.0.1:8765")
    pairing = app.config["PAIRING"]

    print("\n-- auth --")
    # A remote (non-localhost) client with no token must be refused.
    remote = {"environ_base": {"REMOTE_ADDR": "192.168.1.55"}}
    r = app.test_client().get("/api/items", **remote)
    check("unpaired remote gets 401", r.status_code == 401, str(r.status_code))

    # Localhost is trusted implicitly.
    r = app.test_client().get("/api/items")
    check("localhost reads without pairing", r.status_code == 200, str(r.status_code))

    # Remote cannot mint pairing codes.
    r = app.test_client().post("/api/pairing/code", **remote)
    check("remote cannot mint codes", r.status_code == 403, str(r.status_code))

    print("\n-- pairing --")
    code = pairing.issue().code
    phone = app.test_client()
    r = phone.post("/api/pair", json={"code": "000000" if code != "000000" else "111111"},
                   **remote)
    check("wrong code rejected", r.status_code == 403, str(r.status_code))

    r = phone.post("/api/pair", json={"code": code}, **remote)
    check("correct code pairs", r.status_code == 200, str(r.status_code))
    check("token cookie set", any(c.key == "bucket_token" for c in phone.cookie_jar)
          if hasattr(phone, "cookie_jar") else "bucket_token" in r.headers.get("Set-Cookie", ""))

    r = phone.post("/api/pair", json={"code": code}, **remote)
    check("code is single use", r.status_code == 403, str(r.status_code))

    r = phone.get("/api/items", **remote)
    check("paired phone reads items", r.status_code == 200, str(r.status_code))

    print("\n-- pc controls --")
    # The server-controls card (pair code + stop button) must exist only for
    # the machine itself; a paired phone gets the drop box without it.
    pc = app.test_client()
    body = pc.get("/").get_data(as_text=True)
    check("localhost page renders pair card", 'id="pairCode"' in body and 'id="stopServer"' in body)
    body = phone.get("/", **remote).get_data(as_text=True)
    check("phone page is paired view", 'data-paired="yes"' in body, body[:200])
    check(
        "phone page hides server controls",
        'id="pairCode"' not in body and 'id="stopServer"' not in body,
    )

    r = phone.post("/api/shutdown", **remote)
    check("phone cannot stop server", r.status_code == 403, str(r.status_code))
    r = pc.post("/api/shutdown")
    stopped = r.get_json()["stopped"] if r.is_json else None
    check(
        "localhost shutdown no-ops under test client",
        r.status_code == 200 and stopped is False,
        r.get_data(as_text=True),
    )

    print("\n-- items --")
    pc = app.test_client()
    r = pc.post("/api/items", json={"text": "hello from the pc"})
    check("pc posts text", r.status_code == 201, str(r.status_code))
    text_id = r.get_json()["items"][0]["id"]
    check("text origin is pc", r.get_json()["items"][0]["origin"] == "pc")

    r = phone.post("/api/items", data={"text": "hello from the phone"}, **remote)
    check("phone posts text", r.status_code == 201, str(r.status_code))
    check("phone origin is phone", r.get_json()["items"][0]["origin"] == "phone")

    png = bytes.fromhex(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
        "890000000a49444154789c6360000002000100ffff03000006000557bfabd400"
        "00000049454e44ae426082"
    )
    r = pc.post(
        "/api/items",
        data={"files": (io.BytesIO(png), "shot.png", "image/png")},
        content_type="multipart/form-data",
    )
    check("image upload accepted", r.status_code == 201, str(r.status_code))
    img = r.get_json()["items"][0]
    check("image classified as image", img["kind"] == "image", img["kind"])
    check("image size recorded", img["size"] == len(png), str(img["size"]))

    r = phone.get(f"/api/items/{img['id']}/raw", **remote)
    check("phone downloads image bytes", r.data == png, f"{len(r.data)} bytes")
    check("image mime preserved", r.mimetype == "image/png", r.mimetype)

    r = pc.post(
        "/api/items",
        data={"files": (io.BytesIO(b"col1,col2\n1,2\n"), "data.csv", "text/csv")},
        content_type="multipart/form-data",
    )
    check("file upload accepted", r.status_code == 201, str(r.status_code))
    check("csv classified as file", r.get_json()["items"][0]["kind"] == "file")

    r = pc.get(f"/api/items/{text_id}/raw")
    check("text raw returns full body", r.data == b"hello from the pc", r.data[:40])

    print("\n-- long text truncation --")
    long_text = "x" * 9000
    r = pc.post("/api/items", json={"text": long_text})
    long_id = r.get_json()["items"][0]["id"]
    listed = next(i for i in pc.get("/api/items").get_json()["items"] if i["id"] == long_id)
    check("preview truncated in list", len(listed["body"]) == 4096, str(len(listed["body"])))
    check("truncated flag set", listed["truncated"] is True)
    raw = pc.get(f"/api/items/{long_id}/raw").data.decode()
    check("raw returns untruncated text", len(raw) == 9000, str(len(raw)))

    print("\n-- share target --")
    r = phone.post(
        "/share",
        data={
            "text": "shared from android",
            "files": (io.BytesIO(png), "share.png", "image/png"),
        },
        content_type="multipart/form-data",
        **remote,
    )
    check("share target redirects", r.status_code == 302, str(r.status_code))
    check("share reports 2 items", "shared=2" in r.headers.get("Location", ""),
          r.headers.get("Location", ""))

    print("\n-- batch mode --")
    r = app.test_client().get("/api/batch-mode", **remote)
    check("batch mode needs auth", r.status_code == 401, str(r.status_code))
    check("batch mode defaults to ask",
          pc.get("/api/batch-mode").get_json() == {"mode": "ask"},
          json.dumps(pc.get("/api/batch-mode").get_json()))
    check("phone reads batch mode",
          phone.get("/api/batch-mode", **remote).get_json() == {"mode": "ask"})
    r = phone.put("/api/batch-mode", json={"mode": "banana"}, **remote)
    check("bad mode rejected", r.status_code == 400, str(r.status_code))
    r = phone.put("/api/batch-mode", json={"mode": "zip"}, **remote)
    check("phone sets batch mode",
          r.status_code == 200 and r.get_json() == {"mode": "zip"},
          r.get_data(as_text=True))
    check("mode is shared with pc",
          pc.get("/api/batch-mode").get_json() == {"mode": "zip"})

    def upload(files, **kw):
        return pc.post("/api/items", data={"files": files, **kw},
                       content_type="multipart/form-data")

    # Fresh streams every call: the test client drains them, so reusing one
    # BytesIO across posts would upload empty bodies the second time round.
    def trio():
        return [(io.BytesIO(b"AAA"), "a.txt", "text/plain"),
                (io.BytesIO(b"BBB"), "a.txt", "text/plain"),
                (io.BytesIO(b"CCC"), "pic.png", "image/png")]

    r = pc.post("/api/items",
                data={"bundle": "zip", "text": "caption", "files": trio()},
                content_type="multipart/form-data")
    got = r.get_json()["items"]
    check("bundle returns zip plus text", len(got) == 2
          and {i["kind"] for i in got} == {"file", "text"}, json.dumps(got))
    bundled = next(i for i in got if i["kind"] == "file")
    check("bundle mime is zip", bundled["mime"] == "application/zip", bundled["mime"])
    check("bundle name is dated zip", bundled["name"].startswith("bucket-")
          and bundled["name"].endswith(".zip"), bundled["name"])
    raw = pc.get(f"/api/items/{bundled['id']}/raw").data
    names = zipfile.ZipFile(io.BytesIO(raw)).namelist()
    check("duplicate names deduped", names == ["a.txt", "a (1).txt", "pic.png"],
          str(names))
    with zipfile.ZipFile(io.BytesIO(raw)) as zf:
        bodies = [zf.read(n) for n in names]
    check("bundle bytes intact", bodies == [b"AAA", b"BBB", b"CCC"], str(bodies))
    check("no bundle temp files leak",
          not [p for p in (DATA / "blobs").iterdir() if p.suffix == ".tmp"],
          str([p.name for p in (DATA / "blobs").iterdir()]))

    r = upload((io.BytesIO(b"ZZZ"), "solo.txt", "text/plain"), bundle="zip")
    solo = r.get_json()["items"]
    check("lone file with bundle flag stays single",
          len(solo) == 1 and solo[0]["name"] == "solo.txt", json.dumps(solo))

    r = upload(trio())
    check("no bundle flag means separate items",
          len(r.get_json()["items"]) == 3, json.dumps(r.get_json()["items"]))

    r = phone.post("/share", data={"bundle": "zip", "files": trio()},
                   content_type="multipart/form-data", **remote)
    check("share target bundles on request", "shared=1" in r.headers.get("Location", ""),
          r.headers.get("Location", ""))

    body = pc.get("/").get_data(as_text=True)
    check("page carries batch switch", 'id="batchAsk"' in body
          and 'id="batchZip"' in body and 'id="batchSep"' in body)
    check("page carries batch dialog", 'id="batchVeil"' in body
          and 'id="batchRemember"' in body)

    pc.put("/api/batch-mode", json={"mode": "ask"})

    print("\n-- sse --")
    broker = app.config["BROKER"]
    sub = broker.subscribe()
    pc.post("/api/items", json={"text": "push me"})
    got = sub.get(timeout=2.0)
    check("sse frame published", "item:add" in got and "push me" in got, got[:80])

    # A client that stops draining must be dropped, not grow unbounded.
    for _ in range(store_mod.MAX_ITEMS + 100):
        broker.publish("noise", {"n": 1})
    check("slow subscriber dropped", sub not in broker._subscribers)

    print("\n-- delete + retention --")
    before = len(pc.get("/api/items").get_json()["items"])
    r = pc.delete(f"/api/items/{text_id}")
    check("delete returns ok", r.status_code == 200, str(r.status_code))
    after = len(pc.get("/api/items").get_json()["items"])
    check("item count dropped by one", after == before - 1, f"{before} -> {after}")
    check("deleted item 404s", pc.get(f"/api/items/{text_id}/raw").status_code == 404)

    print("\n-- blob cleanup --")
    r = pc.post(
        "/api/items",
        data={"files": (io.BytesIO(png), "gone.png", "image/png")},
        content_type="multipart/form-data",
    )
    doomed = r.get_json()["items"][0]["id"]
    blob_files = {p.name for p in (DATA / "blobs").iterdir()}
    check("blob written to disk", any(doomed in n for n in blob_files))
    pc.delete(f"/api/items/{doomed}")
    blob_files = {p.name for p in (DATA / "blobs").iterdir()}
    check("blob removed on delete", not any(doomed in n for n in blob_files))

    print("\n-- retention cap --")
    original = store_mod.MAX_ITEMS
    store_mod.MAX_ITEMS = 5
    try:
        for i in range(12):
            pc.post("/api/items", json={"text": f"item {i}"})
        remaining = pc.get("/api/items").get_json()["items"]
        check("count capped at MAX_ITEMS", len(remaining) == 5, str(len(remaining)))
        check("newest survives", remaining[0]["body"] == "item 11", remaining[0]["body"])
    finally:
        store_mod.MAX_ITEMS = original

    print("\n-- device revocation --")
    devices = pc.get("/api/devices").get_json()["devices"]
    check("device listed", len(devices) == 1, str(len(devices)))
    pc.delete(f"/api/devices/{devices[0]['id']}")
    r = phone.get("/api/items", **remote)
    check("revoked device gets 401", r.status_code == 401, str(r.status_code))

    print("\n-- clear --")
    r = pc.post("/api/items/clear")
    check("clear returns count", r.get_json()["removed"] > 0, json.dumps(r.get_json()))
    check("bucket empty after clear", pc.get("/api/items").get_json()["items"] == [])
    check("blobs dir empty after clear", not list((DATA / "blobs").iterdir()))

    print("\n-- static --")
    for path, expect in [
        ("/manifest.webmanifest", 200),
        ("/sw.js", 200),
        ("/static/app.css", 200),
        ("/static/app.js", 200),
        ("/static/icon-192.png", 200),
        ("/static/icon-512.png", 200),
        ("/static/icon-maskable.png", 200),
    ]:
        check(f"{path} serves", pc.get(path).status_code == expect)

    manifest = json.loads(pc.get("/manifest.webmanifest").data)
    check("manifest declares share_target", "share_target" in manifest)
    check("share action matches route", manifest["share_target"]["action"] == "/share")

    print(f"\n{len(PASS)} passed, {len(FAIL)} failed")
    if FAIL:
        print("failed: " + ", ".join(FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
