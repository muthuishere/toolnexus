# Live validation — 2026-11-05

The two live items in tasks.md: **4.4** (ADR 0038 D8 cutover gate) and **4.2** (design §9 joint
calibration). Code: `spikes/classifier-gate` (`//go:build live`, refuses to run without
`TN_LIVE=1`; CI never builds it). Items: `spikes/classifier-gate/fixture/items.jsonl` (28 real
items: toolnexus issue titles and commit subjects, plus 4 image items from screenshots in this repo)
and `fixture/joint.json` (10 issue titles × 3 questions).

```
cd spikes/classifier-gate
TN_LIVE=1 go run -tags live . -mode wire                       # no network
TN_LIVE=1 go run -tags live . -mode gate  -profile myjev
TN_LIVE=1 sec run TYPESAFE_API_KEY -- go run -tags live . -mode gate -profile jev -images
TN_LIVE=1 sec run TYPESAFE_API_KEY -- go run -tags live . -mode gate -profile jev -only rate-effort-acp -repeat 6
TN_LIVE=1 go run -tags live . -mode joint -profile myjev
TN_LIVE=1 sec run TYPESAFE_API_KEY -- go run -tags live . -mode joint -profile jev
```

Endpoints: `myjev` = the owner's local model (`127.0.0.1:21131`, `myjev`, free, text only) and
`jev` = hosted TypeSafe `jev-latest` (it served `jev-1.14.0`). The old arm is the installed
`jevx` with `--json --fresh --no-context` and a throwaway `JEVX_CONFIG` that holds only the
profile (no question pack, no ledger). The new arm is `Classifier.State(text).Evaluate(q)`, no
cache, `ValidateDecision`, then jevx's verdict rule over `DefaultThresholds.Band`.

## 4.4 — D8 cutover gate

| run | is | pick | rate | images | total |
|---|---|---|---|---|---|
| wire capture (request bodies, no network) | 11/11 | 7/7 | 6/6 | — | **24/24 byte-identical** |
| myjev (local) | 11/11 | 7/7 | 6/6 | — | **24/24**, max \|Δ\| 0.000 |
| jev (hosted) | 11/11 | 7/7 | **5/6** | 4/4 | **27/28** (96.4%), max \|Δ\| 0.09 |

Latency p50 (text): myjev jevx 46 ms / toolnexus 27 ms; hosted jevx 391 ms / toolnexus 292 ms.
The jevx figure includes starting the process; images p50 588 ms (raw wire) / 621 ms
(toolnexus).

**The one miss** is `rate-effort-acp` on hosted Jev: jevx `large` (confidence 0.62), toolnexus
`unsure` (0.58), against `minConfidence` 0.6. The requests are byte-identical (wire mode). Asking
the same item 6 more times per arm (`gate-jev-rate-effort-acp-…-x6.json`) gives jevx confidences
0.56 0.56 0.58 0.64 0.55 0.53 and toolnexus 0.60 0.55 0.56 0.56 0.58 0.56. **Both arms cross the
threshold in both directions**: 4/6 agree, and the two misses go opposite ways. That is the
endpoint's non-determinism (spike F2) at a band edge, not the encoder.

**Strict reading of D8** ("any mismatch blocks that verb"): `is` and `pick` pass, **`rate` is
blocked** on hosted Jev. On the deterministic local model every verb passes with identical
numbers. D8 as written cannot be reliably passed on a non-deterministic endpoint by any verb with
items near a band edge; the owner decides whether wire identity plus the deterministic run is the
gate, or whether D8 is amended to allow noise-explained misses.

Image items: jevx has no image input, so the old arm for them is the System One request built by
hand (`assets` + `TypeSafe-Danger-Zone-Images: true`), not jevx. 4/4 match. This proves the
toolnexus image wire works against the live endpoint; it is not a jevx comparison.

## 4.2 — joint-calibration A/B (design §9)

Each state asked once with all three questions, once per question, and the joint request again
(to measure the endpoint's own noise). 50 calls per endpoint.

| endpoint | max \|Δ\| joint vs single | max \|Δ\| joint vs identical repeat | mean (split / repeat) | verdict flips |
|---|---|---|---|---|
| myjev | **0.000** | 0.000 | 0 / 0 | 0 |
| jev (hosted) | **0.080** | 0.070 | 0.028 / 0.022 | 1 (`urgent` 0.21 → 0.20 at the 0.2 edge) |

- **myjev**: independent. Joint and single answers are bit-identical.
- **Hosted Jev: not proven.** Max \|Δ\| 0.08 exceeds the design's 0.05 floor, so the test as
  written fails. But the same request repeated also moves by up to 0.07, so the floor taken from
  spike F2 (σ 0.015, spread 0.05, measured on one item) is too tight for this endpoint today. The
  split distribution is slightly wider than the repeat one (mean 0.028 vs 0.022), a gap too small
  to tell apart at n = 30. This run neither shows joint calibration nor rules it out.
- Consequence, per design §9: **per-question caching and automatic chunking stay unshipped.**
  Proving independence on hosted Jev needs a criterion that compares split against repeat noise
  with more samples (for example 30 states × several repeats), not a fixed 0.05 floor.

## Cost

Hosted calls: 56 (gate) + 12 (repeat) + 50 (joint) = 118. Input tokens ≈ 75k (text ~320 per
call, images 3.6k–7.5k). TypeSafe reports no cost; at the OpenRouter Jev rate measured in spike
F5 (≈ $0.04 per 1M input tokens) that is about **$0.003**. myjev runs were free.

## Not verified

- Other ports: only the Go port's classifier was exercised live. The byte claims for other ports
  rest on the shared fixtures.
- jevx's hosted-only redaction (secrets, emails, phones) was not exercised: no item contains
  any, and the wire capture runs against a local (non-hosted) URL.
- Cloudflare, OpenRouter and Vercel backends (task 4.3) were not run.
