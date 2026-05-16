# Wheelson

*“See which earnings names are worth the wheel — with an on-device quality score.”*

Wheelson is an Android app for **The Wheel Strategy** ([what's that?](https://www.moomoo.com/us/learn/detail-options-wheel-strategy-117831-250138079)). It shows a vertical feed of screened stocks (symbol, quote, AI quality score) and runs **local LLM inference** to score each name against Wheel-oriented fundamentals.

Feed data matches the shape produced by **Tracy** (the companion FastAPI backend, `GET /feed`): `event`, `quote`, and `stock_criteria`. The app ships with a bundled `feed.json` for offline use; you can replace it with fresh API output.

> **Disclaimer:** Wheelson is for research and education only. It is not financial advice. Market data may be delayed or inaccurate. On-device model outputs can be wrong or inconsistent. You are responsible for your own trading decisions.

## Prerequisites

- **Android Studio** (Ladybug or newer recommended)
- **JDK 11+**
- **Physical Android device** strongly recommended (large on-device model, CPU inference)
- **minSdk 35** / **targetSdk 36** (see `app/build.gradle.kts`)
- A compatible **MediaPipe LiteRT `.task`** model file (see [On-device model](#on-device-model))

Third-party data in `feed.json` is subject to each upstream provider’s terms (e.g. Finnhub, Yahoo Finance via Tracy).

## What it does

1. **Feed UI** — loads `app/src/main/assets/feed.json` and renders a card per ticker: **symbol**, **price**, and **quality score** at a glance.
2. **Stock quality (junior agent)** — `StockQualityEvaluationAgent` sends each item’s `stock_criteria` to the on-device LLM and expects structured JSON (including reasoning fields); score is parsed with strict JSON + regex fallback.
3. **Senior review** — `SeniorStockQualityEvaluationAgent` audits the junior output against the same `stock_criteria` and returns a stricter `{"score": N}` (integer 1–10). The UI prefers the senior score; if senior review fails, it falls back to the junior score.
4. **Orchestration** — `AiOrchestrator` (singleton) copies the model from assets to internal storage once, loads a single `LlmInference` instance, and serializes inference so only one model lives in memory.

Evaluation runs **one ticker at a time** after the feed loads. While a score is pending, the card shows a loading spinner in the quality chip.

## Quick start

1. Clone the repo and open it in Android Studio.
2. Download a MediaPipe-compatible Gemma `.task` bundle (not GGUF). See [On-device model](#on-device-model).
3. Place the file at:

   ```
   app/src/main/assets/models/gemma3-1b-it-int4.task
   ```

   Update `MODEL_ASSET_PATH` / `MODEL_FILENAME` in `AiOrchestrator.kt` if you use a different filename.

4. Connect a physical device (or an emulator with enough RAM and the native library available).
5. **Run** the `app` configuration.

### Updating feed data

Replace or regenerate:

```
app/src/main/assets/feed.json
```

Example: export from Tracy’s `GET /feed` and copy the `items` array into a top-level `{ "items": [ ... ] }` object. Each item should include `event`, `quote`, and `stock_criteria` for AI scoring.

### Example feed item

`stock_criteria` is abbreviated; the live file includes full trends, dividend history, and institutional holders.

```json
{
  "event": {
    "date": "2026-04-15",
    "symbol": "FTI",
    "hour": "bmo",
    "quarter": 1,
    "year": 2026
  },
  "quote": 70.555,
  "stock_criteria": {
    "symbol": "FTI",
    "name": "TechnipFMC plc",
    "market_cap": 28225169408.0,
    "debt_to_equity": 0.399,
    "current_ratio": 2.54,
    "return_on_equity": 0.296,
    "revenue_growth_yoy": -0.144,
    "analyst_recommendations": {
      "strongBuy": 20,
      "buy": 40,
      "hold": 27,
      "sell": 0,
      "strongSell": 0
    }
  }
}
```

## On-device model

| Setting | Value |
|---------|--------|
| Default asset path | `app/src/main/assets/models/gemma3-1b-it-int4.task` |
| Runtime copy | `context.filesDir/models/` (Option B — copy once from assets) |
| Inference API | [MediaPipe Tasks GenAI](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) (`com.google.mediapipe:tasks-genai`) |
| Backend | CPU (`LlmInference.Backend.CPU`) |
| Max tokens | 4096 (`AiOrchestrator.DEFAULT_MAX_TOKENS`) |

**Important:** The `.task` file must be a **zip-based MediaPipe / LiteRT model bundle**. If `unzip -l your.model.task` fails, the file is not the right format (e.g. raw GGUF).

Suggested models (pick one size that fits your device):

- `gemma3-1b-it-int4.task` — smaller, faster (current default in code)
- `gemma3-4b-it-int4-web.task` — higher quality, much larger APK / copy time / RAM

Model files are typically **not** committed to git due to size; add them locally after download.

## AI agents

### Junior — `StockQualityEvaluationAgent`

- **Input:** `stock_criteria` (`JsonObject` from feed)
- **Output:** Raw JSON from the model (may include `step_by_step_reasoning`, `reflection`, `score`)
- **Parsing:** Strips markdown fences; Gson parse with regex fallback for `"score": N`

### Senior — `SeniorStockQualityEvaluationAgent`

- **Input:** `stock_criteria` + junior’s full JSON string
- **Output:** Strict `{"score": N}` where `N` is an integer from 1 to 10
- **Role:** Cross-check junior reasoning against raw data; penalize hallucinations or missed red flags

### Prompt format

Instruction-tuned Gemma expects the chat template:

```
<start_of_turn>user
...prompt...
<end_of_turn>
<start_of_turn>model
```

Keep prompts compact — long `stock_criteria` JSON plus long instructions can hit context limits and produce empty or garbled output.

## Debugging

Logcat filters:

```
package:mine tag:AiOrchestrator
package:mine tag:StockQualityAgent
package:mine tag:SeniorStockQualityAgent
```

Common issues:

| Symptom | Likely cause |
|---------|----------------|
| `INSTALL_FAILED_MISSING_SHARED_LIBRARY` (`llm_inference_engine_jni`) | Emulator/ABI without the JNI library; use a physical device or set `android:required="false"` on the native library (already in manifest) |
| `Unable to open zip archive` | Model file is not a valid `.task` bundle |
| Empty LLM response | Prompt too long or missing chat template |
| `Score out of range` / parse failure | Model returned non-integer score (e.g. `-0.7`) or invalid JSON |

## Tech stack

| Layer | Choice |
|-------|--------|
| UI | Jetpack Compose, Material 3 |
| JSON | Gson |
| Async | Kotlin coroutines |
| LLM | MediaPipe `LlmInference` (singleton `AiOrchestrator`) |

## Project layout

| Path | Purpose |
|------|---------|
| `app/src/main/assets/feed.json` | Bundled feed (Tracy-shaped JSON) |
| `app/src/main/assets/models/` | On-device `.task` model (add locally) |
| `app/src/main/java/.../ui/feed/FeedScreen.kt` | Feed list UI + evaluation loop |
| `app/src/main/java/.../data/FeedRepository.kt` | Loads `feed.json` from assets |
| `app/src/main/java/.../model/FeedModels.kt` | `FeedItem`, `Event`, optional `option_chain` |
| `app/src/main/java/.../ai/AiOrchestrator.kt` | Model copy, init, inference |
| `app/src/main/java/.../ai/StockQualityEvaluationAgent.kt` | Junior quality agent |
| `app/src/main/java/.../ai/SeniorStockQualityEvaluationAgent.kt` | Senior audit agent |

## Relationship to Tracy

**Tracy** is the **backend** that screens earnings names, enriches quotes and `stock_criteria`, and ranks by put seller ROI. **Wheelson** is the **mobile client** that consumes that feed shape and adds **on-device AI quality scoring** for presentation while you review candidates.

Typical workflow:

1. Run Tracy locally and call `GET /feed` for a date range.
2. Save the response (or `items` only) as `app/src/main/assets/feed.json`.
3. Build and run Wheelson on a device with the model installed.

## Caveats

- Inference is **CPU-only** and **sequential per ticker** — a full feed can take several minutes.
- Small models may return invalid scores (decimals, negatives) or malformed JSON; parsing fallbacks and senior review mitigate but do not eliminate this.
- `feed.json` in the repo may be stale relative to live markets.
- Emulator performance and native library availability vary; a physical phone is the most reliable dev target.

## Contributing

Issues and pull requests are welcome. For larger changes, open an issue first to discuss the approach.
