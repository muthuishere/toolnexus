# Spikes

Runnable prototypes that back the ADRs. A spike is **evidence**, not a test suite and not
shipped code — none of it lives in any port, and every proposed primitive here exists only
inside these files.

```bash
cd js && npm install && npm run build   # spikes run against the built js/dist
node docs/spikes/run-all.mjs            # all hermetic spikes; non-zero exit on any failure
node docs/spikes/run-all.mjs --live     # also the ones needing a real model + API key
```

## Two rules, both learned the hard way

**1. A spike must be runnable by someone other than its author.** Resolve the bundle
relatively — `import { DIST } from "./_harness.mjs"` — never an absolute path. Every spike
written before 2026-09-14 hardcoded one author's home directory, so the evidence four ADRs
cited could not be reproduced by anybody.

**2. A spike must be able to fail.** Use `check(label, cond)` from `_harness.mjs`, and end with
`report(title)`. A file that only `console.log`s its findings exits 0 whether or not the finding
held: `0006-graph-stress.mjs` printed `stopped LOUDLY: false` for a month — a real defect in the
spike's own setup, which ADR 0017 had already documented as corrected and which nobody saw,
because nothing was watching the exit code.

Corollaries worth keeping:

- **Assert the negative too.** An invariant that has never been observed failing is decoration.
  `0009` feeds each invariant a violating trace (4/4 caught); `0016` T6 does the same.
- **Name what you did NOT assert, and why** — `notApplicable(label, why)`. §7D deliberately
  leaves scheduling unobservable, so asserting an interleaving would pin what the spec refuses
  to pin. An omission that stops being mentioned is indistinguishable from one that was
  forgotten.
- **Keep the printed shape stable.** ADRs quote spike output verbatim; `check` prints the same
  `  label: true` form the earliest spikes wrote by hand.

## `_harness.mjs`

`DIST` · `section` · `check` · `note` · `notApplicable` · `throws` · `report` · `virtualClock`
· `drain`.

`virtualClock` drives the §7D `clock` seam so schedule tests are deterministic rather than sleep
races. Its `advance()` yields real macrotask turns between timers, because a scheduled fire wakes
a handle whose turn awaits a mocked `fetch` — microtask ticks alone are not enough.

## The files

| spike | ADR | what it establishes |
|---|---|---|
| `0004` | 0017 | the proposal's static graph, with a conditional back-edge, on shipped verbs |
| `0005` | 0017 | dynamic fan-out + join; §10 suspension survives a host-driven node |
| `0006` | 0017/0020 | stress: 60-wide, 200-deep, failure-as-result, budget stops loudly |
| `0007` | 0017 | the corrected budget placement (`AgentDef`, not `RuntimeOptions`) |
| `0009` | 0016 | `harness` as an option; loop invariants over the trace, with negatives |
| `0010` | 0018 | why the completion gate must be a harness property: delegation |
| `0011` | 0018 | live-model scenarios (needs a key; skipped by default) |
| `0012` | 0020 | cron as a `Schedule` seam over the heartbeat; fixture table; misfire |
| `0013` | 0020 | `sequence`/`parallel`/`until`; the grammar cannot express an unbounded cycle |
| `0014` | 0020 | an agent spec as data, resolved against host-supplied bindings |
| `0015` | 0020 | dynamic construction, and **capability narrows, never widens** |
| `0016` | 0020 | all four layers at once: depth/width, adversarial documents, hygiene |
