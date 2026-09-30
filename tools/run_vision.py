#!/usr/bin/env python3
"""Run the supplied image analysis service; no replacement backend is generated."""
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, '-m', 'uvicorn', 'main:app', '--host', '0.0.0.0', '--port', '8001'], cwd=root/'module4/backend', check=True)
