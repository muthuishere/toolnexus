## Why

Issue #157 (ADR 0038 Q2, owner-approved): jevx resolves each profile to a `ClassifierOptions`, and a
profile's `type` is a classifier backend. Users asked for an `openai` type, which is OpenAI's
Decisions API (`POST /v1/decisions`, model `gpt-6-luna`). It asks the same three question kinds as
System One (predicate / choice / score) but uses another wire. That wire belongs in toolnexus, so
every front end gets it with no adapter of its own.

## What Changes

- New backend `openai`: `https://api.openai.com/v1`, model `gpt-6-luna`, key `OPENAI_API_KEY`
  (sent only to `api.openai.com` over https, the #155 rule), images yes, wire `decisions`, no
  danger-zone header.
- New wire `decisions`: `POST {baseUrl}/decisions` with `{model, input, questions:[…]}`. Questions
  are mapped by type and sent in ASCII caller-key order. Caller keys are **not** sent, and answers
  are read back by position into the one `Decision` type, with `calibrated: false`. A `refusal`
  answer is an error. Raw questions, one-option choices and more than 400 questions fail before any
  request.
- Reuses every shared `ClassifierOptions` field (baseUrl, model, apiKeyEnv, headers, timeout,
  httpClient, retries, onError, retryBaseMs, requestParams, bodyTransform, cache, supports, onMetric).
- **No native batch**: the Decisions API has none (one `input` per request), so `states().evaluate`
  keeps its concurrent fan-out.
- Out of scope (owner): no model catalogue and no per-model classification (#158 §1). No gateway
  presets for OpenRouter `api/alpha/decisions` or Vercel. Those work today as `backend: "openai"`
  with a `baseUrl` override and a caller-named `apiKeyEnv`, but no preset row exists until a live
  probe proves them.
- SPEC §8B: backend table row, *Decisions wire* section, unknown-backend error text.

## Impact

All eight ports. Fixtures: new `examples/judge/backends/openai-decisions.json`; `presets.json` and
`key-routing.json` gain openai rows. Non-breaking, except that the unknown-backend error text now lists
`"openai"`.
