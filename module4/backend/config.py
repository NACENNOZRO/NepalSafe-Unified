"""
config.py
Central place for settings that differ between dev/staging/prod.

IMPORTANT: ADMIN_API_URL below is a PLACEHOLDER. Replace it with your
real admin ML prediction index endpoint before deploying, or set it via
the ADMIN_API_URL environment variable (preferred -- keeps secrets out
of source control).
"""

import os

# ---- Admin ML prediction index (Module 4 target) ----
# TODO: REPLACE_ME with your real endpoint, e.g.
#   "https://admin.yourdomain.com/api/v1/disaster-reports"
ADMIN_API_URL = os.environ.get("ADMIN_API_URL", "https://REPLACE_ME.example.com/api/disaster-reports")

# Set via env var if/when the admin endpoint needs auth. Currently unset
# because you said no auth is needed yet -- add one later without
# touching transmitter.py, it already checks for this.
ADMIN_API_KEY = os.environ.get("ADMIN_API_KEY", "")

ADMIN_API_TIMEOUT_SECONDS = float(os.environ.get("ADMIN_API_TIMEOUT_SECONDS", "10"))

# ---- Upload handling ----
MAX_UPLOAD_SIZE_MB = 15
ALLOWED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
UPLOAD_TMP_DIR = os.environ.get("UPLOAD_TMP_DIR", "/tmp/disaster_uploads")
