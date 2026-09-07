# Upgrade your existing RAMBO Scan repository to V2.5

Use the same GitHub repository that already produced a green APK build.

## Simplest replacement method

1. Download and extract `RamboScan_V2.5_DeepFallback.zip`.
2. Open your existing GitHub `RamboScan-` repository.
3. Replace the existing **app** folder with the V2.5 `app` folder contents. If GitHub's browser upload does not remove old files, that is fine; the old unused `scan_frame.xml` can remain without affecting behavior.
4. Upload/replace the `tests` folder so `knowledge_search_selftest.py` is included.
5. Replace `.github/workflows/build-apk.yml` with the V2.5 workflow.
6. Commit directly to `main` with: `Upgrade RAMBO Scan to V2.5 deep fallback`.
7. Open **Actions**. The build should start automatically.
8. Wait for the newest run to turn green.
9. Open the green run → **Artifacts** → download `RamboScan-V2.5-DeepFallback-debug-apk`.
10. Extract the artifact ZIP and install `app-debug.apk` over your current RAMBO Scan app.

## What to test on the phone

### Test 1 — full screen scanner
Open Scan. There should be no small center rectangle. The entire camera view is the scan area.

### Test 2 — local knowledge
Point at: `What is superheat?`
Expected: local knowledge should answer without requiring Deep Search.

### Test 3 — calculator
Point at: `Calculate IRR. Cash flows: -100, 30, 40, 50.`
Expected deterministic result: approximately **8.90%**.

### Test 4 — deep fallback
Point at a current or obscure question not represented in the local packs. The status should move from `SOLVE • LOCAL` to `DEEP SEARCH` if local evidence is weak or the local stage times out. Internet access is required only for this exception path.

### Test 5 — timing
Normal result: 20-second countdown.
Deep exception path: automatic reset no later than 90 seconds after recognition. The result countdown shows the time remaining inside that hard cap.
