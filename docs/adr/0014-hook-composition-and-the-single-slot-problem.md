# ADR 0014 — Hook composition: one slot, two spec-canonical uses

- **Status:** **Proposed** (2026-08-15) — discussion document, no code in this repository has
  been changed.
- **Date:** 2026-08-15
- **Driver:** `SPEC.md:943` says "Compaction is the canonical use of the §8 `beforeLLM` hook".
  `SPEC.md:1174` says the route-gate — "an expensive-tier route is gated" — is *also* a
  `beforeLLM` hook. Both are in this repository's own spec, and `Hooks.BeforeLLM` is **one
  field** (`golang/client.go:182`). A host that wants both must fold them into a single
  function value by hand, and nothing in any port composes, orders, or checks that fold.
- **Honesty note:** written by a consumer of the Go port (`agentic-nexus`), from `file:line`
  in this repository. The weakest estimate here is the claim that a chain helper is "cheap in
  seven ports" — the helper's *code* is small everywhere, but its **semantics** are new
  observable behavior that has to be pinned identically in seven languages whose hook
  signatures already differ (`ctx` in `golang`, a bare 1-arity map fn in `clojure`
  (`clojure/src/toolnexus/client.cljc:198`), promise-capable in `js`
  (`js/src/client.ts:130`)). That is the real bill, and it is not small.

## Context

### What exists and works

The §8 seam is complete and, since ADR 0008, reachable from the §7D agent runtime at both
levels. `Hooks` is four optional function fields — `BeforeLLM`, `AfterLLM`, `BeforeTool`,
`AfterTool` (`golang/client.go:179-192`) — with clean, already-pinned return semantics that
matter for everything below:

- `BeforeLLM` is **not** side-effect only. It returns `*LLMOverride`, where "a nil field
  leaves that value unchanged; a non-nil (even empty) slice replaces it"
  (`golang/client.go:227-232`), and the replacement becomes the working transcript for the
  rest of the run (`SPEC.md:940`). It is a `messages → messages` transform, i.e. a **pipeline
  stage**. Chaining is therefore semantically well-defined, which is the precondition for
  this whole ADR.
- `BeforeTool` returning a non-nil `Result` **short-circuits** the tool — the real tool never
  runs (`golang/client.go:185-188`). That is a deny/veto, and denies are the one thing that
  must not be quietly widened by a later stage.
- "any hook returning an error aborts the run with that error" (`golang/client.go:176-177`).

Resolution across the two levels is `def-over-runtime, replace never merge`, per field
independently. It is one conditional, spelled the same way in **seven** ports:
`golang/agents/runtime.go:1131-1138`, `js/src/agents/runtime.ts:768`,
`python/src/toolnexus/agents/runtime.py:776`,
`java/.../agents/AgentRuntime.java:549`, `csharp/src/Toolnexus/Agents/AgentRuntime.cs:504`,
`elixir/lib/toolnexus/agents/handle.ex:155`,
`clojure/src/toolnexus/agents/runtime.cljc:759`. (ADR 0008 said six; `clojure/` joined after,
and it carries the seam correctly. Its `elixir` deferral is closed too: the wrong-arity hook
that used to vanish now **raises**, `elixir/lib/toolnexus/client.ex:989-996`.)

### The gap — and a correction to how it is usually described

The framing "the def-level hook silently deletes the runtime-level one" is **wrong**, and the
ADR is better for saying so. That drop is not a bug, not silent-by-accident, and not
undocumented: it is a stated requirement (`openspec/specs/agent-runtime/spec.md:296-303`), a
spec rule with its reason attached — "composing two transcript rewrites has no defined order"
(`SPEC.md:826`) — and it is conformance-pinned in every port by fixture scenario H3
(`examples/agent-hooks/fixture.json`, "Merging is non-conformant"). Every port's field doc
says it in prose (`golang/agents/runtime.go:147`, `js/src/agents/runtime.ts:94`).

The real gap is one level down, and the library cannot see it:

**There is exactly one slot per hook kind, and `SPEC.md` names at least two independent
tenants for `beforeLLM` alone** — compaction (`SPEC.md:943`) and the route-gate
(`SPEC.md:1174`). A third is implied: `add-governed-execution-layer` was archived
**never-built** with the reason "superseded in practice: §7D budgets + **hooks** + §10
approval suspension cover the need"
(`openspec/changes/archive/_never-built/README.md`). Policy denial was delegated to
`beforeTool` — the same single slot a cache or an arg-rewriter would want.

So a host wiring compaction **and** a route-gate **and** tracing writes one closure that does
all three. Two independent subsystems in that host each assigning `opts.Hooks.BeforeLLM` is a
plain struct-field assignment: the second wins, the first never ran, and **toolnexus never
sees the first value at all**. No warning in this library can reach that failure. The
composition it needs does not exist here, so it gets re-derived, differently, in every host —
which is the drift this repository exists to prevent, one layer out.

