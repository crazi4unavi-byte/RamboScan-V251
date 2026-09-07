# RAMBO Scan V2.5 — Full Screen + Deep Fallback

Core loop: **SEE → RECOGNIZE → SOLVE → DISPLAY → RESET**

## What changed from V2.4

- The camera preview is full screen. The old 300dp × 180dp decorative scan rectangle is removed.
- OCR still analyzes the full CameraX image frame, not a crop.
- Normal answers still display for 20 seconds.
- Local solving gets up to 15 seconds.
- If local cache/database/calculator/on-device AI is weak, unavailable, time-sensitive, or times out, RAMBO Scan automatically enters **DEEP SEARCH**.
- Deep Search uses zero-key public APIs: Wikipedia, Wikibooks, and selected Stack Exchange sites.
- The entire exception path is capped at **90 seconds total** from question detection to automatic reset.
- The local stage gets 15 seconds, deep public-web search up to 45 seconds, synthesis up to 10 seconds, leaving roughly 20+ seconds for display if every earlier stage uses its full budget.
- Deep-web answers are not permanently cached, because current information can become stale.
- The result panel is scrollable for longer deep-analysis answers.
- No beep, voice, vibration, shutter sound, or confirmation step was added.

## Where the database lives

The six `knowledge_*.tsv` files are bundled inside the APK under `app/src/main/assets/`. They total only about **164 KiB**, so they barely change APK size.

On first use, `KnowledgeStore` copies those records into an app-private Android FTS4 SQLite database named:

`rambo_knowledge.db`

Android stores it in the app's private data area (normally under a path conceptually like `/data/user/0/com.ramboscan.app/databases/rambo_knowledge.db`). Other apps cannot normally browse that directory.

The on-device Gemini Nano model is not bundled into this APK. It is supplied through Android AICore on supported devices, which is why adding the local AI did not make the APK gigabytes larger.

## Online fallback

The online layer does not scrape Google result pages and does not contain an OpenAI key. It uses documented public APIs:

- Wikipedia MediaWiki API
- Wikibooks MediaWiki API
- Stack Exchange API

Google's current programmatic Web Search Service requires credentials/partner setup, and an OpenAI API key must not be shipped in a mobile APK. A secure OpenAI/Google final-tier service can be added later as a serverless endpoint without changing the normal phone-first architecture.

## Timing

- Local solve budget: 15 seconds maximum
- Deep public-web search budget: 45 seconds maximum
- Deep on-device synthesis budget: 10 seconds maximum
- Normal answer display: 20 seconds
- Deep fallback total cycle hard cap: 90 seconds from recognition to reset

## Tested locally before packaging

- Android XML parsing: PASS
- StableTextGate JVM self-test: PASS
- Finance calculator JVM self-test: PASS
- IRR deterministic sample: PASS
- DCF deterministic sample: PASS
- Waterfall deterministic sample: PASS
- Knowledge retrieval: PASS on IRR, DCF, waterfall, HVAC low suction, superheat, PMP CPI/SPI, and LEED queries
- 401 knowledge records loaded across six packs: PASS
- Full-screen layout guard: PASS
- 15-second local fallback trigger constant: PASS
- 90-second deep total-cycle hard cap: PASS
- No audio/vibration APIs in scanner: PASS

GitHub Actions performs Android compilation/testing and also runs the knowledge-search self-test before producing the APK.
