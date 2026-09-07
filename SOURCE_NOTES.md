# V2.5 source/service notes

The app's normal brain remains phone-first: local SQLite FTS knowledge + deterministic calculator + Gemini Nano/AICore when supported.

The exception path uses documented zero-secret public web APIs only:

- Wikimedia MediaWiki Action API (Wikipedia)
- Wikimedia MediaWiki Action API (Wikibooks)
- Stack Exchange API

No Google result-page scraping is used. No OpenAI key is embedded in the APK.

Deep-search evidence is used as temporary context and is not added to the long-lived answer cache, reducing stale-current-answer risk.
