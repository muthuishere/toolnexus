# ADR 0013 — §7F needs a model-free tool-result pruning stage before it summarizes

- **Status:** **Proposed** — discussion document. Nothing in `SPEC.md`, `conformance/` or
  any of the seven ports has been changed by this ADR.
- **Date:** 2026-08-15
- **Driver:** `SPEC.md:943` states that "Compaction is the canonical use of the §8 `beforeLLM`
  hook", and every port implements that one way: summarize the head with a model
  (`CompactorOptions.Summarize` is **required** — `golang/agents/compaction.go:64`, and the
  call at `:133` is unguarded). So the only tool §7F gives a long-lived agent for staying
  under budget costs a model round trip, is nondeterministic, and — unlike every other
  behavior in this repository — **cannot be pinned by a shared fixture with exact expected
  output**. This ADR proposes the one compaction behavior that can be: a deterministic,
  model-free tool-result pruning stage that runs first and often makes the summarizing pass
  unnecessary.
- **Honesty note:** written by a **consumer** of the Go port (`agentic-nexus`), not by
  someone who owns the non-Go ports. Every claim below is a `file:line` in this repository
  that I read; the prior-art claims are `file:line` in a separate TypeScript harness and are
  marked as such. Two claims handed to me while writing this turned out to be **wrong** and
  are corrected in place (see "One correction" and "One real defect"). The weakest part of
  this document is the **default values** — I can argue the *unit* from first principles, but
  the three numbers are inherited from one harness's production experience, not measured on
  toolnexus traffic. See "The defaults question" below.

## Context

### What exists and works

§7F compaction is real and shipped in **all seven ports**, not six — the ADR 0008 era count
is stale, and so is `CLAUDE.md:43` ("Runs all six suites"):

| port | §7F implementation |
|---|---|
| js | `js/src/agents/compaction.ts` |
| python | `python/src/toolnexus/agents/compaction.py` |
| golang | `golang/agents/compaction.go` |
| java | `java/src/main/java/io/github/muthuishere/toolnexus/Compaction.java` |
| csharp | `csharp/src/Toolnexus/Agents/Compaction.cs` |
| elixir | `elixir/lib/toolnexus/agents/compaction.ex` |
| clojure | `clojure/src/toolnexus/agents/compaction.cljc` |

The shape is uniform and good: a pure `messages → messages` helper riding the §8 seam, a
byte-identical no-op under budget (`golang/agents/compaction.go:88-89`), the leading system
prompt preserved verbatim (`:94-97`), and the compacted output
`[system, summary, (flush reminder?), …tail]` (`:142-151`). `SPEC.md:969` — "Absent a
`compactor`, a run is byte-identical to today" — holds.

### The gap

Everything §7F can do to a transcript is "call a model on the head". That has three costs:

1. **A model call to save a model call.** The head is summarized in full even when most of
   its bytes are mechanically compressible — a 400 KB file read, an HTTP body, MCP output.
2. **Nondeterminism, in a repo whose product is determinism.** `CLAUDE.md:59` defines
   "correct" as *the ports produce matching output against shared `examples/` fixtures*.
   `Summarize` is host-supplied and MAY call an LLM (`SPEC.md:955`), so §7F's observable
   result is unpinnable by construction. The conformance suite can assert the *no-op* branch
   and the *split point*; it can never assert the compacted bytes.
3. **It attacks the wrong bytes.** In a tool-calling loop — which `CLAUDE.md:26` calls the
   whole point of the library — tool results are the bulk of a long transcript, and they are
   the part with the most redundancy per byte.

### One correction to a claim I was given

I was told §7F's estimator is at parity across ports. It is not, and the ports **disagree in
writing about whether it is meant to be**:

- `python/src/toolnexus/agents/compaction.py:44-45` claims "identical math and serialization
  to the JS `estimateTokens` **so every port splits at the same point**".
- `clojure/src/toolnexus/agents/compaction.cljc:43-45` states the opposite: "The counts are
  **not promised to be equal** across ports … SPEC §7F pins the formula, not the byte count."

