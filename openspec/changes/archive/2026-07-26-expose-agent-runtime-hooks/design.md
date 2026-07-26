## Context

The `§7D` agent runtime owns the LLM client for each handle: it constructs a `ClientOptions`
internally at one site per port and never lets a caller near it. That is deliberate — the
runtime's invariants (soul as `systemPrompt`, the §10 escalating `waitFor`, the gated HTTP
seam, the ONE runtime-wide `ConversationStore` keyed by handle id) all live in that struct, and
a caller that could rewrite them would break team composition, suspension, and durable resume.

The cost of that wall is that the two §8 seams a long-lived agent needs — `hooks` and
`onMetric` — are unreachable. `§7F` compaction is *defined* as a use of `beforeLLM`, and every
port already ships a compaction helper (`agents/compaction.*`, `Compaction.cs`,
`Compaction.java`) that returns exactly such a hook. There is currently no way to hand one to
an agent.

Verified current state, all six ports:

| Port | Runtime options | Client construction | `hooks` | `onMetric` |
|---|---|---|---|---|
| `js/` | `RuntimeOptions` (`agents/runtime.ts:254`) | `createClient` (`runtime.ts:741`) | ✗ | ✗ |
| `python/` | runtime options (`agents/runtime.py`) | `create_client` (`runtime.py:756`) | ✗ | ✗ |
| `golang/` | `agents.Options` (`agents/runtime.go:256`) | `tn.CreateClient` (`runtime.go:1100`) | ✗ | ✗ |
| `java/` | `RuntimeOptions` (fluent, mutable) | `LlmClient.Options` (`AgentRuntime.java:534`) | ✗ | ✗ |
| `csharp/` | `RuntimeOptions` (`AgentTypes.cs:101`) | `LlmClient.Create` (`AgentRuntime.cs:492`) | ✗ | ✗ |
| `elixir/` | keyword opts (`agents/runtime.ex`) | `Client.create` (`handle.ex:143`) | ✗ | ✗ (injects a shared `MetricsRegistry`) |

Client-side names are already stable and idiomatic per port and are **not** being changed:
`hooks`/`onMetric` (js), `hooks`/`on_metric` (python, elixir), `Hooks`/`OnMetric` (go, csharp),
`hooks(...)`/`onMetric(...)` (java builder).

## Goals / Non-Goals

**Goals:**

- Make §7F compaction and §8 observability reachable on a `§7D` agent run, in all six ports.
- Keep the runtime's invariants non-negotiable: the new seam must not be able to touch
  `systemPrompt`, `waitFor`, the HTTP seam, or `store`.
- Unset ⇒ byte-identical to today. This is the property the conformance suite asserts first.
- Let a caller configure **per agent**, not only per runtime — the concrete downstream need is
  per-agent compaction budgets, which a single runtime-wide hook cannot express.

**Non-Goals:**

- A `preCompact` hook that can abort a compaction (ADR 0008, deferred pending downstream
  evidence — new control flow across six ports on a hypothesis).
- `cache_control` breakpoints / `cached_tokens` in `Usage` (ADR 0008, deferred — a
  provider-payload change, its own ADR).
- Changing any §8 hook semantics, the `MetricEvent` shape, or the compaction helpers.
- Any general `configureClient(opts)` escape hatch.

## Decisions

**D1 — Both fields available at two levels: runtime-wide default, per-`AgentDef` override.**
ADR 0008 proposes runtime-level fields only. That is not sufficient for its own stated driver:
the consumer's blocked capability is *per-agent* compaction config, and one runtime-wide
`beforeLLM` closure cannot vary its budget by agent, nor can a runtime-wide `onMetric` attribute
an event to the agent that produced it. Adding the same two fields to `AgentDef` costs one more
optional field per port and solves both: a caller writes the compactor (or the metric sink) with
the agent already closed over. Precedence is **replace, never merge** — a def-level value wins
outright for that agent; unset falls back to the runtime value; both unset ⇒ no hook. Merging
was rejected: composing two `beforeLLM` transcript rewrites has no obvious correct order and
would be a new semantic in a byte-parity spec.

Alternative considered — runtime-level `hooksFor(agentName)` factory: one field, per-agent
result. Rejected: a factory is a fourth kind of thing in the options surface, is awkward in
`elixir/` and `java/`, and `AgentDef` is already where per-agent configuration lives (soul,
model, tools, budget).

**D2 — Forwarded verbatim, never interpreted.** The runtime assigns the resolved value straight
into the `ClientOptions` field it already builds. It does not compose, wrap, reorder, or default
either value, and it does not read them. Everything §8 specifies about hook and metric semantics
continues to hold unchanged, which is what makes the second entry point cheap: there is nothing
new to keep in parity beyond "the same value arrives".

**D3 — Two typed fields, not one `configureClient` mutator.** ADR 0008's rejected alternative,
and this design keeps that rejection. A mutator over the options struct would let a caller
overwrite `systemPrompt`, `waitFor`, the HTTP seam or `store`, turning the runtime's invariants
into advice; it also ports badly to `elixir/`, where options are an immutable keyword list.
Narrow typed fields cost a return trip when the next seam is needed and keep the parity story
clean.

