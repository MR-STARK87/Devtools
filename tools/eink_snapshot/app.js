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
      const img = document.createElement("img");
      img.src = raw;
      img.alt = item.name;
      img.loading = "lazy";
      body.append(img);
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

  list.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-act]");
    if (!button) return;
    const id = button.closest(".item").dataset.id;
    const act = button.dataset.act;

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
     a promise (true = confirmed) so call sites read like the old confirm(). */
  function askConfirm() {
    return new Promise((resolve) => {
      const veil = el("confirmVeil");
      const okBtn = el("confirmOk");
      const cancelBtn = el("confirmCancel");

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
      toast("Bucket cleared");
    }
  });

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
    const form = new FormData();
    chosen.forEach((file) => form.append("files", file, file.name));
    if (await post(form)) {
      toast(chosen.length === 1 ? "Sent 1 file" : `Sent ${chosen.length} files`);
    }
  }

  el("send").addEventListener("click", sendText);
  el("pick").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    sendFiles(fileInput.files);
    fileInput.value = "";
  });

  textArea.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
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
    });
    stream.addEventListener("item:reset", () => {
      items.clear();
      render();
    });
    stream.addEventListener("error", () => {
      setConn("lost", "reconnecting");
      // EventSource retries on its own. Refetching on the next open covers
      // anything published while the stream was down.
    });
  }

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
