#!/usr/bin/env bash
# Pairing walkthrough over real HTTP, mirroring the actual topology:
# the code is minted on localhost (the PC) and claimed from the LAN address
# (the phone). Loopback is trusted implicitly, so claiming over 127.0.0.1
# would prove nothing.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
# $HERE is a Git Bash path (/c/...), but $PY is native Windows Python and
# cannot import from it. Hand Python the translated path.
HERE_WIN="$(cygpath -w "$HERE" 2>/dev/null || printf '%s' "$HERE")"
PY="$HERE/.venv/Scripts/python.exe"
LOCAL="${LOCAL:-http://127.0.0.1:8765}"
LAN="${1:-}"

if [ -z "$LAN" ]; then
  LAN="http://$(PYTHONPATH="$HERE_WIN" "$PY" -c 'from bucket.server import detect_lan_ip; print(detect_lan_ip())'):8765"
fi
echo "PC   origin: $LOCAL"
echo "phone origin: $LAN"

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT
jget() { "$PY" -c "import json,sys; print(json.load(sys.stdin)$1)"; }
say() { printf '\n--- %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1"; exit 1; }

say "1. unpaired device on the LAN is refused"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$LAN/api/items")
echo "GET /api/items unpaired -> $CODE (expect 401)"
[ "$CODE" = "401" ] || fail "unpaired device was not refused"

say "2. LAN device cannot mint its own pairing code"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$LAN/api/pairing/code")
echo "POST /api/pairing/code from LAN -> $CODE (expect 403)"
[ "$CODE" = "403" ] || fail "a remote device could mint pairing codes"

say "3. PC mints a pairing code"
PIN=$(curl -s -X POST "$LOCAL/api/pairing/code" | jget '["code"]')
echo "code: $PIN"

say "4. wrong code is rejected"
WRONG=$([ "$PIN" = "000000" ] && echo "111111" || echo "000000")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$LAN/api/pair" \
  -H 'Content-Type: application/json' -d "{\"code\":\"$WRONG\"}")
echo "wrong code -> $CODE (expect 403)"
[ "$CODE" = "403" ] || fail "a wrong code was accepted"

say "5. phone claims the real code"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -X POST "$LAN/api/pair" \
  -H 'Content-Type: application/json' -d "{\"code\":\"$PIN\"}")
echo "claim -> $CODE (expect 200)"
[ "$CODE" = "200" ] || fail "valid code was not accepted"
grep -q bucket_token "$JAR" || fail "no bucket_token cookie was set"

say "6. the code is single use"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$LAN/api/pair" \
  -H 'Content-Type: application/json' -d "{\"code\":\"$PIN\"}")
echo "replay -> $CODE (expect 403)"
[ "$CODE" = "403" ] || fail "the pairing code was reusable"

say "7. paired phone reads the list"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$LAN/api/items")
echo "GET /api/items paired -> $CODE (expect 200)"
[ "$CODE" = "200" ] || fail "paired device was refused"

say "8. phone-sent items are labelled 'phone'"
ORIGIN=$(curl -s -b "$JAR" -X POST "$LAN/api/items" -F 'text=sent from the phone' \
  | jget '["items"][0]["origin"]')
echo "origin: $ORIGIN (expect phone)"
[ "$ORIGIN" = "phone" ] || fail "origin was '$ORIGIN', not 'phone'"

say "9. a forged token is refused"
CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  -H 'Cookie: bucket_token=deadbeefdeadbeefdeadbeef' "$LAN/api/items")
echo "forged cookie -> $CODE (expect 401)"
[ "$CODE" = "401" ] || fail "a forged token was accepted"

say "10. share target accepts a file from the phone"
# This is Windows curl.exe: it cannot resolve Git Bash paths, and -F @path
# mishandles the spaces in "My work folder". Feed it a relative path instead.
CODE=$(cd "$HERE/tests" && curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -X POST "$LAN/share" \
  -F 'files=@fixture.png;type=image/png' -F 'text=shared via android')
echo "POST /share -> $CODE (expect 302)"
[ "$CODE" = "302" ] || fail "share target rejected the upload"

printf '\nAll pairing checks passed.\n'
