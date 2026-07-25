# AI Provider Live Test - 2026-07-24

All calls in this report were real HTTPS requests using credentials already present in the process environment. No mock server or fake response was used. Credentials are intentionally omitted.

## Python gateway non-streaming

| Provider | Base URL | Model | Result | Prompt | Completion | Total |
| --- | --- | --- | --- | ---: | ---: | ---: |
| OpenAI relay | `https://api1.aisz.mom/v1` | `gpt-5.6-terra` | 200, `GATEWAY_OK` | 4441 | 8 | 4449 |
| DashScope/Qwen | official compatible endpoint | `qwen-plus` | 200, `GATEWAY_OK` | 34 | 4 | 38 |
| DeepSeek | official compatible endpoint | `deepseek-chat` | 200, `GATEWAY_OK` | 25 | 4 | 29 |
| Volcengine Ark/Doubao | official Ark endpoint | `doubao-seed-2-0-lite-260215` | 200, `GATEWAY_OK` | 58 | 28 | 86 |

The immutable test events are recorded in `.local-logs/ai-provider-live-2026-07-24.jsonl`.

## Streaming usage

Each provider was called with `stream=true` and `stream_options.include_usage=true`.

| Provider | SSE result | Final content | Official total tokens |
| --- | --- | --- | ---: |
| OpenAI relay | 200, 5 events | `STREAM_OK` | 4424 |
| DashScope/Qwen | 200, 5 events | `STREAM_OK` | 14 |
| DeepSeek | 200, 5 events | `STREAM_OK` | 12 |
| Ark/Doubao | 200, 23 events | `STREAM_OK` | 76 |

## Provider rotation

The first attempt used an intentionally unavailable OpenAI model and produced a real HTTP failure. The second attempt switched to `qwen-plus` and succeeded. The ledger contains both rows under run `live-provider-rotation-20260724`; the successful response reports 36 total tokens.

## Image path

A real 2689-byte PNG containing `x^2 + 2x + 1 = (x + 1)^2` was created in memory and sent through `FormulaRecognitionService` as a base64 `data:image/png` URL. `gpt-5.6-luna` returned the same LaTeX and plain text with confidence `1.0`.

Images are not uploaded to public object storage by this worker. The authenticated Java/backend request supplies a base64 data URL to `/v1/formula-recognition`; Python validates MIME type, base64 integrity and byte limits, converts WMF to PNG when required, and sends the in-memory data URL to the configured visual provider. Page batches are assembled into an in-memory JPEG contact sheet. No local provider-facing file path is exposed.

## Issue found and fixed

The configured OpenAI relay translates Chat Completions to the Responses API and rejected requests whose translated `tools` value was null. Python now sends a disabled compatibility tool schema for non-official relays while retaining `tool_choice=none`. Text and image calls both returned 200 after this change.

`estimatedCost` remains zero until deployment supplies real prices in `MATH_AGENT_AI_PRICES_JSON`; token counts above are provider-reported usage, not local estimates.

## Full SSE gateway migration

`POST /v1/agent-runs` now returns `text/event-stream`; the old blocking contract is available only at `POST /v1/agent-runs/sync` during caller migration. The typed event order is:

```text
started -> provider -> delta* -> usage -> completed
                         \-> tool_call -> tool_result -> provider -> delta* -> usage -> completed
                         \-> error
```

A real worker was started on `127.0.0.1:18091` and consumed with `requests` streaming mode. HTTP headers were `Content-Type: text/event-stream` and `X-Accel-Buffering: no`. The first event arrived at 0.046 seconds, the first model delta at 2.597 seconds, and completion at 3.259 seconds, with 19 separately received deltas. This verifies that the endpoint does not buffer a completed answer and repackage it afterward.

The migrated Python streaming runtime was then called against all configured providers:

| Provider | Delta count | Content | Official total tokens |
| --- | ---: | --- | ---: |
| OpenAI relay | 4 | `STREAM_GATEWAY_OK` | 4470 |
| DashScope/Qwen | 2 | `STREAM_GATEWAY_OK` | 190 |
| DeepSeek | 6 | `STREAM_GATEWAY_OK` | 321 |
| Ark/Doubao | 5 | `STREAM_GATEWAY_OK` | 461 |

A real Qwen streaming tool-call response was also assembled from incremental function-name/argument chunks. It produced `search_visible_resources(query=function monotonicity)` and official usage of 215 tokens without exposing a path or arbitrary URL.