The Clojure docstring is the true one, and the units are already three different things:
Go counts **bytes** (`golang/agents/compaction.go:39-48`), Elixir counts **bytes**
(`elixir/lib/toolnexus/agents/compaction.ex:53`, `byte_size`), JS and Java count **UTF-16
code units** (`js/src/agents/compaction.ts:47`, `java/…/Compaction.java:42`), Python counts
**code points** (`len`). For ASCII these coincide; for a transcript containing an emoji or
CJK they do not, so the same transcript can split at different points in different ports
today. That is not this ADR's defect to fix, but it is exactly why the *new* stage must not
be specified in the estimator's unit.

## Decision

Add an **optional, composable, model-free pruning stage** to §7F that runs **before**
summarization and rewrites only over-budget **tool results**, keeping a head and a tail with
an explicit marker in between. It makes no model call, no network call, and no nondeterministic
choice: given the same input messages and the same three numbers, every port emits the same
bytes.

Prior art, verified in a separate TypeScript harness (`~/Downloads/deepseek-harness-master`):
`packages/compaction/compaction-tool-result-pruner/src/index.ts:83-122` (`pruneContent`),
config at `src/config.ts:7` and types at `src/types.ts:4-11`.

Rules that make this cheap and safe:

1. **Unset ⇒ byte-identical to today.** The stage is off unless configured, and with it off a
   compactor produces the bytes it produces today. Same guarantee `SPEC.md:969` already gives
   for an absent compactor, and the first property the conformance suite asserts — before any
   pruning fixture, a fixture proving nothing moved.
2. **Composition by optional presence, not a registry.** The summarizing compactor runs the
   pruning pass if one is configured, **re-measures**, and returns a no-op override if the
   transcript is now under budget — never reaching `Summarize`. That is the harness's shape
   (`compaction-basic/src/index.ts:281-312`: prune → `meter.measure` → threshold re-check →
   return `null`), and it is the whole economic point: the expensive pass often never runs.
   No new plugin surface, no ordering config, no registry.
3. **The unit is Unicode code points, and `SPEC.md` must say the word.** This is the
   load-bearing detail. The seven ports' native string types disagree — Go is bytes
   (`[]rune` to get code points), Java/C#/JS/Clojure-on-both-hosts are UTF-16 code units,
   Python is code points, Elixir/Erlang binaries are UTF-8 and `String.slice/2` is
   **grapheme**-based. A spec sentence like "keep the first N characters" is therefore
   **unimplementable identically**: N "characters" of a string containing an emoji is a
   different cut in five of the seven. Worse, a naive UTF-16 slice can land **inside a
   surrogate pair** and emit an unpaired surrogate — not merely different bytes, but invalid
   text on the wire. Pinning code points fixes the cut point in all seven and makes splitting
   a surrogate pair structurally impossible (`index.ts:96` — `Array.from(block.text)` before
   slicing; the harness says so in its own doc comment at `:78-80`, and is honest that
   **grapheme clusters may still split**, which is acceptable and must be stated in the spec
   rather than discovered).
4. **Only `tool` results, only over-budget ones, only the model-visible surface.** A result
   whose text is at or below `thresholdChars` is untouched. The full-fidelity original stays
   in whatever the host logs and in the store — `beforeLLM` replaces the *working* transcript
   for the rest of the run (`SPEC.md:1031-1033`), which is the correct blast radius.
5. **The marker is spec text, not an implementation detail.** It is bytes on the wire and
   bytes in a fixture, so `SPEC.md` pins the literal string, exactly as it pins
   `"[Summary of earlier conversation]\n"` today (`golang/agents/compaction.go:139`).
6. **`SPEC.md` first**, per the prime directive (`CLAUDE.md:49-53`): §7F gains the stage, its
   three options with their defaults, the code-point rule, and the marker literal — then the
   ports, then a fixture.

### Scope: this ADR is deliberately one seam

Three adjacent asks came out of the same investigation. All three are **excluded** here, with
reasons.

