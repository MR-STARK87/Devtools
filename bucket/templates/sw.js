/* Service worker: exists so the app is installable and can act as an Android
   share target. It deliberately does NOT cache API responses or blobs — the
   bucket is live shared state, and a stale cached list would be worse than a
   spinner. Only the static shell is precached, so the app opens instantly and
   shows a real message when the laptop is off.

   SHELL carries a hash of the actual asset bytes, injected by the server. Edit
   app.css and this file changes, so the browser sees a byte-different worker,
   reinstalls, and drops the old cache. A hardcoded version would strand every
   installed phone on whatever shipped first. */

const SHELL = "bucket-shell-{{ version }}";
const ASSETS = [
  "/",
  "/static/app.css",
  "/static/app.js",
  "/static/icon-192.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(SHELL)
      .then((cache) => cache.addAll(ASSETS))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((key) => key !== SHELL).map((key) => caches.delete(key))),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);

  if (request.method !== "GET" || url.origin !== self.location.origin) return;

  // Live data and blobs always go to the network. No offline fallback: an
  // empty bucket and an unreachable laptop must not look the same.
  if (url.pathname.startsWith("/api/")) return;

  // Navigations: network first so pairing state and fresh markup win, with
  // the cached shell as the offline answer.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request).catch(() =>
        caches.match("/").then((hit) => hit ?? offlineResponse()),
      ),
    );
    return;
  }

  // Static assets: cache first for instant loads, then refresh in the
  // background so the next open has the new bytes. Combined with the hashed
  // SHELL name, an edit on the PC reaches the phone without a reinstall.
  event.respondWith(
    caches.match(request).then((hit) => {
      const network = fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(SHELL).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => hit);
      return hit ?? network;
    }),
  );
});

function offlineResponse() {
  return new Response(
    "<!doctype html><meta charset=utf-8>" +
      "<meta name=viewport content='width=device-width,initial-scale=1'>" +
      "<title>Bucket</title>" +
      "<body style=\"margin:0;display:grid;place-items:center;height:100dvh;" +
      "background:#0B0B0C;color:#9D9DA4;font:16px system-ui;text-align:center\">" +
      "<div><p style='color:#F2F2F3'>Bucket is unreachable.</p>" +
      "<p>Check that your laptop is on the hotspot and Bucket is running.</p></div>",
    { headers: { "Content-Type": "text/html; charset=utf-8" } },
  );
}
