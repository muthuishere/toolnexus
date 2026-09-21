# ADR 0021 — The encoding carries the judgment: what a `Classifier` caller must write, and how to detect when they have not

- **Status:** **Accepted — measured 2026-09-20.** Not argued from priors: every claim below is a
  number from a run that could have come out the other way, and two of my own predictions did not
  survive (recorded in *Corrections* rather than quietly dropped).
- **Date:** 2026-09-20
- **Driver:** ADR 0020 decides the `Classifier` *seam* — the wire, the question types, the
  canonical request. It says nothing about what a caller should put **inside** a question, which
  turns out to be where essentially all of the outcome lives. A caller who satisfies every rule
  in 0020 can still build something that ranks at chance, and neither the schema, the validation
  contract, nor the HTTP status will tell them.
- **Evidence:** two working demos driven by `typesafe/jev-1.13` over OpenRouter —
  `spikes/game/dino/` (real browser, ~800 ms budget) and `spikes/game/snake/` (1 s budget, seeded,
  four-way encoding ablation plus a shuffle control). **Raw per-decision records are retained for
  snake only** (`spikes/game/snake/results/*.json` — every number in the tables below). The dino
  run's records were deleted before they were committed, so its figures here are as reported at
  the time and are **not independently re-checkable from this repo**. Snake's sweeps are
  reproducible via `snake/ablate.sh` and `snake/uncapped.sh`; a fresh dino run would produce
  different numbers, since neither the game nor the judge is deterministic.
- **Related:** ADR 0020 (the seam), `openspec/changes/add-judge`.

## Context

`Classifier` hands the caller three question types and a validation contract. Both are about
*shape*. Nothing in either is about *content*, and the corpus of existing Jev demos suggested
content might be where the variance is: `jev-tetris` reported that deleting one self-contradictory
sentence roughly doubled every score, and `jev-plays-wordle` ships `criteria={w: None}` — no
per-option description at all — with no evaluation that would reveal whether that works.

Two games were built to find out, with everything except the wording held fixed: same seeds, same
board, same legal-option deck, same code baseline, same tick budget. The only variable is how the
state and the options are written.

## The measurements

### 1. The option sentences carry the judgment. The state description does not.

Snake, 3 games per style, identical seeds, one `choice` per move over the legal moves:

| encoding | what changes | apples | agrees w/ baseline | median confidence |
|---|---|---|---|---|
| **prose** | no digits; each option described by consequence | **17, 17, 17** | 0.63 | 0.80 |
| **stale** | one clause frozen, no longer tracks the board | 17, 17, 17 | 0.64 | 0.82 |
| **raw** | the same facts as numbers | 12, 15, 14 | 0.70 | 0.62 |
| **labels** | bare option ids, no descriptions | **0, 1, 0** | **0.29** | **0.29** |
| *shuffle control* | *prose, numbers permuted onto the wrong options* | *1, 0, 1* | *0.37* | — |

`labels` keeps the **full prose state** and replaces each option with its own id. It lands on the
shuffle control's floor. Describing the situation buys nothing if the options are not described;
every usable bit of signal was in the sentence attached to each option.

**This is a caller-facing failure with no detector in ADR 0020.** `criteria = {id: id}` is a
schema-valid `choice`, passes `validateChoice`, returns HTTP 200 and a well-formed distribution —
and ranks at chance. It is also, verbatim, the shape the only published Wordle demo ships.

### 2. Numbers degrade; they do not collapse

`raw` sends `x=5,y=6`, `room=142`, `food_distance=9`. Score falls ~18%, confidence 0.80 → 0.62.
The jaggedness note ("does not count reliably") reads like a prediction of failure; the measured
behaviour is graceful degradation. Worth stating precisely, because "never send a number" is
otherwise cargo-culted as a taboo rather than costed as a tradeoff.

### 3. A clause that stops tracking the state costs a third of the score — when the metric can see it

`stale` freezes the room clause. Under a 150-move cap it was **free**: 17, 17, 17, identical to
prose. Uncapped, with games ending when the snake dies:

| | prose | stale | code baseline |
|---|---|---|---|
| median apples | 38 | **25** (−34%) | 40 |
| median moves survived | 466 | **242** (−48%) | 526 |

The clause was never free. The **measurement was blind**: a capped game never lets the snake get
long enough for a bad room call to kill it. Any evaluation of an encoding must be run on the
regime where the missing information actually binds, or it will certify a broken encoding.

### 4. Constant noise is cheap; selective misdirection is not

The dino build shipped a `leap` option reading *"passes over whatever is **standing** in your
way."* Nothing is standing when a bird is coming, so leaping read as irrelevant to birds and the
judge crouched at every one — including the knee-high bird, where crouching is fatal. Rewriting
one clause so the options use the same height vocabulary as the state:

| | before | after |
|---|---|---|
| agrees with baseline, bird waves | **50%** (2/4) | **100%** (18/18) |
| median distance | 79 m | **309 m** |

