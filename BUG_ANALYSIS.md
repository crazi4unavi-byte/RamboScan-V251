# V2.5 Bug / Failure Analysis

## Fixed

### Small scanner window looked like OCR was cropped
Cause: a 300dp × 180dp decorative `scan_frame` view sat over a full-screen PreviewView. OCR itself already used the complete image frame, but the UI implied a small scan area.
Fix: removed the small frame from the scanner layout. Camera preview and OCR remain full-frame.

### Local database seemed to add no APK size
This was expected, not a bug. Six text packs total about 164 KiB. Android may round displayed app size, and the shared Gemini Nano/AICore model is not packaged inside the APK.

### Local AI can be unavailable or weak
Fix: bounded 15-second local stage plus automatic public-web fallback.

### Deep web answers becoming stale in cache
Fix: deep-web results are deliberately not persisted to the long-term answer cache.

### Current questions incorrectly reusing old cached answers
Fix: obvious time-sensitive wording bypasses exact/similar caches and goes through the fresh fallback path.

### Long deep answers did not fit the old result panel
Fix: result content is now scrollable. Deep fallback is bounded by a 90-second total-cycle hard cap.

## Remaining limitations

- Public web APIs can be unavailable, rate-limited, or lack a useful result.
- Wikipedia/Wikibooks/Stack Exchange are backup research sources, not authoritative for every certification rule, manufacturer specification, code requirement, or financial assumption.
- Google Search cannot be anonymously embedded as a reliable programmatic fallback; its current official service requires credentials/partner configuration.
- OpenAI API keys must not be shipped in an Android APK. A truly automatic ChatGPT final tier requires a secure backend/serverless endpoint.
- Gemini Nano support varies by phone. If unavailable, the app can still use its local database, deterministic calculator, and extractive public-web fallback.
