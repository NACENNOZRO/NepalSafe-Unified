"""
transmitter.py
Module 4 -- sends a completed disaster analysis report to the admin
ML prediction index.

This is intentionally a thin, isolated module: main.py builds the report
(Modules 1-4), then hands it here to be transmitted. If you ever change
where reports go (a different admin server, a message queue, a database
instead of an HTTP endpoint), this is the only file you touch.

Configure the real endpoint in config.py (ADMIN_API_URL) or via the
ADMIN_API_URL environment variable -- do not hardcode it here.
"""

import logging
import requests

from config import ADMIN_API_URL, ADMIN_API_KEY, ADMIN_API_TIMEOUT_SECONDS

logger = logging.getLogger("transmitter")


class TransmissionError(Exception):
    """Raised when the admin server could not be reached or rejected the report."""
    pass


def transmit_report(report: dict) -> dict:
    """
    POSTs the full report JSON (location + analysis + metadata) to the
    admin ML prediction index.

    Returns the admin server's JSON response on success.
    Raises TransmissionError on network failure, timeout, or a non-2xx
    response -- callers decide whether that should fail the whole request
    or just be logged (see main.py: currently logged, not fatal, so a
    citizen still gets their analysis result even if the admin server is
    temporarily down).
    """
    if not ADMIN_API_URL or "REPLACE_ME" in ADMIN_API_URL:
        logger.warning(
            "ADMIN_API_URL is still a placeholder — skipping transmission. "
            "Set the real admin endpoint in config.py or the ADMIN_API_URL env var."
        )
        raise TransmissionError("ADMIN_API_URL is not configured yet (placeholder value).")

    headers = {"Content-Type": "application/json"}
    if ADMIN_API_KEY:
        headers["Authorization"] = f"Bearer {ADMIN_API_KEY}"

    try:
        response = requests.post(
            ADMIN_API_URL,
            json=report,
            headers=headers,
            timeout=ADMIN_API_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        logger.error(f"Failed to transmit report to admin index: {e}")
        raise TransmissionError(str(e)) from e

    try:
        return response.json()
    except ValueError:
        return {"status": "sent", "raw_response": response.text}
