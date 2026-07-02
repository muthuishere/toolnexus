## Why

Two gaps in the client loop: (1) streaming was stateless — `ask` remembers by `id` but can't stream,
and `stream()` streams but has no memory; and (2) the hooks expose telemetry but there's no
first-class way to *export* metrics (Prometheus / forward anywhere). Both are small, zero-dependency
additions that make the loop production-usable.

## What Changes

- **Streaming + memory:**
  - `stream(prompt, { toolkit, id? })` — an optional `id` makes streaming stateful: load the thread's
    transcript before the stream, save the updated transcript to the `ConversationStore` on the `done`
    event. No `id` ⇒ stateless (today).
  - `ask(prompt, { toolkit, id?, on_text? })` — an optional `on_text` callback receives each assistant
    text delta as it streams; `ask` still returns the final `RunResult` (return type never changes).
    Block-style streaming; `stream()` stays the iterator for consuming tool events too.
- **Observability (zero-dependency, two outputs from one instrumentation):**
  - `createClient({ ..., on_metric })` — a callback receiving **semantic events** (`{event:"llm"…}`,
    `{event:"tool"…}`, `{event:"run"…}`), forwardable to statsd/logs/OTel/anything.
  - `client.metrics()` — the built-in **Prometheus text exposition** of cumulative counters/histograms
    (no third-party dep). Host mounts it at `GET /metrics`.
  - OTLP/OpenTelemetry push is deferred to a future opt-in companion (not core).
- Applies across **all five ports** (js/python/golang/java/csharp). Backward-compatible. Minor **0.4.0**.

## Capabilities

### New Capabilities
- `client-observability`: `on_metric` semantic events + a zero-dep `client.metrics()` Prometheus renderer.

### Modified Capabilities
<!-- Streaming + ask memory extend the existing client loop (SPEC §8); captured here + in SPEC.md. No
     archived capability spec owns the client loop yet, so this is additive to SPEC §8. -->

## Impact

- **SPEC.md §8** — Streaming (`id`), Conversation memory (`ask` `on_text`), and a new Observability
  subsection (already updated).
- **js/python/golang/java/csharp** — client: `id`/`history` threaded into `stream`, `on_text` on `ask`,
  `on_metric` option + internal metric registry + `metrics()` Prometheus renderer; per-port tests.
- No new dependencies (Prometheus text is plain text). Backward-compatible.
