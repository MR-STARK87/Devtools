# Bucket — User Manual

Bucket is a private drop box between your Windows laptop and your Android
phone. Text, screenshots, and files move directly over your local network.
Nothing is uploaded, synced, or leaves your machine.

This manual covers day-to-day use with the installed setup: the server runs on
the laptop, the Bucket app is installed on your Samsung Galaxy, and the two are
already paired.

---

## 1. Daily use

1. On the laptop, start the server from the project folder. Open **cmd** or
   **PowerShell** in the folder and run:

   ```
   .\run.cmd
   ```

   (Or just double-click `run.cmd`.) The server starts in the background
   with no terminal window: the command opens the drop box in your default
   browser and finishes, so the page is the only window you keep. Server
   output (pairing code, request log) goes to `server.log` in the project
   folder. To run with a visible terminal instead — live request log, and
   the server stops when you close that window — use `run_debug.cmd`.

2. Make sure the phone and laptop are on the same network. The usual setup:
   turn on the phone's hotspot and connect the laptop to it.

3. Open the **Bucket** app on the phone. It searches the local network and finds
   the laptop by itself; the top of the screen shows a green **LIVE** indicator
   when connected.

4. `run.cmd` opens <http://localhost:8765> in your browser — that tab is the
   drop box. (Closing the tab does not stop the server.)

That is the whole routine. Once the laptop is found, everything is live: an item
added on either side appears on the other within a second or two.

**Stop the server.** Use the **Stop server** card in the left rail of the drop
box page. The page then shows “Server stopped” and you can close the tab. Only
the laptop's own page has that control — a paired phone never sees it.

### Laptop to phone

- **Paste or drop.** Click the drop zone and paste (`Ctrl+V`), or drag files
  onto it.
- **Screenshot fast path.** Press `Win+Shift+S` to capture, then `Ctrl+V` in the
  page — the clipboard image arrives as a file with no extra step.
- **Plain text.** Type in the text box and press `Ctrl+Enter`.

### Phone to laptop

- **From inside the app.** Type in the text box and send. Use **CHOOSE FILES**
  to attach files from the phone.
- **From any app (share sheet).** In the app you are reading (Gallery, Files,
  WhatsApp, a browser link), tap **Share**, then pick **Bucket**. The content is
  sent straight to the laptop; you do not even see the app open.
- **While away from home (offline queue).** Sharing when the laptop is not
  reachable does not lose the content: the phone copies the bytes into its
  private outbox and toasts "Saved for later". The next time the phone finds
  the laptop (open the Bucket app on the same network), everything queued is
  uploaded automatically, oldest first. The queue lives in the app's private
   storage, is capped at 1 GB, and survives app restarts and reboots. Clearing
   the app's data clears the queue.

### Several files at once

Sending two or more files in one go — dropping them together, picking
several with **Attach files**, pasting several, or sharing several from
another app — asks first: **Zip into one** (a single
`bucket-<date>-<time>.zip` item) or **Send separately** (one item per file,
as before). A single file never asks, and text sent alongside files always
stays its own item.

- **Remember this choice** skips the question next time and always does
  what you picked.
- The **Several files** switch under the text box (Ask / Zip / Separate)
  shows the current answer and changes it — that is also how you turn a
  remembered choice back off.
- The answer is shared: choosing on the laptop applies to the phone too,
  and the other way round.
- Sharing several files from another app while offline asks the same
  question; the answer travels with the queued share and is applied when it
  uploads later.

---

## 2. Pairing a new phone (or re-pairing)

Pairing codes only exist on the laptop side, and are short-lived, so a pair is
always user-initiated:

1. Start the server (`.\run.cmd`). On the drop box page, read the code from the
   **Pair a phone** card in the left rail. The card keeps a live code on screen
   — it renews itself every two minutes and mints a fresh one as soon as a code
   is used, so there is always one waiting. (If you started with
   `run_debug.cmd`, the code and QR code also print in that terminal.) The code
   is valid for **2 minutes** and can be used **once**.
2. On the phone, open the Bucket app. If it is not yet paired it shows a code
   entry screen. Enter the six digits.
