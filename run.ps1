# Starts Bucket headless: no console window, logs to server.log, opens the
# drop box in the default browser. The only window you keep is the page.
# Close this terminal freely — stop the server with the "Stop server"
# control on the page, or run run_debug.ps1 for a visible terminal instead.
# Double-click this file, or: powershell -File run.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Join-Path $here ".venv\Scripts\python.exe"
$pythonw = Join-Path $here ".venv\Scripts\pythonw.exe"

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

$arguments = @("-m", "bucket.server")
if ($origin) { $arguments += "--origin"; $arguments += $origin }

Start-Process -FilePath $pythonw -ArgumentList $arguments -WorkingDirectory $here
& $pythonw (Join-Path $here "tools\open_browser.py") "http://localhost:8765"
