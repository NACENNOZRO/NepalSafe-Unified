# NepalSafe

### Android safety systems · Offline SOS · Environmental reporting

NepalSafe is a student engineering project integrating an Android safety dashboard, mesh SOS communication, community reporting and an image-analysis service. This repository preserves the available integration source and its documented validation limits.

**Stage:** Integration prototype · **Native client:** Kotlin / Android · **Image backend:** Python · **Additional client:** Expo / React Native

## Source files

Browse `android/`, `module4/`, `tools/` and `tests/` directly in this repository. The original [NepalSafe-Integration-Source.zip](NepalSafe-Integration-Source.zip) is retained as a source snapshot.

| Folder | Contents |
| --- | --- |
| `android/` | Native dashboard, mesh transport, SOS storage and Android resources |
| `module4/` | Expo client, native integration snippets and Python image service |
| `tools/` | Android build, service checks and source packaging helpers |
| `tests/` | Alert storage and backend API tests |

## Integration status

The archive includes the supplied native application and Module 4. Original Module 1 and Module 3 sources were unavailable when this package was produced. Android compilation and end-to-end device testing remain unverified. See [VALIDATION.md](VALIDATION.md) for the recorded checks and remaining work.

## Setup and technical notes

### Original integration package notes

This is an updated **source package**, not a newly compiled APK or an end-to-end verified release.
Module 2's supplied native Android project remains the launcher and dashboard. Existing mesh transport,
SOS packet storage, voice recording, manual connections, and image-analysis algorithms are preserved.

## What is included

- `android/`: the supplied native app, with clearer dashboard navigation, live service readiness,
  larger/wrapping controls, direct community reporting, and connection validation.
- `module4/`: the original Expo client, native integration snippets, assets and Python backend.
  The Python service now persists recent alerts to SQLite; its original analyzer is unchanged.
- `tools/build_android.py`: builds, runs unit tests and Android lint, and copies the resulting APK.
- `tools/run_vision.py`: starts the supplied image-analysis server.
- `tools/check_services.py`: checks real warning/forecast/image-alert contracts without sending SOS.
- `tools/pack_module_sources.py`: reduces module upload size by omitting dependency caches,
  virtual environments and Gradle build outputs. It preserves source, assets and model/data files.
- `tests/`: local alert storage tests plus API integration tests requiring the backend dependencies.
- `.github/workflows/android.yml`: an optional build workflow described by the original package; it is not included in this source import.

## What remains blocked

**Module 1 and Module 3 original archives were not accessible.** Their source, trained models,
backend routes and chatbot cannot be reconstructed from a PDF without replacing your work.
The native project already contains warning, community and route client screens, but that is not
proof that every feature from those original modules is present.

The supplied Module 4 backend's admin destination is also unset (`ADMIN_API_URL`). Its image result
can work independently, but photo-to-prediction-engine forwarding requires the original receiver URL.
No substitute prediction model, fake chatbot, fake live risk values or fake route service is provided.

The supplied PDF uses module numbers differently from the ZIP contents. This package follows your
explicit instruction: **the project inside module2 copy.zip stays the main dashboard**. The PDF is
used for architectural context, not to replace the existing code or relocate its operating region.

## Build on your computer

1. Install Android Studio, JDK 17 or newer, Android SDK Platform 35 and Android SDK Build-Tools.
2. Open `android/` in Android Studio, or set `ANDROID_HOME` to your SDK directory.
3. From this package folder run:

```bash
python3 tools/build_android.py
```

The Gradle wrapper downloads its declared version and dependencies. The output, after all gates pass,
is `build-output/NepalSafe-Integration-debug.apk`. On Windows use `python` instead of `python3`.
The existing version code and application ID are retained; use your existing signing configuration
when replacing a previous installation. Do not uninstall an installation containing SOS history just
to solve a signing mismatch.

The original machine-specific `local.properties` is omitted. Open Android Studio to regenerate it.
No newly built APK is included because the execution environment had no Android SDK/Gradle distribution
and blocked the required download. Kotlin compilation, lint and device UI rendering are therefore unverified.

## Run the existing image service

Create and activate a Python virtual environment, then:

```bash
python -m pip install -r module4/backend/requirements.txt httpx
python tools/run_vision.py
```

Configure `ADMIN_API_URL` to the **actual original prediction-engine report receiver**, and
`ADMIN_API_KEY` if required, before starting the service. Do not guess a receiver path.
`ALERT_DB_PATH` can point to a persistent disk; by default it is `module4/backend/data/alerts.sqlite3`.
Run one service worker for this version; live WebSocket fan-out remains process-local as in the original.

Run your original warning/community/routing backend on its existing port (the native app defaults to 8000).
The image service here listens on 8001. On a physical phone on the same Wi-Fi, open Connection settings
and use your computer's LAN address, not `10.0.2.2`. That emulator-only address remains the original default.
For a remotely hosted installation use the actual HTTPS service addresses.

```bash
python tools/check_services.py --warning http://YOUR-LAN-IP:8000 --vision http://YOUR-LAN-IP:8001
python -m unittest discover -s tests -v
```

Read-only service checks validate response structure, not hazard accuracy, delivery or route safety.

## Recover the missing modules without a 600 MB combined upload

Run this against your original module folders; keep the output directory outside them:

```bash
python3 tools/pack_module_sources.py "/path/to/module1" "/path/to/module3" --output "/path/to/source-uploads"
```

It creates separate `module1-source.zip` and `module3-source.zip` files. It does not edit the originals.
Model weights, datasets and assets are retained. If those make the files large, they must still be provided;
this helper does not silently discard them. Source ZIPs should include lockfiles and build scripts.

## Device acceptance checklist — not yet run

- Dashboard launches with readable controls at normal and large font settings; all original controls remain.
- Two phones approve permissions, discover/connect, exchange text and recorded voice SOS packets.
- Repeat with internet off, screen off, restart and a third relay phone; verify persistent queued messages.
- A running original warning backend supplies current hazards and forecasts; a stopped backend shows
  an unavailable/saved state rather than inventing data.
- Online SOS, resolve, check-in, direct help request/answer and community photo report work on test accounts.
- Camera and gallery each produce real analysis; deny location and verify EXIF/unavailable handling.
- Analyzed report reaches the original prediction engine; verify its response and the risk map update.
- Alerts survive an image-server restart and appear via WebSocket and polling.
- Original chatbot and safe route behavior must be compared against Modules 1 and 3 when received.
- Check map behavior offline: the original Leaflet code and OSM tiles currently require internet;
  this package does not falsely claim offline map caching or verified hazard-avoiding navigation.

See `VALIDATION.md` for the exact checks performed and remaining limits.
