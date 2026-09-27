# Parity matrix — add-judge-adapters (2026-09-28)

Every requirement and scenario in the spec delta and SPEC §8B, checked in each of the seven ports.
**F** = pinned by a shared fixture that every port asserts (`examples/judge/adapters/`).
**T** = pinned by a port-local test. **C** = holds by construction, with no direct test.
**fixed** = the cell was red before this sweep (design.md O6–O8).

| Scenario | js | python | golang | java | csharp | elixir | clojure |
|---|---|---|---|---|---|---|---|
| Builders → wire inputs (video example) | F | F | F | F | F | F | F |
| context + message sugar | F | F | F | F | F | F | F |
| Duplicate name → error naming key | F | F | F | F | F | F | F |
| State(role, data) / non-object → `data` | F (new) | F (new) | F (new) | F (new) | F (new) | F (new) | F (new) |
| Cut-points exclusive (0.30 / 0.70 uncertain) | F+T | F+T | F+T | F+T | F+T | F+T | F+T |
| Near-uniform choice not sure | F | F | F | F | F | F | F |
| Custom bands | F | F | F | F | F | F | F |
| value() / band / sure / choice per answer (`wantAnswers`) | F (new) | F (new) | F (new) | F (new) | F (new) | F (new) | F (new) |
| Missing → `missing answer "<key>"`, id, question | F | F | F | F | F | F | F |
| Uncertain → `uncertain answer "<key>"` | F fixed | F fixed | F fixed | F fixed | F fixed | F fixed | F fixed |
| Request id `gate:<i>:<key>` | F | F | F fixed (skip renumbered) | F | F | F | F |
| Default escalation: `no rule fired`, `gate:default`, question `""` | F | F | F fixed (no question) | F | F | F fixed (nil) | F |
| Policy default action | F | F | F | F | F | F | F |
| skipUncertain skips a later-firing rule | F | F | F | F | F | F | F |
| skipUncertain does not skip a missing answer | F | F | F | F | F | F | F |
| Check order: uncertain before fit | F | F fixed | F | F fixed | F fixed | F fixed | F |
| Misfit rule escalates, never skipped | F fixed (was silent false) | F | F | F fixed (NPE on no-condition) | F fixed (was skipped) | F fixed (silent false / skipped) | F |
| Tape miss: exact message, on evaluate | T | T | T | T | T | T | T |
| Tape miss sends no request | C | C | C | T | C | C | C |
| evaluateBatch state order | T | T | T | T | T | T | T |
| evaluateBatch lowest failing index | T (new) | T (new) | T (new) | T (new) | T (new) | T (new) | T (new) |
| evaluateBatch empty → error, nothing sent | T | T | T | T | T | T | T |
| Canonical request byte-identical | T | T | T | T | T | T | T |

Fixture size after the sweep: gate-cases 21 cases; state-cases 5 cases.

**Mutation check.** Each port was broken on purpose and the tests were confirmed to fail, then the
break was reverted.
- Reason text: all 7 ports.
- Skip index / skip-missing: golang, java.
- Misfit escalation: js, csharp, elixir, clojure.
- Default id / `data.question`: elixir, clojure.

**Still open.** Misfit reasons are port-specific by design (SPEC §8B), so the fixture omits
`want.reason` for them. "Tape miss sends no request" holds only because the replaying classifier
has no transport, and only java counts requests.
