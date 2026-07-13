## Context

The fleet routes work to models **through toolnexus**. The routing decision is made *outside*
the library — by the operator's registry (`~/.config/deemwar-one-os/openrouter.json`) and the
`fleet-nexus` skill that reads it — and handed to toolnexus as a concrete `{baseUrl, style,
model}`. toolnexus's job in the routing chain is narrow but load-bearing: **execute the chosen
route faithfully and expose a gate**. This design records why that split is correct and cites
the routing literature it follows.

## Research grounding (cited in the spec)

- **FrugalGPT** — Chen, Zaharia, Zou, "FrugalGPT: How to Use Large Language Models While Reducing
  Cost and Improving Performance" (arXiv:2305.05176, 2023). Cascade of LLMs with a learned
  quality estimator + stop-judge; reports up to ~98% cost reduction at matched quality by
  sending each query to the cheapest model that clears a quality bar.
- **RouteLLM** — Ong et al., "RouteLLM: Learning to Route LLMs with Preference Data"
  (arXiv:2406.18665, 2024). Learns a binary strong/weak router from preference data; frames
  routing as riding the **cost–quality Pareto frontier** — most quality for a given cost, least
  cost for a given quality.
- Survey framing — "Dynamic Model Routing and Cascading for Efficient LLM Inference: A Survey"
  (arXiv:2503.04445): routing (pick one model up front) vs. cascading (escalate on low
  confidence) are two families over the same frontier.

**Where toolnexus sits on the frontier.** FrugalGPT/RouteLLM route *per query* using a learned
estimator. toolnexus + the fleet registry route *per job class* using **static, operator-chosen
tiers** (`classify_verify`/`route` → cheap; `fleet_agentic` → strong). This is a deliberately
simpler, deterministic point on the same Pareto frontier:

- **Auditable & reproducible** — the tier for a job class is a config value, not a model's
  runtime guess; every route is explainable and replayable. This matches the fleet's governance
  ethos (the `brain` shield, the provenance log in `add-governed-execution-layer`).
- **No estimator to train or drift** — the operator owns the quality/cost tradeoff explicitly.
- **Cascading is a future add-on, not a prerequisite** — if a cheap route's answer fails a
  verifier, the *caller* can escalate to a stronger tier (a FrugalGPT-style stop-judge lives
  above toolnexus, in the fleet loop / `citenexus` verify organ), and toolnexus already supports
  it by simply being called again with a different `model`. This change does not build a learned
  router; it guarantees the substrate a router would need: faithful execution + a gate.

## Goals / Non-Goals

- **Goal**: guarantee (and test) that the caller's chosen model id is the one billed — no silent
  escalation. Document the tier registry contract and the `beforeLLM` route-gate seam.
- **Non-Goal**: a learned/automatic router or in-library cascade. Routing *policy* stays with the
  operator/fleet; toolnexus provides faithful execution + gate, not model selection.
- **Non-Goal**: hard veto of a tool call — that is `add-governed-execution-layer`.

## Decisions

- **D1 — Faithfulness is a contract, not a feature.** All five ports already send `model`
  verbatim; this change asserts it with a conformance test rather than adding code, so the
  property can't regress. The test drives the **CLI** (`toolnexus run --once`) against a mock
  OpenAI-style server, because the CLI is the exact surface `fleet-nexus`/`huddle-nexus` invoke.
- **D2 — The gate is `beforeLLM`, the policy is external.** `hooks.beforeLLM` already receives
  `{messages, tools, model, turn}` per turn. That is the seam to gate an expensive-tier route.
  The reference gate raises/aborts when `brain check "<why this model>"` does not return
  `guaranteed`. toolnexus ships no `brain` dependency; a host wires the adapter. Hard veto at the
  *tool* layer remains the separate governed-execution change; these compose (route-gate at the
  model call, tool-veto at `execute()`).
- **D3 — Registry shape is documented, not owned.** The tier registry lives in the CEO's
  runtime config, not the repo. The spec documents the shape (`base_url`, `style`,
  `api_key_env`, `models.<tier>`) as the contract between the fleet skills and toolnexus so both
  sides can be checked, without toolnexus depending on that file.

## Open Questions

- Should the CLI grow a `--tier` flag that resolves against a registry path, so callers name a
  job class instead of a raw slug? Deferred — keeps model selection in the fleet layer for now.
- Unifying the `beforeLLM` route-gate with the governed-execution tool-veto into one policy
  object is tracked as out-of-scope in `add-governed-execution-layer` §8.1.
