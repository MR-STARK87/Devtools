"use strict";

const paired = document.documentElement.dataset.paired === "yes";

const el = (id) => document.getElementById(id);
const toastBox = el("toast");
let toastTimer = null;

function toast(message) {
  toastBox.textContent = message;
  toastBox.dataset.show = "1";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => delete toastBox.dataset.show, 2400);
}

/* --- E-INK-MODE:BEGIN (see tools/remove_eink.py to strip) --------- */

/* E ink mode toggle. The visual theme lives entirely in eink.css, scoped
   under html.eink; this only flips the class, persists the choice, and keeps
   the button's pressed state + the browser theme-color honest. Runs on the
   gate screen too, so the mode is settable before pairing. */
(function () {
  const btn = document.getElementById("einkToggle");
  if (!btn) return;
  const root = document.documentElement;
  const meta = document.querySelector('meta[name="theme-color"]');

  const sync = () => {
    const on = root.classList.contains("eink");
    btn.setAttribute("aria-pressed", on ? "true" : "false");
    if (meta) meta.setAttribute("content", on ? "#E5E5E0" : "#EDEFF2");
  };

  btn.addEventListener("click", () => {
    root.classList.toggle("eink");
    const on = root.classList.contains("eink");
    try {
      localStorage.setItem("bucket-eink", on ? "1" : "0");
    } catch (e) {
      /* Private mode / storage denied: the toggle still works for this
         view, it just will not survive a reload. */
    }
    sync();
  });

  sync();
})();

/* --- E-INK-MODE:END ----------------------------------------------- */

/* --- pairing screen ------------------------------------------------ */

if (!paired) {
  const form = el("pairForm");
  const error = el("pairError");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    error.hidden = true;
    const response = await fetch("/api/pair", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: el("code").value }),
    });
    if (response.ok) {
      location.replace("/");
      return;
    }
    error.textContent = "That code is wrong or expired. Generate a new one.";
    error.hidden = false;
  });
}

/* --- main app ------------------------------------------------------ */

