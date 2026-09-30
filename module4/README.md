# Disaster Image Analysis Module — Setup & Troubleshooting Notes

## Folder structure
```
disaster_apk_module/
├── backend/                    FastAPI server (deploy this separately, e.g. on your own VM/cloud)
│   ├── main.py                 POST /analyze endpoint — entry point the APK calls
│   ├── analyzer.py             Modules 1-3: flood/damage/fire CV heuristics
│   ├── location_extractor.py   Module 4: resolves lat/lng (app GPS > EXIF > unavailable)
│   ├── report_generator.py     Builds the final JSON report
│   ├── transmitter.py          Module 4: forwards report to your admin ML prediction index
│   ├── config.py               ADMIN_API_URL, upload limits, etc.
│   └── requirements.txt
└── android_client/             Copy these into your existing APK's app module
    ├── MainActivity.kt         Camera capture → GPS → upload → show result
    ├── LocationHelper.kt       Module 4 (client side): live GPS fetch
    ├── ApiClient.kt            Retrofit client that calls the backend
    ├── AndroidManifest_snippet.xml   Permissions, <activity>, FileProvider — merge into your manifest
    ├── build_gradle_snippet.txt      Dependencies — add to your app's build.gradle
    └── res/
        ├── layout/activity_main.xml  UI for MainActivity.kt
        └── xml/file_paths.xml        FileProvider path config

```

## Two things YOU must configure before this works end-to-end

1. **`backend/config.py` → `ADMIN_API_URL`**
   Currently a placeholder (`https://REPLACE_ME.example.com/...`). Set it to your
   real admin ML prediction index endpoint, either by editing the file or
   setting the `ADMIN_API_URL` environment variable when you run the server.
   Until this is set, `/analyze` still returns the citizen's result normally —
   `admin_transmission.transmitted` will just be `false`.

2. **`android_client/ApiClient.kt` → `ApiConfig.BASE_URL`**
   Currently a placeholder. Set it to wherever you deploy `backend/main.py`
   (e.g. `https://your-server.com/`, or `http://10.0.2.2:8000/` for a local
   emulator pointing at your dev machine).

## Running the backend
```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```
Test it's alive: `GET http://<host>:8000/health` → `{"status": "ok"}`

## Merging into your existing APK
- Copy the 3 `.kt` files into your app's Kotlin source set (adjust the
  `package com.example.disasterreport` line to match your app's package).
- Merge `AndroidManifest_snippet.xml`'s permissions, `<activity>`, and
  `<provider>` blocks into your existing `AndroidManifest.xml` (don't just
  drop the file in — your manifest already exists).
- Merge `build_gradle_snippet.txt`'s dependencies into your app's
  `build.gradle`.
- Copy `res/layout/activity_main.xml` and `res/xml/file_paths.xml` into your
  app's matching `res/` folders — or, if `MainActivity` isn't your entry
  point, rename/adapt the layout and wire it into whichever screen calls this.
- If `MainActivity` should NOT be your launcher activity (i.e. you already
  have one), remove the `<intent-filter>` block from the `<activity>` entry
  in the manifest snippet.

## Every error found and fixed during troubleshooting

| # | File | Problem | Fix |
|---|------|---------|-----|
| 1 | `main.py` | `analyze_image()` and `requests.post()` (blocking calls) ran directly inside an `async def` endpoint — would freeze the server for all other concurrent citizens while one image was being processed | Moved all blocking work into `_process_report()`, dispatched via `run_in_threadpool` |
| 2 | `main.py` | Upload was read/written with blocking sync I/O (`shutil.copyfileobj`) inside the async function | Switched to `await image.read()` + threadpool-dispatched file write |
| 3 | `main.py` | `lat`/`lng`/`user_id` form fields typed as non-`Optional` — fragile across FastAPI/pydantic versions | Explicit `Optional[...]` typing |
| 4 | `main.py` ↔ `ApiClient.kt` | When admin transmission *succeeds*, `admin_transmission.detail` became a JSON object; the Kotlin side expects a `String`, and Gson would throw a parse exception the first time transmission actually succeeds | Backend now always `json.dumps()`s non-string `detail` values before returning |
| 5 | `location_extractor.py` | Some Pillow/phone combinations store the GPS hemisphere ref (`N`/`S`/`E`/`W`) as `bytes` instead of `str`; comparing `b'S' in ("S","W")` silently evaluates to `False`, producing a wrong-signed (positive instead of negative) latitude/longitude for Southern/Western hemisphere photos, with no error raised | Decode `bytes` refs to `str` before comparing |
| 6 | `MainActivity.kt` | `takePictureLauncher.launch(photoUri)` passed a nullable `Uri?` where the launcher requires non-null `Uri` — **compile error** | Launch with the local non-null `uri` instead of the nullable field |
| 7 | *(missing entirely)* | `MainActivity.kt` references `R.layout.activity_main` and `R.id.captureButton` / `previewImage` / `resultText`, but no layout XML existed — **guaranteed compile failure** | Added `res/layout/activity_main.xml` with matching ids |
| 8 | `build_gradle_snippet.txt` | `MainActivity.kt` uses `lifecycleScope`, but `androidx.lifecycle:lifecycle-runtime-ktx` was missing from the dependency list — **compile failure** (`unresolved reference: lifecycleScope`) | Added the dependency |
| 9 | `AndroidManifest_snippet.xml` | No `<activity>` declaration was provided for `MainActivity` | Added it, with a note to remove the launcher `<intent-filter>` if you already have one |

## Things that are correct-but-worth-knowing (not bugs, just be aware)

- **`analyzer.py`'s known limitation is intentional, not a bug**: it cannot
  always tell muddy floodwater apart from reddish-brown dry landslide
  debris — both share the same color range. When that happens it reports
  `"Flood or Landslide/Debris ... manual check recommended"` rather than
  guessing. This is documented in the file itself.
- **Location can legitimately be `"unavailable"`**: if the phone denies
  location permission, GPS is off, and the photo has no EXIF GPS, the
  report will have `lat`/`lng` as `null` and `source: "unavailable"` —
  by design, never fabricated.
- **`admin_transmission.transmitted: false` is expected** until you set a
  real `ADMIN_API_URL` — it does not mean the analysis failed.
