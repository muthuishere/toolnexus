# How the existing Jev game demos are built — and what beats them

Research 2026-09-20. Ten repos read at source level. This file is the brief for our build.

## The one-line architecture, universal across all ten

> **"Pick from a deck, never name a card."** — `jev-plays-pokemon-red/docs/SHARED.md`

Code enumerates a provably-legal option set; Jev only ranks it. Never "what should I do",
always "which of these". One `choice` is the spine; `noul`s ride free in the same request
(fan-out: many questions, one round trip, one state ingest). `score` is nearly unused.

Other invariants: always an escape-hatch option (`none`/`BLOCKED`/`keep_running`); criteria are
**code-generated sentences, not labels**; fixed clock + a deadline the answer must beat, with a
named taxonomy for how the tick resolved (`jev / late / error / forced`); serial by default —
parallelism lives *inside* the request; fail-open to a hand-written heuristic **that is also the
published baseline**; nothing is ever repaired (invalid distribution ⇒ no action).

## The rule that dominates a word game

`docs.typesafe.ai/model-jaggedness/jev-1.13` #2, verbatim: Jev *"is not a calculator… **does not
count reliably. This covers characters in a word**, occurrences of a term, items in a long list."*

So for Wordle: **every letter-level operation must be code, and must be visibly code.**
- Never send a letter count, a position index, or a candidate count as a digit.
  `position 3` → `"the third letter"`. `142 left` → `"a long list — well over a hundred"`.
- Never ask Jev anything letter-level ("does SLATE contain an E").
- Jev is asked exactly one genuinely semantic question: *which of these words is the better bet* —
  where "is this the kind of word an answer list uses" is a judgment code cannot make.

### The prose-conversion craft (`pong-jev/src/core.ts`)

Header states the bet: *"the model is never shown a coordinate. It only ever sees words."*

```ts
if (Math.abs(h) < 0.25) return "level with the centre of my paddle"
if (Math.abs(h) < 0.75) return `slightly ${dir} my paddle`
if (Math.abs(h) < 2)    return `clearly ${dir} my paddle`
return `far ${dir} my paddle`
```
It ships a `mode:"raw"` numeric encoder **as a negative control** — "the encoding the docs warn against."

### The option-sentence rule (`jev-tetris/src/describe.js`)

```
// Every option uses the identical sentence template, because equivalent
// wordings are not guaranteed to produce equivalent judgements.
// Consequence first, position last — the position is the least important fact.
```
The single biggest measured win in the whole corpus was **deleting one self-contradictory
sentence** from a template — roughly doubled every score. Found by reading the run log.

## Validation contract to copy verbatim (`jev-ultrafast`)

`choice ∈ ids`; `set(probabilities) == set(ids)`; all finite in [0,1]; `|Σp − 1| < 0.02`;
`probabilities[choice] >= max(probabilities) − 1e-6`. Otherwise: *"Invalid TypeSafe response; no
action executed."* **Nothing is repaired.** Budgets double for model calls vs actions, so
stale-retry loops cannot burn money.

## What they measure

- `jev-ultrafast`: median 9.450 s → 7.092 s, **median Jev latency 178 ms**, 1092→101 CDP calls —
  then: *"Three pairs are too few for a strong statistical claim (two-sided sign-test p = 0.25)."*
- `pong-jev`: concurrency 1 → decision age 196 ms; concurrency 3 → 111 ms; **per-call latency
  unchanged at 379 ms**. ~520 input tok/decision → **$0.02 per 1000 decisions**.
- `jevscape`: *"A random choice over this action set beats every leaderboard model… Read the
  leaderboard as 'this action space is strong', not 'Jev is stronger than Opus'."*
- `pokemon-red`: Brier + 95% CI **vs a constant predictor**, and prints `worse than the constant
  predictor` when it is.

## Visual grammar (adopt wholesale)

Sorted probability bars, winner highlighted · a **live ms counter that runs while thinking** and
freezes on the answer · cumulative `$` counter · the literal state/question JSON on screen · a
decision feed with `APPLIED / FORCED / LATE / ERROR` badges.

Best specific tricks:
- `typesafe-snake` **DeadlineBar** — rAF countdown, `ok/warn/danger`, a `missed` state. Makes the
  tick budget the primary visual, so "late" is a first-class outcome.
- `jev-tetris` — confidence as **block brightness**: *"DIM BLOCKS: how sure it was when it placed
  them. The wall is a record of its doubt."* Dotted ring on the **runner-up**. Footer:
  `JEV · WEIGHTS OURS, PROBABILITIES ITS`. A `PANEL` legend naming every HUD element.
- `jev-for-chrome` — numbered badges on the real page matching the model's indices; chosen target
  flashes green 600 ms; bottom pill `Jev: CLICK Search · 220ms`.
