/* Dictate phone client — studio rework, Rambler "once at end".
 * No speech recognition: Gboard streams words into #mic live.
 * Every input posts the full value raw and types live.
 * Rambler is OFF by default. When ON, live typing stays raw;
 * tapping "Clean with Rambler" sends the whole transcript once
 * via Ollama Cloud and diff-types the cleaned result.
 */
(function () {
  "use strict";

  var voice = document.getElementById("voice");
  var voiceLabel = document.getElementById("voiceLabel");
  var liveMeta = document.getElementById("liveMeta");
  var pairing = document.getElementById("pairing");
  var dict = document.getElementById("dictate");
  var mic = document.getElementById("mic");
  var liveError = document.getElementById("liveError");
  var ramblerToggle = document.getElementById("ramblerToggle");
  var ramblerBadge = document.getElementById("ramblerBadge");
  var ramblerHint = document.getElementById("ramblerHint");
  var ramblerBar = document.getElementById("ramblerBar");
  var ramblerCleanBtn = document.getElementById("ramblerClean");
  var wordCount = document.getElementById("wordCount");
  var charCount = document.getElementById("charCount");
  var paperLive = document.getElementById("paperLive");
  var statusPill = document.getElementById("statusPill");
  var statusPillLabel = document.getElementById("statusPillLabel");
  var statusTitle = document.getElementById("statusTitle");
  var statusDesc = document.getElementById("statusDesc");
  var statusTime = document.getElementById("statusTime");
  var meterFill = document.getElementById("meterFill");
  var ramblerTag = document.getElementById("ramblerTag");
  var ramblerModelLabel = document.getElementById("ramblerModelLabel");
  var ramblerHostLabel = document.getElementById("ramblerHostLabel");
  var toastEl = document.getElementById("toast");

  var lastSent = null;
  var lastSeq = 0;
  var retrying = false;
  var ramblerAvailable = false;
  var ramblerModel = "gpt-oss:20b";
  var ramblerHost = "ollama.com";
  var ramblerCleaning = false;
  var armedUntil = 0;
  var armed = false;
  var paired = false;

  // --- stepper ---------------------------------------------------------
  // The page is a four-step flow (pair → arm → speak → polish). Steps are
  // display state only: the marks mirror pairing/arm/rambler state, and heads
  // collapse manually. Nothing here gates the API — tapping the transcript
  // still auto-arms, so Gboard-first users never touch step 02.

  function stepEls() {
    try { return document.querySelectorAll('.step[data-step]'); }
    catch (e) { return []; }
  }

  function setMark(id, text) {
    var m = document.getElementById(id);
    if (m) m.textContent = text;
  }

  function setStepState(name, state) {
    var s = document.querySelector('.step[data-step="' + name + '"]');
    if (s && s.dataset.state !== state) s.dataset.state = state;
  }

  function renderSteps() {
    var hasText = !!(mic && mic.value && mic.value.trim().length > 0);
    var ramblerOn = !!(ramblerToggle && ramblerToggle.checked);
    setStepState("pair", paired ? "done" : "now");
    setMark("pairMark", paired ? "set ✓" : "now");
    setStepState("arm", !paired ? "locked" : (armed ? "done" : "now"));
    setMark("armMark", !paired ? "locked" : (armed ? "live ⁁" : "now"));
    setStepState("speak", !paired ? "locked" : "now");
    setMark("speakMark", !paired ? "locked" : (armed ? "live ⁁" : (hasText ? "draft" : "ready")));
    setStepState("polish", !paired ? "locked" : ((ramblerOn && hasText) ? "now" : "idle"));
    setMark("polishMark", !paired ? "locked" : ((ramblerOn && hasText) ? "ready" : (ramblerOn ? "on" : "off")));
  }

  function wireSteps() {
    var heads = document.querySelectorAll(".step__head");
    Array.prototype.forEach.call(heads, function (head) {
      head.addEventListener("click", function () {
        var step = head.closest ? head.closest(".step") : null;
        if (!step || step.dataset.state === "locked") return;
        var collapsed = step.dataset.collapsed === "1";
        if (collapsed) {
          delete step.dataset.collapsed;
          head.setAttribute("aria-expanded", "true");
        } else {
          step.dataset.collapsed = "1";
          head.setAttribute("aria-expanded", "false");
        }
      });
    });
  }

  function seq() {
    lastSeq = Math.max(Date.now(), lastSeq + 1);
    return lastSeq;
  }

  function api(path, body) {
    return fetch(path, {
      method: body === undefined ? "GET" : "POST",
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  function toast(msg) {
    if (!toastEl) return;
    toastEl.textContent = msg;
    toastEl.dataset.show = "1";
    clearTimeout(toastEl._t);
    toastEl._t = setTimeout(function () { delete toastEl.dataset.show; }, 2600);
  }

  function fmtTime(sec) {
    if (!sec) return "—";
    var d = Math.round(sec - Date.now() / 1000);
    if (d <= 0) return "expired";
    if (d < 60) return d + "s left";
    return Math.floor(d / 60) + "m " + (d % 60) + "s left";
  }

  function updateCounts() {
    if (!mic) return;
    var text = mic.value || "";
    var chars = text.length;
    var words = text.trim() === "" ? 0 : text.trim().split(/\s+/).length;
    if (charCount) charCount.textContent = chars + " chars";
    if (wordCount) wordCount.textContent = words + (words === 1 ? " word" : " words");
  }

  function updateRamblerCleanBtn() {
    if (!ramblerCleanBtn) return;
    var hasText = !!(mic && mic.value && mic.value.trim().length > 0);
    var on = !!(ramblerToggle && ramblerToggle.checked);
    var shouldShow = on && hasText && !ramblerCleaning;
    ramblerCleanBtn.hidden = !shouldShow;
    if (ramblerCleanBtn) ramblerCleanBtn.disabled = ramblerCleaning;
    renderSteps();
  }

  function setStatus(label, isLive) {
    armed = isLive;
    if (ramblerCleaning) {
      voiceLabel.textContent = "Cleaning…";
      voice.className = "live live";
      voice.dataset.state = "cleaning";
      if (liveMeta) liveMeta.textContent = "Rambler";
      if (paperLive) {
        paperLive.textContent = "Cleaning whole transcript…";
        paperLive.dataset.state = "cleaning";
      }
      if (statusPill) {
        statusPill.dataset.state = "cleaning";
        if (statusPillLabel) statusPillLabel.textContent = "Cleaning";
      }
      if (statusTitle) statusTitle.textContent = "Cleaning…";
      if (statusDesc) statusDesc.textContent = "Tidying the entire transcript at once. This replaces what’s on the PC in one diff.";
      if (meterFill) meterFill.dataset.state = "cleaning";
      updateRamblerCleanBtn();
      return;
    }

    if (voice) {
      voiceLabel.textContent = label;
      if (isLive) {
        voice.className = "live live";
        voice.dataset.state = "live";
        if (liveMeta) liveMeta.textContent = fmtTime(armedUntil);
      } else {
        voice.className = "live";
        voice.dataset.state = "";
        if (liveMeta) liveMeta.textContent = "30s arm";
      }
    }
    if (paperLive) {
      if (isLive) {
        paperLive.textContent = "Live — typing to your PC";
        paperLive.dataset.state = "live";
      } else if (label === "Offline") {
        paperLive.textContent = "Offline — cannot reach PC";
        paperLive.dataset.state = "";
      } else if (label === "Unpaired") {
        paperLive.textContent = "Unpaired";
        paperLive.dataset.state = "";
      } else {
        paperLive.textContent = "Idle — tap the field to arm";
        paperLive.dataset.state = "";
      }
    }
    if (statusPill) {
      statusPill.dataset.state = isLive ? "live" : "";
      if (statusPillLabel) statusPillLabel.textContent = isLive ? "Live" : "Idle";
    }
    if (statusTitle) statusTitle.textContent = isLive ? "Live" : "Idle";
    if (statusDesc) {
      statusDesc.textContent = isLive
        ? "Your PC is armed. Whatever you say is typed into the focused window."
        : "Tap Arm above, or just start speaking — the PC types only while live.";
    }
    if (meterFill) {
      meterFill.dataset.state = isLive ? "live" : "";
    }
    updateRamblerCleanBtn();
  }

  function showError(msg) {
    liveError.textContent = msg;
    liveError.hidden = false;
    toast(msg);
  }
  function clearError() { liveError.hidden = true; }

  function arm() { api("/api/arm", {}).then(function(){ refresh(); }).catch(function () {}); }

  // --- rambler toggle --------------------------------------------------

  function loadRamblerPref() {
    try { return localStorage.getItem("rambler") === "1"; } catch (e) { return false; }
  }
  function saveRamblerPref(on) {
    try { localStorage.setItem("rambler", on ? "1" : "0"); } catch (e) {}
  }

  function updateRamblerUI() {
    var on = ramblerToggle.checked;
    if (ramblerTag) {
      ramblerTag.textContent = on ? "ON" : "OFF";
      ramblerTag.className = on ? "tag on" : "tag";
    }
    if (ramblerModelLabel) ramblerModelLabel.textContent = ramblerModel;
    if (ramblerHostLabel) ramblerHostLabel.textContent = ramblerHost.replace(/^https?:\/\//, "");

    if (!ramblerAvailable) {
      ramblerBadge.textContent = "offline — set key in .env";
      ramblerBadge.className = "badge off";
      ramblerBadge.hidden = false;
      ramblerHint.textContent = "Rambler needs OLLAMA_API_KEY on the PC. Add it to .env and restart the server.";
      ramblerHint.hidden = !on;
      if (on) ramblerToggle.checked = true;
      if (ramblerBar) ramblerBar.style.opacity = on ? "1" : "0.92";
      updateRamblerCleanBtn();
      return;
    }
    if (on) {
      ramblerBadge.textContent = "cloud · " + ramblerModel;
      ramblerBadge.className = "badge on";
      ramblerBadge.hidden = false;
      ramblerHint.textContent = "Rambler on — type live raw, then tap Clean to tidy the whole transcript at once. No new ideas added.";
      ramblerHint.hidden = false;
    } else {
      ramblerBadge.textContent = "cloud · " + ramblerModel;
      ramblerBadge.className = "badge";
      ramblerBadge.hidden = true;
      ramblerHint.hidden = true;
    }
    updateRamblerCleanBtn();
  }

  function fetchRamblerStatus() {
    api("/api/rambler").then(function (res) {
      if (!res.ok) return;
      res.json().then(function (body) {
        ramblerAvailable = !!body.available;
        ramblerModel = body.model || ramblerModel;
        ramblerHost = body.host || ramblerHost;
        updateRamblerUI();
      });
    }).catch(function () {});
  }

  if (ramblerToggle) {
    ramblerToggle.checked = loadRamblerPref();
    fetchRamblerStatus();
    updateRamblerUI();
    ramblerToggle.addEventListener("change", function () {
      saveRamblerPref(ramblerToggle.checked);
      updateRamblerUI();
      clearError();
      if (ramblerToggle.checked) toast("Rambler on — will clean once at the end");
      else toast("Rambler off");
      updateRamblerCleanBtn();
    });
  } else {
    fetchRamblerStatus();
  }

  // --- sending ---------------------------------------------------------
  // Live typing is always raw (no debounce). Rambler cleaning is explicit via button.

  function doSend(text, useRambler) {
    var isClear = text === "";
    if (text === lastSent && !isClear && !useRambler) return;
    lastSent = text;
    if (useRambler) {
      ramblerCleaning = true;
      setStatus("Cleaning…", true);
      if (meterFill) {
        meterFill.style.width = "100%";
        meterFill.dataset.state = "cleaning";
      }
      if (ramblerCleanBtn) { ramblerCleanBtn.hidden = true; ramblerCleanBtn.disabled = true; }
    }
    api("/api/text", { text: text, seq: seq(), rambler: !!useRambler }).then(function (res) {
      var wasCleaning = ramblerCleaning;
      ramblerCleaning = false;
      if (res.ok) {
        res.json().then(function (body) {
          // If this was a Rambler clean, sync textarea to cleaned result so next edits start from cleaned
          if (wasCleaning && useRambler && body && typeof body.cleaned === "string" && body.cleaned.length) {
            mic.value = body.cleaned;
            lastSent = body.cleaned;
            updateCounts();
            updateRamblerCleanBtn();
            toast("Cleaned — replaced on PC");
          } else if (wasCleaning) {
            toast("Cleaned");
          } else if (!useRambler && text !== "") {
            // raw live, no toast spam
          }
          clearError();
          retrying = false;
          refresh();
        }).catch(function(){
          clearError();
          retrying = false;
          refresh();
        });
        return;
      }
      res.json().then(function (body) {
        if (res.status === 401) { showPairing(); return; }
        if (body.error === "disarmed") {
          setStatus("Idle", false);
          arm();
          if (!retrying) { retrying = true; lastSent = null; doSend(text, useRambler); }
        }
        else if (body.error === "modifiers") showError("A key is held down on the PC — release it first.");
        else if (body.error === "too long") showError("That text is too long.");
        else if (body.error && body.error.indexOf("rambler") !== -1) {
          showError(body.error);
        }
        else showError(body.error || "Could not send");
        refresh();
      });
    }).catch(function () { ramblerCleaning = false; setStatus("Offline", false); updateRamblerCleanBtn(); });
  }

  function cleanWithRambler() {
    var text = mic.value || "";
    if (!text.trim()) { toast("Nothing to clean"); return; }
    if (!ramblerToggle || !ramblerToggle.checked) { toast("Turn Rambler on first"); return; }
    // Ensure armed before cleaning — arm extends window
    arm();
    doSend(text, true);
  }

  function send() {
    var text = mic.value;
    updateCounts();
    updateRamblerCleanBtn();
    // Always send raw live, even when Rambler is on. Cleaning is separate explicit action.
    if (text === "" ) {
      doSend("", false);
      return;
    }
    doSend(text, false);
  }

  if (mic) {
    mic.addEventListener("focus", function(){ arm(); updateCounts(); updateRamblerCleanBtn(); });
    mic.addEventListener("input", function () { arm(); updateCounts(); send(); });
    // no auto-clean on blur anymore — explicit button owns it
    mic.addEventListener("keyup", function(){ updateCounts(); updateRamblerCleanBtn(); });
    updateCounts();
  }

  if (ramblerCleanBtn) {
    ramblerCleanBtn.addEventListener("click", cleanWithRambler);
  }

  var clearBtn = document.getElementById("clear");
  if (clearBtn) clearBtn.addEventListener("click", function () {
    mic.value = "";
    lastSent = null;
    updateCounts();
    updateRamblerCleanBtn();
    doSend("", false);
    toast("Cleared");
  });

  var disarmBtn = document.getElementById("disarm");
  if (disarmBtn) disarmBtn.addEventListener("click", function () {
    api("/api/disarm", {}).then(function(){ refresh(); toast("Stopped"); }).catch(function () {});
  });

  function tickMeter() {
    if (!meterFill || !statusTime) return;
    if (!armed || !armedUntil) {
      meterFill.style.width = "0%";
      if (statusTime) statusTime.textContent = "—";
      return;
    }
    var now = Date.now() / 1000;
    var total = 30;
    var remain = Math.max(0, armedUntil - now);
    var pct = Math.max(0, Math.min(100, (remain / total) * 100));
    meterFill.style.width = pct.toFixed(1) + "%";
    if (statusTime) statusTime.textContent = remain > 0 ? Math.ceil(remain) + "s left · auto-pauses" : "expired";
    if (voice && liveMeta && armed) liveMeta.textContent = Math.ceil(remain) + "s left";
    if (remain <= 0) {
      setStatus("Idle", false);
    }
  }

  function refresh() {
    api("/api/status").then(function (res) {
      if (!res.ok) {
        if (res.status === 401) showPairing();
        else setStatus("Offline", false);
        return;
      }
      res.json().then(function (state) {
        armedUntil = state.armed_until || 0;
        setStatus(state.armed ? "Live" : "Idle", state.armed);
        tickMeter();
        if (typeof state.rambler_available !== "undefined") {
          var avail = !!state.rambler_available;
          if (avail !== ramblerAvailable) {
            ramblerAvailable = avail;
            ramblerModel = state.rambler_model || ramblerModel;
            updateRamblerUI();
          }
        }
      });
    }).catch(function () { setStatus("Offline", false); });
  }

  var helpToggle = document.getElementById("helpToggle");
  var helpPanel = document.getElementById("helpPanel");
  if (helpToggle && helpPanel) {
    helpToggle.addEventListener("click", function() {
      var show = helpPanel.hidden;
      helpPanel.hidden = !show;
      helpToggle.setAttribute("aria-expanded", String(show));
      if (show) helpPanel.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });
  }


  // --- QR scan — pairs via the PC's QR (code in /pair?code=XXXXXX) ----------
  var scanBtn = document.getElementById("scanBtn");
  var scanModal = document.getElementById("scanModal");
  var scanClose = document.getElementById("scanClose");
  var scanVideo = document.getElementById("scanVideo");
  var scanCanvas = document.getElementById("scanCanvas");
  var scanErrorEl = document.getElementById("scanError");
  var scanStream = null;
  var scanRaf = null;
  var scanDetector = null;

  function showScanError(msg) {
    if (!scanErrorEl) return;
    scanErrorEl.textContent = msg;
    scanErrorEl.hidden = false;
    toast(msg);
  }
  function stopScan() {
    if (scanRaf) { cancelAnimationFrame(scanRaf); scanRaf = null; }
    if (scanStream) { scanStream.getTracks().forEach(function(t){ t.stop(); }); scanStream = null; }
    if (scanVideo) { try { scanVideo.pause(); } catch(e){} scanVideo.srcObject = null; }
    if (scanModal) scanModal.hidden = true;
  }
  async function scanLoop() {
    if (!scanModal || scanModal.hidden) return;
    if (!scanDetector || !scanVideo || scanVideo.readyState < 2) {
      scanRaf = requestAnimationFrame(function(){ setTimeout(scanLoop, 120); });
      return;
    }
    try {
      var barcodes = await scanDetector.detect(scanVideo);
      if (barcodes && barcodes.length) {
        var raw = barcodes[0].rawValue || "";
        handleScanned(raw);
        return;
      }
    } catch (e) {}
    scanRaf = requestAnimationFrame(function(){ setTimeout(scanLoop, 90); });
  }
  function handleScanned(raw) {
    var code = "";
    raw = (raw || "").trim();
    try {
      var u = new URL(raw);
      code = u.searchParams.get("code") || "";
      if (!code) {
        var path = u.pathname.replace(/^\/+/, "");
        if (/^\d{6}$/.test(path)) code = path;
      }
      if (!code) {
        var m = raw.match(/\b(\d{6})\b/);
        if (m) code = m[1];
      }
    } catch (e) {
      var m2 = raw.match(/\b(\d{6})\b/);
      if (m2) code = m2[1];
      else if (/^\d{6}$/.test(raw)) code = raw;
    }
    if (!code || !/^\d{6}$/.test(code)) {
      showScanError("QR didn’t contain a 6-digit code — try again or type it.");
      scanLoop();
      return;
    }
    stopScan();
    var codeInput = document.getElementById("code");
    if (codeInput) codeInput.value = code;
    api("/api/pair", { code: code }).then(function(res){
      if (res.ok) {
        if (scanErrorEl) scanErrorEl.hidden = true;
        var pe = document.getElementById("pairError");
        if (pe) pe.hidden = true;
        toast("Paired via QR — opening dictate");
        showDictate();
      } else {
        showScanError("Wrong or expired code — generate a new QR on the PC.");
        var pe2 = document.getElementById("pairError");
        if (pe2) { pe2.textContent = "Wrong or expired code — check your PC's terminal."; pe2.hidden = false; }
      }
    }).catch(function(){
      showScanError("Could not reach PC — check hotspot.");
    });
  }
  async function startScan() {
    // In the Android app prefer the native scanner: the page is served over
    // plain-HTTP LAN, which is not a secure context, so WebView exposes
    // neither getUserMedia nor BarcodeDetector. The native ScanActivity uses
    // the camera directly (no secure-context gate) and returns the QR text
    // via window.__dictateNativeScan below.
    try {
      if (window.DictateNative && typeof window.DictateNative.scanQr === "function") {
        window.DictateNative.scanQr();
        return;
      }
    } catch (e) {}
    if (!scanModal || !scanVideo) return;
    if (scanErrorEl) scanErrorEl.hidden = true;
    // Plain-HTTP LAN origins are not a secure context, so some browsers /
    // WebViews hide navigator.mediaDevices entirely. Fail fast with a
    // type-the-code message instead of a TypeError.
    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== "function") {
      scanModal.hidden = false;
      showScanError("Camera scan isn’t available on this connection — type the 6-digit code instead.");
      return;
    }
    scanModal.hidden = false;
    // reset video
    try {
      scanStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" } }, audio: false });
      scanVideo.srcObject = scanStream;
      await scanVideo.play();
    } catch (e) {
      var msg = e && e.name === "NotAllowedError" ? "Camera blocked — allow it in site settings, or type the code." : (e && e.message) ? e.message : "Could not open camera.";
      showScanError(msg);
      return;
    }
    if ("BarcodeDetector" in window) {
      try { scanDetector = new BarcodeDetector({ formats: ["qr_code"] }); } catch(e) { scanDetector = new BarcodeDetector(); }
      scanLoop();
    } else {
      // No decoder available: release the camera again so the light does not
      // stay on behind an error card, but keep the modal open for code entry.
      if (scanStream) { scanStream.getTracks().forEach(function(t){ t.stop(); }); scanStream = null; }
      if (scanVideo) { try { scanVideo.pause(); } catch(e){} scanVideo.srcObject = null; }
      showScanError("Camera scan needs Chrome 83+ — type the 6-digit code instead.");
    }
  }
  // Entry point for the Android app's native ScanActivity (see
  // android-dictate/java/com/dictate/ScanActivity.java). The native side
  // passes the raw QR text; parsing + pairing reuse the web path exactly.
  window.__dictateNativeScan = handleScanned;
  if (scanBtn) scanBtn.addEventListener("click", startScan);
  if (scanClose) scanClose.addEventListener("click", stopScan);
  if (scanModal) scanModal.addEventListener("click", function(e){ if (e.target === scanModal) stopScan(); });

  var pairForm = document.getElementById("pairForm");
  if (pairForm) pairForm.addEventListener("submit", function (e) {
    e.preventDefault();
    var code = document.getElementById("code").value.trim();
    api("/api/pair", { code: code }).then(function (res) {
      var err = document.getElementById("pairError");
      if (res.ok) {
        err.hidden = true;
        toast("Paired — opening dictate");
        showDictate();
      } else {
        err.textContent = "Wrong or expired code — check your PC's terminal.";
        err.hidden = false;
      }
    }).catch(function () {
      document.getElementById("pairError").textContent = "Could not reach your PC.";
      document.getElementById("pairError").hidden = false;
    });
  });

  function showDictate() {
    paired = true;
    pairing.hidden = true;
    dict.hidden = false;
    refresh();
    fetchRamblerStatus();
    if (mic) mic.focus();
    updateRamblerCleanBtn();
  }

  function showPairing() {
    paired = false;
    dict.hidden = true;
    pairing.hidden = false;
    setStatus("Unpaired", false);
    var c = document.getElementById("code");
    if (c) c.focus();
    renderSteps();
  }

  // Explicit arm affordance for step 02 (tapping the transcript auto-arms
  // too, via the mic focus/input handlers above).
  var armBtn = document.getElementById("armBtn");
  if (armBtn) armBtn.addEventListener("click", function () {
    arm();
    toast("Armed — speak now");
  });

  wireSteps();
  renderSteps();

  api("/api/status").then(function (res) {
    if (res.ok) showDictate();
    else showPairing();
  }).catch(function () { showPairing(); });

  setInterval(function(){ refresh(); tickMeter(); }, 1000);
  setInterval(tickMeter, 200);
})();
