# CLAUDE.md

## What this is

Bucket is a private LAN drop box between one PC and paired phones. Text, screenshots, and files move directly over the local network; nothing is synced or leaves the machine. The server is a single-user Flask app serving a PWA; a small native Android client (WebView wrapper + share-sheet activity) is built without Gradle.

Dictate is a sibling tool in the same repo: speak into the phone's Gboard voice typing and the words are typed into whatever window is focused on the PC, over the LAN. Same pairing/trust model, no speech recognition of its own. Optional "Rambler" cleanup calls Ollama Cloud to tighten the transcript once at the end.

Source of truth for behaviour and rationale: `README.md` plus the in-code comments (they explain *why*, not just *what*).

## Commands

All commands run from the project root.

```powershell
.\run.ps1                                  # headless Bucket on :8765 (PowerShell only): no console, server.log, opens the browser
.\run.cmd                                  # same, but works in cmd AND PowerShell, any execution policy
.\run_debug.cmd / .\run_debug.ps1          # visible-terminal Bucket: banner + live request log, stops when window closes
.\run_dictate.cmd / .\run_dictate.ps1      # starts Dictate server on :8766
.venv\Scripts\python.exe tools\selftest.py # Bucket checks against Flask's test client (no server needed)
.venv\Scripts\python.exe tools\selftest_dictate.py  # Dictate checks (fake typer, no keystrokes)
bash tests\pairing_flow.sh                 # real-HTTP pairing walkthrough; server must be running
.venv\Scripts\python.exe tools\make_icons.py  # regenerate Bucket PWA + Android icons
.venv\Scripts\python.exe tools\make_dictate_icons.py  # regenerate Dictate launcher icons
.\android\build_apk.ps1                    # build + sign android\bucket.apk (needs JDK + Android SDK)
.\android-dictate\build_apk.ps1            # build + sign android-dictate\dictate.apk
```

There is no linter, formatter, or typechecker configured. `requirements.txt` pins `flask==3.1.0` and `qrcode==8.0` only.

## How to run

- `python -m bucket.server` — defaults: host `0.0.0.0`, port `8765`, data dir `<repo>/data` (see `main()` in `bucket/server.py:346`). `--log-file` forces file logging; it also auto-engages when stdout/stderr are absent (pythonw). Use `python -X utf8 -u -m bucket.server` if console output garbles.
- `python -m dictate.server` — defaults: host `0.0.0.0`, port `8766`, data dir `<repo>/dictate-data` (`dictate/server.py:258`, `DEFAULT_PORT = 8766`).
- `.\run.ps1` / `.\run.cmd` set `PYTHONPATH=<repo>` and pass `--origin $env:BUCKET_ORIGIN` when set (used for Tailscale TLS: QR encodes the HTTPS name). Both are **headless by default**: they launch the server with `pythonw` (no console at all), which writes its own plain-text output to `<repo>/server.log`, then auto-open `http://localhost:8765` in the default browser and exit — the page's local-only Stop-server card is how you shut it down. `run.cmd` is the shell-agnostic launcher; `run.ps1` is PowerShell-only. `run_debug.cmd` / `run_debug.ps1` keep the old visible-terminal behavior (painted banner + live request log; closing the window stops the server). First run creates `.venv`, installs `requirements.txt`, and runs `tools/make_icons.py`.
- `.\run_dictate.ps1` / `.\run_dictate.cmd` do the same for Dictate, using `$env:DICTATE_ORIGIN` / `--origin` and defaulting to `<repo>/dictate-data` when unset.
- On startup each server mints a pairing code and prints a QR. The code is 6 digits, valid 2 minutes (`PAIRING_TTL = 120.0` in `bucket/auth.py:25`), single use. Bucket's banner is the painted ANSI banner from `bucket/console.py`; Dictate's is a plain `qrcode.print_ascii` banner (`dictate/server.py:303`). Headless Bucket (`run.cmd`) prints the plain banner into `server.log` instead, and the PC page's Pair-a-phone card shows a self-renewing code, so a terminal is never needed.
- `detect_lan_ip()` (`bucket/server.py:332`, `dictate/server.py:246`) picks the LAN address by opening a UDP socket to `8.8.8.8:80` (no packet is sent). `--origin` overrides it for Tailscale (`https://<machine>.<tailnet>.ts.net`).
- Rambler (Dictate only) is configured via `<repo>/.env` — `OLLAMA_API_KEY`, `OLLAMA_MODEL` (default `gpt-oss:20b`), `OLLAMA_HOST` (default `https://ollama.com`). See `.env.example`. `dictate/rambler.py` re-reads `.env` on every `get_config()` call so editing the file without restarting takes effect.

