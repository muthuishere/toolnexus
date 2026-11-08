# Opt-in model fallback chain on overload (issue #190)

## Why

Karya (go-agent-cli) runs long agent sessions against providers that return `529 Overloaded`
(Anthropic, TypeSafe) or `503` (OpenRouter, Gemini) during load spikes. Today the client spends its
`retries` budget on the one configured model and then fails the whole run, although a sibling model
on the same endpoint would have answered. A host cannot do this itself: the model is serialized into
the request body before the transport runs (see `add-llm-upstream-failover`, spike 2b), and
`RunResult.model` would then misreport who served.

## What Changes

- New optional client option `fallbackModels: string[]` (idiomatic per port). When the retry budget
  on the current model is exhausted **and the final status is `503` or `529`**, the call switches to
  the next model with a fresh budget. Nothing else triggers it.
- `stream` emits `{ type: "fallback", from, to }` before switching. `RunResult.model`, the `llm`/`run`
  metric events and `afterLLM` report the model that actually served.
- Env kill switch `TOOLNEXUS_DISABLE_MODEL_FALLBACK=1|true` turns the chain off at runtime.
- Absent or empty option ⇒ byte-identical.

## Relationship to `add-llm-upstream-failover`

This is that design's **model-only first slice**: same `baseUrl`/`style`/credentials/transport, only
the model varies, so it needs neither `add-canonical-transcript` (D7) nor per-attempt transport
re-derivation of credentials. It adopts D1 (retry inside, fallback outside, budget resets), D5
(first token commits a stream) and D6 (report the server, not the config). It deliberately narrows:

- **Trigger** is overload only (`503`/`529`), not the whole retryable set (D2). A `429` is a rate
  limit on *this caller*, which the same model resolves with `Retry-After`; switching models on it
  would turn routine throttling into silent model changes. The broader change may still widen this
  for full upstream failover, where a dead region also justifies network errors.
- **No cooldown** (D4) and no stickiness — each call starts at its own model. Deterministic, and no
  process-global state.
- **`beforeLLM` is not re-run per fallback.** The route-gate concern in the broader proposal is about
  an upstream list built for availability silently reaching a gated tier; here the fallback list is
  the operator's own explicit model choice, configured next to `model`. A host that gates per model
  can omit expensive models from the list.

## Capabilities

### Modified Capabilities

- `resilience-policy`: adds the model fallback chain as an outer tier around the unchanged retry
  budget; `onError`, the no-suspend rule and the conformance matrix keep their meaning.

## Impact

- `SPEC.md §8` Resilience (new `fallbackModels` paragraph) and §8 Streaming (new event).
- Client loop in all eight ports; `conformance/options_manifest.json` gains `fallbackModels`.
- Compatible with the `{type:"retry"}` stream event being added for #189 (independent event).