The DeepSeek harness is the honest counter-evidence that a single non-composable slot does not
survive contact with real use: its equivalent is a waterfall
(`'agent/pre-step'(payload, next)` → `{kind:'reject'} | {kind:'enter', messages}`,
`packages/core/agent/src/runtime-types.ts:231` and `:53-55`), registration order **is**
composition order, and — counted in their tree, not taken on report — **fourteen** independent
packages register that one hook (compaction-basic, agent-instructions, time-context,
tmux-context, tool-skill, plan-mode, goal-round-driver, repeat-tool-reminder,
session-checkpoint-policy, subagent-in-process-driver, tool-cordis, and the claude-code and
codex hook bridges). Its tool layer goes further: extensible listeners may allow/deny/ask and
are reorderable, but the owner's policy is a registered guard where "later waterfall listeners
cannot turn a guard denial back into permission" (`packages/core/tools/README.md:25`;
`ToolRuntime.prepareExecution`, `packages/core/tools/src/index.ts:1463`). That asymmetry
— pipeline for rewrites, first-deny-wins for vetoes — is the part worth taking. The
middleware/`next()` machinery is not: it makes ordering an implicit property of registration
order, which is exactly the kind of thing a byte-parity spec should refuse.

## Decision

Ship a **spec-defined pure composition helper**, one per port, and change nothing about how
the runtime resolves or forwards hooks.

```
chainHooks(h1, h2, …) -> Hooks        // golang: tn.ChainHooks(...*Hooks) *Hooks
```

Rules that make it cheap and safe:

