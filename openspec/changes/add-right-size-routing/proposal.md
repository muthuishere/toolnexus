## Why

toolnexus is the **routing organ** of the deemwar fleet: the CEO's `fleet-nexus` skill and the
`huddle-nexus` voice both drive work to an OpenRouter model *through toolnexus's client/CLI*
(`toolnexus run --base-url … --style openai --model <slug> --once "<prompt>"`, or the
library `Client` with the same `{baseUrl, style, model}`). The point of doing so is
**right-sizing**: send a cheap job (classify / verify / route) to a cheap model and reserve the
expensive model for agentic work — the CEO's registry already encodes this as tiers
(`~/.config/deemwar-one-os/openrouter.json`: `models.classify_verify` / `route` →
`deepseek/deepseek-chat`, `models.fleet_agentic` → `moonshotai/kimi-k2`).

That right-sizing only holds if two properties are **guaranteed and tested**, not assumed:

1. **Model faithfulness** — the model id the caller selected is the model that actually gets
   billed. If the client ever rewrote, defaulted, or dropped the `model` field, a "cheap" route
   would silently escalate cost. Today this is true in all five ports but is nowhere asserted as
   a *routing* contract.
2. **A gate on escalation** — an expensive route should be *governable*: the fleet must be able
   to require a reason before an expensive-tier call runs (complementarity with the `brain`
   organ's constraint shield). Today the `beforeLLM` hook exists but its use as a cost/route gate
   is undocumented, and hard veto lives in the separate `add-governed-execution-layer` change.

This change pins the right-size-routing contract in the spec and proves it with a **hermetic
conformance test** (mock endpoint, no live key), so CI protects the property the whole fleet
depends on. It is grounded in the model-cascade / routing literature (see `design.md`):
FrugalGPT (Chen, Zaharia, Zou 2023 — up to ~98% cost reduction by routing to the cheapest model
that clears a quality bar) and RouteLLM (Ong et al. 2024 — learned strong/weak routing riding
the cost–quality Pareto frontier). toolnexus deliberately implements the *deterministic,
per-job-class* point on that frontier (static tiers chosen by the operator) rather than a learned
per-query router — auditable and reproducible, consistent with the fleet's governance ethos.

## What Changes

- **Document + test the model-faithfulness contract**: `Client.run` (and the `toolnexus run`
  CLI) SHALL transmit the caller-configured `model` id to the endpoint **unchanged** on every
  turn — no rewrite, no silent default when the caller supplied one. Add a hermetic conformance
  test (mock OpenAI-style server) asserting the request body's `model` equals the configured
  tier model and the returned text is the model's answer. This is the CEO use case
  ("route one verify/classify job to a cheap model, get the correct result") pinned in CI.
- **Document the routing-tier registry contract** the fleet consumes: the shared config shape
  (`base_url`, `style`, `api_key_env`, `models.<tier>`) and the job-class→tier mapping, so
  `fleet-nexus` / `huddle-nexus` and toolnexus agree on one contract instead of an implicit one.
- **Document the brain-check route gate**: how `hooks.beforeLLM` (which already receives the
  `model` for each turn) is the seam where an expensive-tier route can be gated — the reference
  pattern shells out to `brain check "<why this model>" --json` and aborts the run when the
  shield does not return `guaranteed`. toolnexus does not hard-depend on `brain`; the hard-veto
  path is `add-governed-execution-layer`. Add a hermetic test proving a `beforeLLM` gate can
  block an expensive-tier route (raise/abort) while allowing a cheap-tier route.
- Off by default, zero behavior change when unused: model faithfulness is already the behavior;
  this change only *asserts* it and documents the two governance seams. No new dependency.

## Capabilities

### New Capabilities
- `right-size-routing`: the caller-controlled, byte-faithful model-tier routing contract that
  the fleet drives toolnexus through, plus the documented `beforeLLM` route-gate seam.

### Modified Capabilities
(none — additive. Existing `client-observability` behavior is unchanged; this pins an
already-true property and documents the hook seam.)

## Impact

- Affected: `SPEC.md` (new "Right-size routing" note alongside §8 Hooks/Observability), a new
  hermetic conformance test in `golang/` (the CLI + fleet contract are Go), and the shared
  `examples/` docs pointer to the routing-tier registry shape. Library-level model faithfulness
  is already exercised by each port's existing client tests (cited in `tasks.md`); the NEW test
  asserts it as the *routing* contract end-to-end through the CLI path fleet-nexus uses.
- Not affected: MCP/skill discovery, adapter mapping, publishing/CI beyond the new Go test,
  the client loop's control flow. No model is called live in CI.