if (paired) {
  const list = el("items");
  const tpl = el("tpl-item");
  const empty = el("empty");
  const count = el("count");
  const clearBtn = el("clear");
  const textArea = el("text");
  const fileInput = el("file");

  const items = new Map();

  const fmtSize = (bytes) => {
    if (bytes < 1024) return `${bytes} b`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} kb`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1048576).toFixed(1)} mb`;
    return `${(bytes / 1073741824).toFixed(2)} gb`;
  };

  const fmtTime = (seconds) => {
    const then = new Date(seconds * 1000);
    const delta = (Date.now() - then.getTime()) / 1000;
    if (delta < 45) return "just now";
    if (delta < 3600) return `${Math.round(delta / 60)} min ago`;
    if (delta < 86400) return `${Math.round(delta / 3600)} hr ago`;
    return then.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  };

  function render() {
    const ordered = [...items.values()].sort(
      (a, b) => b.created_at - a.created_at,
    );
    list.replaceChildren(...ordered.map(node));
    empty.hidden = ordered.length > 0;
    clearBtn.hidden = ordered.length === 0;
    count.textContent =
      ordered.length === 0
        ? "No items"
        : `${ordered.length} item${ordered.length === 1 ? "" : "s"}`;
  }

  function node(item) {
    const frag = tpl.content.cloneNode(true);
    const li = frag.querySelector(".item");
    li.dataset.id = item.id;
    li.dataset.origin = item.origin;
    if (item.fresh) {
      li.classList.add("item--new");
      delete item.fresh;
    }

    li.querySelector(".item__kind").textContent = item.kind;
    li.querySelector(".item__size").textContent = fmtSize(item.size);
    const time = li.querySelector(".item__time");
    time.textContent = fmtTime(item.created_at);
    time.dateTime = new Date(item.created_at * 1000).toISOString();
    li.querySelector(".item__origin").textContent =
      item.origin === "pc" ? "from pc" : "from phone";

    const body = li.querySelector(".item__body");
    const raw = `/api/items/${item.id}/raw`;

    if (item.kind === "text") {
      const pre = document.createElement("pre");
      pre.textContent = item.body ?? "";
      body.append(pre);
      if (item.truncated) {
        const more = document.createElement("p");
        more.className = "item__more";
        more.textContent = "Preview truncated. Copy or open for the full text.";
        body.append(more);
      }
    } else if (item.kind === "image") {
      // The thumbnail is the opener: a real <button> so keyboard users get
      // it for free, with the feed <img> inside. Clicking never navigates —
      // it opens the in-page viewer (or the native viewer inside the app).
      const thumb = document.createElement("button");
      thumb.type = "button";
      thumb.className = "item__thumb";
      thumb.setAttribute("aria-label", `Open image ${item.name}`);
      thumb.dataset.id = item.id;
      const img = document.createElement("img");
      img.src = raw;
      img.alt = item.name;
      img.loading = "lazy";
      img.decoding = "async";
      img.draggable = false;
      thumb.append(img);
      body.append(thumb);
    } else {
      const name = document.createElement("div");
      name.className = "item__file";
      name.textContent = item.name;
      body.append(name);
    }

    const act = (name) => li.querySelector(`[data-act="${name}"]`);
    act("save").href = `${raw}?download=1`;
    act("save").setAttribute("download", item.name);
    act("open").href = raw;

    if (item.kind !== "text") act("copy").remove();
    if (item.kind === "text") act("open").remove();

    return frag;
  }

  /* --- actions ----------------------------------------------------- */

  /* --- image viewer ------------------------------------------------ */

  /* Same-app window for images: the feed thumbnail reuses its already-loaded
     /raw URL, so the full view is a cache hit and feels instant — no page
     navigation, no new tab. Non-images never come here: their Save/Open
     anchors fall through to the browser, or inside the Android app to the
     native bridge, which hands them to the system's default viewer. */
  const viewer = el("viewer");
  const viewerImg = el("viewerImg");
  const viewerName = el("viewerName");
  const viewerCount = el("viewerCount");
  const viewerCap = el("viewerCap");
  const viewerOpen = el("viewerOpen");
  const viewerSave = el("viewerSave");
  const viewerClose = el("viewerClose");
  const viewerPrev = el("viewerPrev");
  const viewerNext = el("viewerNext");
  const viewerStage = el("viewerStage");
  let viewerIndex = -1;
  let viewerId = null;
  let viewerLastFocus = null;

  // The bridge only exists inside the Android WebView; a plain browser tab
  // has no window.BucketNative, so every call is guarded and falls back to
  // the anchor's default behaviour. Truthy-checked (not typeof): on some
  // WebView versions injected methods do not report "function".
  const openNativeImage = (item) => {
    const bridge = window.BucketNative;
    if (bridge && bridge.openImage) {
      try {
        bridge.openImage(item.id, `/api/items/${item.id}/raw`, item.name);
        return true;
      } catch {
        return false;
      }
    }
    return false;
  };

  const openNativeFile = (item, raw) => {
    const bridge = window.BucketNative;
    if (bridge && bridge.openFile) {
      try {
        bridge.openFile(raw, item.name, item.mime || "");
        return true;
      } catch {
        return false;
      }
    }
    return false;
  };

  const imageItems = () =>
    [...items.values()]
      .filter((item) => item.kind === "image")
      .sort((a, b) => b.created_at - a.created_at);

  function showViewerAt(index) {
    const gallery = imageItems();
    if (!gallery.length) return;
    viewerIndex = (index + gallery.length) % gallery.length;
    const item = gallery[viewerIndex];
    viewerId = item.id;
    const raw = `/api/items/${item.id}/raw`;
    viewerLastFocus = viewerLastFocus || document.activeElement;
    viewerStage.dataset.loading = "1";
    viewerImg.alt = item.name;
    viewerImg.src = raw;
    viewerName.textContent = item.name;
    viewerName.title = item.name;
    viewerCount.textContent =
      gallery.length > 1 ? `${viewerIndex + 1} / ${gallery.length}` : "";
    viewerCap.textContent =
      `${item.name} · ${fmtSize(item.size)} · ${fmtTime(item.created_at)}`;
    viewerOpen.href = raw;
    viewerSave.href = `${raw}?download=1`;
    viewerSave.setAttribute("download", item.name);
    const multi = gallery.length > 1;
    viewerPrev.disabled = !multi;
    viewerNext.disabled = !multi;
    viewerPrev.style.visibility = multi ? "" : "hidden";
    viewerNext.style.visibility = multi ? "" : "hidden";
    if (viewer.hidden) {
      viewer.hidden = false;
      viewerClose.focus();
    }
    // Neighbours next: opening the next image is then a cache hit too.
    for (const near of [viewerIndex - 1, viewerIndex + 1]) {
      const neighbour =
        gallery[(near + gallery.length) % gallery.length];
      if (neighbour && neighbour.id !== item.id) {
        const pre = new Image();
        pre.decoding = "async";
        pre.src = `/api/items/${neighbour.id}/raw`;
      }
    }
  }

  function openViewerById(id) {
    const at = imageItems().findIndex((item) => item.id === id);
    if (at < 0) return;
    showViewerAt(at);
  }

  function closeViewer() {
    if (viewer.hidden) return;
    viewer.hidden = true;
    viewerIndex = -1;
    viewerId = null;
    viewerImg.removeAttribute("src");
    delete viewerStage.dataset.loading;
    if (viewerLastFocus && viewerLastFocus.focus) {
      try {
        viewerLastFocus.focus();
      } catch {
        /* focus is best-effort */
      }
    }
    viewerLastFocus = null;
  }

  // If the open image vanishes (delete/clear/SSE) while viewing, follow it:
  // advance to the nearest surviving image, or close when none remain. New
  // arrivals only re-seat the index so Prev/Next keep walking the same image.
  function syncViewerAfterChange(reload = true) {
    if (viewer.hidden || viewerIndex < 0) return;
    const gallery = imageItems();
    if (!gallery.length) {
      closeViewer();
      return;
    }
    const stillAt = gallery.findIndex((item) => item.id === viewerId);
    if (stillAt >= 0) {
      viewerIndex = stillAt;
      if (!reload) return;
    } else {
      viewerIndex = Math.min(viewerIndex, gallery.length - 1);
    }
    showViewerAt(viewerIndex);
  }

  if (viewer && viewerImg) {
    viewerImg.addEventListener("load", () => delete viewerStage.dataset.loading);
    viewerImg.addEventListener("error", () => {
      delete viewerStage.dataset.loading;
      viewerCap.textContent = "Could not load this image.";
    });
    viewerClose.addEventListener("click", closeViewer);
    viewerPrev.addEventListener("click", () => showViewerAt(viewerIndex - 1));
    viewerNext.addEventListener("click", () => showViewerAt(viewerIndex + 1));
    viewer.addEventListener("click", (event) => {
      if (event.target.closest("[data-viewer-close]")) closeViewer();
    });
    document.addEventListener("keydown", (event) => {
      if (viewer.hidden) return;
      if (event.key === "Escape") closeViewer();
      else if (event.key === "ArrowLeft") showViewerAt(viewerIndex - 1);
      else if (event.key === "ArrowRight") showViewerAt(viewerIndex + 1);
    });
  }

  list.addEventListener("click", async (event) => {
    const thumb = event.target.closest(".item__thumb");
    if (thumb) {
      const thumbId = thumb.closest(".item").dataset.id;
      const thumbItem = items.get(thumbId);
      if (!thumbItem) return;
      if (openNativeImage(thumbItem)) return;
      openViewerById(thumbId);
      return;
    }
    const button = event.target.closest("[data-act]");
    if (!button) return;
    const id = button.closest(".item").dataset.id;
    const act = button.dataset.act;

    if (act === "open") {
      // Text has no Open button. Images always stay in-app: the native
      // viewer inside the app, the lightbox overlay in a browser tab — the
      // Open button and the thumbnail do the same thing. Everything else
      // goes to the system default via the bridge, or the anchor's browser
      // default without it. (The viewer dialog keeps its own plain Open
      // link as the escape hatch to the raw tab.)
      const item = items.get(id);
      if (!item || item.kind === "text") return;
      if (item.kind === "image") {
        event.preventDefault();
        if (!openNativeImage(item)) openViewerById(id);
        return;
      }
      if (openNativeFile(item, `/api/items/${id}/raw`)) {
        event.preventDefault();
      }
      return;
    }

    if (act === "save") {
      const item = items.get(id);
      const name = item?.name ?? "file";
      const pretty = name.length > 32 ? name.slice(0, 29) + "\u2026" : name;
      toast(`Downloading ${pretty}`);
      return;
    }

    if (act === "del") {
      const response = await fetch(`/api/items/${id}`, { method: "DELETE" });
      if (response.ok) {
        items.delete(id);
        render();
        syncViewerAfterChange();
      }
      return;
    }

    if (act === "copy") {
      event.preventDefault();
      // Fetch the raw body rather than the rendered preview: long text is
      // truncated in the list, and copying a partial value silently is worse
      // than a slightly slower copy.
      const text = await (await fetch(`/api/items/${id}/raw`)).text();
      await copyText(text);
    }
  });

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast("Copied");
      return;
    } catch {
      // Clipboard API needs a secure context. Over plain http on the LAN it
      // throws, so fall back to a hidden textarea and execCommand.
    }
    const scratch = document.createElement("textarea");
    scratch.value = text;
    scratch.setAttribute("readonly", "");
    scratch.style.cssText = "position:fixed;top:-1000px;opacity:0";
    document.body.append(scratch);
    scratch.select();
    const ok = document.execCommand("copy");
    scratch.remove();
    toast(ok ? "Copied" : "Copy blocked. Use Open, then copy manually.");
  }

  /* --- confirm dialog ---------------------------------------------- */

  /* A workbench-styled stand-in for window.confirm. The browser prompt is
     jarringly off-brand next to the page, and on the phone it would fight
     the app's own dialog; this way PC and phone get the same card. Returns
     a promise (true = confirmed) so call sites read like the old confirm().
     Callers may pass their own title/body; the defaults below are re-applied
     on every open so one call can never leak its text into the next. */
  const confirmDefaults = {
    title: el("confirmTitle").textContent,
    body: el("confirmBody").textContent,
  };

  function askConfirm(title = confirmDefaults.title, body = confirmDefaults.body) {
    return new Promise((resolve) => {
      const veil = el("confirmVeil");
      const okBtn = el("confirmOk");
      const cancelBtn = el("confirmCancel");
      el("confirmTitle").textContent = title;
      el("confirmBody").textContent = body;

      const close = (answer) => {
        veil.hidden = true;
        document.removeEventListener("keydown", onKey);
        resolve(answer);
      };
      const onKey = (event) => {
        if (event.key === "Escape") close(false);
      };

      veil.hidden = false;
      document.addEventListener("keydown", onKey);
      // Enter confirms, matching muscle memory from the native prompt.
      okBtn.focus();

      okBtn.onclick = () => close(true);
      cancelBtn.onclick = () => close(false);
      veil.onclick = (event) => {
        if (event.target === veil) close(false);
      };
    });
  }

  clearBtn.addEventListener("click", async () => {
    if (!(await askConfirm())) return;
    const response = await fetch("/api/items/clear", { method: "POST" });
    if (response.ok) {
      items.clear();
      render();
      syncViewerAfterChange();
      toast("Bucket cleared");
    }
  });

  /* --- multi-file batch choice ------------------------------------- */

  /* One account-wide answer for "several files at once": ask every time,
     always zip, or always send separately. Lives on the server (GET/PUT
     /api/batch-mode) so the PC page, the phone page, and the native share
     sheet all obey the same setting. The composer switch is the visible
     handle for it; the dialog's "Remember" checkbox is the shortcut that
     flips it without visiting the composer. */
  let batchMode = "ask";
  const batchSegs = [...document.querySelectorAll(".seg[data-mode]")];

  function syncBatchSeg() {
    batchSegs.forEach((btn) =>
      btn.setAttribute("aria-pressed", String(btn.dataset.mode === batchMode)),
    );
  }

  async function setBatchMode(mode) {
    const response = await fetch("/api/batch-mode", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ mode }),
    });
    if (!response.ok) {
      toast("Could not save choice");
      return false;
    }
    batchMode = mode;
    syncBatchSeg();
    return true;
  }

  batchSegs.forEach((btn) =>
    btn.addEventListener("click", async () => {
      if (btn.dataset.mode === batchMode) return;
      if (await setBatchMode(btn.dataset.mode)) {
        toast(
          btn.dataset.mode === "ask"
            ? "Will ask for several files"
            : btn.dataset.mode === "zip"
              ? "Several files: always zip"
              : "Several files: always separate",
        );
      }
    }),
  );

  async function loadBatchMode() {
    try {
      const response = await fetch("/api/batch-mode");
      if (response.ok) batchMode = (await response.json()).mode ?? "ask";
    } catch {
      // Unreachable at load (should not happen on a LAN page, but cheap
      // to survive): stay on "ask", the safe default that always prompts.
    }
    syncBatchSeg();
  }

  /* Same workbench veil pattern as askConfirm, but a three-way choice:
     Zip into one / Send separately / Cancel (veil click, Esc, Cancel).
     Resolves null on cancel, else {zip, remember}. */
  function askBatch(files) {
    return new Promise((resolve) => {
      const veil = el("batchVeil");
      const remember = el("batchRemember");
      const names = files.map((file) => file.name).filter(Boolean);
      el("batchTitle").textContent = `Send ${files.length} files?`;
      el("batchBody").textContent =
        names.length > 0
          ? `${names.slice(0, 3).join(", ")}${names.length > 3 ? `, +${names.length - 3} more` : ""} — zip into one item, or send separately?`
          : "Zip them into one item, or send them separately?";

      const close = (answer) => {
        veil.hidden = true;
        document.removeEventListener("keydown", onKey);
        resolve(answer);
      };
      const onKey = (event) => {
        if (event.key === "Escape") close(null);
      };

      remember.checked = false;
      veil.hidden = false;
      document.addEventListener("keydown", onKey);
      el("batchZipBtn").focus();

      el("batchZipBtn").onclick = () =>
        close({ zip: true, remember: remember.checked });
      el("batchSepBtn").onclick = () =>
        close({ zip: false, remember: remember.checked });
      el("batchCancel").onclick = () => close(null);
      veil.onclick = (event) => {
        if (event.target === veil) close(null);
      };
    });
  }

  /* --- PC-only controls: pair-a-phone code + stop server ------------ */

  /* Shown only when this browser *is* the machine (loopback). The pairing
     code normally lives in the terminal banner, and Ctrl+C normally stops
     the server — but the headless launcher hides that terminal, so this
     card keeps minting and displaying the code, and is the polite way to
     shut a server that has no console to quit from. */
  const opsCard = el("opsCard");
  if (opsCard) {
    const pairCode = el("pairCode");
    const pairMeta = el("pairMeta");
    const stopBtn = el("stopServer");
    let codeUntil = 0; // epoch ms at which the shown code dies
    let stopped = false;

    const showCode = (data) => {
      pairCode.textContent = data.code;
      codeUntil = data.expires_at * 1000;
    };

    async function mintCode() {
      if (stopped) return;
      try {
        const response = await fetch("/api/pairing/code", { method: "POST" });
        if (response.ok) showCode(await response.json());
      } catch {
        /* server went away — the tick below respects `stopped` */
      }
    }

    async function syncCode() {
      if (stopped) return;
      try {
        const response = await fetch("/api/pairing/code");
        if (!response.ok) return;
        const data = await response.json();
        if (data.code) showCode(data);
        else await mintCode();
      } catch {
        /* offline; try again next tick */
      }
    }

    // A code is single-use (a successful pair consumes it) and dies two
    // minutes after minting. One tick a second runs the countdown; once the
    // code is gone — used or expired — a fresh one is minted right away, so
    // there is always a live code on screen, like the terminal used to show.
    setInterval(async () => {
      const left = codeUntil - Date.now();
      if (left <= 0) {
        pairMeta.textContent = "minting a fresh code…";
        await mintCode();
        return;
      }
      const secs = Math.ceil(left / 1000);
      pairMeta.textContent =
        `valid ${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, "0")}`;
    }, 1000);

    syncCode();

    stopBtn.addEventListener("click", async () => {
      if (stopped) return;
      const ok = await askConfirm(
        "Stop the Bucket server?",
        "The server shuts down and this page goes offline. Nothing is lost — " +
          "start it again any time with run.cmd.",
      );
      if (!ok) return;
      stopped = true; // silence the pairing chatter before the server dies
      const response = await fetch("/api/shutdown", { method: "POST" });
      if (response.ok) {
        el("stoppedVeil").hidden = false;
        el("stoppedOk").addEventListener("click", () => window.close());
      } else {
        stopped = false;
        toast("Could not stop the server");
      }
    });
  }

  /* --- sending ----------------------------------------------------- */

  async function post(body) {
    const response = await fetch("/api/items", { method: "POST", body });
    if (!response.ok) {
      toast(response.status === 413 ? "File too large" : "Send failed");
      return null;
    }
    // Items also arrive over SSE; adding them here too keeps the UI honest
    // if the stream is momentarily down. ingest() dedupes by id.
    const data = await response.json();
    data.items.forEach((item) => ingest(item));
    return data;
  }

  async function sendText() {
    const text = textArea.value;
    if (!text.trim()) return;
    const form = new FormData();
    form.append("text", text);
    if (await post(form)) {
      textArea.value = "";
      toast("Sent");
    }
  }

  async function sendFiles(files) {
    const chosen = [...files];
    if (!chosen.length) return;
    let zip = batchMode === "zip";
    if (chosen.length > 1 && batchMode === "ask") {
      const answer = await askBatch(chosen);
      if (!answer) return; // dismissed: send nothing
      if (answer.remember) {
        await setBatchMode(answer.zip ? "zip" : "separate");
      }
      zip = answer.zip;
    }
    const form = new FormData();
    if (zip && chosen.length > 1) form.append("bundle", "zip");
    chosen.forEach((file) => form.append("files", file, file.name));
    if (await post(form)) {
      toast(
        zip && chosen.length > 1
          ? `Zipped ${chosen.length} files into one`
          : chosen.length === 1
            ? "Sent 1 file"
            : `Sent ${chosen.length} files`,
      );
    }
  }

  el("send").addEventListener("click", sendText);
  el("pick").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    sendFiles(fileInput.files);
    fileInput.value = "";
  });

  textArea.addEventListener("keydown", (event) => {
    // Enter sends; Shift+Enter inserts a newline. Ignore IME composition so
    // Enter that only commits a candidate does not fire a send.
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      sendText();
    }
  });

  /* On phones the composer is a fixed bottom bar, so it takes up no space in
     the scroll flow and the feed has to reserve its height instead. That
     height is not a constant — the textarea is user-resizable and the web
     fonts land after first paint — so measure the real element and publish it
     as --composer-h rather than hardcoding a number that goes stale the first
     time someone drags the textarea taller. */
  const composer = el("drop");
  const publishComposerHeight = () => {
    const height = Math.round(composer.getBoundingClientRect().height);
    if (height > 0) {
      document.documentElement.style.setProperty("--composer-h", `${height}px`);
    }
  };
  if ("ResizeObserver" in window) {
    new ResizeObserver(publishComposerHeight).observe(composer);
  } else {
    // Older WebViews: no live tracking, but at least match the current layout.
    publishComposerHeight();
    addEventListener("resize", publishComposerHeight);
  }

  /* Paste anywhere: a screenshot on the clipboard arrives as a file in the
     paste event, so Win+Shift+S then Ctrl+V lands an image with no extra UI. */
  document.addEventListener("paste", (event) => {
    const files = [...(event.clipboardData?.files ?? [])];
    if (!files.length) return;
    event.preventDefault();
    sendFiles(files);
  });

  /* --- drag and drop ---------------------------------------------- */

  let dragDepth = 0;

  document.addEventListener("dragenter", (event) => {
    event.preventDefault();
    dragDepth += 1;
    document.body.classList.add("dragging");
  });

  document.addEventListener("dragover", (event) => event.preventDefault());

  document.addEventListener("dragleave", () => {
    // Counting enter/leave pairs avoids the flicker from child elements
    // firing dragleave while the pointer is still over the page.
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) document.body.classList.remove("dragging");
  });

  document.addEventListener("drop", (event) => {
    event.preventDefault();
    dragDepth = 0;
    document.body.classList.remove("dragging");
    if (event.dataTransfer?.files?.length) {
      sendFiles(event.dataTransfer.files);
      return;
    }
    const text = event.dataTransfer?.getData("text/plain");
    if (text) {
      const form = new FormData();
      form.append("text", text);
      post(form).then((ok) => ok && toast("Sent"));
    }
  });

  /* --- live stream ------------------------------------------------- */

  function ingest(item) {
    if (!items.has(item.id)) item.fresh = true;
    items.set(item.id, item);
    render();
    syncViewerAfterChange(false);
  }

  const conn = el("conn");
  const connLabel = el("connLabel");

  function setConn(state, label) {
    conn.dataset.state = state;
    connLabel.textContent = label;
  }

  async function load() {
    const response = await fetch("/api/items");
    if (response.status === 401) {
      location.reload();
      return;
    }
    const data = await response.json();
    items.clear();
    data.items.forEach((item) => items.set(item.id, item));
    render();
  }

  function connect() {
    const stream = new EventSource("/api/events");

    stream.addEventListener("open", () => setConn("live", "live"));
    stream.addEventListener("item:add", (event) => ingest(JSON.parse(event.data)));
    stream.addEventListener("item:remove", (event) => {
      items.delete(JSON.parse(event.data).id);
      render();
      syncViewerAfterChange();
    });
    stream.addEventListener("item:reset", () => {
      items.clear();
      render();
      syncViewerAfterChange();
    });
    stream.addEventListener("error", () => {
      setConn("lost", "reconnecting");
      // EventSource retries on its own. Refetching on the next open covers
      // anything published while the stream was down.
    });
  }

  loadBatchMode();
  load().then(() => {
    connect();
    const params = new URLSearchParams(location.search);
    const shared = Number(params.get("shared"));
    if (shared > 0) {
      toast(shared === 1 ? "Shared 1 item" : `Shared ${shared} items`);
      history.replaceState(null, "", "/");
    }
  });

  // A phone suspends timers while backgrounded, so the SSE socket is often
  // dead by the time you look at it again. Refetch on wake.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") load();
  });

  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  }
}