- `pokemon-red` — bars lerp from the previous decision; **shows its fallbacks**:
  `NO ANSWER: RATE LIMITED, CODE DEFAULT`.

**Nobody renders a live side-by-side arm.** Every baseline is a README table. That is an open lane.

## What the best do that the mediocre don't

1. **Ship a control that can kill the thesis, and run it.** `jev-tetris` is the gold standard:
   - **Shuffle control** — keep Jev's numbers, permute which option each belongs to. Play collapses
     to random (0 lines, dead at 26 pieces). Proves the code isn't quietly steering.
   - **Keyword control** — 23 lines of regex over *the identical sentences*, no API. It **beats Jev
     outright**. Published as the headline: *"The defensible claim: given only prose, JEV ranks
     options well enough to play credible Tetris, far above random. Not: JEV is good at Tetris."*
2. **Say what the numbers cannot support** — *"an earlier version of this document said '60%' — it
   is unsupported."*
3. **Publish what didn't work** — the API's richer structured criteria form raised confidence
   0.41→0.47 and **halved** the score.
4. **Verification independent of the model's own DONE.**
5. Mediocre ones (`typesafe-snake`, `jev-t-rex-runner`) have the best UI and **zero evaluation**.

## Wordle is effectively unclaimed

Across ~110 game repos in ten awesome lists: **one Wordle repo**,
[`levente-horvath/jev-plays-wordle`](https://github.com/levente-horvath/jev-plays-wordle) —
created 2026-09-18, pushed one minute later, **0 stars, no description, in no list**. It uses
`criteria={w.upper(): None for w in options}` — **no per-option description at all**, which is the
exact thing the house style says carries the judgment. Measures solved/games and a call count. No
latency, no cost, no tokens, no calibration, no baseline.

The real prior art is [`zebedelu/sudoku-vs-jev`](https://github.com/zebedelu/sudoku-vs-jev):
one described option per legal `(cell,digit)`, most-constrained-first.

| Level | Solved | Forced | Latency | Confidence | Cost |
|---|---|---|---|---|---|
| Easy | 5/5 | 98% | 0.56 s | 80% | $0.0157 |
| Hard | **1/5** | 75% | 0.51 s | **67%** | $0.0486 |

Jev nails forced moves and **collapses exactly where it must guess between equally-legal
options** — which is precisely the Wordle endgame. Expect the same shape. **And it ships
`MAX_OPTIONS = None` with 700+ options against a documented 255 cap** — an open, visible flaw in
the flagship constraint demo.

Zero hangman, Mastermind, Semantle, Connections, anagrams, crossword solving.

## Our build: what clears the bar, and what beats it

**Clears it:** one `choice` + speculative `noul`s per turn; identical sentence template per option
(consequence first, word last); no digits anywhere in the state; `validate_choice` verbatim, never
repair; one survivor ⇒ no API call; fail-open to the code ranker and **label it on screen**.

**Solve the 255 cap explicitly and publish it** — Wordle's answer list is 2,309 and day-2 survivor
sets exceed 255. Bucket by information-gain tier, sample ≤N per tier, and say so in the state:
`"shown: 40 of about 300 words still possible, chosen to span the useful range"`. Being the repo
that names and fixes what the flagship demo ships broken is free credibility.

**Beats it:**
1. **Shuffle control + keyword control.** No word demo has either.
2. **Publish the baseline we lose to** — optimal entropy solver ≈3.42 mean guesses, 100% in 6.
   Four arms, same answer list, same order: code-optimal · code-ranker-only · Jev · frontier LLM.
3. **Live side-by-side arms on screen** — structurally different from all ten existing demos.
4. **Report p95, not just medians.** Nobody in the corpus does. That alone puts us ahead.
5. **The metric that beats latency:** *guesses that contradicted feedback already on the board.*
   The frontier arm will visibly hallucinate words violating known constraints; **Jev scores 0 by
   construction**, because it can only pick from a filtered deck. That is a better argument for
   typed decisions than any speed number.
6. **The `--aim` equivalent** — ask the strategic nouls (`should_play_safe`, `is_it_determined`)
   one turn early and speculatively, so the round trip overlaps the previous guess's animation.
   Bank latency in the encoding, not in concurrency.
7. **`docs/findings.md` in the Tetris voice** — what the controls showed, where the shortlist cap
   distorts, which turn Jev degrades on, and whether confidence predicts failure. (Tetris found it
   does not: *"One catastrophic turn came back at 0.48, right at the run average."*)

**Footer for the HUD:** `FILTERING OURS · CHOICE ITS · JEV NEVER SEES A LETTER`

## Cost facts for the counter

$0.042/MTok input, **output free**. 64k context, 32k for state + longest question. 255-option
choice cap. 2–10 score levels. Noul returns **no confidence field**. Question ids are never sent
to the model. `P(noul)` and `1 − P(not noul)` are not comparable — never carry a threshold across
question types.
