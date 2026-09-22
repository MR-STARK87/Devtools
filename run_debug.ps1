# Runs Bucket in a visible terminal (debug / troubleshooting): the pairing
# banner and request log print here, and closing this window stops the server.
# For the usual headless start (browser only, logs to server.log) use run.ps1.
# Double-click this file, or: powershell -File run_debug.ps1
$ErrorActionPreference = "Stop"
$Host.UI.RawUI.WindowTitle = "Bucket - LAN drop box (debug)"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Join-Path $here ".venv\Scripts\python.exe"

if (-not (Test-Path $python)) {
    Write-Host "Setting up for the first time..." -ForegroundColor Cyan
    python -m venv (Join-Path $here ".venv")
    & $python -m pip install --quiet --upgrade pip
    & $python -m pip install --quiet -r (Join-Path $here "requirements.txt")
    & $python (Join-Path $here "tools\make_icons.py")
}

# ORIGIN pins the address printed in the QR. Set it to your Tailscale name
# once TLS is on, otherwise the LAN address is detected automatically.
$origin = $env:BUCKET_ORIGIN
$env:PYTHONPATH = $here

if ($origin) {
    & $python -m bucket.server --origin $origin
} else {
    & $python -m bucket.server
}
