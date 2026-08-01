# Spikes — what was asked, and what was measured

Fourteen spikes. Each answers ONE question about whether part of the toolnexus
contract works in portable Clojure **identically on Clojure (JVM) and cljgo**.
`BRIEF.md` is the binding constraint set; read it before adding one.

**Every spike here has a README stating its question, its verdict, the exact
numbers, and what it did NOT cover.** A spike reporting success it did not
measure is worse than no spike.

## Current — built to BRIEF.md, all three modes diffed

Each has a `run-both.sh` that runs JVM, cljgo AOT and cljgo interpreted, and
**diffs the outputs byte for byte**. Re-run 2026-08-01 on koine 0.7.1 and
cljgo v0.8.4+ (`56da5a3`): **all ten PASS, all byte-identical.**

| spike | what | bytes |
|---|---|---|
| S15 | §0.2–0.7 vertical slice | 2746 |
| S16 | client loop, native + http, parallel calls | 405 |
| S17 | **composition** — A2A → loop → MCP-over-HTTP → stdio child + skill + native | 641 |
| S18 | MCP streamable-HTTP | 2275 |
| S19 | agent skills, byte-exact output | 4059 |
| S20 | A2A outbound | 2506 |
| S21 | `serve` — §7B + §7C | 2939 |
| S22 | builtins | 11085 |
| S23 | suspension (§10) | 3259 |
| S24 | the test harness and its gate | 920 |

S24's payload grew 887 → 920 bytes on this re-run: the extra 33 are cljgo #170
working (`cljgo-version` now carries the commit). **Compare the three modes
against each other, never against yesterday's number.**

## Superseded — kept as the record, not as coverage

These four predate koine. They have no `run-both.sh` and answered their
questions on one host only; their READMEs say so and name what replaced them.
**Do not extend them** — a new question belongs in a spike built to `BRIEF.md`.

| spike | question | replaced by |
|---|---|---|
| S01 | can one `.cljc` load on both hosts at all? | S15/S16/S17 + the port |
| S05 | will two hosts' JSON encoders agree byte for byte? | `koine.json` (sorted keys) |
| S07 | can we talk to a child over stdio? | `koine.process`, S15/S17/S18 |

S03 is the exception: still **runnable and still useful**.

| spike | question | verdict |
|---|---|---|
| S03 | is there any dual-host Clojure library, or must the seam be written? | **11 of 11 popular libraries carry Java interop — zero are usable on cljgo.** koine 0.7.1: zero `(:import …)`, zero `java.*` refs. |

S03 is the empirical answer to "why not just use a library?", and the reason the
port's rule is *koine is the only third-party dependency*. Run `scan.sh` before
adding any dependency.
