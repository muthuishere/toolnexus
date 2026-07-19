## Why

toolnexus's fleet already dogfoods its own tool routing (MCP calls, A2A hops) with no
governance layer in between: any tool a model picks runs, unobserved and unverifiable. The
existing `ClientOptions.hooks.beforeTool` can deny a call, but only for callers that go through
the bundled client loop — a host that calls `Toolkit.execute()` directly (our own fleet agents,
A2A `serve` inbound, any third-party harness) gets no guardrail and no audit trail at all. Per
the toolnexus roadmap (`company-os/roadmaps/toolnexus.md`, owner directive 2026-07-06): harden
the fundamentals first, then ship the "our-style" OSS the market doesn't have — a governance
plane that is deterministic and auditable, not a vibes/LLM filter, positioned against the
early/thin MCP-security incumbents (Invariant Labs, Microsoft Agent Governance).

## What Changes

- Add an optional **governance hook** at the `Toolkit` level (below the client loop, so it
  covers every caller of `execute()` — MCP tool calls and A2A hops alike, not just calls made
  through `ClientOptions.hooks`).
- **Shield-check stage**: an optional, pluggable, synchronous veto callback run before
  `execute()`. Reference-implemented as an adapter that shells out to the `brain` CLI's
  deterministic constraint shield (`brain check "<decision>" --reward R --signal k=v --json`)
  and denies the call when the shield vetoes or is not `"guaranteed"`. The adapter is swappable;
  toolnexus does not hard-depend on `brain`.
- **Provenance log stage**: every `execute()` call (allowed or denied) appends one hash-chained
  event (tool name, source, args digest, verdict, timing, actor) to a local append-only log —
  `prev`/`hash` linking (sha256), so any edit breaks the chain. Independent of and lower-level
  than the existing `on_metric` observability hook.
- **Receipt stage**: an on-demand rollup of a log range into a verifiable receipt, deliberately
  shaped to match the company's existing **Autonomy Receipt** schema (schema id, operator,
  period, totals, integrity{algo, root, provenance, verify}, events[]) so toolnexus's own
  receipts compound with that separate OSS product instead of inventing a competing format.
- Off by default; zero cost when unused, matching the existing Hooks/Observability opt-in
  pattern. No new required dependency for any of the 5 ports.
- Byte-identical shape across all 5 language ports; new shared conformance fixtures under
  `examples/` cover shield-deny, log chain verification, and receipt rollup.
- **Not in this change**: implementation code. This proposal + its spec deltas + `tasks.md`
  scope the design and the per-language task breakdown only; building it is a follow-up change.
  Public release of the resulting package stays owner-gated — no publishing/release config is
  touched here.

## Capabilities

### New Capabilities
- `governed-execution`: the Toolkit-level governance hook — shield-check, provenance log, and
  Autonomy-Receipt-compatible rollup wrapping every `execute()` call (MCP + A2A + native +
  http + builtin sources alike).

### Modified Capabilities
(none — this is additive; existing `client-observability`, `a2a-outbound`, `mcp-inbound` etc.
behavior is unchanged. A future change may wire `ClientOptions.hooks.beforeTool` to delegate to
this layer, but that is out of scope here.)

## Impact

- Affected: `Toolkit` construction/options in all 5 ports (new optional `governance`/`guard`
  option), a new small module per port (shield adapter interface + reference `brain` adapter,
  hash-chain log writer, receipt rollup), `examples/` (new shared fixtures), `SPEC.md` (new
  section documenting the contract, alongside existing §8 Hooks/Observability).
- Not affected: MCP/skill discovery, adapter mapping (OpenAI/Anthropic/Gemini), the client
  loop's own hooks, publishing/CI beyond adding the new fixtures to each port's test run.
