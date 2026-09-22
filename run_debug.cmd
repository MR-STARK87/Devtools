@echo off
rem Bucket in a visible terminal (debug / troubleshooting). The server runs
rem in the foreground: the pairing banner and live request log print here,
rem and closing this window stops the server. For the usual headless start
rem (browser only, logs to server.log) use run.cmd.
rem
rem Works from cmd and PowerShell alike:
rem   cmd:        run_debug
rem   powershell: .\run_debug.cmd
setlocal
cd /d "%~dp0"
title Bucket - LAN drop box (debug)
set "PY=%~dp0.venv\Scripts\python.exe"

if not exist "%PY%" (
    echo Setting up for the first time...
    python -m venv ".venv"
    if errorlevel 1 goto fail
    "%PY%" -m pip install --quiet --upgrade pip
    "%PY%" -m pip install --quiet -r "%~dp0requirements.txt"
    if errorlevel 1 goto fail
    "%PY%" "%~dp0tools\make_icons.py"
    if errorlevel 1 goto fail
)

set "PYTHONPATH=%~dp0"
if defined BUCKET_ORIGIN (
    "%PY%" -m bucket.server --origin "%BUCKET_ORIGIN%"
) else (
    "%PY%" -m bucket.server
)
goto :eof

:fail
echo.
echo Setup failed. Check that Python is installed and on PATH, then run again.
exit /b 1
