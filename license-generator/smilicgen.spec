# PyInstaller spec - reproducible one-file build of the license generator.
#   pip install -r requirements-build.txt
#   pyinstaller --clean smilicgen.spec          ->  dist/SmartManagerLicenseGenerator(.exe)
# NEVER put signing keys into the build: the key lives in the owner's data directory, outside this folder.
from PyInstaller.utils.hooks import collect_data_files

a = Analysis(
    ["run_generator.py"],
    pathex=["."],
    datas=collect_data_files("tzdata"),
    hiddenimports=["tkinter", "zoneinfo"],
    excludes=["pytest"],
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas, [],
    name="SmartManagerLicenseGenerator",
    console=False,          # GUI app; use `python -m smilicgen <command>` (or a console build) for the CLI
    upx=False,
)
