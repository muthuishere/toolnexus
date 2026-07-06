## Context

`Toolkit.execute(name, args, ctx?)` is the single funnel every tool source (mcp, native, http,
builtin, a2a) already runs through (SPEC.md §4). `ClientOptions.hooks.beforeTool`/`afterTool`
(§8) already let a caller deny or rewrite a call — but only for callers that go through
`createClient(...).run()`. Our own fleet, the A2A inbound `serve` path, and any third-party
harness built directly on `Toolkit` bypass the client entirely and get no guardrail, no log.

The company already has a deterministic constraint shield (`brain`) and a receipt format
(`autonomy-receipt/v0-spike`, `ceo/spikes/autonomy-receipts/`) from a separate research spike.
This design wires toolnexus into both without adopting a hard dependency on either.

## Goals / Non-Goals

**Goals:**
- One governance hook, attached at `Toolkit` construction, that wraps every `execute()` call —
  independent of which host loop (or none) is driving it.
- Deterministic shield-check: a call can be denied before it runs; the check itself must be a
  pure function of (call, context) → verdict, never an LLM judgment call.
- Tamper-evident local provenance log (hash-chained), independent of `on_metric`.
- On-demand receipt rollup shaped to interoperate with the existing Autonomy Receipt schema.
- Byte-identical option surface and log/receipt schema across all 5 ports.
- Zero overhead when the option is omitted.

**Non-Goals:**
- Not a replacement for `ClientOptions.hooks` — this is a lower layer; a later change may have
  the client's `beforeTool`/`afterTool` delegate to it, but that wiring is out of scope here.
- Not a hosted/remote audit service — this change is a local, verifiable log + receipt only
  (the roadmap's "Product (later): hosted governance/audit plane" is explicitly a later stream).
- Not a policy language or rules engine — the shield-check stage is a pluggable callback
  interface; toolnexus ships one reference adapter (`brain`), not a constraint DSL.
- Not going through `/opsx:apply` in this change — this proposal stops at design + spec deltas +
  a task breakdown; a follow-up change implements it.

## Decisions

**D1 — Attach at `Toolkit`, not `ClientOptions.hooks`.**
The mission and our own dogfooding need coverage for *any* caller of `execute()`, not just the
bundled client loop. Alternative considered: extend `beforeTool`/`afterTool` only. Rejected —
it would miss direct `Toolkit.execute()` callers (our fleet's own routing, A2A `serve`), which
is exactly the audience the roadmap says to dogfood first.

**D2 — Shield-check is a pluggable synchronous callback, not a toolnexus-native rules engine.**
Signature (idiomatic per language): `(call: {name, source, args, ctx}) -> {allow: bool, reason?:
string, guaranteed: bool}`. Reference adapter shells out to `brain --repo <repo> check "<call
summary>" --reward <derived> --signal <k=v,...> --json` and maps its `veto`/`guaranteed` fields.
Alternative considered: embed a constraint DSL in toolnexus itself. Rejected — duplicates
`brain`, and violates "reuse brain's shield" from the roadmap directly; toolnexus's job is the
tool-calling runtime, not constraint modeling.

**D3 — Provenance log is a local, hash-chained NDJSON file, not a new service.**
Each event: `{ts, tool, source, argsDigest (sha256 of canonicalized args, not raw args —
avoids logging secrets), verdict: allow|deny, deniedReason?, actor, durationMs, prev, hash}`.
`hash = sha256(prev + canonicalJSON(event-without-hash))`. Mirrors the Autonomy Receipt spike's
own `sha256-chain` integrity model so the two compose. Alternative considered: reuse
`on_metric` — rejected, `on_metric` is observability (best-effort, may be dropped/batched by the
sink); provenance needs to be authoritative and independent of whether anyone is listening.

**D4 — Receipt rollup schema mirrors `autonomy-receipt/v0-spike`.**
`{schema: "toolnexus-receipt/v0", operator, period: {from, to}, totals: {calls, allowed,
denied, distinct_tools}, integrity: {algo: "sha256-chain", root, provenance: "each event
chained to the previous via sha256", verify: "recompute chain from events[]; a tampered field
breaks the root"}, events: [...]}`. `operator` and `events[].actor` are caller-supplied strings
(no assumption of git commits — toolnexus events are tool calls, not commits, unlike the spike's
git-anchored version). Verification is a pure recompute of the hash chain, no external service.

**D5 — Off by default, one constructor option.**
`Toolkit.Options.governance?: { shield?: ShieldFn, log?: LogSink }` (idiomatic naming per
language). Omitted ⇒ `execute()` behaves exactly as it does today, no allocation, no file I/O.

## Risks / Trade-offs

- [Shield adapter shells out to an external CLI (`brain`) per call] → latency/availability risk
  for high-frequency tool loops. Mitigation: the shield interface is pluggable — the `brain`
  adapter is a reference implementation, not the only option; callers needing low latency
  supply their own in-process `ShieldFn`.
- [Hash-chained log is append-only and local] → doesn't protect against an attacker with disk
  access rewriting the whole chain from event 0. Mitigation: out of scope for v0 (matches the
  "spike" maturity of the Autonomy Receipt format itself); a future receipt can anchor `root` to
  a git commit the way the spike does, once this compounds with that OSS for real.
- [Five-language byte-identical hash chains] → canonical JSON serialization must be
  byte-identical across langs (key order, number formatting) or hashes diverge. Mitigation:
  fixed key order + explicit canonicalization rule in the spec (not "whatever the language's
  JSON encoder does"); conformance fixtures include a fixed input → fixed expected hash.

## Migration Plan

Purely additive — no existing option, method, or output shape changes. Ports adopt the new
`governance` option independently; omission is a no-op. No rollback needed beyond not setting
the option.

## Open Questions

- Exact `brain check` argument mapping (what `--reward`/`--signal` values a generic tool call
  should synthesize) is left to the reference adapter's implementation task, not pinned here —
  answer by reading `brain`'s current CLI contract at implementation time (it may have moved).
- Whether the client's `beforeTool`/`afterTool` should later delegate to this layer (avoiding
  two guardrail surfaces) is deliberately deferred to a follow-up change, not decided here.