**Deferred — tool-pairing-balanced range selection (and one real defect).** I was asked to
check whether §7F can orphan a tool call. For the **openai** dialect it cannot: the tail is
forced to begin at a `user` turn (`golang/agents/compaction.go:99-122`), and an openai tool
result is `{role:"tool", tool_call_id, …}`, so a user-boundary tail can never start with an
orphan. But for the **anthropic** dialect the same rule is not sufficient: tool results are
appended as `messages = append(messages, map[string]any{"role": "user", "content": blocks})`
(`golang/client.go:1256`) where `blocks` are `tool_result` items carrying `tool_use_id`
(`:1227-1230`). A tail that begins at *that* user message is a `tool_result` orphaned from
the `tool_use` that the summarizer just ate. `SPEC.md:962-965` states tool-pair safety as an
invariant ports MUST hold, and the user-boundary implementation of it is dialect-blind. This
is a genuine defect and it is **not this ADR's** — it needs its own ADR, and it is entangled
with `add-canonical-transcript` (below), which changes what "a tool result" even is. Bundling
it here would make a deterministic, testable stage wait on a dialect question.

**Deferred — a shadow-price / accounting event for pruned tokens.** The harness emits a
`compaction/prune` event pricing each shadowed node through a token meter so pure consumers
can subtract it without per-node state (`compaction-tool-result-pruner/src/index.ts:127-135`).
Attractive, and I want it eventually — but it is a new §8 event type in a seven-port parity
contract, and toolnexus has no equivalent of that meter. The pruning stage is useful and
fully testable without it. Ask for it when someone actually needs the number.

**Deferred — pruning anything other than tool results.** Assistant text, system messages,
user turns. Tool results are the safe case: they are machine output, the host still has the
original, and truncating one degrades an agent far less than truncating what the model or the
user said. Widening the blast radius is a separate argument, on separate evidence.

### The defaults question, stated honestly

Defaults are part of the byte-parity contract: if `thresholdChars` differs by port, identical
input produces different bytes, so they must be pinned in `SPEC.md` and not left to each port.
Which makes **picking them wrong the main risk in this proposal** — a wrong default is not a
bug in one port, it is a spec change later.

Recommended, inherited from the harness (`src/config.ts` / `src/types.ts:4-11`):
`thresholdChars = 8192`, `headChars = 4096`, `tailChars = 1024`, marker
`"\n\n[... tool result middle pruned ...]\n\n"`. Rationale: a pruned result is ~5 KB where the
original could be megabytes; the head dominates because tool output is front-loaded
(file heads, HTTP status + headers, MCP preamble) while the tail exists to catch a trailing
error or total; and at §7F's own `ceil(chars/4)` heuristic the threshold is roughly 2 K tokens,
which is a sane "this one result is now a meaningful fraction of the window" line.

What would change my mind, concretely: a measurement over real transcripts of (a) the
distribution of tool-result sizes — if the mass sits under 8192 the stage rarely fires and
the threshold should drop; and (b) how often the answer the agent needed was in the middle
that got cut, which is the failure mode this design cannot detect for itself. I have not run
either. Anyone who has should overrule the numbers before they land in `SPEC.md`.

### Overlap with in-flight work — `add-canonical-transcript`

This proposal **overlaps and should be sequenced behind it**. That change makes the loop keep
one provider-neutral message representation and render it at call time
(`openspec/changes/add-canonical-transcript/proposal.md`), and it explicitly names §7F as one
of the three capabilities that "already assume messages are portable and are wrong for the
same reason". A pruner written today would need two shape-detectors — openai's
`{role:"tool"}` and anthropic's `user`-message `tool_result` blocks (`golang/client.go:1227`,
`:1256`) — and both would be rewritten by that change. Specifying "an over-budget tool result"
against a canonical shape is a much smaller spec sentence and a much smaller fixture. If this
ADR is accepted, its OpenSpec change should depend on that one.

## Consequences

- **The per-port change is small and mechanical**, which is the point: slice at code-point
  boundaries, splice in a fixed marker, re-measure. No new control flow, no I/O, no async.