## Architecture

### Server (`bucket/`)

`bucket/server.py` — Flask app factory `create_app(data_dir: Path, origin: str)`. Apps keep their collaborators in `app.config`: `STORE`, `BROKER`, `PAIRING`, `ORIGIN`. Unit tests build an app the same way (against `data_test/`). `main()` serves via werkzeug's `make_server` (not `app.run`) so it holds the server object — it is stashed on `app.config["SERVER"]` for `POST /api/shutdown` — and `console.RequestHandler` is passed through. `VERSIONED_ASSETS = ("app.css","app.js")` hashed together with `index.html` for the service-worker version.

`bucket/store.py` — SQLite index over a blob directory. A single connection guarded by one `threading.Lock` (SSE holds requests open for hours, so per-request connections would leak). Blobs are written **before** the row exists (`shutil.copyfileobj` in 256 KiB chunks) so a crash leaves an orphan file, never a dangling index entry. Suffix is `Path(name).suffix[:16]`.

`bucket/auth.py` — pairing codes and request guards. Tokens are stored only as SHA-256 hashes (`hash_token`). `Pairing` holds at most one live code; `issue()` voids the previous one; `redeem()` uses `hmac.compare_digest` and is single-use.

`bucket/events.py` — SSE fan-out (`Broker`). Per-subscriber bounded queue (`QUEUE_DEPTH = 64`); a stalled client is dropped, not given unbounded memory. Stream yields `retry: 2000` then heartbeats (`: ping`) every `HEARTBEAT_SECONDS = 20.0` to keep sleeping phones/proxies from reaping the connection.

`bucket/__init__.py` — re-exports `create_app` (lazy import from `server` to avoid `RuntimeWarning` on `python -m bucket.server`).

### API — Bucket (port 8765)

| Method & path | Auth | Notes |
|---|---|---|
| `GET /` | — | renders `index.html`; `paired` depends on `authenticate()`, and `local` (loopback viewer) decides whether the server-controls card renders |
| `GET /manifest.webmanifest`, `GET /sw.js` | — | `sw.js` served from root for full-origin scope; `Cache-Control: no-cache` |
| `POST /api/pairing/code` | local | mints a code (returns `code`, `expires_at`, `url`) |
| `GET /api/pairing/code` | local | peek at the current code (`{"code": null}` when none) |
| `GET /pair?code=...` | — | link pairing; sets cookie, redirects to `/` (renders `paired.html` on failure) |
| `POST /api/pair` | — | API pairing; sets cookie |
| `GET/DELETE /api/devices` | local | list / revoke devices |
| `GET /api/items` | device | newest first, text previews capped at 4096 |
| `POST /api/items` | device | multipart `files` and/or `text`, or JSON `{text}`; `origin` is `pc` vs `phone` via `g.device`; `bundle=zip` with 2+ files stores one dated `.zip` item (`store_bundle`), text still stays its own item |
| `GET /api/items/<id>/raw` | device | full bytes / full text (`?download=1` forces attachment; text uses `_safe_download_name`) |
| `DELETE /api/items/<id>` | device | deletes row + blob, publishes `item:remove` |
| `POST /api/items/clear` | device | clears everything, publishes `item:reset` |
| `GET /api/batch-mode` | device | the shared multi-file answer: `{"mode": "ask"|"zip"|"separate"}` (default `ask`) |
| `PUT /api/batch-mode` | device | JSON `{"mode"}` flips the same answer for PC page, phone page, and native share sheet |
| `POST /share` | device | Android share-target; accepts `title`+`text`+`url`+`files`, redirects to `/?shared=N`; honors `bundle=zip` the same way `/api/items` does |
| `GET /api/events` | device | SSE `item:add`, `item:remove`, `item:reset` (`Cache-Control: no-cache`, `X-Accel-Buffering: no`) |
| `GET /api/health` | none | `{"ok":true,"origin":...}`; used by Android discovery |
| `POST /api/shutdown` | local | graceful stop behind the page's Stop-server card; no-op under the test client |

