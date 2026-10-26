#!/usr/bin/env bash
# Opens the SmartManager License Manager (macOS / Linux).  Needs Python 3.10+ with Tk (Linux: sudo apt install python3-tk)
cd "$(dirname "$0")"
python3 -c "import cryptography, tzdata" 2>/dev/null || python3 -m pip install -r requirements.txt
exec python3 -m smilicgen
