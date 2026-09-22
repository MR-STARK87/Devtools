@echo off
rem Dictate launcher. Works from cmd and PowerShell alike:
rem   cmd:        run_dictate
rem   powershell: .\run_dictate.cmd
setlocal
cd /d "%~dp0"
set "PY=%~dp0.venv\Scripts\python.exe"

if not exist "%PY%" (
    echo Setting up for the first time...
    python -m venv ".venv"
    if errorlevel 1 goto fail
    "%PY%" -m pip install --quiet --upgrade pip
    "%PY%" -m pip install --quiet -r "%~dp0requirements.txt"
    if errorlevel 1 goto fail
)

set "PYTHONPATH=%~dp0"
if defined DICTATE_ORIGIN (
    "%PY%" -m dictate.server --origin "%DICTATE_ORIGIN%"
) else (
    "%PY%" -m dictate.server
)
goto :eof

:fail
echo.
echo Setup failed. Check that Python is installed and on PATH, then run again.
exit /b 1