- **The bill is seven ports, and Clojure is the awkward one.** ADR 0009 constrains the port
  to a **single `.cljc` source tree on `clojure.core` alone**, running on JVM Clojure *and*
  three Go-hosted dialects, and the existing compaction file already documents the cost of
  that: `compaction.cljc:48-49` uses `(quot (+ n 3) 4)` because "`Math/ceil` is Java interop
  and would end the cljgo half of this port". Code-point slicing needs exactly that kind of
  host-specific call — JVM `String.codePointCount`/`offsetByCodePoints`, JS `Array.from` —
  and one `.cljc` source must produce identical bytes on four hosts without reaching for
  either. Someone must find the `clojure.core`-only formulation before this is agreed, not
  during implementation. **Elixir is the second-awkward** for the opposite reason: its natural
  `String.slice/2` is grapheme-based, so the idiomatic call is the *wrong* one here and the
  implementation must go through code points deliberately.
- **This is the only compaction behavior that can be conformance-tested exactly.** A fixture
  of input messages plus expected output bytes, run in seven languages — the format
  `conformance/` and `examples/` already exist for. No other part of §7F admits one.
- **It is prefix-cache friendly and replay-stable.** Same input ⇒ same bytes ⇒ the prefix a
  provider cached is still the prefix sent, and a replayed run compacts the same way.
- **It widens §7F's option surface by three numbers and a marker string**, all optional, all
  no-ops when unset. Nothing existing changes.
- **If rejected:** §7F should say plainly that compaction is model-dependent by design and
  that its output is therefore **outside the conformance contract** — that the parity promise
  covers the no-op branch and the split point only. That is a defensible position. What is
  not defensible is the current silence, where a repo whose prime directive is byte parity
  ships one canonical feature whose bytes nothing checks and whose token estimator two ports
  document contradictory promises about.

## Alternatives considered

- **Tell hosts to write it in `Summarize`.** A host can already truncate tool results inside
  its own `Summarize` before calling a model. **Rejected as the general answer** for the same
  reason ADR 0008 rejected consumer-side compaction: it is per-host, unpinnable, and every
  host writes the surrogate-pair bug independently. Determinism is only worth anything if it
  is *the library's* determinism.
- **A `countTokens`-based pruner instead of a character-based one.** Superficially neater —
  reuse §7F's existing budget unit. **Rejected**: that unit is not at parity across ports
  today (see the correction above), so building the one exactly-testable behavior on top of
  the one already-drifting number would inherit the drift and destroy the fixture.
- **Prune inside the client loop as tool results are appended, not in `beforeLLM`.** Cheaper
  (no re-scan) and it would shrink the stored transcript too. **Rejected**: it changes
  `RunResult.messages` and what the `ConversationStore` persists, i.e. it destroys data the
  host did not ask to lose, and it would fire even on runs that never approach the budget.
  §7F's blast radius — the working transcript for the rest of the run — is the right one.
- **A general "transcript rewriter" plugin registry.** More powerful, and it would absorb the
  deferred asks above. **Rejected**: a registry is a surface with ordering semantics to
  specify in seven languages, for one known stage. Optional presence costs a return trip if a
  second stage ever appears, and keeps this one obviously safe.
- **Do nothing.** Defensible if §7F's model call is considered cheap relative to the tokens
  it saves. But the harness's own composition
  (`compaction-basic/src/index.ts:281-312`, prune → re-measure → possibly return without
  summarizing) exists precisely because it often is not — the model-free pass frequently ends
  the compaction outright.

## Next gate

Per the prime directive, this ADR is the discussion, not the change. If accepted, the work is
an **OpenSpec change**, sequenced behind `add-canonical-transcript`, whose spec deltas pin:
the three options and their defaults, the **code-point** unit and the explicit non-guarantee
about grapheme clusters, the literal marker string, the compose-and-re-measure rule, and the
unset-is-byte-identical guarantee — plus a shared fixture under `examples/` with exact
expected bytes, including a non-BMP case whose cut lands mid-surrogate-pair, run against all
**seven** ports.
