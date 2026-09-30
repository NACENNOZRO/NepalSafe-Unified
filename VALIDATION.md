# Validation and limitations

## Performed

- 5 alert-store tests passed: restart persistence, stable event IDs, bounded chronological history,
  concurrent writes and rejection of invalid numeric records without corrupting existing data.
- 3 API tests were skipped because FastAPI, HTTPX, multipart and OpenCV are unavailable here.
  They are supplied for execution after dependencies are installed; they are not counted as passed.
- Python source syntax and every Android resource XML parsed successfully.
- Every original `@+id` in the supplied Android layout files remains present after the interface changes.
- Byte comparisons against the supplied archives confirmed no changes to `NearbyMeshManager.kt`,
  `LifelineService.kt`, `MeshPacket.kt`, `PacketStore.kt`, `analyzer.py`, `location_extractor.py`,
  `report_generator.py`, `transmitter.py`, or the separate Module 4 Expo `App.js`.

These are source/storage checks, not an Android compilation or end-to-end certification.

## Build/environment blockers observed

- No Android SDK or installed Gradle distribution was available.
- The supplied Gradle wrapper attempted `gradle-8.9-bin.zip` and failed with
  `java.net.SocketException: Network is unreachable`.
- Installing backend requirements plus HTTPX failed: no matching distribution was available from
  the accessible package source. Backend HTTP and WebSocket execution was not possible.
- No Android device/emulator was available for camera, BLE/Wi-Fi Direct, voice, background relay
  or visual layout testing. Large-font wrapping is implemented in XML but not device-rendered here.
- No new APK was built. The earlier `NepalSafe-Unified-debug.apk` was already in the original ZIP;
  it does not contain the new source changes and is not included as a new result in this package.

## Integration boundaries

- Only `module2 copy.zip`, `module4.zip` and the architecture PDF were accessible.
- Original Modules 1 and 3 were not read, reconstructed or replaced.
- Live warning, prediction, original chatbot and route endpoints must come from those modules.
- Image-analysis forwarding still requires the real `ADMIN_API_URL` and any required authentication.
- Existing EXIF-first location precedence is retained in the original image module, even though an
  older README describes phone GPS first. No speculative algorithm change was made.
- SQLite history supports restarts; WebSocket subscribers remain process-local. Run one worker.
- The original map still loads Leaflet and tiles from the internet. No offline maps are claimed.
- The original mesh implementation is retained. Its existing authentication/encryption and internet
  gateway limitations are described in `android/ORIGINAL_README.md`; they have not been rewritten.

## Changes made

| Area | Change |
| --- | --- |
| Main dashboard | Service readiness, direct community report access, visual hierarchy and consistent action labels |
| Layout controls | Minimum touch height and wrapping text instead of fixed-height clipping |
| Connection settings | Validate HTTP(S) base URLs; test actual read-only API response contracts; testing no longer silently saves |
| Location actions | Check-in, online SOS, route and community report require a recent real location; default map coordinates are not sent as a user location |
| Camera analysis | Only a recent location is attached; otherwise the existing EXIF/unavailable logic handles location |
| Map | No false user marker for fallback coordinates; community tooltip text is escaped |
| Route failure | Removes an unverified hardcoded downhill instruction when the route service fails |
| Image alerts | Poll all recent alerts, snapshot feed state safely, avoid popups on stopped screens, clear reconnect callbacks on stop |
| Image backend | Persist recent alerts, bound multipart reads, validate base64 and coordinate ranges |
| Handoff | Build script, optional CI workflow, API checker, storage/API tests and smaller source-archive helper |

All original files are retained except generated dependencies/caches/build outputs and the Mac-specific
`local.properties`. The original Android README is retained as `ORIGINAL_README.md`.
