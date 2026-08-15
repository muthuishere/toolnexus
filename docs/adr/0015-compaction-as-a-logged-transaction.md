# ADR 0015 — Compaction as a logged transaction: **rejected**; make it observable and honestly scoped instead

- **Status:** **Proposed** (2026-08-15) — recommending the *small* half of what this ADR set out
  to argue. The proposal that opened this investigation was: make the transcript an append-only
  log and turn compaction into a bracketed, crash-detectable transaction over it. Verification
  killed the crash argument outright and left two real, much cheaper defects. Both fixes are spec
  sentences plus one metric event; neither touches the transcript representation.
- **Date:** 2026-08-15
- **Driver:** `SPEC.md §7F` (line 936) makes compaction a pure `messages → messages` helper whose
  output *replaces* the working transcript, and that array "flows into `RunResult.messages` and
  the `ConversationStore`" (`SPEC.md:940-943`). `§10`'s durable path (line 1252) promises resume
  "across restarts" (`SPEC.md:1265-1268`) from a persisted transcript. The two sections meet on
  one array, and `SPEC.md:948-949` describes their interaction in a single sentence that is true
  of only one of the two durable paths this library ships.
- **Honesty note:** written by a consumer of the Go port (`agentic-nexus`), not by a port owner.
  Every claim is a `file:line` verified in this repository; no code here was changed. **My weakest
  estimate is the non-Go blast radius of decision rule 1** — I read `golang/` end to end and only
  sampled `js/`, `python/` and `elixir/`, so "a `compaction` metric event is additive everywhere"
  is a shape argument, not a measurement, in six of seven ports. The `elixir` hook-arity trap
  recorded at `openspec/changes/archive/2026-07-26-expose-agent-runtime-hooks/design.md:121-124`
  is exactly the kind of thing that shape arguments miss.

## Context

### What exists and works

Compaction ships in **all seven ports** — `clojure/src/toolnexus/agents/compaction.cljc`,
`csharp/src/Toolnexus/Agents/Compaction.cs`, `elixir/lib/toolnexus/agents/compaction.ex`,
`golang/agents/compaction.go`, `java/src/main/java/io/github/muthuishere/toolnexus/Compaction.java`,
`js/src/agents/compaction.ts`, `python/src/toolnexus/agents/compaction.py` — and it is not in
question. The Go implementation is 168 lines of pure function
(`golang/agents/compaction.go:76-154`): under budget it returns `nil, nil` (line 89), i.e. the
byte-identical no-op `§7F` promises at `SPEC.md:969`.

The `§10` × `§7F` collision was found once already and handled. ADR 0008's spike measured it
(`.../expose-agent-runtime-hooks/design.md:113-118`: "measured: `pre=81 post=81`"), the test
survives at `golang/agents/hooks_test.go:261-289`, and it is now pinned as a conformance scenario
at `openspec/specs/agent-runtime/spec.md:233-239`. The mechanism is not a rewind at all: the
agent runtime's `pending` branch simply **never calls `Save`** (`golang/agents/runtime.go:1183-1199`),
while the `done` and `incomplete` branches do (`:1206`, `:1214`). The store keeps the pre-turn
value because nothing wrote over it. Correct, and cheap.

### The gap — three findings, only two of which survive scrutiny

**Finding 1 — `SPEC.md:948` states an agent-runtime rule as if it were universal.**
It reads: "A compacted turn that then suspends is rewound with the rest of the turn: the stored
transcript returns to its full pre-turn state and the resumed replay compacts again." That is
true under `§7D`. It is **false on the bare-client durable path**: `AskStream` saves
unconditionally after a successful `RunWithHistory` (`golang/client.go:644-650`) — there is no
status check — so a conversation that compacted and *then* halted `pending` is persisted
**compacted**, permanently. The spec already knows these two paths diverge; the very next rule
over is scoped explicitly ("The §10 append rule is scoped to the bare client",
`openspec/specs/agent-runtime/spec.md:41-47`). `§7F`'s sentence just never got the same treatment.
Neither behavior is wrong. The documentation of them is.

**Finding 2 — compaction is irreversible and, worse, invisible.**
`ConversationStore.save` is whole-transcript last-write-wins (`golang/client.go:1977-1984`), so
the summarized head is gone the moment a compacted turn is stored; nothing in `§8`'s store
contract (`SPEC.md:1098-1101`, exactly two methods) can recover it. Meanwhile `summarize` **may
call an LLM** (`SPEC.md:955`) — a lossy, non-deterministic, occasionally hallucinating step whose
input is destroyed by its own success. And there is **no compaction event on the `§8` metric
channel**: the emitted vocabulary is three events, `llm` / `tool` / `run`
(`golang/client.go:391,397,565,568`; `SPEC.md:1129-1131`), none of which fire on compaction. A
host today cannot answer "did this agent compact, when, and how much did it drop" from anything
the library exposes. That is the defect I would fix first, and it costs nothing structurally.

