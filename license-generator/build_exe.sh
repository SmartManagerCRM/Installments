#!/usr/bin/env bash
# Builds the standalone license generator for the CURRENT operating system (PyInstaller cannot cross-compile).
set -euo pipefail
cd "$(dirname "$0")"
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements-build.txt
python -m unittest discover -s tests -t .          # never ship a build whose tests fail
pyinstaller --clean --noconfirm smilicgen.spec
echo "Built: $(pwd)/dist/SmartManagerLicenseGenerator"
sha256sum dist/SmartManagerLicenseGenerator* 2>/dev/null || shasum -a 256 dist/SmartManagerLicenseGenerator*