**D4 — `elixir/` keeps its existing shared `MetricsRegistry` behavior unchanged.** `runtime.ex:62`
creates one registry and `handle.ex:143` injects it into every handle's client, so runtime-wide
aggregation already works there. `on_metric` is added *alongside* it as the caller-facing sink,
exactly as `Toolnexus.Client` already relates the two (`client.ex:560-561`: record to the
registry, then call the sink). No other port grows a registry in this change.

**D5 — `csharp/`'s `RuntimeOptions.CloneWithRegistry` must copy both new fields.** It is a
hand-written field-by-field clone (`AgentTypes.cs:135`); a missed field there is a silent
per-subtree drop that no other port can reproduce. Called out as its own task, with a test.

**D6 — Fixture-first conformance.** A shared fixture under `examples/compaction/` (the directory
already exists) drives an agent run twice against the same scripted transcript: once with a
compactor attached through the new seam, once without. The without-run must be byte-identical to
the run recorded before this change; the with-run must show the summarized head and the verbatim
leading system prompt. That single fixture pins D2 and the unset guarantee in all six ports.

## Spike results (2026-07-26, branch `spike/agent-runtime-hooks`)

The seam was implemented for real in `golang/` and `elixir/` — the driver port and the port
ADR 0008 called "the one to think about before agreeing" — and exercised with throwaway tests.
Everything in Decisions above held. Five findings that change the plan:

**S1 — The design works, end to end, in both ports.** `golang`: a per-agent compactor took a
seeded 81-message transcript down to 3 messages on the wire, leading soul verbatim, summary
present. `elixir`: same fixture, 81 → 4 stored after the turn. Def-over-runtime replacement
behaves as specified in both, and the two fields resolve independently.

**S2 — Unset-is-byte-identical is now measured, not asserted.** A golden capture (wire
transcript + stored transcript + transition trace + result) was produced from a `main` git
worktree and from the branch: **identical**. `go test -race ./...` and `mix test` (336 tests)
both pass unchanged with the seam in place. This is the method task 2.3 should use.

**S3 — §10 rewind already does the right thing with compaction, and the spec must say so.**
A turn that compacts and *then* suspends is rewound to its pre-turn checkpoint, so the stored
transcript returns to all 81 messages and the compaction is discarded (measured: `pre=81
post=81`). That is correct — the compactor is pure, so resume re-compacts — but it is currently
accidental, not specified. **Add a scenario pinning it**, otherwise a port could "optimize" by
persisting the compacted head and silently lose the rewind guarantee.

**S4 — `elixir`'s `before_llm` is arity-1 and a wrong-arity hook is silently ignored.**
`client.ex:828` guards on `is_function(f, 1)` and falls through to the no-op branch otherwise —
the spike's first attempt passed a 3-arity function and the hook simply never ran, with no error.
Once hooks arrive from two places this becomes much easier to hit. **Add a task**: either a loud
error on a non-arity-1 `before_llm`, or an explicit "silently ignored" note in the spec. Not
adding one is a decision, but it should be a decision.

**S5 — `:registry` means two different things one layer apart in `elixir`.** In the agent
runtime's options it is the **agent registry** (`runtime.ex:78`); in `Client.create` it is the
**MetricsRegistry** (`handle.ex`). Forwarding `on_metric` puts both in the same function. Harmless
today, a trap for whoever implements it — called out in the elixir tasks.

## Risks / Trade-offs

- **A hook can break the runtime's transcript contract.** `beforeLLM` rewrites the working
  transcript, and the runtime rewinds that transcript on a §10 pending. A caller-supplied hook
  that drops the leading system prompt or orphans a tool pair produces a broken agent.
  → Mitigation: the shipped compaction helpers already guarantee tool-pair safety and system-
  prompt preservation; the spec states plainly that a hand-written hook owns those invariants
  itself, and the §7D docs point at the helper as the supported path.
- **Hooks run synchronously inside a gated turn.** A slow hook holds a turn-gate slot and can
  starve sibling agents.
  → Mitigation: documented, not enforced. Same contract §8 already states for a directly
  constructed client; the gate wraps only the HTTP call, so a slow hook delays its own agent
  first.
- **Two levels means a precedence rule to keep in parity across six ports.**
  → Mitigation: the rule is one sentence (def replaces runtime) with a dedicated scenario and a
  fixture case where a runtime-level hook is set and one agent overrides it.
- **`§8` gains a second entry point**, so hook semantics must now hold identically on both
  paths — a real, small widening of the parity contract.
  → Mitigation: D2 makes the runtime a pass-through, so the only new assertion is "the same
  value arrives", not a duplicated semantics suite.
- **`java/` was assumed absent by ADR 0008 and is not.** Whoever owns the port has one more
  implementation than the ADR budgeted.
  → Mitigation: recorded in the proposal and in `tasks.md` as a full sixth parity column;
  `java/`'s mutable fluent `RuntimeOptions` makes it the cheapest of the six.
