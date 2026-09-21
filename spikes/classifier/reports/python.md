# Spike report — Python (`spikes/classifier/python/`)

## 1. Verdict

**Feasible.** No friction worth naming. Python needs no new dependency, no hand-rolled JSON
canonicaliser, and no new shape for `criteria` — the whole spike is 212 lines of stdlib.

## 2. The four gate items

Run (from `spikes/classifier/python/`): `python3 -m pytest -q` → **8 passed in 0.32s**.

| # | gate | result | proof |
|---|---|---|---|
| 1 | byte-exact request | **PASS** | `test_request_is_byte_exact` — `build_request(...) == fixture/request.json`, 514 bytes, sha256 matches `request.sha256`. Plus `test_score_criteria_array_order_survives`. |
| 2 | parse | **PASS** | `test_parse_decision` — `noul` 0.98, `choice` "shipping" + probabilities `{billing:0.39, technical:0.0, shipping:0.61}`, `score` 1.21 + 3-entry legend, usage. |
| 3 | one judge, wired | **PASS** | `test_judge_bands` / `test_judge_as_guardrail_shape` — `git status --short`→`""` (allow), `shutil.rmtree("/")`→`"deny: risk=2.97 (destructive or irreversible)"`, `rm -rf ./build`→`"ask: risk=2.25 …"`. `static` backend keyed by the canonical request bytes. |
| 4 | invariant | **PASS** | `test_judge_cannot_flip_a_prior_deny` — the guardrail is composed through the **real port's** `toolnexus.agents.loop.guarded_hooks` (imported read-only, not copied); first-deny-wins holds in both orders. |

Caveat on the test env: gates 1–3 are pure stdlib on system `python3`. Gate 4 imports the real
port, so it needs the port's own deps (`mcp<2`, `pyyaml`) — I ran the suite in a throwaway venv.
The spike code itself imports nothing outside the stdlib.

## 3. LOC (non-blank, non-comment)

| unit | LOC |
|---|---|
| `Classifier` — 3 question types + `question_to_wire` + `canonical_json` + `build_request` | 40 |
| `Classifier` — 3 answer types + `Decision` + `decision_from_wire` | 45 |
| `Classifier` — `StaticClassifier` + `SystemOneClassifier` (urllib wire) + protocol | 60 |
| **classifier total** | **145** |
| `judge` (`Judge`, `Verdict`, `bands`, `as_guardrail`, fail-closed) | **67** |

## 4. The union problem — the finding

**Python pays nothing.** Modelled as three `@dataclass`es (`Noul`/`Choice`/`Score`) each with a
`type: Literal[...] = "..."` default field and a differently-typed `criteria`
(`dict[Literal["true","false"],str] | None`, `dict[str,str]`, `list[str]`).
`Question = Union[Noul, Choice, Score]`. That is exactly the port's existing idiom —
`types.py` already uses `@dataclass` for `Request`/`Answer`/`ToolResult` and `Literal` for
`Answer.reason`, and `client.py` already carries `ClientStyle = Literal[...]`.

- **Serialization needed one 5-line hand-written `question_to_wire`**, not because of the union
  but because `dataclasses.asdict` would emit `"criteria": null` for `Noul`, and the fixture
  requires the key **absent**. Same helper handles all three arms.
- **`answers` (discriminated map)** is `dict[str, NoulAnswer|ChoiceAnswer|ScoreAnswer]` built by a
  9-line `match`-style dispatch on `raw["type"]`, raising `DecisionError` on an unknown tag.
  Read-out is `isinstance` narrowing, which type-checkers follow on a `Union` of dataclasses.
- `TypedDict` was the alternative: it would serialize with no helper (absent key = key not set)
  but gives dot-free `d["answers"]["x"]["noul"]` access and no runtime discrimination. Plain
  dicts give no typing at all. **Dataclasses win** — they match the port and they are what the
  `Guardrail`/`Verdict` boundary already returns elsewhere.
- Cost of the union in Python: **0 extra types, 14 lines of dispatch.** Whatever Go/Java/C#
  pay here, Python is not the constraint.

## 5. Canonical JSON

**Native, no hand-rolling.**
`json.dumps(v, sort_keys=True, separators=(",",":"), ensure_ascii=False).encode("utf-8")`
reproduces `fixture/request.json` byte-for-byte on the first run.

- `sort_keys` sorts recursively by Python `str <` = code-point order = ASCII order for these keys.
- `sort_keys` touches **objects only**; `score.criteria` list order is untouched (pinned by a test
  that also asserts a reversed array stays reversed).
- No trailing newline (`json.dumps` adds none); `ensure_ascii=False` for the UTF-8 rule.

**Float risk: low but not zero.** Python uses `repr`-shortest round-trip, so `1.21` → `1.21`,
`0.98` → `0.98`, and the integral probability `0` → `0` (not `0.0`) because it stays an `int`
through `json.loads`. **One real trap:** small magnitudes flip to exponent form —
`0.000016716` re-emits as `1.6716e-05`. That is only in `usage.cost` (response-side), so it does
not touch the request conformance claim, but any port that echoes a `Decision` back out would
drift here. Worth pinning in the spec: canonical form is defined for the **request** only.

## 6. Friction

1. **`Guardrail` is sync in this port.** `agents/loop.py:103` calls `rail(ev)` and never awaits,
   while the rest of the port is `async`. A judge that does HTTP therefore must either block the
   loop or be given an async form — matching ADR 0020's "`Guardrail` gains an async/ctx form".
   **That contract change is required for Python, not optional.** My spike's classifier is sync
   for this reason; a real one wants `async def evaluate` + an awaited guardrail.
2. **No httpx needed.** `urllib.request` does the POST in ~10 lines, same as `client.py:_post`.
   Adding httpx would be a dependency regression; do not.
3. **`ask` has nowhere to go.** `Guardrail` is a two-valued type (`"" ⇒ allow`, string ⇒ deny), so
   the ASK band is currently emitted as a deny with an `"ask: "` prefix. The §10 `Pending` routing
   the ADR describes is the missing piece, and it is a real contract change in all seven ports.
4. `StaticClassifier` keying recordings by canonical request bytes made gate 3 trivial and gave
   gate 1 a second, free assertion — recommend that as the fixture design for the other six.

## 7. Live call

Ran (key read from `OPENROUTER_API_KEY` at call time, never printed):
`POST https://openrouter.ai/api/v1/systemone`, model `typesafe/jev-1.13`.

- **Latency: 468 ms** (single call, cold).
- Shape **matched exactly** — parsed with no changes: `model typesafe/jev-1.13-20260917`,
  `is_refund_request.noul 0.98`, `department.choice "shipping"` (0.64/0.36/0), `urgency.score 1.25`
  with the 3-entry legend, `usage {input_tokens:398, output_tokens:72, cost:1.6716e-05}`.
- Values drift run-to-run vs the recorded fixture (`urgency` 1.21 → 1.25, `department` 0.61 → 0.64),
  which confirms the fixture must be a `static` recording and never a live assertion.
