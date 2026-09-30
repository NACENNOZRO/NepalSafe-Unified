"""
location_extractor.py
Module 4 -- Location extraction for the Disaster Image Module.

Purpose: when a citizen/user takes a photo on their phone, most cameras
embed the GPS coordinates (where the photo was taken) into the image's
EXIF metadata automatically. This module reads that metadata so the
pipeline knows WHERE the photo was taken, without the user having to type
lat/lng manually.

Behavior:
 - If the image has EXIF GPS tags -> return those coordinates, source="exif".
 - If not (screenshot, downloaded image, GPS off, stripped metadata,
   non-JPEG format that doesn't carry EXIF, etc.) -> return None, and the
   pipeline falls back to whatever --lat/--lng the caller supplied
   manually, source="manual". If neither is available, location stays
   None and the report says so -- it is never guessed or fabricated.

This is read-only metadata extraction from the file the user already
provided. It does not access device location services, does not track
the user, and does not infer location from image content (that is not
something pixels alone can reliably tell you).
"""

from PIL import Image
from PIL.ExifTags import TAGS, GPSTAGS


def _get_exif_dict(image_path: str) -> dict:
    """Returns the raw EXIF dict (tag name -> value) for an image, or {}."""
    try:
        img = Image.open(image_path)
        raw_exif = img._getexif()  # may be None
    except Exception:
        return {}

    if not raw_exif:
        return {}

    exif = {}
    for tag_id, value in raw_exif.items():
        tag_name = TAGS.get(tag_id, tag_id)
        exif[tag_name] = value
    return exif


def _get_gps_dict(exif: dict) -> dict:
    """Converts the raw GPSInfo block into named GPS tags."""
    gps_raw = exif.get("GPSInfo")
    if not gps_raw:
        return {}

    gps = {}
    for key, value in gps_raw.items():
        tag_name = GPSTAGS.get(key, key)
        gps[tag_name] = value
    return gps


def _dms_to_decimal(dms, ref) -> float:
    """
    Converts EXIF GPS degrees/minutes/seconds (as a tuple of 3 rationals)
    plus a hemisphere reference ('N'/'S'/'E'/'W') into signed decimal degrees.

    Fixed during troubleshooting: some phone/Pillow combinations store the
    ref as bytes (b'S') instead of str ('S'). Comparing bytes to a str
    tuple silently fails (b'S' in ("S","W") is False), which would give a
    positive-only latitude/longitude for photos taken in the Southern or
    Western hemisphere -- a wrong location with no error raised. Decoding
    bytes to str here closes that gap.
    """
    if isinstance(ref, bytes):
        ref = ref.decode("ascii", errors="ignore")

    degrees = float(dms[0])
    minutes = float(dms[1])
    seconds = float(dms[2])

    decimal = degrees + (minutes / 60.0) + (seconds / 3600.0)

    if ref in ("S", "W"):
        decimal = -decimal

    return decimal


def extract_gps_from_exif(image_path: str):
    """
    Attempts to read GPS coordinates embedded in the photo's EXIF metadata.

    Returns:
        (lat, lng) as floats if GPS EXIF tags are present and valid.
        None if the image has no usable GPS metadata.
    """
    exif = _get_exif_dict(image_path)
    gps = _get_gps_dict(exif)

    if not gps:
        return None

    try:
        lat_dms = gps["GPSLatitude"]
        lat_ref = gps["GPSLatitudeRef"]
        lng_dms = gps["GPSLongitude"]
        lng_ref = gps["GPSLongitudeRef"]
    except KeyError:
        return None

    try:
        lat = _dms_to_decimal(lat_dms, lat_ref)
        lng = _dms_to_decimal(lng_dms, lng_ref)
    except (TypeError, ValueError, IndexError, ZeroDivisionError):
        return None

    # Sanity bounds -- reject corrupt/garbage EXIF rather than passing junk on
    if not (-90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0):
        return None

    return (round(lat, 6), round(lng, 6))


def resolve_location(image_path: str, manual_lat=None, manual_lng=None) -> dict:
    """
    Single entry point used by the pipeline.

    Priority:
      1. GPS EXIF metadata embedded in the photo itself (source="exif")
      2. Manually supplied --lat/--lng (source="manual")
      3. Neither available (source="unavailable", lat/lng = None)

    Returns a dict: {"lat": float|None, "lng": float|None, "source": str}
    """
    exif_coords = extract_gps_from_exif(image_path)

    if exif_coords is not None:
        lat, lng = exif_coords
        return {"lat": lat, "lng": lng, "source": "exif"}

    if manual_lat is not None and manual_lng is not None:
        return {"lat": manual_lat, "lng": manual_lng, "source": "manual"}

    return {"lat": None, "lng": None, "source": "unavailable"}
