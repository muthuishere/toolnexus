## Context

`SPEC.md §0` item 7 pins the format adapters as schema-only and outbound-only. There is no
inbound counterpart anywhere in the library, so a caller can declare tools to a provider but
cannot receive the calls it makes. See ADR-0011 for the full reasoning, including the
generality assessment the owner required ("build it only if it's useful for all") and the
strongest argument against.

Two facts from the prior work shape this design:

- **The agent loop is the wrong tool for callers who own their conversation.** The OpenAI
  protocol hands a caller the complete history *including prior tool results* on every
  request. Serving that through the loop means adding suspension semantics and state to a
  problem that has neither. Measured in `golang/relay_test.go`: parking `waitFor` across an
  external boundary works, but makes a stateless need stateful.
- **Structure, not text, is the hard part.** A translator that flattens messages to text
  silently destroys `tool_call_id` and an assistant turn's `tool_calls`, and multi-turn tool
  use cannot survive it regardless of anything the loop does. This was a real bug in the
  first consumer.

`golang/` is implemented and green (12 tests) and is the reference port.

## Goals / Non-Goals

**Goals:**
- One provider call, no loop, no execution, no state — self-contained and concurrency-safe.
- OpenAI shapes in and out, so provider knowledge stays in the library.
- Serve toolnexus's *own* users (toolkits from any source), not only callers holding OpenAI
  JSON — this is what makes it a library capability rather than a proxy bolt-on.
- Preserve tool structure through inbound translation.
- Byte-identical behavior for everything that exists today.

**Non-Goals:**
- Streaming translation (deliberate follow-up; the shape is known, correctness first).
- Gemini-style *upstreams*. Client styles are `openai`/`anthropic`; `ToGemini` covers
  declarations only, and a Gemini upstream would need its own inbound/outbound mapping.
- Executing anything. There is no execution path in this entry point at all.
- Replacing relay + durable resume, which stays the answer for proxy-managed memory.
- Envelope assembly, routing, auth brokering — proxy concerns that belong in the proxy. If
  this entry point starts growing them, it has drifted (see ADR-0011).

## Decisions

### D1 — A separate entry point, not a flag on the loop

`translate` sits beside `run`/`ask`/`stream` rather than adding a "don't execute" mode to
them. The loop's contract is *the library executes tools*; a flag that inverts it would make
every loop invariant conditional. Rejected: `maxTurns:1` (tools still execute or suspend) and
an `afterLLM` hook returning a sentinel error to abort after one call (abuses an error path
for control flow, and duplicates provider knowledge in every caller — the first consumer
found this and declined it themselves).

### D2 — Two tool inputs: OpenAI array *and* toolkit

Both, composing. The OpenAI array serves a caller holding a client's request body; the
toolkit serves every existing toolnexus user. Taking only the former was the first cut and it
was the bolt-on the owner's condition caught — it served exactly one shape of caller.

### D3 — `arguments` is a JSON string, both directions

Outbound results carry `arguments` as a string because that is the OpenAI wire form, letting
a caller echo it byte-for-byte to a conforming client. Inbound, an assistant turn's string
`arguments` is **parsed back to an object** because native provider blocks want an object.
Ports must also tolerate a caller sending `arguments` as an object, since some clients do.

### D4 — Consecutive tool results merge into one user turn

Providers that use content blocks expect one result-bearing turn answering the preceding
assistant turn, not one turn per result. This is the single most likely place for a port to
diverge and produce a request the provider rejects, so it has its own scenario and its own
per-port test.

### D5 — Tool calls win the finish reason

A turn emitting any tool call reports `tool_calls`, whatever the provider said. A conforming
OpenAI client branches on `finish_reason`, and any other value with tool calls present would
strand them.

### D6 — Reuse retries, params and the LLM metric; skip tool hooks

A translating caller should not lose resilience or observability by not using the loop. Tool
hooks cannot fire because no tool runs — stated explicitly so a port does not invent a
half-firing.

### D7 — Go is the reference; the other five port its tests

Same rule as the sibling relay change. The reference tests are the cross-port oracle,
especially D4.

## Risks / Trade-offs

- **Scope drift into a gateway SDK** → The capability is framed as completing the adapter
  round trip. The Non-Goals name the proxy concerns that must stay out; ADR-0011 records the
  drift signal to watch for.
- **Six-port divergence in message translation** (the real risk here — it is fiddlier than
  the outbound adapters) → Per-port tests for the multi-turn exchange, the three-results
  merge, content-parts flattening, and both `arguments` forms. Go's assertions are the oracle.
- **`finishReason` vocabularies differ per provider** → Mapped explicitly with a scenario per
  outcome, and an unknown stop reason falls back to `"stop"` rather than passing through.
- **A caller might expect `translate` to honor `Builtins`/toolkit filters as protection** →
  It does not need to: nothing executes. Documented as a property of the design, not of
  configuration.
- **Duplication with the loop's provider mapping** → Some assembly logic is parallel to the
  loop's. Accepted for now: unifying them would touch the loop, which this change
  deliberately does not. Worth revisiting once streaming translation lands.

## Migration Plan

Purely additive and opt-in; nothing to migrate. No existing entry point changes behavior.
Rollback is reverting the change. Ships in the next coordinated all-port version bump.

## Open Questions

- Naming per port (`translate` vs `translateOnce` vs a `TranslationClient`). Idiomatic shape
  wins per port; the *behavior* is pinned by the spec.
- Should the result expose the provider's raw stop reason alongside the mapped
  `finishReason`? Go returns the whole raw response, which covers it; a dedicated field may
  be friendlier.
- Whether a future streaming translation should reuse this request type or take its own.
