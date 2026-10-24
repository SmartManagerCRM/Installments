@echo off
REM Builds SmartManagerLicenseGenerator.exe on Windows (run from the license-generator folder).
python -m venv .venv || exit /b 1
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements-build.txt || exit /b 1
python -m unittest discover -s tests -t . || exit /b 1
pyinstaller --clean --noconfirm smilicgen.spec || exit /b 1
echo Built: %CD%\dist\SmartManagerLicenseGenerator.exe
certutil -hashfile dist\SmartManagerLicenseGenerator.exe SHA256
