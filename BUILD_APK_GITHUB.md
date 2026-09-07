# GitHub APK build — V2.5

1. Upload/replace the V2.5 project files in the existing repository.
2. Commit to `main`.
3. GitHub Actions automatically runs `Build RAMBO Scan V2.5 Deep Fallback APK`.
4. The workflow first checks the local knowledge retrieval test, then performs a non-blocking public API smoke check, then runs Android unit tests and `:app:assembleDebug`.
5. Wait for the green check.
6. Open the successful run and download artifact `RamboScan-V2.5-DeepFallback-debug-apk`.
7. Extract it and install `app-debug.apk`.

If the Android build turns red, open **Build and test debug APK** and copy the first compiler error, not only the summary.