`MAX_UPLOAD_BYTES = 512MB` (`bucket/server.py:29`) sets Flask's `MAX_CONTENT_LENGTH`. There is **no custom 413 handler**; the JS relies on the status code to show "File too large".

### Dictate server (`dictate/`)

`dictate/server.py` — Flask app factory `create_app(data_dir: Path, origin: str, typer: Typer | None)`. Reuses `bucket.auth` (`Pairing`, `pair_device`, `hash_token`, `COOKIE_NAME`, `require_local`/`require_device`) and `bucket.store.Store` (devices table) verbatim — `authenticate()` reads `app.config["STORE"]`, so the config key must exist even though Dictate stores no items. Takes an injected `typer` so tests can stub the keystroke layer. `no_cache_static` sends `Cache-Control: no-cache` for `/` and `/static/*` (no service worker; WebView cache is not trusted).

`dictate/session.py` — the armed session. Rolling 30 s timeout (`ARM_TIMEOUT = 30.0`): `arm()` and every `type_text()` extend it, so a lost phone can never keep injecting keystrokes. `type_text(text, seq)` diffs the new value against the session buffer via common prefix/suffix (`_split`) — the middle is backspaced and retyped. `seq` is a client wall-clock timestamp; older requests are ignored (stale). `MAX_TEXT_CHARS = 50000`. Holds its own `threading.Lock` across the `typer` calls, serializing concurrent phone requests.

`dictate/typer.py` — Windows `SendInput` with `KEYEVENTF_UNICODE` (bypasses keyboard layout; clipboard untouched). `INPUT` union is sized via `MOUSEINPUT` (40 bytes on x64) or `SendInput` fails with `ERROR_INVALID_PARAMETER`. Typing is refused while a modifier (Shift/Ctrl/Alt/LWin/RWin) is physically held (`GetAsyncKeyState`), because `SendInput` would apply it. `per_char_delay = 0.002` s.

`dictate/rambler.py` — speech-to-thought cleanup via Ollama Cloud (`https://ollama.com/api/chat`, no local model). `get_config()` re-reads `.env` on every call; placeholders (`your_api_key_here`, `placeholder`, `changeme`, `xxx`, empty, or ≤10 chars) count as not configured. `clean_text` truncates input at 12 000 chars, posts `{model, messages:[system,user], stream:false, options:{temperature:0.1}}` with `Authorization: Bearer <key>`, expects `{"message":{"content":...}}` (falls back to `response`/`content`), strips surrounding quotes, and raises `RuntimeError` on HTTP/URL errors. `SYSTEM_PROMPT` enforces 8 rules: strip filler/false starts, collapse repetition, reorganize for flow, tighten prose, preserve intent, preserve voice, paragraphs only, no meta-commentary.

Dictate API (port 8766):

| Method & path | Auth | Notes |
|---|---|---|
| `GET /` | — | phone UI; pairing gate when unpaired |
| `POST/GET /api/pairing/code`, `GET /pair?code=`, `POST /api/pair`, `GET/DELETE /api/devices` | as Bucket | same shapes as Bucket (`/pair` link returns JSON error, not `paired.html`) |
| `POST /api/arm` | device | extends the armed window; returns `armed_until` |
| `POST /api/text` | device | `{text, seq, rambler?}`; types the diff; `409 disarmed`, `400 modifiers/too long`; when `rambler:true` cleans via Ollama first (never holds session lock during network call), returns `{cleaned, rambler:true}` |
| `POST /api/disarm`, `GET /api/status` | device | status: `{armed, armed_until, buffer_len, rambler_available, rambler_model}` |
| `GET /api/rambler` | device | `{available, model, host}` |
| `GET /api/health` | none | `{ok, origin, armed, rambler_available}`; Android discovery |

### Authentication model

