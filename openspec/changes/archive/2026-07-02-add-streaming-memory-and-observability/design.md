## Context

`ask`/`run` got conversation memory; `stream` did not. Hooks expose telemetry but nothing exports it.
Both are additive client-loop features with zero new dependencies.

## Goals / Non-Goals

**Goals:** streaming that remembers (`stream` `id`), block-style streaming on `ask` (`on_text`), a
zero-dep observability surface (`on_metric` semantic events + built-in Prometheus text). Byte-parity,
backward-compatible. **Non-Goals:** OTLP/OpenTelemetry push (future companion), dollar-cost computation
(tokens only), changing `run`/`stream` event shapes, a new metrics HTTP server (host mounts the text).

## Decisions

**`on_text` on `ask`, not `stream: true`.** A boolean that changes the return type is a smell; `ask`
always returns `RunResult`, and `on_text` (a callback) is the opt-in streaming path. `stream()` stays
the idiomatic iterator for pulling tool events. Internally `ask(on_text)` runs the streaming loop and
forwards `text` deltas to the callback, assembling the `RunResult` from `done`.

**`id` on `stream` for memory parity.** Memory is orthogonal to streaming, so the same `id` switch
applies to `stream`: load history in, save `result.messages` on `done`. Requires threading `history`
into `streamOpenAI`/`streamAnthropic` (they currently don't accept it).

**`on_metric` = semantic events, not primitives.** Users get `{event, …fields}` records they can read
and forward; the counter/histogram mapping is the library's internal concern. This keeps the public
surface human and lets the built-in Prometheus registry be a private detail.

**Prometheus text is rendered by hand (no dep).** The exposition format is trivial plain text; each
port renders it directly, keeping the "right-sized, minimal deps" promise and byte-parity. One internal
registry accumulates counters/histograms from the same events `on_metric` sees.

## Risks / Trade-offs

- [Histogram buckets differ per port] → pin a fixed bucket set in SPEC/tests so `..._duration_seconds`
  renders identically across the five ports.
- [Metric cardinality] → labels restricted to bounded values (model/tool/source/status/type); never
  the conversation `id`.
- [on_text vs stream() duplication] → `ask(on_text)` reuses the streaming loop internally; no second
  loop implementation.
