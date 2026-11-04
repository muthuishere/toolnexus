# Decision request styles (researched 2026-11-04, before any live run)

This lists every wire shape for "typed questions over a state" that I could find. "Docs" means the
provider's own documentation. "3rd party" means secondary sources only.

## A. Single state, many questions: the System One shape

`{model, state, questions:{name:{type, instructions, criteria}}}` → `{model, answers:{name:{…}}, usage}`.
Question types are `noul | choice | score`, plus `box` on Jev 1.14. `questions` is a **map**.

| # | route | path | differences | images | source |
|---|---|---|---|---|---|
| A1 | TypeSafe | `api.typesafe.ai/v1/systemone` | base shape | `assets` map + `TypeSafe-Danger-Zone-Images: true` (Jev 1.13/1.14); base64 **or multipart** (`request` part + file parts) | docs |
| A2 | OpenRouter System One | `openrouter.ai/api/v1/systemone` | reply adds `id`, `provider`, `usage.cost` | not documented on this path | docs |
| A3 | OpenRouter Decisions (alpha) | `openrouter.ai/api/alpha/decisions` | **same System One shape** (`state`, map, `noul`); `usage.input_tokens_details.cached_tokens`; `session_id` is for grouping only | `image_url` parts inside `state`, **or** `assets` base64 (multipart `part` not accepted) | docs (API reference) |
| A4 | Vercel TypeSafe-compatible | `ai-gateway.vercel.sh/typesafe/v1/systemone` | reply adds `provider_metadata.gateway`; optional `providerOptions.gateway.models` fallback | not documented | docs |
| A5 | Cloudflare Workers AI | `…/accounts/{id}/ai/run[/<model>]` | **wrapped**: `{model, input:{state,questions}}` → `{success, result}`, sometimes a nested `{state:"Completed", result}` | Clef: its own `images` field (base64 data URL). Jev here: not documented | 3rd party + jevx code |
| A6 | Self-hosted System One (openjevx, IOMED fork) | `{base}/v1/systemone` | base shape | depends on the server | jevx profile / fork |

## B. Single state, many questions: the "Decisions" shapes (a different wire)

| # | route | path | shape |
|---|---|---|---|
| B1 | Vercel AI SDK HTTP | `ai-gateway.vercel.sh/v1/evaluate` | `state` + map, but the type is `boolean` (answer `probability`), and usage is camelCase `inputTokens` |
| B2 | OpenAI-compatible Decisions (Vercel, OpenAI) | `ai-gateway.vercel.sh/v1/decisions` | `input` (string or messages), `questions` is an **array** with `name`; types `predicate | choice | score`; `choices:[{value,description}]`, `levels:[{label}]`; answers come back as an array in question order; a `refusal` answer type exists. Images are `input_image` data URLs, up to 128 per request |

B1 and B2 serve any decision model, among them `openai/gpt-6-luna-decisions`, Perplexity, Liquid, Clef and Jev. Through B2,
Jev answers text only: images return a 400.

## C. Many states: batch

| # | route | shape | status |
|---|---|---|---|
| C1 | toolnexus today | `EvaluateBatch`: client-side fan-out, 16 calls in flight, results in order | shipped, all ports |
| C2 | IOMED self-hosted | `POST /v1/systemone/batch`: 1–50 `{state, questions, model?}` items, results in order | not on public TypeSafe |
| C3 | Cloudflare async batch | `queueRequest: true` → `request_id` → poll until done; results map by index; `external_reference` per item; 10 MB payload | docs (whether it works for Jev or Clef is untested) |
| C4 | TypeSafe public / OpenRouter / Vercel | none; one state per request | docs |

## D. "Add the state once, then ask": state reuse and caching

| # | mechanism | what it gives |
|---|---|---|
| D1 | ask every question in one request (A or B) | the documented way to share a state: Jev reads the state once and scores all the questions |
| D2 | provider prompt cache, implicit | OpenRouter reports `usage.input_tokens_details.cached_tokens`. **Unknown** whether a repeated state with *different* questions hits it |
| D3 | a state handle or session ("upload state, get an id") | **no provider offers one.** No state id or `cache_control` field exists anywhere; OpenRouter's `session_id` is observability only. A typesafe-ai/skills issue (#17) asks TypeSafe to confirm that requests are independent |
| D4 | host-side answer cache | jevx `core/cache.go`: a hash of (url, model, state, question) with a TTL. Host state, so it stays in jevx (ADR 0038 D2) |

## What the live spike will measure, once you approve

1. A1–A5 with a text case and an image case, using the same body. This shows whether "one style" really is one body
   everywhere.
2. A3 vs A1: is the alpha Decisions API byte-compatible with System One?
3. D2: the same state twice, with different questions, then the same questions → read `cached_tokens` and latency.
4. C1 vs C3: client fan-out vs Cloudflare's async batch for N states.
5. B2 is skipped unless you want it: it is a second wire, and there is no Vercel key in `sec`.