- Localhost (`127.0.0.1` / `::1`) is trusted implicitly (`authenticate()` in `bucket/auth.py:90` returns the "local" device). This is a deliberate assumption — the PC already has filesystem access.
- `require_local` (`bucket/auth.py:117`) restricts code minting and device management to localhost so a paired phone cannot add more devices.
- Paired devices authenticate via the `bucket_token` cookie (HttpOnly, SameSite=Lax, `max_age` 1 year) or an `Authorization: Bearer <token>` header. Only the token hash is persisted; a stolen DB is not replayable.
- The cookie is set `secure=True` only when `ORIGIN` starts with `https://` (`_set_token_cookie`, `bucket/server.py:293`; `dictate/server.py:218` via `_origin_https()`) — on plain HTTP a Secure cookie would be silently dropped and pairing would break.
- `touch_device` updates `last_seen` on every authenticated request.

### Frontend — Bucket

- `bucket/static/app.js` — vanilla JS, no framework. `paired` comes from `<html data-paired>` attribute. Live view is a `Map` of items fed by SSE (`ingest()` dedupes by id) with a full refetch on reconnect / `visibilitychange` (phones suspend timers while backgrounded, so SSE dies). `load()` handles `401` by reloading (revoked). Copy fetches `/raw` for the full body (preview is truncated); falls back to hidden textarea + `execCommand` when `navigator.clipboard` needs a secure context. Drag uses `dragDepth` counting to avoid flicker from child `dragleave`. Toasts for `?shared=N`. "Clear all" asks via an in-page workbench dialog (`askConfirm()` in `app.js` + `.veil`/`.dialog` in `app.css`/`index.html`, promise-based, Esc/backdrop = cancel) instead of `window.confirm` — so the browser prompt never appears and phone/PC see the same card. `MainActivity.showClearConfirmation` (the old `onJsConfirm` native override) is now a dormant fallback: nothing calls `confirm()` anymore, but it stays in case one returns. Sending 2+ files with mode `ask` opens a second workbench dialog (`askBatch()`: Zip into one / Send separately / Cancel + Remember checkbox); the composer also carries an Ask/Zip/Separate switch bound to the same server mode, so a remembered choice is always reversible from the page.
- Composer height: on phones the composer is `position: fixed` bottom bar (see `bucket/static/app.css:964`). It is out of flow, so the feed reserves `var(--composer-h)` measured live via `ResizeObserver` on `el("drop")`; fallback is `186px`.
- `bucket/static/app.css` — Workbench rework: light concrete theme on `:root` (`--ground #EDEFF2`, `--surface #FFFFFF`, `--ink #0E1013`, `--signal #FF3B1F`, `--hazard #FFD400`, `--radius-lg 18px`, etc.), fonts `Space Grotesk` + `JetBrains Mono`. Layout is a grid (`360px` composer + feed) collapsing to flex column at `≤880px`. The origin shows as a text chip ("from pc"/"from phone") with a left rail accent (`pc` ink, `phone` signal) rather than the old amber/cyan rail. Defensive `[hidden] { display: none !important; }`. Touch targets 36 px (44 px on touch), shrunken under `@media (pointer: fine)`. Drag state adds hazard stripes and `backdrop-filter` overlay. Includes Bucky the bucket mascot (inline SVG in `index.html` + `BuckyView` in Android) with float/blink/sparkle animations.
- `bucket/templates/index.html` renders either the drop zone (`paired`) or the pairing gate, plus the `<template id="tpl-item">` and the `rail` with Bucky card. For a loopback viewer (`local`) the rail also carries the `opsCard`: a self-renewing six-digit pairing code and a Stop-server button (the headless launcher's only graceful shutdown), with a stopped-state `stoppedVeil` overlay for after the server exits. `bucket/templates/paired.html` is the link-pairing landing (`ok` via `?ok`).
- `manifest.webmanifest` declares the PWA `share_target` pointing at `POST /share` (`enctype multipart/form-data`, `params title/text/url/files` with `accept` image/video/audio/text/application).

### Frontend — Dictate

- `dictate/static/app.js` — phone-only, no service worker. On `focus`/`input` of `#mic` it `POST /api/arm` and posts the full current value raw (`doSend(text, false)`) — Gboard rewrites the field live, so the last value always wins. `seq = max(Date.now(), last+1)` keeps it monotonic across reloads. Live raw typing is never debounced; Rambler cleaning is explicit: the clean button appears only when the toggle is on, there is text, and not already cleaning, and sends `{text, seq, rambler:true}` once. Status pill + meter poll `/api/status` every second (plus `tickMeter` every 200 ms). The page is a stepper (`renderSteps()` mirrors paired/armed/rambler state into `data-state` marks only — it never gates the API, so tapping the transcript still auto-arms). QR scan prefers the app's native `ScanActivity` via the `DictateNative` JS bridge (`window.__dictateNativeScan` reuses `handleScanned`); the `BarcodeDetector`+`getUserMedia` path is a browser-only fallback, since WebView over plain-HTTP LAN is not a secure context and exposes neither API.
- `dictate/static/app.css` — Folio edition: cool gallery grays (`--desk #E8EBE3`, `--sheet #FFFFFF`, `--ink #181C22`) with one blue-pencil accent (`--pencil #1F36E0`); functional red (`--danger`) is errors only. Fonts `Newsreader` (display numerals/titles) + `Inter` (body) + `IBM Plex Mono` (data). Signature: oversized italic step numerals, newspaper folio slug, proofreader's caret as the live mark, manuscript transcript with a margin rule. Buttons are editorial rectangles with hard offset shadows.
- `dictate/templates/index.html` — folio masthead (slug + live caret), `#pairing` as step 01, `<ol id="dictate">` as steps 02 Arm (`#armBtn` explicit arm + `#disarm`), 03 Speak (`#mic` manuscript), 04 Polish (`#ramblerToggle`/`#ramblerClean` + model labels), Scan QR modal (`scanVideo`/`scanCanvas`/`scanFrame`), toast. All historic JS IDs are kept; `helpToggle`/`helpPanel` were dropped (JS guards their absence).

### Service worker (`bucket/templates/sw.js`)

Served from root so its scope covers the origin, and rendered with `version = hash(app.css + app.js + index.html)` (`_asset_version`, `bucket/server.py:278`). The hash makes every frontend edit produce a byte-different worker, so installed phones update without reinstallation. **Never** cache `/api/*` responses (live shared state) and never serve the worker from an HTTP cache (`Cache-Control: no-cache` in `bucket/server.py:67`) — stale copies strand updates. Shell is precached (`/`, `app.css`, `app.js`, `icon-192.png`); navigations are network-first with offline fallback to cached shell; static assets are cache-first with background refresh. Dictate has no service worker by design (`no_cache_static` revalidates instead).

### Terminal (`bucket/console.py`)

The startup banner, pairing QR, and request logs are painted with hand-rolled ANSI (24-bit color, half-block `▀▄` glyphs for the QR) — deliberately no `rich`/`colorama` dependency, so the venv stays at `flask+qrcode` and the pipeline is fully ours. `setup()` enables Windows VT processing via ctypes (colorama's trick without the import) and switches stdout to UTF-8; it is a no-op when stdout is not a TTY, when `NO_COLOR` is set, or `TERM=dumb` — the plain banner prints instead. `RequestHandler` subclasses werkzeug's `WSGIRequestHandler` (passed to `make_server` from `bucket/server.py:main`) to append a per-request duration; `_RequestFormatter` colors each line by status class (2xx green, 3xx amber, 4xx/5xx red, method cyan, path bright), dims non-request lines like ` * Running on ...`, and strips escape codes entirely when stdout is not a TTY so `server.log` stays plain. QR cells are amber on a dark slate (`INK_SOFT`). The terminal keeps the amber/cyan brand identity the web UI dropped in the monochrome rework — the terminal is the only colored surface left. Dictate's terminal (`dictate/server.py:285`) is intentionally plain (no ANSI) and prints `print_ascii(invert=True)` for the QR plus rambler config and a warning about focused-window typing.

### Storage layout

```
<data_dir>/                 # <repo>/data (Bucket) or <repo>/dictate-data (Dictate)
  bucket.db    SQLite: items (id, kind, name, mime, size, body, blob_path, origin, created_at)
               and devices (id, label, token_hash, created_at, last_seen)
  blobs/       one file per blob: <item_id><suffix>, suffix = original name's ext (≤16 chars)
```

Retention: newest `MAX_ITEMS = 200` items **or** `MAX_TOTAL_BYTES = 2GB`, whichever hits first, then the oldest are dropped (`_enforce_retention`, `bucket/store.py:191`). Text previews in list responses are capped at 4096 bytes with a `truncated` flag; the full body is only served by `/raw` (`raw_text`). A `settings` key/value table holds the account-wide `batch_mode` (`ask`/`zip`/`separate`, default `ask`); it is global, not per-device, because the loopback PC page has no device row. Dictate uses the same `Store` but stores no items — only the `devices` table — yet `app.config["STORE"]` must exist for `authenticate()`.

### Android client (`android/`)

Why a native app at all: install-to-home-screen and the web share_target both need a secure context, which a plain-HTTP LAN address is not. Wrapping the same page in a WebView gets both without TLS, and traffic still arrives from the phone's real LAN address (so the server's trust model is intact).

- `MainActivity.java` — WebView hosting the served page; adds file picking (`onShowFileChooser`) and downloads that a plain-HTTP browser tab cannot do. Downloads fetch in-app over `HttpURLConnection` with the pairing cookie attached (same pattern as the share upload) and write via `MediaStore.Downloads` on API 29+ (`ContentValues` + `DISPLAY_NAME`/`MIME_TYPE`) or the public Downloads dir on 24–28 (with `WRITE_EXTERNAL_STORAGE` request) and `MediaScannerConnection.scanFile`. Unique filenames via `uniqueFile` (` (1)`, …) . Deliberately **not** `DownloadManager`: on scoped storage several OEM builds silently fail to write its destination file. Also shows a custom `showClearConfirmation` dialog for `confirm()` (hazard stripe, workbench tokens) so the platform gray alert never appears.
- `ShareActivity.java` — `SEND`/`SEND_MULTIPLE` intent filter; uploads to `/api/items` directly over `HttpURLConnection` (multipart `boundary=----bucket<ms>`, `chunkedStreaming`, cookie attached, bytes streamed via `copy`). Resolves display names via `OpenableColumns.DISPLAY_NAME`. Never logs the cookie. Sharing 2+ files with mode `ask` shows a native choice dialog (Zip into one / Send separately / Cancel + Remember checkbox, resolved on a worker thread via `CountDownLatch`); the mode is read from the server when reachable and mirrored in `SharedPreferences` for offline shares, and a remembered choice is pushed back with `PUT /api/batch-mode`. When the remembered server is unreachable or unpaired it does **not** fail: `Outbox.store()` copies the bytes into `<filesDir>/outbox/<seq>/` (blob files first, `meta.json` written last — same blob-before-index crash contract as `Store`; a crash leaves a nameless dir swept on next scan; the resolved `bundle` choice rides in `meta.json` and is replayed on flush, so pre-update queued entries without the key flush as separate) and toasts "Saved for later". Content URIs cannot be deferred — their read grants die with the activity — so the copy at share time is mandatory, not an optimization.
- `Uploader.java` — the single multipart POST implementation for `/api/items` (`post(base, cookie, parts, text, zip)` with lazily-opened `Source` streams; `zip` adds the `bundle=zip` field), plus `cookieFor()` (null when unpaired) and `displayName()`. Both `ShareActivity` (direct path) and the outbox flush send byte-identical requests through it.
- `Outbox.java` — offline queue. `store()` caps the queue at `MAX_TOTAL_BYTES = 1GB` (refuse + toast when full, `copyBounded` enforces it during the copy); `flush(ctx, base)` walks entries oldest-first (`%013d` seq names sort lexically), re-reads the pairing cookie per entry, stops at the first failure (server gone or unpaired — order preserved), deletes only entries that got a 2xx. Unparseable/missing-blob entries are swept during scan, not retried forever.
- `MainActivity.java` — after discovery succeeds and the page loads, `flushOutbox()` runs on a worker thread and toasts the result ("Sent N queued items" / "N waiting — pair to send"). Never delays the feed. A share whose POST response is lost can arrive twice on retry — accepted duplicate risk, same as the direct path.
- `Server.java` — discovery: tries the remembered base URL (`SharedPreferences "bucket" / "base_url"`), else probes every host in the local `/24` on port 8765 (48 threads, awaits the first `200` on `/api/health` whose body contains `"ok"` and `"origin"`; timeouts 1200 ms for remembered, 900 ms for sweep, 25 s total). Persists the found base. `localPrefixes()` enumerates `NetworkInterface`s for IPv4 `/24` prefixes.
- `AndroidManifest.xml` — `usesCleartextTraffic="true"` (plain HTTP is the point), minSdk 24 / targetSdk 34, `INTERNET` + network-state + `ACCESS_WIFI_STATE` (+ `WRITE_EXTERNAL_STORAGE` maxSdk 28), `package com.bucket`, `BuckyView` workbench palette (`GROUND #EDEFF2`, `SIGNAL #FF3B1F`, etc.) for the native loading screen.
- `build_apk.ps1` — no Gradle. Sequence: `aapt2 compile` → `aapt2 link` → `javac --release 11` → `d8 --min-api 24` → zip `classes.dex` into the APK → `zipalign -f 4` → `apksigner sign`. Requires Android SDK `build-tools\36.0.0` and `platforms\android-34\android.jar` (paths from `ANDROID_SDK_ROOT` or `%LOCALAPPDATA%\Android\Sdk`). The signing keystore (`android\bucket-debug.jks`, pass `bucketdev`) is **kept across rebuilds** — changing the key would force an uninstall to update.
- `res/mipmap-*/ic_launcher.png` are committed; `build/` artifacts are intermediate.

### Android-dictate (`android-dictate/`)

Same no-Gradle pipeline as Bucket (`android-dictate/build_apk.ps1`), package `com.dictate`, label "Dictate". `Server.java` is Bucket's discovery with `PORT = 8766` and prefs `"dictate"`. `MainActivity.java` hosts the page (`LOAD_NO_CACHE` + `clearCache(true)` because Dictate has no SW versioning) and exposes a `DictateNative` JS bridge (`hasNativeScanner`/`scanQr`) that launches `ScanActivity.java` — a native Camera1 preview decoding with a vendored minimal ZXing QR subset (`java/com/google/zxing/{qrcode,common}` + a few top-level classes, Apache-2.0, pure Java, compiled like our own sources; `InvertedLuminanceSource` included because `LuminanceSource.invert()` needs it). Native exists because WebView over plain-HTTP LAN is not a secure context and exposes neither `getUserMedia` nor `BarcodeDetector`, so the page's web scanner is a browser-only fallback. `onPermissionRequest` keeps a `pendingWebPermission` and settles it in `onRequestPermissionsResult` (WebKit never re-asks; dropping it hangs `getUserMedia` forever). `build_apk.ps1` jars `build/classes` into `classes.jar` before `d8` so the class list never blows the `d8.bat` command-line limit. Signing key `dictate-debug.jks` (pass `bucketdev`) — Dictate's copy uses `CN=Dictate` (Bucket's copy uses `CN=Bucket`); both are self-signed debug keys and either is fine, but do not swap them without an uninstall. Launcher icons from `tools/make_dictate_icons.py`.