1. **Order is argument order, first to last.** Not registration order, not a priority number,
   not `prepend:true`. Order is observable behavior, so it is written at the call site where a
   reader can see it. This is the same discipline `SPEC.md:1212` already applies to the
   request pipeline ("base body → `BeforeLLM` hook → `RequestParams` merge → `BodyTransform`
   → marshal → wire").
2. **`beforeLLM` / `afterTool` are pipelines.** Each stage sees the current
   `messages`/`tools` (resp. `result`); a non-nil return replaces them **for the next stage**;
   the composite's return is the accumulated replacement, or *nothing* if no stage returned
   anything. Nil-in-nil-out is what preserves byte-identity.
3. **`beforeTool` is first-deny-wins.** The first stage returning a non-nil `Result`
   short-circuits: later stages **do not run and cannot revoke it**. `Args` rewrites feed
   forward. This is the one place the helper is not a plain fold, and it is deliberate — a
   composed permission check must not be widened by a hook that happens to come after it.
4. **`afterLLM` runs all stages in order** (observers). The first error aborts, matching
   `golang/client.go:176`; remaining stages do not run.
5. **Empty ⇒ nothing.** `chain()` of zero stages, or of all-nil hooks, returns the same as no
   hooks at all. The conformance suite asserts this first, exactly as ADR 0008 did.
6. **Zero runtime change.** `Options`/`AgentDef` keep one `hooks` field, `def-over-merge`
   stays, H3 stays green. A caller that wants both levels writes
   `chainHooks(runtimeHooks, defHooks)` itself — explicit, at the call site, with the order
   visible.
7. **`SPEC.md` first.** §8 gains the helper and its five rules; §7F gains one sentence saying
   a compactor composes with other `beforeLLM` tenants via `chain`, and that a `hooks` value
   set at two levels does not compose implicitly.

### Scope: this ADR is deliberately one seam

**Deferred — an ordered list / registration API on the runtime (option (a)).** Replacing the
single field with `[]*Hooks`, or adding `addHook(...)`, makes order a property of the
runtime's own state, forces a def-vs-runtime *interleaving* rule (does a def's stage run
before or after the runtime's?), and re-opens the §10 rewind interaction ADR 0008's spike
already found (`SPEC.md:840-846`: a turn that compacts then suspends rewinds and re-compacts —
with N stages, "re-run all of them, in order" needs stating). It also breaks H3. If `chain`
turns out to be what every consumer writes anyway, promoting it into the options surface is a
cheap follow-up ADR with real usage behind it. Doing it first is guessing.

**Deferred — tool-permission tiers and a non-revocable owner guard.** The DeepSeek
allow/deny/ask tier plus a guard registration slot is a governance feature, not a composition
feature. toolnexus has **no** permission hook today — `beforeTool`'s short-circuit is the only
gate, and `add-governed-execution-layer` was consciously archived rather than built. Rule 3
above gives composed denies the safety property without introducing the concept; a tiered
permission model deserves its own ADR that revisits that archived proposal on its merits.

**Deferred — a loud diagnostic when a def-level hook shadows a runtime-level one (option
(c)).** Precedent exists and is genuinely one line (`golang/client.go:758`:
`log.Printf("[toolnexus] …")`; `SPEC.md:288`, `SPEC.md:367` are spec'd warnings whose text is
not pinned, so parity is cheap). **Rejected as the primary answer** for a reason that only
shows up when you check the spec: def-over-runtime is the *designed* path for per-agent
compaction budgets (`openspec/specs/agent-runtime/spec.md:302`), so a runtime-wide tracer plus
a per-agent compactor — the exact configuration a healthy consumer wants — would warn on every
agent, forever, about behavior the spec calls correct. And it cannot see the drop that
actually bites, which is one host subsystem overwriting another's closure before toolnexus is
ever called. A warning that fires on correct usage and misses the real fault is worse than
silence. It could be revisited as a *one-shot* note in §7D docs rather than a log line.

## Consequences

- **Nothing existing changes.** No options field, no resolution change, no fixture output
  moves. `chain` is additive and opt-in; unset stays byte-identical, which is the property the
  conformance suite pins first.
- **The bill is parity of *semantics*, not of code.** Seven ports × four hook kinds × the fold
  rules, plus a shared `examples/hook-chain/fixture.json` pinning: order of side effects, that
  stage 2 sees stage 1's rewritten messages, that a `beforeTool` deny stops the chain, and
  that an all-nil chain is byte-identical. That fixture *is* the decision; the code is small.
- **`elixir/` and `clojure/` are the ports to think about before agreeing.** Both take a hook
  map of plain function values, so `chain` returns a map of closures — idiomatic enough. But
  `elixir` now *raises* on wrong arity (`elixir/lib/toolnexus/client.ex:989-996`), so a
  composed hook must preserve arity exactly or the chain converts a silent no-op into a
  crash; and `clojure` is `.cljc` dual-host (ADR 0009), so the helper has to be sync-safe on
  the JVM and correct under the JS host's async client, where `js` hooks may return promises
  (`js/src/client.ts:130`) and the fold must await each stage.
- **§8 gains its first host-facing utility.** Until now §8 is pure contract. A helper is a
  small widening of what "parity" covers — an argument for keeping its rules to five.
- **The library stops implying that two spec-canonical uses of one hook can coexist without
  the host inventing the answer.** Today §7F and §11's route-gate both claim `beforeLLM` and
  neither mentions the other.
- **If rejected:** `SPEC.md` §8 should say plainly that **each hook kind holds exactly one
  callback, that composing multiple concerns onto one hook is entirely the host's
  responsibility, and that toolnexus defines no order for it** — and §7F and the §11
  route-gate paragraph should each cross-reference the other as competing tenants of
  `beforeLLM`. The present state, where two sections independently call the same single slot
  "the canonical use", reads as though the library had thought about their coexistence.

## Alternatives considered

- **(a) An ordered list of hooks on the runtime.** The complete fix, and what the DeepSeek
  harness's nine consumers prove is eventually needed. **Rejected for now**: it changes
  resolution semantics, breaks a shipped conformance fixture, needs a def-vs-runtime
  interleaving rule and a §10 rewind statement, and buys nothing `chain(a, b)` doesn't until
  hooks are registered by parties who cannot see each other's call sites. Deferred in writing,
  not dismissed.
- **(c) Loud diagnostic only.** Cheapest, precedented, and genuinely tempting — see the
  deferral above for why it fires on correct usage and misses the real failure.
- **A `Compactor` field alongside `Hooks`, so compaction stops competing for the slot.**
  Rejected on ADR 0008's own reasoning: §7F defines compaction *as a use of* `beforeLLM`, so a
  dedicated field duplicates §8 rather than reaching it — and it would fix exactly one of the
  slot's tenants while the route-gate keeps colliding with tracing.
- **A `next()`-style middleware onion (the DeepSeek shape).** Rejected: `next()` makes order
  implicit in registration and gives each stage the power to skip the rest, which is a control
  construct, not a fold. Toolnexus's hooks already return values; a fold over return values is
  the smaller thing that fits what is there.
- **Do nothing.** Defensible if hosts are expected to own composition — but then the spec must
  say so out loud (see "If rejected"), because right now it says the opposite twice.

## Next gate

Per the prime directive this ADR is the discussion, not the change. If accepted, the work is
an **OpenSpec change** whose deltas pin the five fold rules in §8, the one-sentence §7F and
§11 cross-references, and a shared `examples/hook-chain/fixture.json` — then seven ports
against that fixture. If **rejected**, the work is a much smaller docs change: the three
sentences named in "If rejected", which cost nothing and remove the false impression.
