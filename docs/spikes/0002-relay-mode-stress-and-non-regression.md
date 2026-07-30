# Spike 0002 — relay mode: executable stress test + non-regression proof

**Status:** RESOLVED — **GREEN.** 14/14 spike tests pass; full Go suite green under
`-race` with the spike present; `go vet` clean.
**Date:** 2026-07-30 · branch `feat/relay-mode`
**Evidence:** `golang/relay_spike_test.go` — runs the **real** agent loop against a mock
LLM on **unmodified library code**. Reproduce: `cd golang && go test -run TestSpike -v .`
**Follows:** spike 0001 (which was source-verified only, and AMBER) · ADR-0010

## Why a second spike

Spike 0001 read the source and reasoned. That was enough to find the design but not
enough to bet six ports on it, because the §10 suspension layer it builds on is
**hardened and byte-verified across six ports** — the highest-risk place in the repo to
be wrong. This spike is the empirical half, and it answers two questions spike 0001
could not:

1. Does relay actually work on the existing primitive, end to end, or only on paper?
2. Does introducing it perturb existing behavior?

Method: prototype relay **entirely from today's public API** — a `Tool` that returns
`Pending` on first call and returns the caller's output on retry-with-answer — and drive
the real loop with a scripted mock LLM that records every request body, so assertions
are on *what the provider actually received*, not on internal state.

## Results

| # | Experiment | Result |
|---|---|---|
| S1 | Single relay call, in-process (`WaitFor`) | ✅ **works today, unmodified** |
| S2 | Caller-side tool failure relays back | ✅ error `tool_result`, loop continues |
| S3 | 3 parallel relay calls, in-process | ✅ all 3 surface, transcript balanced |
| S4 | 3 parallel relay calls, durable (no `WaitFor`) | ❌ **gap F2 confirmed, measured** |
| S5 | Relay + a REAL executing tool in one turn | ✅ real tool still runs (both paths) |
| S6 | Declaring a relay tool the model never calls | ✅ inert — no observable difference |
| S7 | Relay suspension is not a tool error | ✅ inherits §10's rule |
| S8 | Multi-round relay (3 rounds, same tool) | ✅ no lockout |
| S9 | Caller declines (`Ok:false`) | ✅ error result, run completes |
| S10 | Round trip through `ConversationStore` | ✅ ids + values replay structurally |
| S11 | **Anthropic-native loop** (in-process + durable) | ✅ works; same gap, same numbers |
| S12 | **Streaming loop** (in-process + durable) | ✅ works, emits `pending`; same gap |

### The headline: relay works today on the in-process path (S1)

The model emits a call → the host's `waitFor` receives it as structured data
(`kind:"tool_call"`, `data.name`, `data.input`) → nothing executes host-side → the
host's output becomes a real `tool_result` → **and the assertion checks it arrived in the
provider's next request body**, not just in `RunResult`. The run finishes `done`.

That settles ADR-0010's central claim empirically: **relay is a use of §10, not a new
loop mode.** For an in-process host, the feature already exists; `RelayTool` is
ergonomics over a mechanism that is already correct.

### The confirmed gap, now measured (S4)

Three relay calls in one turn on the durable path:

```
MEASURED BASELINE — durable relay halt: 3 tool_calls vs 1 tool_result (ids [c1]);
unbalanced transcript is not replayable to Anthropic. Pending carries only "alpha".
```

So both forks are real, and quantified:

- **F2** — the caller sees 1 of 3 calls. A conforming OpenAI client needs all 3.
- **F1** — the single `tool_result` present is the placeholder **error**, and the other
  two slots are simply absent. Anthropic requires one `tool_result` per `tool_use`, so
  that saved transcript cannot be replayed. The caller has nowhere to put truth.

S4 asserts the **current** behavior deliberately: it is the baseline the change must
move, and it will fail loudly if someone implements F2-a without updating it. It even
guards against silent drift — it fails if `data.calls` appears, i.e. if F2-a landed and
the spike went stale.

### The non-regression proof

