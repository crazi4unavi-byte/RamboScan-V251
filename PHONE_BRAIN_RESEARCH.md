# Phone Brain research — V2.2

V2.2 uses a hybrid local architecture rather than treating the small on-device LLM as the entire database.

- ML Kit Text Recognition performs OCR on-device.
- ML Kit GenAI Prompt API beta4 uses Gemini Nano through Android AICore when supported.
- The Prompt API is beta and device support varies, so a separate offline knowledge layer is required for resilience.
- SQLite FTS4 is used for fast lexical retrieval from bundled knowledge packs.
- Retrieved passages are inserted into the on-device AI prompt (local RAG).
- If Gemini Nano is unavailable, retrieval itself becomes the answer fallback instead of showing a network/backend failure.
- Exact and very-similar previous answers are cached locally.

Future high-end option: LiteRT-LM can run a downloadable local open-weight model such as Gemma directly on Android. This can broaden device support but requires a large model file and substantially more storage/RAM, so it is not forced into V2.2.
