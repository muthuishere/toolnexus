# Spike report — live backend behaviour (ADR 0020 gate, pre-port)

Run 2026-09-20 against **OpenRouter Decisions** (`POST https://openrouter.ai/api/v1/systemone`,
model `typesafe/jev-1.13`, served by provider `TypeSafe`, answering as `jev-1.13-20260917`).
No TypeSafe waitlist key was needed. Key read from `OPENROUTER_API_KEY` at call time, never logged.

## F1 — The wire is exactly as researched, and our canonical form is accepted

`fixture/request.json` (514 bytes; keys sorted recursively, arrays untouched, compact separators,
sha256 `d5fa5c11…`) returns HTTP 200 unchanged. The response carries the documented
`model` / `answers` / `usage`, plus three OpenRouter-only fields — `id`, `provider`, and
`usage.cost` — which the fixture strips so the recorded shape stays portable.

**Consequence:** the canonical-request conformance claim in ADR 0020 D2 is real and testable.
Sorting keys recursively while leaving arrays alone is correct and necessary: `score.criteria`
is an array whose **order is the level numbering**, so a naive "sort everything" canonicaliser
would silently renumber the rubric.

## F2 — Jev is NOT deterministic. CI can never assert on live answers.

Same 514 bytes, repeated:

| run | `is_refund_request` (noul) | `department` P(shipping) | `urgency` (score) |
|---|---|---|---|
| 1 | 0.98 | 0.63 | 1.25 |
| 2 | 0.98 | 0.65 | 1.22 |
| 3 | 0.98 | 0.62 | 1.20 |

Twelve concurrent calls on a borderline command (`git push origin main`): scores
1.90–1.95, **spread 0.05, σ = 0.0153** on a 0–3 scale.

**Consequences.**
1. The `static` backend is not a convenience, it is the **only** thing CI can byte-assert.
   ADR 0020 D3's four backends are load-bearing.
2. **Thresholds need clearance.** A band boundary within ~0.1 of a typical value will flip
   between runs. The `bands` rule needs a documented minimum clearance, or hysteresis. This is
   a new finding — the ADR does not currently say it, and it must.
3. A noul at 0.98 was stable across every run; the jitter is concentrated in `score` and in
   `choice` probabilities. Binary gates are the most reproducible primitive.

## F3 — The three-band guard beats the regex, on the exact case the docs miss

The coding-agent page ships `const DANGEROUS = /\b(rm\s+-rf|git\s+push|--force|…)\b/i`.
Same three commands through `score risk[0..3]` + `noul from_untrusted`, rule
`deny ≥2.5 or untrusted ≥.7; ask ≥1.5; else allow`:

| command | risk | untrusted | judge | the shipped regex |
|---|---|---|---|---|
| `git status --short` | 0.02 | 0.09 | **ALLOW** | allow ✓ |
| `python3 -c "import shutil; shutil.rmtree('/')"` | 2.97 | 0.47 | **DENY** | **allow — misses it entirely** |
| `rm -rf ./build` | 2.25 | 0.14 | **ASK** | deny (hard, no ask band) |

The regex has no third band, so it must either interrupt on `rm -rf ./build` as if it were
`rm -rf /`, or widen and miss both. Gate item 3 of the ADR is proven live.

## F4 — `choice` scales to the documented 255, and latency is FLAT in roster size

One `choice` over a synthetic-but-realistic skill roster, plus a `prose_suffices` gate.
Request: "pull the numbers out of last quarter's board deck and chart them for Thursday."

| options | request bytes | input tokens | latency | pick | confidence |
|---|---|---|---|---|---|
| 20 | 1 696 | 792 | 0.52 s | `dataviz` | 0.69 |
| 64 | 5 427 | 2 015 | 0.54 s | `dataviz` | 0.92 |
| 128 | 10 876 | 3 816 | 0.48 s | `dataviz` | 0.95 |
| 200 | 17 068 | 5 904 | 0.54 s | `dataviz` | 0.90 |
| 255 | 21 798 | 7 499 | 0.61 s | `dataviz` | 0.94 |
| 300 | 25 668 | — | — | `HTTP 400 "Too many choices. Must have at most 255 choices."` |

**Consequences.**
1. **Latency does not grow with the roster** (0.48–0.61 s across a 12× range). That is the
   parallel-sampling property, and it is what makes `SkillRelevance` viable on a real roster —
   a per-turn judge over 255 skills costs the same wall-clock as one over 20.
2. Accuracy *improved* with more options (0.69 → 0.94): irrelevant distractors drain probability
   rather than adding noise.
3. The 255 cap fails **loudly and legibly**, so the backend can detect it and chunk. Chunking
   above 255 is required (ADR 0020 D3) and now has a concrete trigger to detect.
4. Cost at 255 options is $0.000315/turn; at 20 options, $0.000033/turn.

## F5 — `style: "llm"` is a compatibility exit, not an equivalent

Identical 64-skill routing job, three backends:

| backend | latency | pick | confidence | input tok | cost | distribution |
|---|---|---|---|---|---|---|
| **Jev** (`typesafe/jev-1.13`) | **0.52 s** | `dataviz` | 0.85 | 1 791 | **$0.000075** | **64 probabilities** |
| `openai/gpt-4o-mini` | 1.72 s | `dataviz` | 0.95 | 1 235 | $0.000191 (2.5×) | none |
| `deepseek/deepseek-chat` | 2.28 s | `dataviz` | 1.00 | 920 | $0.000254 (3.4×) | none |

All three routed correctly — but Jev is **3.3–4.4× faster, 2.5–3.4× cheaper, and the only one
that returns a distribution.** The LLMs emit round, self-reported confidence (0.95, 1.00): the
textbook overconfidence signature.

A second probe on the support-ticket fixture had the LLM backend **disagree outright** —
`department: billing` at 0.95 where Jev said `shipping` at 0.61, and `urgency: 2 (urgent)`
where Jev said 1.21 (elevated).

**Consequence:** ADR 0020's `calibrated: false` flag is not defensive paperwork, it is required.
Thresholds tuned on Jev must not silently apply to the LLM backend. The docs must say that
switching `style` requires re-tuning, and `bands` should refuse to run on an uncalibrated
backend unless the policy opts in.

## F6 — Operational notes

- 12 concurrent requests: median 0.80 s, max 1.45 s, **no 429s**. The per-turn battery pattern
  does not need a queue at this scale.
- Live latency 0.48–1.03 s is well above TypeSafe's 70–500 ms claim — gateway overhead. Budget
  ~0.5–1.0 s for a per-turn judge, not 100 ms.
- Failure modes seen are clean JSON errors with a `code` and a human-readable `message`, which
  maps onto the existing §8 `ErrorInfo → Tier` classifier without a second retry policy.

## What this changes in ADR 0020

1. **Add a threshold-clearance rule** to D4: `bands` boundaries must sit ≥0.1 from expected
   values; document σ ≈ 0.015 and offer hysteresis. (New — the ADR does not say this.)
2. **Promote `calibrated`** from a flag to a policy input: `bands` opts in explicitly before
   running on an uncalibrated backend. (F5 is stronger evidence than the ADR assumed.)
3. **Canonicalisation is "sort objects, never arrays"** — state it in D2, since the naive
   implementation is wrong in a way tests on `noul`/`choice` alone would not catch.
4. Record that **OpenRouter is a working backend today without a TypeSafe key**, which removes
   the early-access risk from the critical path and gives CI a live-smoke option.
