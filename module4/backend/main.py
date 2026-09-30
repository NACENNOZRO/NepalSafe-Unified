"""
main.py
FastAPI backend for the Disaster Image Analysis system.

This is what the Android APK talks to. Flow per request:

  1. APK uploads a photo (multipart/form-data), optionally with lat/lng
     the phone already has from GPS (Android's FusedLocationProviderClient
     is usually more accurate/faster than EXIF, so the client MAY send it
     directly -- see /analyze below for the priority order).
  2. Module 1-3 (analyzer.py) runs the CV heuristics on the photo.
  3. Module 4 (location_extractor.py) resolves the final lat/lng:
       client-supplied GPS > photo EXIF GPS > "unavailable"
  4. report_generator.py builds the JSON report.
  5. Module 4 (transmitter.py) forwards the report to the admin ML
     prediction index (config.py: ADMIN_API_URL).
  6. The same report is returned to the APK so the citizen sees their
     own result immediately, regardless of whether step 5 succeeded.

IMPORTANT (fixed during troubleshooting): analyzing an image (OpenCV) and
transmitting to the admin server (requests.post) are both CPU/IO-blocking
calls. Running them directly inside an `async def` endpoint would freeze
FastAPI's single event loop for every other concurrent request -- fine
with one test user, bad with a real APK with many simultaneous citizens.
All blocking work is done in `_process_report()` and dispatched via
`run_in_threadpool`, which runs it on a worker thread instead.

Run locally:
    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8000 --reload

Then from the APK, POST to:  http://<your-server>:8000/analyze
"""

import base64
import json
import logging
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, List

from fastapi import FastAPI, UploadFile, File, Form, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from alert_store import AlertStore

from analyzer import analyze_image
from location_extractor import resolve_location
from report_generator import build_report
from transmitter import transmit_report, TransmissionError
from config import MAX_UPLOAD_SIZE_MB, ALLOWED_IMAGE_EXTENSIONS, UPLOAD_TMP_DIR

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("main")

app = FastAPI(
    title="Disaster Image Analysis API",
    description="Backend for the citizen-reporting APK: analyzes uploaded "
                 "photos for flood/fire/structural-damage signals, resolves "
                 "location, and forwards results to the admin ML prediction index.",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

Path(UPLOAD_TMP_DIR).mkdir(parents=True, exist_ok=True)

# ---- Live Alert Broadcast System ----
active_websockets: List[WebSocket] = []
ALERT_STORE = AlertStore(os.environ.get("ALERT_DB_PATH", str(Path(__file__).parent / "data" / "alerts.sqlite3")))
ALERT_HISTORY: List[dict] = ALERT_STORE.recent(50)

async def broadcast_alert(alert_data: dict):
    """Sends disaster alert to all connected mobile clients."""
    await run_in_threadpool(ALERT_STORE.add, alert_data)
    ALERT_HISTORY.append(alert_data)
    # keep last 50 alerts
    if len(ALERT_HISTORY) > 50:
        ALERT_HISTORY.pop(0)

    disconnected = []
    for ws in list(active_websockets):
        try:
            await ws.send_json({"type": "DISASTER_ALERT", "alert": alert_data})
        except Exception:
            disconnected.append(ws)
    for ws in disconnected:
        if ws in active_websockets:
            active_websockets.remove(ws)

@app.websocket("/ws/alerts")
async def websocket_alerts(websocket: WebSocket):
    await websocket.accept()
    active_websockets.append(websocket)
    try:
        # Send recent disaster alerts immediately upon connection
        await websocket.send_json({
            "type": "INIT_ALERTS",
            "alerts": ALERT_HISTORY[-5:]
        })
        while True:
            # Heartbeat / message listen
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning(f"WebSocket error: {e}")
    finally:
        if websocket in active_websockets:
            active_websockets.remove(websocket)

@app.get("/alerts")
def get_alerts():
    """Returns recent disaster alerts for polling or app initial load."""
    return {"alerts": ALERT_HISTORY[-20:]}

@app.get("/alerts/latest")
def get_latest_alert():
    """Returns the single latest disaster alert."""
    latest = ALERT_HISTORY[-1] if ALERT_HISTORY else None
    return {"latest": latest, "total_alerts": len(ALERT_HISTORY)}


@app.get("/health")
def health_check():
    """Simple liveness check the APK (or a load balancer) can poll."""
    return {"status": "ok"}


def _process_report(
    tmp_path: str,
    original_filename: str,
    user_id: Optional[str],
    lat: Optional[float],
    lng: Optional[float],
) -> dict:
    """
    All the blocking work for one request, run off the event loop via
    run_in_threadpool. Raises HTTPException on failures the caller should
    surface to the client; returns the finished report dict otherwise.
    """
    try:
        analysis = analyze_image(tmp_path)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=f"Could not analyze image: {e}")

    # Module 4: location resolution.
    # App-supplied live GPS takes priority over EXIF (phone GPS at
    # capture time is usually more accurate than whatever the camera
    # app wrote into EXIF, and some camera apps strip EXIF GPS anyway).
    location = resolve_location(tmp_path, manual_lat=lat, manual_lng=lng)
    if location["source"] == "manual":
        location["source"] = "app_gps"  # this came from the phone's live GPS, not a CLI flag

    report = build_report(original_filename, analysis, user_id, location)

    # Module 4: forward to admin ML prediction index.
    # Non-fatal: the citizen's own result is not blocked by this.
    #
    # "detail" is normalized to always be a string (json.dumps'd if the
    # admin server returned a JSON object). This matters for the Kotlin
    # client: AdminTransmissionStatus.detail is typed String? there, and
    # Gson will throw a parse exception if this field is sometimes a
    # string and sometimes a JSON object.
    try:
        admin_response = transmit_report(report)
        detail = admin_response if isinstance(admin_response, str) else json.dumps(admin_response)
        admin_status = {"transmitted": True, "detail": detail}
    except TransmissionError as e:
        logger.warning(f"Admin transmission failed (non-fatal): {e}")
        admin_status = {"transmitted": False, "detail": str(e)}

    report["admin_transmission"] = admin_status
    return report


