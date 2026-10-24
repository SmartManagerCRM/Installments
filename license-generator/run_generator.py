"""Entry point used by PyInstaller (and handy for `python run_generator.py`)."""
import sys

from smilicgen.cli import main

if __name__ == "__main__":
    sys.exit(main())
