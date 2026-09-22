@echo off
rem Stops the headless Bucket server started by run.cmd. Finds whatever is
rem listening on :8765 and ends it, so it also works if the page is closed
rem or the Stop button is unreachable. Safe to run when no server is up.
rem (The usual way to stop is the Stop server card on the page.)
setlocal
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r ":8765 .*LISTENING"') do (
    taskkill /PID %%p /F >nul 2>&1
)
echo Bucket server stopped (if it was running).