**Finding 3 — the crash-detectability argument does not apply here, and I should say so plainly.**
The DeepSeek harness brackets compaction with three log-only events and releases the lock last
precisely so "a crash mid-operation [is] a detectable orphaned lock … rather than a
`compaction/end` that falsely claims compaction finished"
(`deepseek-harness-master/docs/subsystems/compaction.md:19`). That is necessary **because its
transcript is the durable substrate**: an append-only session event log, mutated incrementally,
where the summary rides an ordinary `user/message` carrying `surfaceOp:{op:'replace',start,end}`
(`.../compaction.md:11`) and the original events survive underneath, tagged with a
backend-independent checkpoint marker
(`packages/compaction/compaction/src/checkpoint.ts:33-51`). Excellent design — for that substrate.

toolnexus has no such window. Compaction is a pure in-process function that never touches the
store; the store is written **once, whole, at turn end**. There is no state in which the store
holds a half-compacted transcript, so there is nothing for a bracket to detect. Importing the
bracket would add three durable events, a lock, and an orphan-scan to protect against a failure
mode that cannot occur. **One genuine residual survives**: `SPEC.md:1100-1101` specifies `save`
as a bare two-method contract and never requires it to be atomic. A host's file store *can* tear
mid-write, and because the library replaces the whole value, a torn write is the **only**
corruption mode compaction has. That is a host obligation to document, not a library transaction
to build.

### The overlap that decides the cost question

`openspec/changes/add-canonical-transcript/` (in-flight, untracked) is **already** re-plumbing the
message representation in all seven ports. Its Impact names every client file plus "each port's
compaction module" (`proposal.md:71-78`) and states the risk in its own words: "this touches the
single hottest path in the library in seven languages" (`proposal.md:88`). It is doing the *shape*
change (dialect-free messages). An append-only-log change would be the *semantics* change over the
same lines, in the same seven ports, at the same time. Running both at once is how a byte-parity
project loses its parity.

## Decision

**Do not make the transcript append-only, and do not add compaction transaction events.** Adopt
four rules, all of which are spec text plus one additive event, and none of which change a stored
byte when unused.

1. **`§7F` gains one `on_metric` event: `event:"compaction"`**, emitted once per applied
   compaction, carrying at minimum dropped-message count, dropped-token estimate, kept-tail token
   estimate and elapsed ms (idiomatic casing per port, exactly as `SPEC.md:1126-1128` already
   allows — this event is **not** byte-identical across ports, and does **not** join the
   Prometheus set at `SPEC.md:1138-1141`, whose text *is* pinned). Unset `on_metric` ⇒ no
   observable change, the same guarantee `§7F` already gives for an absent compactor
   (`SPEC.md:969`). This is what turns an irreversible operation into an auditable one.
2. **Scope `SPEC.md:948-949` explicitly.** State that the rewind-to-checkpoint rule is the `§7D`
   runtime's (`openspec/specs/agent-runtime/spec.md:233-239`), and state plainly that on the
   bare-client durable path `ask` persists the compacted transcript together with the halted turn,
   so **the pre-compaction head is not recoverable after a durable suspension on that path**. A
   host that needs the original must snapshot it in its own store.
3. **Pin the host's `save` obligation.** `§8`'s `ConversationStore` gains one sentence: `save`
   SHALL be atomic per id — a reader must observe either the previous transcript or the new one,
   never a prefix. This is the whole of the crash-safety story for compaction, and it belongs to
   whoever implements the file/db/redis provider.
4. **Say what a failing `summarize` does.** Today it kills the run: the Go loop returns
   `RunResult{}, err` on a `beforeLLM` error (`golang/client.go:824-826`), so a transient provider
   blip inside the summarizer destroys a turn that was otherwise fine, and `ask` returns before
   `save` (`golang/client.go:645-647`) so at least nothing is persisted. That is defensible —
   proceeding uncompacted would just overflow the window — but it is currently undocumented
   behavior on a step the spec explicitly says may call an LLM. Document it; do not change it.

### Scope: this ADR is deliberately one seam

Everything else the investigation turned up is **excluded**, with reasons.

**Deferred — the append-only transcript with a checkpoint-marked replacement projection.** This is
the interesting idea and it is the wrong year for it. It is a representation change in seven ports
while `add-canonical-transcript` is mid-flight over the identical lines, it makes
`ConversationStore` transcripts grow without bound (the one thing `§7F` exists to prevent, moved
from the model's window to the host's disk), and its payoff — recoverable pre-compaction history —
is available to any host today by snapshotting before `ask`. Revisit only *after*
`add-canonical-transcript` archives, and only if a consumer produces a real incident where a lost
head mattered.

**Deferred — the shadow-price accounting event.** DeepSeek's pruner appends a metering event
immediately adjacent to each replacement carrying the shadowed token count, so a pure replay
consumer subtracts cost with zero per-node state
(`packages/compaction/compaction-tool-result-pruner/src/index.ts:157-161`). That is a good idea
and it belongs with **model-free tool-result pruning — ADR 0013, being written today**. Naming it
here so it is not orphaned: whichever of 0013 and 0015 lands second should own it, and it should
be one event shape covering both, not two. I have not edited 0013.

