#!/usr/bin/env python3
"""Remove e ink mode from Bucket, restoring the pre-eink files.

E ink mode was deliberately added as three removable pieces:
  1. bucket/static/eink.css          (new standalone file)
  2. E-INK-MODE blocks in bucket/templates/index.html
  3. E-INK-MODE block in bucket/static/app.js

This script restores index.html and app.js from the snapshot taken before
the feature was added (tools/eink_snapshot/), deletes eink.css, and then
removes the snapshot itself. After it runs, the tree is byte-identical to
what it was before e ink mode existed.

Usage:  .venv\\Scripts\\python.exe tools\\remove_eink.py
Run from the project root. Add --dry-run to preview without touching files.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT = ROOT / "tools" / "eink_snapshot"
TARGETS = [
    (SNAPSHOT / "index.html", ROOT / "bucket" / "templates" / "index.html"),
    (SNAPSHOT / "app.js", ROOT / "bucket" / "static" / "app.js"),
]
EINK_CSS = ROOT / "bucket" / "static" / "eink.css"


def main() -> int:
    parser = argparse.ArgumentParser(description="Remove e ink mode from Bucket.")
    parser.add_argument("--dry-run", action="store_true",
                        help="show what would happen without changing anything")
    args = parser.parse_args()

    # Refuse to run if a snapshot file is missing: restoring from a partial
    # snapshot would silently leave half the feature in place.
    missing = [src for src, _ in TARGETS if not src.is_file()]
    if missing:
        print("Snapshot incomplete, cannot safely remove:", file=sys.stderr)
        for src in missing:
            print(f"  {src.relative_to(ROOT)}", file=sys.stderr)
        print("Delete the remaining e-ink pieces by hand, or restore the "
              "snapshot first.", file=sys.stderr)
        return 1

    for src, dest in TARGETS:
        print(f"restore {dest.relative_to(ROOT)} from snapshot")
        if not args.dry_run:
            dest.write_bytes(src.read_bytes())

    if EINK_CSS.is_file():
        print(f"delete   {EINK_CSS.relative_to(ROOT)}")
        if not args.dry_run:
            EINK_CSS.unlink()

    print(f"delete   {SNAPSHOT.relative_to(ROOT)}/ (the snapshot itself)")
    if not args.dry_run:
        for leftover in sorted(SNAPSHOT.iterdir(), reverse=True):
            leftover.unlink()
        SNAPSHOT.rmdir()

    print("Done — e ink mode fully removed." if not args.dry_run
          else "Dry run — nothing was changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