### Icons

`tools/make_icons.py` writes the Bucket PWA icons (`bucket/static/icon-192.png`, `icon-512.png`, `icon-maskable.png`) **and** the Android launcher icons (`android/res/mipmap-*/ic_launcher.png`, at density sizes) by hand — raw PNG chunks, no image library. Palette at the top: `GROUND (0x0B,0x0B,0x0C)` ↔ intended `--ground`, `INK (0xF2,0xF2,0xF3)` ↔ intended `--ink`; the `*_LT`/`*_DK` shades are icon-only lighting. **Note:** the current Bucket web theme is the light Workbench (`--ground #EDEFF2`, `--ink #0E1013`, `--signal #FF3B1F`), so the icon tile (near-black `0B0B0C` with white letter) is intentionally not synced to the CSS — it stays dark for launcher contrast while the page is light. The glyph is a procedural grotesk letter B: a rounded stem, two D-bowls whose right edges are semicircles, a bar joining them at mid-height, and the counters subtracted (`letter_sdf`, `make_icons.py:111`). All geometry is SDFs with per-pixel anti-aliasing; `--preview` prints an ASCII render. Maskable icon uses a larger glyph inset (`0.22`) because Android crops it to a circle.

`tools/make_dictate_icons.py` generates the Dictate launcher icons (`android-dictate/res/mipmap-*/ic_launcher.png`) with the same PNG writer; the glyph is a mic + cursor (pill body + ball bottom + stem + base + distinct `CURSOR` bar at `0.875`, all SDFs, `glyph_sdf`). The palette is warm paper on ink (`INK #121417`, `PAPER #FDFCF8` etc.), matching `dictate/static/app.css` `--paper`/`--ink` (do not import Bucket's workbench palette). No PWA icons — Dictate has no service worker.

## Tests

- `tools/selftest.py` — browses the whole Bucket API through Flask's test client against a fresh `data_test/` dir (wiped at start). 70 checks (64 named `check()`s in source; the static-servers loop expands one of them into 7 per path) across auth, pairing, the PC-only server-controls card, items, truncation, share target, batch mode (mode API, zip bundling with dedupe, lone-file guard, `/share` bundling, page markup), SSE, retention, revocation, and static serving (including `manifest.json` `share_target.action == "/share"`).
- `tests/pairing_flow.sh` — assumes a running server and real `curl`; mints a code on `127.0.0.1` and claims it over the LAN address (loopback would prove nothing since it is trusted implicitly). `LOCAL`/`LAN` env vars override defaults (default `LAN` is `http://<detect_lan_ip()>:8765`). Windows quirks handled: `HERE_WIN` via `cygpath -w`, and the fixture upload uses a relative path (`tests/fixture.png`) because Windows `curl.exe` mangles Git Bash paths with spaces. 10 steps: 401 for unpaired, 403 mint from LAN, mint on PC, wrong code 403, claim 200 + cookie, single-use, paired 200, origin `phone`, forged token 401, `/share` 302.

Run order that exercises everything: `selftest.py`, then start the server and run `pairing_flow.sh`.

- `tools/selftest_dictate.py` — walks Dictate's API through the test client with a recording fake `Typer` (no keystrokes ever simulated). 27 checks: pairing (local mint, LAN claim, single-use, revoke), arm/disarm, diff typing (append, revision with 5 backspaces + 8 retyped, shrink, clear), stale-seq rejection, modifier guard, oversize (60 000 chars), and lockout of unpaired devices. The phone is impersonated with a `REMOTE_ADDR` override (`192.168.1.42` / `77`) because loopback is trusted implicitly.

## Gotchas / invariants to preserve

- Do not add a trust bypass for any IP besides `127.0.0.1` / `::1`.
- Keep the token cookie `HttpOnly` + `path=/`; secure-flag logic lives in one place (`_set_token_cookie` / `_origin_https()`) and is load-bearing for plain-HTTP pairing.
- The service worker must never cache `/api/` responses and must always be served with `no-cache`. Dictate must never cache `/` or `/static/*` either (`no_cache_static`), since it has no SW to version.
- Blob write → row insert ordering in `add_blob` is a crash-safety contract; don't reorder.
- One SQLite connection + one lock is deliberate; per-request connections would leak on long-lived SSE threads.
- Editing `app.css`, `app.js`, or `index.html` changes the worker `SHELL` hash automatically — no manual version bumping.
- `data/`, `data_test/`, `dictate-data/`, `dictate-data-test/`, `server.log`, screenshots at root, and `.venv/` are live/generated runtime state, not source. The `bucket-debug.jks` / `dictate-debug.jks` keystores are committed intent — keep them.
- Dictate's `Session.type_text` holds its lock across the `SendInput` calls; that serializes concurrent requests from the phone. A large paste can hold the lock for a second or two — acceptable for one user, and correctness beats concurrency here.
- `dictate/__init__.py` re-exports `create_app` lazily (same pattern as `bucket/__init__.py`); an eager import makes `python -m dictate.server` emit a `RuntimeWarning` and behave unpredictably.
- Never log the `bucket_token` cookie (both `ShareActivity` and `MainActivity` download paths avoid it).
- Rambler must not hold the `Session` lock during the Ollama network call (`dictate/server.py:176` cleans first, then calls `session.type_text`); and `clean_text` errors must surface as `502` with the cloud's body, not as `500` or a silent swallowing.
- Treat `OLLAMA_API_KEY` placeholders as not configured (`dictate/rambler.py:92` set) and re-read `.env` on every `get_config()` so editing without restart works.