Both this and `stale` are "a clause that is wrong", and they cost wildly different amounts.
`stale` is wrong **uniformly** — the same false claim on every option, carrying no signal but no
lie either. The dino clause was wrong **differentially**: it made exactly one option read as
irrelevant to exactly one situation. The judge's answer was coherent with what it was told; what
it was told was wrong.

### 5. Confidence reports on the question, not on the answer

| observation | confidence |
|---|---|
| moves agreeing with the baseline vs diverging from it | 0.87 vs 0.54 |
| a described deck vs an undescribed one (`labels`) | 0.80 vs **0.29** |
| moves into a tight pocket vs the rest | 0.75 vs 0.67 |

And the clincher: **`stale` carried the highest median confidence of any style (0.82) while being
the worst working encoding.** Confidence tracks how constrained the position is and how thin the
information is — not whether the answer is right.

That yields one deployable thing and one hard limit.

- **Deployable:** a confidence distribution sitting near the uniform floor (`1/n` for an
  `n`-option choice) is a detectable symptom of an encoding that says nothing, visible from
  responses alone — no baseline, no labels, no outcomes. It runs on live traffic.
- **Limit:** it cannot separate a good encoding from a subtly wrong one. A wrong-but-answerable
  question still reads as answerable.

## Decision

**D1 — `SPEC.md` states the encoding obligation as a requirement on the caller, not as advice.**
For a `choice`, `criteria[id]` is the *only* thing that differentiates options to the model.
Passing an id, an empty string, or a value equal to the key is schema-valid and ranks at chance.
This is stated where the `choice` type is defined, with the measured number next to it.

**D2 — Ports SHALL detect the degenerate-criteria case and report it; they SHALL NOT repair it.**
If every `criteria` value for a `choice` is empty, or equal to its own key, or identical to every
other value, the port emits a one-time warning through the existing metrics/log sink naming the
question id. It does not rewrite the request. (Never repairing is ADR 0020's rule and it stands;
this is detection, which is the thing a caller cannot do for themselves from a 200 response.)

**D3 — `Decision` exposes a `nearUniform` signal per choice answer.** True when the distribution
is within a documented tolerance of `1/n`. It is derived, cheap, and it is the only encoding
health check available without ground truth. It is **advisory** and explicitly not a correctness
signal — the same sentence that carries `calibrated` carries this.

**D4 — The docs carry the encoding guidance with the numbers attached, not as style advice.**
`cookbook/judge` gains a section stating: describe every option; use one identical sentence
template across options; keep arithmetic in code and hand over the conclusion; and a clause that
is wrong about one option in one situation costs far more than a clause that is uninformative
everywhere. Each claim cites its measurement.

**D5 — Encoding evaluation guidance ships with a shuffle control.** The one cheap test that
distinguishes "the judge is ranking" from "my code is steering" is to keep the returned
probabilities and permute which option each belongs to. 17 apples → 1. It costs one function and
it is the only control in this space that can fail. Documented in `harness/judge-live`.

## Corrections — two of my own predictions did not survive

**1. "A stale clause will be expensive" — it scored identically under the cap.** It was expensive,
but only once the cap came off. The prediction was right and the experiment was wrong, which is
worse than being wrong, because a capped run would have shipped a false negative.

**2. "Self-trapping moves are a safety metric" — retracted.** An earlier reading treated "moves
into a pocket smaller than the snake" as a safety measure, on stale scoring 3 against prose's 0.
Uncapped, the **code baseline makes the most of them — 49, a 3.2% per-move rate against prose's
1.4% — and survives longest and scores highest.** The count scales with snake length, so comparing
raw counts across runs of different lengths measures length, not danger. The conclusion about
`stale` was right; this evidence for it was not.

**3. The predicted endgame win did not appear.** Prose was expected to beat greedy uncapped,
because greedy-toward-apple is precisely the policy that walks into a pocket and the encoding has
a clause for it. It did not: 38 apples to 40, dying the same death. Three games, so not a
significant loss either — what is reportable is the absence of the predicted win.

## Consequences

- A caller who follows ADR 0020 exactly can still ship something that ranks at chance. D2 and D3
  are the difference between that failing loudly and failing silently for the life of the feature.
- `nearUniform` is a fourth derived field on an answer; it is computed, not transported, so no
  wire change and no fixture change.
- The guidance is opinionated about prose over numbers on an 18% measurement, not on a taboo. A
  caller who needs numbers now has the price.
- **These numbers are from two games, three runs each, one model, one machine.** They are strong
  enough to justify a warning and a derived flag; they are not strong enough to justify refusing a
  request, which is why D2 warns rather than rejects.

## Gate

1. `labels`-shaped input (every criterion equal to its key) produces a warning naming the question
   id, in all seven ports, and still sends the original bytes. ☐
2. `nearUniform` is computed identically in all seven ports against a shared fixture, including
   the boundary case. ☐
3. `cookbook/judge` carries D4's four claims, each with its measurement cited. ☐
4. A run that constructs no `Classifier` is byte-identical to `main`. ☐ *(inherited from 0020)*