3. Done. The phone stores a token and stays paired until you revoke it — you
   never re-enter a code for that phone.

Codes time out quickly by design. Only the laptop can create codes, so a phone
already on the bucket cannot invite more devices by itself.

---

## 3. Revoking a device

On the laptop, open a PowerShell prompt in the project folder and list the
paired devices:

```powershell
.\venv\Scripts\python.exe -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8765/api/devices').read().decode())"
```

Find the ID of the phone you want to cut off, then delete it:

```powershell
.\venv\Scripts\python.exe -c "import urllib.request; print(urllib.request.urlopen(urllib.request.Request('http://localhost:8765/api/devices/<ID>', method='DELETE')).read().decode())"
```

The revoked phone immediately stops being able to read or write, and must pair
again.

---

## 4. Saving files on the phone

- **Files the laptop sent.** In the app, open the item and tap **SAVE**. The
  phone downloads it into **Downloads** (visible in My Files / Gallery). The
  laptop can also mark a file for download with the same button.
- **Sent items.** Items you send from the phone remain in the bucket on the
  laptop; they are stored in the `data/` folder next to the server, not on the
  phone.

Saved files land in the Downloads folder. If Save does nothing, the phone was
probably just locked or the screen timed out — unlock it and tap Save again.

---

## 5. Why a dedicated app, and what it changes

The web page already works in a browser tab over plain HTTP. Two features need a
more secure context than a browser tab on a plain-HTTP LAN address provides:

- installing the page as a real app on the home screen, and
- appearing in the Android share sheet.

The Bucket app wraps the same page in a WebView, which lifts both restrictions
without any TLS certificate or cloud service. Your phone talks to the laptop's
real LAN address, so the server still knows it is the phone (not a program
running on the laptop itself).

The phone finds the laptop itself: it first remembers the last used address,
otherwise it probes every host on the local `/24` network on the bucket port and
connects to the first one that answers. Because of that, the hotspot must be the
phone's own — no static IP configuration is needed when the laptop hops
networks.

---

## 6. Troubleshooting

**"Could not find Bucket on this network."**
The app cannot reach the laptop. Likely causes, in order:

1. The server is not running — start it with `.\run.cmd` on the laptop.
2. The laptop is not on the phone's hotspot (or the phone's hotspot is off).
   Reconnect the laptop to the hotspot.
3. Windows Firewall is blocking port 8765 — allow the Python process (or port
   8765) through once.

After fixing the cause, tap **Search again** in the app.

**App says LIVE but the laptop page is empty.**
You are looking at different buckets? There is only one server, so this is
usually a caching page. Send any item and it should appear. The page refreshes
itself when items change.

**Share says it needs pairing / "open the app to pair".**
The share action runs before pairing on this phone. Open the Bucket app,
complete pairing, and share again. Shares from a paired phone work without
opening the app.

**"Saved for later" — where did my share go?**
Into the phone's offline outbox, because the laptop was not reachable (or the
phone was not paired at the time). It is not lost: open the Bucket app once
the phone and laptop share a network again and everything queued is sent
automatically. If the toast says "items waiting — pair to send", pairing was
missing; pair once and reopen the app. If the outbox was full (1 GB cap), the
share was refused, not queued — free space by opening the app near the laptop
so the queue flushes.

**Save on the phone does nothing.**
Unlock the phone and make sure the screen is on, then tap **SAVE** again. If it
still fails, check the Downloads app on the phone for an error state.

**The phone says LIVE is gone after I closed the laptop window.**
`run.cmd` is headless by design, so a closed window is normal — the server
keeps running. If LIVE is really gone, the server was stopped (Stop server on
the page, or a `run_debug.cmd` window being closed). Start it again with
`.\run.cmd`; the phone reconnects automatically.

**The laptop got a new IP address.**
Doesn't matter — the app finds the new address on the next open. If it complains,
tap **Search again**.

---

## 7. Privacy quick facts

- Nothing leaves your local network; there is no account, no cloud, no sync.
- Tokens are stored as hashes; a stolen database is not replayable.
- Only paired devices can read or write. Over plain HTTP traffic on the hotspot
  is readable by other devices already on that hotspot — the hotspot is the
  practical boundary.