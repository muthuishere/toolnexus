# ADR 0008 — The §7D agent runtime must expose the §8 hooks (so §7F compaction is reachable)

- **Status:** **Accepted and shipped** (2026-07-26) — implemented across all **six** ports via
  OpenSpec change `expose-agent-runtime-hooks`. Two corrections to this document, both made
  after checking the code rather than reasoning about it:
  - **Parity scope is six ports, not five.** `java/` *does* have a §7D runtime
    (`agents/AgentRuntime.java`, `RuntimeOptions.java`, `AgentDef.java`, plus
    `Compaction.java`). The "java appears to have none" reading below was wrong.
  - **The seam is two levels, not one.** Runtime-wide fields alone cannot express this ADR's
    own driver — *per-agent* compaction config — so `hooks`/`onMetric` were added to the
    agent definition as well, resolved def-over-runtime (replace, never merge), each field
    independently. See the change's `design.md` D1.

  The seam was **spiked in `golang/` and `elixir/` before acceptance**; the spike measured the
  unset-is-byte-identical claim against output recorded from `main`, and turned up three things
  the paper design missed (the §10 rewind interaction, a silently-ignored wrong-arity hook in
  `elixir`, and a `:registry` name collision). Both deferrals below stand.
- **Date:** 2026-07-26
- **Driver:** `SPEC.md §7F` states that "Compaction is the canonical use of the §8
  `beforeLLM` hook", and `§7E` defines the persona archetype — a long-lived agent whose
  identity lives in files — which is precisely the agent §7F exists to keep under budget.
  But `§7D`'s agent runtime offers **no way to supply §8 hooks**, so a §7E persona agent
  cannot compact. The spec promises a capability its own runtime cannot deliver.
- **Honesty note:** this ADR is written by a **consumer** of the Go port
  (`agentic-nexus`, ADR 0003, `openspec/changes/add-m3-living-layer`), not by someone who
  has implemented the ports. Every claim below is a `file:line` in this repository, and
  no code in this repository has been changed. Effort estimates for the non-Go ports are
  the weakest part of this document and belong to whoever owns them.

## Context

### What exists and works

Compaction is **already implemented** in the Go port and is not in question. A consumer
verified it end-to-end by driving `tn.CreateClient` directly: over budget the transcript
head really is summarized and the leading system prompt survives verbatim; under budget
the hook is a byte-identical no-op. `§7F`'s own promise holds — "absent a `compactor`, a
run is byte-identical to today" (`SPEC.md:912`).

`tn.ClientOptions` carries both seams a long-lived agent needs:

- `Hooks *Hooks` — `golang/client.go:48`
- `OnMetric func(MetricEvent)` — `golang/client.go:60`

### The gap

`agents.Options` (`golang/agents/runtime.go:256`) has eight fields — `Transport`,
`Registry`, `InboxCap`, `MaxConcurrentTurns`, `Shutdown`, `LLM`, `Store`, `Clock` — and
**neither `Hooks` nor `OnMetric`**.

`golang/agents/runtime.go:1100` builds its `tn.ClientOptions` internally:

```go
client := tn.CreateClient(tn.ClientOptions{
    BaseURL: baseURL, Style: style, Model: model, APIKey: apiKey,
    SystemPrompt: def.Soul, MaxTurns: maxTurns,
    HTTPClient: &http.Client{Transport: gatedTransport{...}},
    WaitFor: waitFor,
})
```

`Hooks` and `OnMetric` are simply not forwarded. There is no argument a caller can pass,
no wrapper it can install from outside, and no ordering trick — the capability is behind
a wall with no door. A consumer that wants compaction on an agent run has exactly three
options today, and all three are bad:

1. Abandon `agents` and build on `tn.Client` directly — losing soul composition, teams,
   budgets, inbox and durable resume, i.e. the entire reason `§7D` exists.
2. Reimplement compaction consumer-side — a second implementation of behavior this
   library already specifies, which is precisely the silent drift the prime directive
   exists to prevent.
3. Ship a configuration surface that is accepted and not honored.

The consumer chose (3) and made it loud: it logs every requested setting it is not
delivering, once per agent, and reports `Attached: false`. That is honest, but the
setting still does nothing.

### Two capabilities are blocked on this one field

Both are complete and tested downstream, and both emit nothing:

| Consumer capability | State | Blocked on |
|---|---|---|
| Distributed tracing (`internal/obs`) | written, tested, **zero spans emitted** since its milestone | `Hooks` / `OnMetric` |
| Per-agent compaction config (`internal/runtime/context.go`) | mapping onto `agents.CompactorOptions` proven against a direct `tn.Client`; `Attached: false` on an agent run | `Hooks` |

The second matters beyond one consumer.
`docs/references/agent-fundamentals-audit-2026-07-17.md` — this repository's own
research — calls missing compaction the **"persona killer"** and says an agent without it
"dies within days". `§7E` ships the persona archetype. Today that archetype cannot use
the fix its own audit identifies as the most important one.

## Decision

Add two optional fields to the `§7D` agent runtime's options and forward them into the
`ClientOptions` the runtime builds:

- **`Hooks`** — the `§8` lifecycle callbacks, forwarded verbatim.
- **`OnMetric`** — the `§8` observability sink, forwarded verbatim.

Rules that make this cheap and safe:

1. **Unset ⇒ byte-identical to today.** Same guarantee `§7F` already gives for an absent
   compactor. This is the property the conformance suite should assert first.
2. **Forwarded, not interpreted.** The agent runtime attaches no meaning to either field.
   It does not compose, wrap, reorder or default them. Everything `§8` already specifies
   about hook semantics continues to hold unchanged.
