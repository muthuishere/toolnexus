## Why

`SPEC.md §7F` states that compaction is "the canonical use of the §8 `beforeLLM` hook", and
`§7E` defines the persona archetype — a long-lived agent whose identity lives in files, exactly
the agent §7F exists to keep under budget. But the `§7D` agent runtime builds its per-agent
client **internally** and forwards neither `hooks` nor `onMetric`, so a §7E persona agent cannot
compact and an agent run emits no observability. The library specifies a capability its own
runtime cannot reach. This is `docs/adr/0008-agent-runtime-must-expose-the-section-8-hooks.md`,
raised by a consumer of the Go port that has finished tracing and compaction subsystems which
both emit nothing.

## What Changes

- Add two **optional** options to the `§7D` agent runtime in every port: `hooks` (the §8
  lifecycle callbacks) and `onMetric` (the §8 observability sink).
- The runtime forwards both **verbatim** into the `ClientOptions` it builds per agent turn. It
  attaches no meaning to either: no composing, wrapping, reordering, or defaulting.
- **Unset ⇒ byte-identical to today.** Same guarantee `§7F` already gives for an absent
  compactor. No existing fixture output changes. Not breaking.
- The seam is **two typed fields, not one escape hatch** — it must not become a route to
  overwrite `systemPrompt`, `waitFor`, the HTTP seam, or `store`, which would break soul
  composition, §10 suspension, and durable resume.
- `SPEC.md §7D`'s options table gains both fields plus a sentence stating that §7F compaction
  is configured on an agent run by supplying a `beforeLLM` compactor through them — the
  §7F↔§7D inconsistency is the defect being fixed, so the cross-language contract moves too.
- A shared `examples/` fixture demonstrates an agent run that stays under budget with a
  compactor attached, and is byte-identical without one.

**Explicitly out of scope** (both deferred in ADR 0008, with reasons): a `preCompact` hook that
can *abort* a compaction (new control flow on a hypothesis; wait for the downstream two-week
live run), and `cache_control` breakpoints (a provider-payload change, deserves its own ADR).

**Correction to ADR 0008:** the ADR guessed parity scope was five ports because `java/` "appears
to have no agents runtime". It does — `java/src/main/java/io/github/muthuishere/toolnexus/agents/`
holds `AgentRuntime.java`, `RuntimeOptions.java`, `AgentDef.java`, `Handle.java` and a
`Compaction.java` helper, in the same shape as the rest. **Scope is six ports.**

## Capabilities

### New Capabilities

_None._ This widens an existing seam rather than introducing a capability.

### Modified Capabilities

- `agent-runtime`: the runtime's cross-cutting-infrastructure requirement gains the two
  forwarded §8 seams, the forwarded-not-interpreted rule, the invariants-are-not-overridable
  rule, and the unset-is-byte-identical guarantee.
- `context-compaction`: compaction becomes reachable from a `§7D` agent run (today the spec
  only describes it on a directly constructed client), with the no-op-below-budget guarantee
  holding identically on that path.
- `client-observability`: `onMetric` gains a second entry point, so its semantic events MUST
  be emitted identically whether the client was constructed directly or by the agent runtime.

## Impact

Per-port, in each case two optional option fields plus forwarding at the one site where the
runtime constructs its client:

| Port | Options type | Client construction site |
|---|---|---|
| `js/` | `RuntimeOptions` — `js/src/agents/runtime.ts:254` | `createClient(...)` — `runtime.ts:741` |
| `python/` | runtime options — `python/src/toolnexus/agents/runtime.py` | `create_client(...)` — `runtime.py:756` |
| `golang/` | `agents.Options` — `golang/agents/runtime.go:256` | `tn.CreateClient(...)` — `runtime.go:1100` |
| `java/` | `RuntimeOptions` — `java/.../agents/RuntimeOptions.java:14` (mutable fluent builder) | `LlmClient.Options` — `AgentRuntime.java:534` |
| `csharp/` | `RuntimeOptions` — `csharp/src/Toolnexus/Agents/AgentTypes.cs:101` (note `CloneWithRegistry` must copy both) | `LlmClient.Create(...)` — `AgentRuntime.cs:492` |
| `elixir/` | keyword options — `elixir/lib/toolnexus/agents/runtime.ex` | `Client.create(...)` — `elixir/lib/toolnexus/agents/handle.ex:143` |

Also changed: `SPEC.md` §7D (options table + the §7F pointer), a shared fixture under
`examples/`, and per-port conformance tests asserting the unset-is-byte-identical property
first. `elixir/` already injects a runtime-wide `MetricsRegistry` into every handle's client
(`runtime.ex:62`, `handle.ex:143`), so its `on_metric` addition is a sink alongside existing
aggregation, not a new concept. No public API is removed or altered; both fields default to
unset in all six ports.