This is what the owner asked to be sure of, and it is the part that lets six ports
proceed:

- **S5 — the highest-risk interaction.** A turn containing one relay call *and* one real
  executing tool: the real tool still executes. Verified on **both** the in-process and
  durable paths, with the real tool ordered first so a halt cannot pre-empt it.
- **S6 — inert when unused.** A declared-but-never-called relay tool produces identical
  `text`, `status`, `turns`, and zero tool calls versus a toolkit without it. This is
  ADR-0010's byte-identical-when-absent rule, measured rather than asserted.
- **S7 / S9 — §10's rules are inherited, not re-implemented.** A resolved relay call is
  not an error; a declined one feeds back an error result and the run still completes.
  No second suspension mechanism appears.
- **S8 — no lockout.** `resolvePending`'s "never loop forever" guard is per-call, so a
  relay tool can legitimately be called again in a later turn. Three rounds, three
  resolutions.
- **Whole suite.** `go vet ./...` clean; `go test -race ./...` green with the spike
  present — including the pre-existing `TestConcurrentSuspensionsSurfaceFirst` and its
  streaming twin, i.e. the hardened §10 concurrency tests still pass untouched.

### S11 — the Anthropic loop, which is the one that matters (caveat closed)

routsi translates *to* Anthropic, so the Anthropic-native loop is the real target. Relay
works there identically: the `tool_result` block references the original `tool_use` id
(`tu_1`) natively, and the caller's output reaches the provider. And the durable baseline
is the same shape, measured on native blocks:

```
MEASURED BASELINE (anthropic loop) — 3 tool_use vs 1 tool_result: the same
unbalanced, non-replayable transcript as the OpenAI loop.
```

That is a useful negative result: the gap lives in the **shared suspension path**, not in
one transcript builder. So F1-a/F2-a is one fix per port, not one per provider style.

### S12 — the streaming loop (caveat closed)

Relay resolves inline on `Stream()` too, and the loop emits the `{type:"pending"}` stream
event carrying the relay request — so a channel host can push the call to the caller in
real time, which is exactly what a streaming proxy needs. The streaming durable halt
reproduces the same baseline (3 calls, 1 result, first-in-order), so **F1-a/F2-a must land
on the streaming path in every port**, not only the non-streaming one.

### S10 closes routsi ADR-010 item 4 by execution

Two `Ask` calls on the same conversation id: the second provider request body contains
`"tool_calls"`, the id `"c1"`, the tool name, and the relayed value. Structural
round-trip confirmed by observing the wire, not by reading the store's type.

## What this changes about the plan

- **Confidence to proceed to six ports.** The mechanism is proven and the blast radius
  is bounded: the design adds a constructor, a pinned `data` shape, and a resume entry
  point — it does not touch the execute-or-not branch, and existing behavior is
  measurably unperturbed.
- **F1-a and F2-a are both necessary, and F2-a is not optional sugar.** S4 shows a
  durable relay caller is broken without it, not merely limited.
- **Scope for the resume entry point is now precise:** it must fill **every** outstanding
  `tool_result` slot of the halted assistant turn (S4 measured 2 missing out of 3), not
  just the halted tool's.
- **The five other ports need this same spike.** Go's loop is the reference, but §10 was
  shipped six times; parity is the product. Each port's `tasks.md` entry should port
  these fourteen cases, and S4's numbers are the cross-port oracle.

## Caveats — stated plainly

- **Go only.** The other five ports are unverified empirically; spike 0001's source
  reading is all that covers them.
- **Mock LLM, no live provider.** Hermetic by repo design. So "Anthropic rejects an
  unbalanced transcript" is a documented provider requirement, not something this spike
  observed a 400 for. The *imbalance itself* is measured; the rejection is inferred.
- ~~OpenAI-style transcript only~~ — **closed by S11** (Anthropic-native loop covered).
- ~~Non-streaming only~~ — **closed by S12** (streaming loop covered).
- `golang/relay_spike_test.go` is spike scaffolding, not the shipping suite. It should be
  folded into the real tests (or deleted) when the change lands.
