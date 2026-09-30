# NepalSafe — Unified Android APK

This project is now the integrated NepalSafe APK. The original Module 2 Lifeline remains the launcher and main dashboard, with every existing mesh/SOS feature preserved. The dashboard also opens:

- **Live Hazards, Community & Routes** — the shared Module 1/Module 3 feature set: live/cached Langtang conditions, four XGBoost hazard pipelines, 72-hour rain/snow forecasts, people nearby, direct help requests, online SOS, queued offline relay, safe routing, OpenStreetMap, and compressed photo alerts.
- **Camera Disaster Analysis** — Module 4: camera/gallery input, GPS with EXIF fallback on the server, flood/damage/fire analysis, severity and confidence details, admin-index forwarding status, backend health, WebSocket live alerts, polling fallback, vibration popups, and community alert history.
- **Backend Connection Settings** — editable endpoints so emulator, LAN phone, or deployed-server builds work without recompilation.

The default endpoints are intended for an Android emulator:

```text
Early warning/community: http://10.0.2.2:8000
Image analysis:          http://10.0.2.2:8001
```

On a physical phone, open **Backend Connection Settings** and replace `10.0.2.2` with the computer's LAN IP or deployed HTTPS address.

Run the supplied backends in separate terminals:

```bash
cd ../module1_ext/NepalSafe-Langtang/NepalSafe2/backend
uvicorn main:app --host 0.0.0.0 --port 8000

cd ../module4_ext/disaster_apk_module/disaster_apk_module/backend
uvicorn main:app --host 0.0.0.0 --port 8001
```

Build the complete APK with Android Studio's Java runtime:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test lintDebug assembleDebug
```

The generated APK is `app/build/outputs/apk/debug/app-debug.apk`; the handoff copy is `output/NepalSafe-Unified-debug.apk`.

## Original Lifeline module

This repository contains a standalone Android APK for **Module 1 (The Lifeline)** from the NepalSafe architecture. Phones running the same app discover each other through Google Nearby Connections (Bluetooth + Wi-Fi Direct), exchange SOS packets, and relay new packets to their other connected peers.

The ready-to-install debug build is `output/NepalSafe-Lifeline-debug.apk`.

Version 0.9.1 updates NepalSafe Lifeline with a simplified, civilian-centric UI and direct system popups:
- Direct system dialogs for runtime permissions (Location, Nearby Devices, Notifications) and native battery optimization ("Let app always run in background?") without intermediate friction screens.
- One-tap quick emergency chips (Flood, Landslide, Medical, Trapped, Food/Water) to compose and broadcast an SOS in under 3 seconds.
- Visual message journey cards with color-coded status badges (Relayed, Waiting, Received) instead of raw text logs.
- Automatic mesh, store-and-forward SQLite queuing, duplicate suppression, and 15s retry sweep remain 100% intact.
- Manual Host/Find Nodes controls remain available under **Advanced connection options**.

## What works

- Continuous automatic advertising and discovery with the `P2P_CLUSTER` topology, crossed-request prevention, and randomized compatibility fallback.
- Manual **Host** and **Find Nodes** modes with a selectable device list and two-phone code approval.
- An on-screen readiness status for permissions, Bluetooth, Wi-Fi, GPS, and Google Play services that vanishes when all requirements are satisfied.
- A persistent hop monitor showing when each SOS is created, paused, received, and forwarded.
- SOS packets require latitude and longitude from a valid GPS/location fix.
- Durable SQLite store-and-forward queue with no automatic packet expiry or deletion.
- Persistent duplicate suppression prevents loops even after the app process restarts.
- Automatic discovery, connection, and replay of all paused packets when a new node appears.
- A 15-second background queue sweep retries any missed or stalled saved-message transfer; successful packet/peer pairs are not repeatedly flooded during the connection.
- Automatic three-second connection retries and radio-role retries after failures.
- A foreground Lifeline service and bounded, renewed CPU wake lock keep scanning and relaying when the app leaves the screen or the display is off.
- Native Android battery-optimization exemption dialog allows background/screen-off relay, and active Lifeline is restored after reboot or app replacement.
- Local sent/received history with hop-count visibility.
- Android 8.0+ runtime permission handling.

## Demo on two phones

1. Install `NepalSafe-Lifeline-debug.apk` on two physical Android devices that include Google Play services.
2. Turn on Bluetooth, Wi-Fi, and Location. Mobile data may be turned off; do not enable airplane mode because it disables the radios used by Nearby Connections.
3. Open the app. The Android system permission pop-ups will directly appear for Location, Nearby Devices, and Notifications, followed by the native "Let app always run in background?" battery optimization prompt. Approve each prompt.
4. Tap **Start Lifeline** on every phone.
5. Wait until each phone reports a nearby connection.
6. On Phone A, tap a quick tag (e.g. **🌊 Flood**) or type an emergency message, and tap **🚨 Broadcast SOS Now**. Phone B displays and stores it. Add Phone C to demonstrate forwarding.

## Test screen-off replay

1. Start Lifeline in **Auto** mode on Phone A, allow screen-off relay, then move Phone B out of range or stop Lifeline there.
2. Send an SOS on Phone A. Its hop monitor must show **CREATED** followed by **PAUSED**.
3. Turn Phone A's display off. Do not swipe away or force-stop NepalSafe; its ongoing notification should remain.
4. Bring Phone B near Phone A and start Lifeline in **Auto** mode. Leave Phone A's display off for at least 30 seconds.
5. Phone B should receive the SOS automatically. Unlock Phone A and confirm a **FORWARDED** entry in the message hop monitor.

## Manual connection on two phones

1. Turn on Bluetooth, Wi-Fi, and Location on both phones, then open NepalSafe and allow every requested permission.
2. On Phone A, tap **Host**. Leave NepalSafe running; it should say that the host is visible.
3. On Phone B, tap **Find Nodes**. When Phone A appears, tap **Connect to…**.
4. Compare the authentication code shown on both phones. If it matches, tap **Accept** on both phones.
5. Wait for **Nearby phone connected**, then send an SOS to test message transfer.

System Bluetooth pairing is not used for this flow. The connection must be made inside NepalSafe. Automatic mode still accepts matching NepalSafe service connections automatically; manual mode requires code approval on both phones.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test assembleDebug
```

The generated APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Packet shape

```json
{
  "version": 2,
  "id": "uuid",
  "kind": "SOS",
  "senderName": "Maya",
  "originDeviceId": "installation-uuid",
  "message": "River is entering the house",
  "latitude": 27.9441,
  "longitude": 85.9483,
  "createdAt": 1789713000000,
  "hopCount": 0
}
```

## Important limitations

- Nearby Connections needs Google Play services; it does not work on devices without it.
- The ongoing Lifeline notification must remain active for background discovery. Force-stopping the app overrides Android's restart mechanisms and pauses radio discovery, but the SQLite message queue remains intact and resumes after Lifeline is started again.
- Some manufacturers add battery controls beyond standard Android. If the app still sleeps, set NepalSafe to **Unrestricted**, allow auto-start/background activity, and remove it from the vendor's sleeping-app list.
- Packets are intentionally retained indefinitely to satisfy the no-drop requirement. A production deployment should add cryptographically authenticated rescue acknowledgements before deleting delivered packets.
- Messages are not end-to-end encrypted or cryptographically signed yet. Do not use this hackathon build for real emergency operations.
- Reaching a phone with internet does not yet upload the SOS to a rescue backend because that endpoint is not part of the supplied Module 1 scope.
