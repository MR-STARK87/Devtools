@echo off
rem Bucket launcher. Works from cmd and PowerShell alike:
rem   cmd:        run
rem   powershell: .\run.cmd
rem
rem Headless by default: the server starts without a console window and the
rem launcher opens the drop box in your default browser, so the only window
rem you keep is the page. Closing this window does NOT stop the server —
rem use the "Stop server" control on the page (localhost), or run_debug.cmd.
rem Server output (pairing code, request log) goes to server.log.
setlocal
cd /d "%~dp0"
set "PY=%~dp0.venv\Scripts\python.exe"
set "PYW=%~dp0.venv\Scripts\pythonw.exe"

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

rem Start the server detached with no console at all (pythonw). The server
rem writes its own log file; the flag-less pythonw child is what keeps Bucket
rem alive after this window closes.
if defined BUCKET_ORIGIN (
    start "" "%PYW%" -m bucket.server --origin "%BUCKET_ORIGIN%"
) else (
    start "" "%PYW%" -m bucket.server
)

rem Wait for /api/health, then open the drop box in the default browser.
"%PYW%" "%~dp0tools\open_browser.py" http://localhost:8765
exit /b 0

:fail
echo.
echo Setup failed. Check that Python is installed and on PATH, then run again.
exit /b 1
