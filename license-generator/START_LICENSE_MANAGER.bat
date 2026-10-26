@echo off
REM Double-click to open the SmartManager License Manager (Windows).
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
  echo Python 3.10 or newer is required. Install it from https://www.python.org/downloads/ ^(tick "Add python.exe to PATH"^) and run this file again.
  pause
  exit /b 1
)
python -c "import cryptography, tzdata" 2>nul || python -m pip install -r requirements.txt
python -m smilicgen
if errorlevel 1 pause
