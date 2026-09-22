# Dictate launcher (PowerShell only). Same job as run_dictate.cmd.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$py = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

if (-not (Test-Path $py)) {
    Write-Host "Setting up for the first time..."
    python -m venv ".venv"
    if ($LASTEXITCODE -ne 0) { throw "venv failed" }
    & $py -m pip install --quiet --upgrade pip
    & $py -m pip install --quiet -r (Join-Path $PSScriptRoot "requirements.txt")
    if ($LASTEXITCODE -ne 0) { throw "pip failed" }
}

$env:PYTHONPATH = $PSScriptRoot
if ($env:DICTATE_ORIGIN) {
    & $py -m dictate.server --origin $env:DICTATE_ORIGIN
} else {
    & $py -m dictate.server
}