**Deferred — a closed compaction failure taxonomy.** DeepSeek carries six codes
(`busy|cancelled|changed|summary|commit|persistence`,
`packages/compaction/compaction/src/index.ts:28-34`) because it has a lock, concurrent manual and
automatic entry points, and a commit step. toolnexus has none of those: compaction is a synchronous
pure call inside one turn, so its only failure is `summary`. A six-value enum for one reachable
value is ceremony. Decision rule 4 covers the real case in a sentence.

## Consequences

- **The bill is small and honest.** One additive metric event per port plus four spec sentences.
  Rule 1 is the only code change; rules 2–4 are documentation of behavior that already exists and
  is already tested (`golang/agents/hooks_test.go:261-289`).
- **Seven ports, not six.** `clojure/` has a compaction module too, so the parity scope for rule 1
  is all seven. ADR 0008's "five ports" reading is stale for this surface.
- **`elixir/` is again the port to think about before agreeing.** Not for the representation — that
  is gone from this proposal — but because its hook plumbing is arity-guarded and fails silently:
  `client.ex:828` guards `is_function(f, 1)` and a wrong-arity hook simply never runs
  (`.../expose-agent-runtime-hooks/design.md:121-124`). A new emission point inside that path
  deserves a test that asserts the event fires, not just that nothing breaks.
- **Nothing breaks.** With `on_metric` unset the change is unobservable, which is the claim the
  conformance suite should pin first — the same method ADR 0008's spike used
  (`.../design.md:108-112`, golden capture from a `main` worktree).
- **A real limitation becomes documented rather than latent.** Today a host can lose a
  conversation's head to a summarizer that may hallucinate, on a path the spec describes as
  rewinding, with no event recording that it happened. After rule 2 that is a stated property a
  host can design around.
- **If rejected:** then `SPEC.md §7F` must at minimum carry the sentence rule 2 asks for — that
  compaction is an **irreversible** replacement of the persisted transcript on the bare-client
  durable path, that the pre-compaction head is unrecoverable through the `ConversationStore`
  contract, and that no event is emitted when it happens. A consumer discovering that after a
  crash, from a store that says one thing and a spec that says another, is the outcome worth
  spending four sentences to avoid.

## Alternatives considered

- **Full append-only transcript + `compaction/start|summary|end` bracket (the original proposal).**
  **Rejected.** The crash it protects against cannot happen here (Finding 3), and its cost lands on
  the exact seven-port hot path `add-canonical-transcript` is currently rewriting
  (`proposal.md:71-78, 88`). Right design, wrong substrate, wrong quarter.
- **The bracket alone, without any representation change** — the "cheap 20% of the safety"
  candidate this ADR was asked to weigh explicitly. **Rejected**, and this is the finding I most
  expected to go the other way. The bracket's value is entirely the orphaned-`start` detection,
  which requires the markers to be *durable*; toolnexus's only durable surface is the transcript
  itself, so writing them means writing non-message entries into a transcript that gets forwarded
  to providers — precisely the coupling `§7F` avoids by being a pure `messages → messages` helper
  (`SPEC.md:940-943`). It is not 90% of the safety for 10% of the bill; it is ~0% of the safety
  (no torn state exists to detect) for a real bill.
- **Make the agent runtime and the bare client behave identically on compaction + pending.**
  Tempting symmetry. **Rejected**: they differ for a reason recorded at
  `SPEC.md:780-783` — the runtime must not persist a placeholder or the resumed parent skips
  re-invoking `task`. Changing either side to match is a behavior change across ports to buy
  tidiness. Scoping the sentence costs nothing.
- **A `PreCompact`/abort hook so a host can snapshot before the drop.** Adjacent, and already
  deferred by ADR 0008 pending live evidence. Nothing found here strengthens the case; rule 1's
  event gives a host the *fact* of a compaction, which is the missing piece, and a host wanting the
  *content* can read the store before `ask`.
- **Do nothing.** Genuinely defensible for findings 1 and 3 — the code is correct, and a careful
  reader of `openspec/specs/agent-runtime/spec.md:41-47` can infer the scoping. Not defensible for
  finding 2: a spec that says "rewound" describing a path that persists, over an operation that
  emits no event and destroys its own input, is a trap that only springs after a crash.

## Next gate

Per the prime directive this ADR is the discussion, not the change. If accepted, the work is a
small **OpenSpec change** — `add-compaction-observability` — sequenced **after**
`add-canonical-transcript` archives, whose deltas pin: the `compaction` metric event and its
fields; the unset-`on_metric` byte-identity guarantee measured by golden capture; the `§7F`
scoping sentence for `§10`; the `save` atomicity obligation on `ConversationStore`; and the
summarize-failure statement. Fixture: an agent that compacts twice in one run and emits two
`compaction` events, byte-identical to today with the sink unset.

Coordination note: ADR 0013 (model-free tool-result pruning, same day) owns the shadow-price
accounting event. If 0013 lands first, this change consumes its event shape rather than defining a
second one.