class AnalyzeJsonRequest(BaseModel):
    image_base64: str
    filename: Optional[str] = "disaster_photo.jpg"
    user_id: Optional[str] = None
    lat: Optional[float] = Field(None, ge=-90, le=90)
    lng: Optional[float] = Field(None, ge=-180, le=180)


@app.post("/analyze-json")
async def analyze_json(payload: AnalyzeJsonRequest):
    """
    JSON endpoint accepting base64-encoded image.
    Bypasses any mobile FormData / multipart serialization issues completely.
    """
    try:
        raw_b64 = payload.image_base64
        if "," in raw_b64:
            raw_b64 = raw_b64.split(",", 1)[1]
        if len(raw_b64) > ((MAX_UPLOAD_SIZE_MB * 1024 * 1024 + 2) // 3) * 4:
            raise HTTPException(status_code=413, detail="Image exceeds upload limit")
        image_bytes = base64.b64decode(raw_b64, validate=True)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid base64 image data: {e}")

    if len(image_bytes) == 0:
        raise HTTPException(status_code=400, detail="Uploaded base64 image is empty.")

    size_mb = len(image_bytes) / (1024 * 1024)
    if size_mb > MAX_UPLOAD_SIZE_MB:
        raise HTTPException(status_code=413, detail=f"Image too large ({size_mb:.1f} MB, max {MAX_UPLOAD_SIZE_MB} MB)")

    tmp_path = os.path.join(UPLOAD_TMP_DIR, f"{uuid.uuid4().hex}.jpg")

    def _write_file():
        with open(tmp_path, "wb") as f:
            f.write(image_bytes)

    try:
        await run_in_threadpool(_write_file)
        report = await run_in_threadpool(
            _process_report, tmp_path, payload.filename or "photo.jpg", payload.user_id, payload.lat, payload.lng
        )

        # Broadcast alert to all connected mobile clients if disaster detected
        if report.get("analysis", {}).get("is_problem"):
            alert_payload = {
                "id": uuid.uuid4().hex[:8],
                "timestamp_utc": report.get("report_generated_utc", datetime.now(timezone.utc).isoformat()),
                "disaster_type": report["analysis"]["disaster_type"],
                "verdict": report["analysis"]["verdict"],
                "severity": report["analysis"]["severity"],
                "severity_score": report["analysis"]["severity_score"],
                "confidence_pct": report["analysis"]["confidence_pct"],
                "location": report.get("location", {}),
                "user_id": payload.user_id,
            }
            await broadcast_alert(alert_payload)

        return JSONResponse(content=report)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


@app.post("/analyze")
async def analyze(
    image: UploadFile = File(..., description="The photo taken by the citizen"),
    user_id: Optional[str] = Form(None, description="Optional citizen/user id from the app"),
    lat: Optional[float] = Form(None, ge=-90, le=90, description="GPS latitude from the phone, if the app captured it directly"),
    lng: Optional[float] = Form(None, ge=-180, le=180, description="GPS longitude from the phone, if the app captured it directly"),
):
    """
    Main multipart endpoint the APK calls after a citizen takes a photo.
    """
    filename = image.filename or "photo.jpg"
    ext = os.path.splitext(filename)[1].lower()
    if not ext or ext not in ALLOWED_IMAGE_EXTENSIONS:
        ext = ".jpg"

    contents = await image.read(MAX_UPLOAD_SIZE_MB * 1024 * 1024 + 1)
    await image.close()

    if len(contents) == 0:
        raise HTTPException(status_code=400, detail="Uploaded file is empty.")

    size_mb = len(contents) / (1024 * 1024)
    if size_mb > MAX_UPLOAD_SIZE_MB:
        raise HTTPException(status_code=413, detail=f"Image too large ({size_mb:.1f} MB, max {MAX_UPLOAD_SIZE_MB} MB)")

    tmp_path = os.path.join(UPLOAD_TMP_DIR, f"{uuid.uuid4().hex}{ext}")

    def _write_tmp_file():
        with open(tmp_path, "wb") as f:
            f.write(contents)

    try:
        await run_in_threadpool(_write_tmp_file)
        report = await run_in_threadpool(
            _process_report, tmp_path, filename, user_id, lat, lng
        )

        # Broadcast alert to all connected mobile clients if disaster detected
        if report.get("analysis", {}).get("is_problem"):
            alert_payload = {
                "id": uuid.uuid4().hex[:8],
                "timestamp_utc": report.get("report_generated_utc", datetime.now(timezone.utc).isoformat()),
                "disaster_type": report["analysis"]["disaster_type"],
                "verdict": report["analysis"]["verdict"],
                "severity": report["analysis"]["severity"],
                "severity_score": report["analysis"]["severity_score"],
                "confidence_pct": report["analysis"]["confidence_pct"],
                "location": report.get("location", {}),
                "user_id": user_id,
            }
            await broadcast_alert(alert_payload)

        return JSONResponse(content=report)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