3. **The runtime's own invariants stay non-negotiable.** These fields must not become a
   route to overwriting `SystemPrompt`, `WaitFor`, `HTTPClient` or `Store` — see the
   rejected alternative below. That is the whole reason the seam is two typed fields
   rather than one escape hatch.
4. **`SPEC.md` first**, per the prime directive: `§7D`'s options table gains both fields
   and a sentence stating that `§7F` compaction is configured on an agent run by
   supplying a `beforeLLM` compactor through them. The `§7F`↔`§7D` inconsistency is the
   defect being fixed, so the spec is where the fix starts.

### Scope: this ADR is deliberately one seam

Two adjacent asks came out of the same downstream investigation. Both are **excluded**
here, with reasons, so this decision stays small enough to be obviously safe.

**Deferred — `PreCompact func(ctx, dropping []any) error` (flush-as-precondition).**
`CompactorOptions.FlushToMemory` today appends a system *reminder* to the
**already-compacted** transcript: no turn runs, the dropped head is gone before the agent
reads it, and nothing gates the compaction on the flush completing. openclaw's pre-compact
flush — per the audit — runs *before* the drop. Making that expressible means a hook that
can **abort** a compaction, which is new control flow plus a decision about what happens
when the flush itself fails, in a byte-parity spec, across five ports. The consumer's own
ADR 0003 §9 sets a two-week live run as the test of whether memory loss at compaction
actually bites; agents that write memory through tools during normal turns may not depend
on a pre-compact sweep the way openclaw does. **Recommendation: wait for that evidence.**
Committing new control flow to a multi-language spec on a hypothesis is the wrong order.

**Deferred — `cache_control` breakpoints.** No port emits them, so Anthropic prompt
caching is structurally unreachable regardless of how a caller orders its prompt, and
`addUsage` (`golang/client.go:321`) drops `cached_tokens` / `cache_read_input_tokens`, so
hit rate cannot be read from `Usage` either. This is a real cost item — a consumer
measured 0.00% → 73.39% on OpenAI purely from prompt ordering, and can do nothing
equivalent on Anthropic. It is a provider-payload change, not a seam, and deserves its
own ADR.

## Consequences

- **The Go change is small; the bill is parity.** Two fields plus two lines at
  `golang/agents/runtime.go:1100`. The actual cost is the same seam plus conformance
  coverage in every port that has a `§7D` runtime.
- **Parity scope is five ports, not six.** `js/`, `python/`, `golang/`, `csharp/` and
  `elixir/` have an agents runtime; **`java/` appears to have none**, so it is already
  behind on `§7D` and this ADR does not change that. Whoever owns `java/` should confirm
  — if that reading is wrong, this ADR's scope is six.
- **`elixir/` is the port to think about before agreeing.** Its options are immutable and
  a hook is a function value; the shape that reads naturally in Go/C#/Python may not be
  idiomatic there. That is an argument about *how*, not *whether* — but it should be
  settled in the OpenSpec proposal, not discovered in implementation.
- **Nothing breaks.** Both fields are optional and nil-valued by default. No existing
  fixture output changes, which is the claim the conformance suite must pin.
- **`§8` gains a second entry point**, so hook semantics now have to hold identically
  whether a caller reaches them through `tn.CreateClient` or through the agent runtime.
  That is a real (small) widening of the parity contract.
- **It unblocks two finished consumer subsystems at once** — tracing and compaction — and
  removes the only current case of this library specifying a capability its own runtime
  cannot reach.
- **If rejected:** `§7F` should say plainly that compaction is unavailable to `§7D`/`§7E`
  agent runs and is reachable only from a directly constructed client. The present
  situation — a spec that promises it and a runtime that cannot deliver it — is worse
  than either decision, because it reads as a working feature.

## Alternatives considered

- **One `ConfigureClient func(*tn.ClientOptions)` escape hatch instead of typed fields.**
  Tempting: one field, and no consumer ever comes back for the next seam. **Rejected** on
  two grounds. It lets any caller overwrite `SystemPrompt`, `WaitFor`, `HTTPClient` or
  `Store`, which would break soul composition, `§10` suspension and durable resume — the
  runtime's invariants would become advisory. And a mutator over a mutable options struct
  does not port cleanly to `elixir/`. Narrow typed fields cost a return trip later and
  keep the parity story clean.
- **Consumer builds `tn.Client` directly.** Works today — it is how compaction was
  verified — but forfeits `§7D` entirely. Rejected as a general answer: it amounts to
  telling every long-lived-agent consumer not to use the agent runtime.
- **Consumer reimplements compaction.** Rejected by the consumer's own ADR 0003 §5, and it
  is the exact silent drift this repository's prime directive exists to prevent.
- **Expose a `Compactor` field specifically, rather than general `Hooks`.** Narrower, and
  it would fix compaction alone — but tracing is blocked on the same wall, and `§7F`
  already defines compaction *as a use of* `beforeLLM`. A compaction-shaped field would
  duplicate `§8` rather than reach it.
- **Do nothing.** Defensible if the §7D runtime is not meant for long-lived agents — but
  `§7E` says it is, and the audit says those agents die without compaction.

## Next gate

Per the prime directive, this ADR is the discussion, not the change. If accepted, the
work is an **OpenSpec change** whose spec deltas pin: the two `§7D` options, the
unset-is-byte-identical guarantee, and a shared `examples/` fixture demonstrating an
agent run that stays under budget with a compactor attached and is byte-identical
without one — then five ports against that fixture.

Downstream evidence, if useful while deciding:
`agentic-nexus/openspec/changes/add-m3-living-layer/UPSTREAM-TOOLNEXUS.md` (exact
signatures and blocked call sites), `internal/obs/obs.go:18`,
`internal/runtime/context.go`.
