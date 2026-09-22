"""Headless companion to run.cmd / run.ps1.

Waits for the Bucket server to answer /api/health (importing Flask and
binding the port takes a moment), then opens the drop box in the default
browser. Runs under pythonw via `start` so nothing flashes on screen. If
the server never comes up the browser still opens and shows the failure —
more honest than a silent exit.

Set BUCKET_NO_BROWSER=1 to skip the final browser step (used by tests;
the launchers never set it).
"""

from __future__ import annotations

import os
import sys
import time
import urllib.request
import webbrowser

URL = "http://localhost:8765"
TIMEOUT_SECONDS = 30.0
POLL_SECONDS = 0.4


def wait_for_server(url: str) -> bool:
    """Poll GET <url>/api/health until it answers 200 or time runs out."""
    health = f"{url}/api/health"
    deadline = time.time() + TIMEOUT_SECONDS
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(health, timeout=1.0) as response:
                if response.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(POLL_SECONDS)
    return False


def main() -> None:
    url = sys.argv[1] if len(sys.argv) > 1 else URL
    wait_for_server(url)
    if os.environ.get("BUCKET_NO_BROWSER") == "1":
        return
    webbrowser.open(url)


if __name__ == "__main__":
    main()
