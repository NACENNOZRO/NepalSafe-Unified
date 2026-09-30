"""
report_generator.py
Builds the JSON report object for a single analyzed image and prints a
human-readable summary. (Email/notify step intentionally left out here --
see notifier.py note in the README if you need to add it back later.)
"""

import json
import os
from datetime import datetime, timezone


def build_report(image_path: str, analysis: dict, user_id: str = None,
                  location: dict = None) -> dict:
    """
    `location` comes from Module 4 (location_extractor.resolve_location):
        {"lat": float|None, "lng": float|None, "source": "exif"|"manual"|"unavailable"}
    "source" tells the reader WHERE the coordinates came from -- GPS
    metadata embedded in the photo itself, a manually supplied --lat/--lng,
    or neither (never guessed from image content).
    """
    return {
        "report_generated_utc": datetime.now(timezone.utc).isoformat(),
        "image_file": os.path.basename(image_path),
        "user_id": user_id,
        "location": location if location is not None else {"lat": None, "lng": None, "source": "unavailable"},
        "analysis": analysis,
    }


def save_json_report(report: dict, out_path: str) -> str:
    with open(out_path, "w") as f:
        json.dump(report, f, indent=2)
    return out_path


def print_summary(report: dict) -> None:
    a = report["analysis"]
    loc = report.get("location") or {"lat": None, "lng": None, "source": "unavailable"}
    if loc["lat"] is not None and loc["lng"] is not None:
        loc_str = f"{loc['lat']}, {loc['lng']}  (source: {loc['source']})"
    else:
        loc_str = "unavailable (no GPS EXIF data, no manual --lat/--lng given)"

    print("=" * 55)
    print(f"Image           : {report['image_file']}")
    print(f"Location        : {loc_str}")
    print(f"Disaster Type   : {a['disaster_type']}")
    print(f"Verdict         : {a['verdict']}")
    print(f"Severity        : {a['severity']} (score {a['severity_score']})")
    print(f"Flood detected  : {a['flood_detected']} ({a['water_coverage_pct']}% water coverage, region texture {a['region_texture_score']})")
    print(f"Damage detected : {a['damage_detected']} (texture score {a['texture_score']})")
    print(f"Fire detected   : {a['fire_detected']} ({a['fire_coverage_pct']}% fire-color, {a['dark_background_pct']}% dark bg)")
    print(f"Confidence      : {a['confidence_pct']}%")
    print("=" * 55)
