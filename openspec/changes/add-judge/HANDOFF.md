# add-judge — build state, 2026-09-20

Working notes for resuming after a context clear. Delete when the change is applied.

## Done

**Phase 1 — the contract. COMPLETE and verified.** Tasks 1.1–1.8, 2.1–2.8 ticked (16).
- `SPEC.md` §8B (line ~1448) — 273 lines, **pure insertion**, no existing section touched.
- `conformance/options_manifest.json` — `classifierOptions`, 14 options, 7 port paths.
- `conformance/check_options_parity.py` — group discovery is now generic (any top-level key
  ending in `Options`) and a group may carry `"landing": true`, which reports a not-yet-written
  port file as **LANDING** — printed every run, never a pass — instead of failing CI during the
  port phases. **This flag is a temporary hole in a parity gate. The change that lands the last
  port MUST delete it.** Currently exits 0 with all 7 rows LANDING.
- `examples/judge/` — 7 fixtures + README: base, hardened, numbers, wide, decisions, degenerate,
  near-uniform. Every hash recomputed independently, all 9 payloads verified.
- `docs/adr/0021-the-encoding-carries-the-judgment.md` — the encoding decisions D1–D5.
- Proposal, tasks and spec delta updated for D1–D3 (3 requirements, 9 scenarios).
- `openspec validate add-judge` → valid.

## Not started

**Phase 2 — Go reference port (tasks 3.1–3.7).** First attempt died on a network error
(`ENOTFOUND`) before writing a single file; `golang/` is untouched by it and still builds. Needs a
clean restart.

**Phase 3 — six ports in parallel** (tasks 4.1–4.8), after Go fixes the API shape:
js, python, java, csharp, elixir, clojure. Each has its own trap documented in tasks.md 4.1–4.6,
and each has a pre-ADR-0021 reference implementation in `spikes/classifier/<lang>/`.

**Phase 4 — docs + changelog** (tasks 5.1–5.7, 6.1–6.5), including deleting the `landing` flag.

## Constants every port agent must be given verbatim

**nearUniform.** `n` = number of entries in the answer's `probabilities` map; `p_i` = their values
AS RETURNED (not renormalised; an offered option absent from the map counts as 0).

    nearUniform  ⇔  max over i of |p_i − 1/n|  ≤  0.05

Tolerance 0.05 **absolute**, comparison **inclusive**, double precision, no sorting, no
renormalising, no rounding before comparison. `n = 1` ⇒ true. Fixtures pin 0.0499 → true and
0.0501 → false, 1e-4 either side, so no port needs an epsilon.

**Degenerate criteria.** All values empty, OR each equal to its own key, OR all identical to one
another with `n ≥ 2`. `n = 1` is never reported. Report **once per question key per classifier**,
through the existing sink, naming the question id. **Never modify the request.**

## Traps found in Phase 1 — tell every port agent

1. **`canonicalSha256` covers `{model, questions}` only, NOT `state`.** State is outside the byte
   claim because numbers do not canonicalise across languages. The spike's recorded hashes cover
   the whole body and are therefore **stale** — do not lift them.
2. **The three guard entries in `decisions.json` share one `questions` payload and one hash**;
   they differ only in `state`. So a `static` backend keyed on the canonical request alone cannot
   tell the three bands apart and **must key on state too**.
3. **`numbers.json` asserts the parse, not the bytes** — numbers only ever appear in `state`.
   Compare numerically, never as strings.
4. `spikes/classifier/<lang>/` predates ADR 0021: no degenerate detection, no `nearUniform`.

## Standing rules for this change

- Never repair a bad response (ADR 0020). Invalid distribution ⇒ no action, not a patched one.
- Secrets use-only: `apiKeyEnv` resolves at call time, never logged, never on an error path.
- No new third-party dependency in any port; the wire is one POST.
- A host that constructs no `Classifier` must be byte-identical to `main`.
- Do not commit; leave work in the tree.
