"""Simple judgments (SPEC §8B, change add-judge-adapters) against the shared fixtures."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from toolnexus import (
    Bands,
    ClassifierError,
    Policy,
    Rule,
    Tape,
    ask,
    canonical_request,
    choice,
    context,
    create_classifier,
    gate,
    noul,
    question_from_wire,
    questions,
    questions_wire,
    score,
    state,
    static_classifier,
)

FIX = Path(__file__).resolve().parents[2] / "examples" / "judge" / "adapters"
STATE = json.loads((FIX / "state-cases.json").read_text())
GATE = json.loads((FIX / "gate-cases.json").read_text())
MODEL = "m"


def run(coro):
    return asyncio.run(coro)


def build(q):
    if q["kind"] == "noul":
        return noul(q["name"], q["instructions"])
    if q["kind"] == "choice":
        return choice(q["name"], q["instructions"], q["options"])
    return score(q["name"], q["instructions"], q["levels"])


@pytest.mark.parametrize("case", STATE["cases"], ids=lambda c: c["name"])
def test_state_cases(case):
    qs = [build(q) for q in case["questions"]]
    if "wantError" in case:
        with pytest.raises(ClassifierError, match=case["wantError"]):
            questions(qs)
        return
    if "roleState" in case:
        st = state(case["roleState"]["role"], case["roleState"]["data"])
    elif "context" in case:
        c = case["context"]
        st = context(c["context"], c["message"], c.get("extra"))
    else:
        st = dict(case["state"])
    assert st == case["wantState"]
    assert questions_wire(questions(qs)) == case["wantQuestions"]


def gate_questions():
    return [(k, question_from_wire(v)) for k, v in GATE["questions"].items()]


def gate_rules(src=None):
    return [
        Rule(r["question"], r["action"], below=r.get("below"), at_least=r.get("at_least"),
             is_=r.get("is"), target=r.get("target", ""))
        for r in (src or GATE["rules"])
    ]


@pytest.mark.parametrize("case", GATE["cases"], ids=lambda c: c["name"])
def test_gate_cases(case):
    st = {"case": case["name"]}
    qs = gate_questions()
    c = static_classifier((st, qs, {"model": MODEL, "answers": case["answers"]}), model=MODEL)
    bands = Bands(**case["bands"]) if case["bands"] else None
    if "policy" in case:
        p = case["policy"]
        rules = Policy(rules=gate_rules(case.get("rules")), default=p["default"], bands=bands,
                       skip_uncertain=p["skipUncertain"])
    else:
        rules = gate_rules(case.get("rules"))
    got = {}
    for k, a in run(ask(c, st, qs, bands)).items():
        e = {"value": a.value()}
        if a.type == "noul":
            e["band"] = a.band
        else:
            e["sure"] = a.sure
        if a.type == "choice":
            e["choice"] = a.choice()
        got[k] = e
    assert got == case["wantAnswers"]
    out = run(gate(c, st, qs, rules, bands))
    w = case["want"]
    assert (out.action, out.target, out.escalated) == (w["action"], w["target"], w["escalated"])
    if out.escalated:
        assert out.request.kind == "input"
        assert set(out.request.data) == {"question", "reason", "answers"}
        assert out.request.data["question"] == w["question"]
        if "reason" in w:
            assert out.request.data["reason"] == w["reason"]
        assert out.request.id == w["requestId"]


def test_builders_byte_identical_to_hand_written():
    hand = {
        "is_appropriate": {"type": "noul", "instructions": "Does message_received contain insults?"},
        "component": {"type": "choice", "instructions": "Which?", "criteria": {"a": "x", "b": "y"}},
        "fix": {"type": "score", "instructions": "How?", "criteria": ["lo", "hi"]},
    }
    built = questions([
        noul("is_appropriate", "Does message_received contain insults?"),
        choice("component", "Which?", {"a": "x", "b": "y"}),
        score("fix", "How?", ["lo", "hi"]),
    ])
    hand_q = {k: question_from_wire(v) for k, v in hand.items()}
    assert canonical_request(MODEL, built) == canonical_request(MODEL, hand_q)
    assert canonical_request(MODEL, built) == json.dumps(
        {"model": MODEL, "questions": hand}, sort_keys=True, separators=(",", ":")).encode()


def test_role_state_and_wrap():
    assert state("r", {"message_received": "hi"}) == {"role": "r", "message_received": "hi"}
    assert state("r", "plain text") == {"role": "r", "data": "plain text"}


def _noul(p):
    return {"model": MODEL, "answers": {"x": {"type": "noul", "noul": p}}}


def test_bands_and_value():
    qs = [noul("x", "q")]
    for p, want, b in [(0.30, "uncertain", None), (0.70, "uncertain", None),
                       (0.55, "yes", Bands(0.20, 0.50)), (0.96, "yes", None), (0.1, "no", None)]:
        c = static_classifier(({"s": 1}, qs, _noul(p)), model=MODEL)
        a = run(ask(c, {"s": 1}, qs, b))["x"]
        assert a.band == want and a.value() == p


def test_choice_value_and_pick():
    qs = [choice("c", "q", {"a": "x", "b": "y"})]
    resp = {"model": MODEL, "answers": {"c": {"type": "choice", "choice": "a", "confidence": 0.9,
                                              "probabilities": {"a": 0.9, "b": 0.1}}}}
    a = run(ask(static_classifier(({}, qs, resp), model=MODEL), {}, qs))["c"]
    assert a.sure is True and a.value() == 0.9 and a.choice() == "a"


def test_policy_default_and_no_rule_fired():
    qs = [noul("x", "q")]
    c = static_classifier(({}, qs, _noul(0.9)), model=MODEL)
    rules = [Rule("x", "fail", below=0.3)]
    out = run(gate(c, {}, qs, Policy(rules)))
    assert out.escalated and out.request.data["reason"] == "no rule fired"
    out = run(gate(c, {}, qs, Policy(rules, default="proceed")))
    assert (out.action, out.escalated) == ("proceed", False)


def test_policy_skip_uncertain():
    qs = [noul("x", "q"), noul("y", "q")]
    resp = {"model": MODEL, "answers": {"x": {"type": "noul", "noul": 0.5},
                                        "y": {"type": "noul", "noul": 0.9}}}
    c = static_classifier(({}, qs, resp), model=MODEL)
    rules = [Rule("x", "fail", below=0.3), Rule("y", "go", at_least=0.7)]
    assert run(gate(c, {}, qs, Policy(rules))).escalated
    out = run(gate(c, {}, qs, Policy(rules, skip_uncertain=True)))
    assert (out.action, out.escalated) == ("go", False)


def test_missing_answer_reason_names_the_key():
    qs = [noul("x", "q")]
    c = static_classifier(({}, qs, _noul(0.9)), model=MODEL)
    out = run(gate(c, {}, qs, [Rule("zz", "go", at_least=0.5)]))
    assert out.escalated
    assert out.request.data["reason"] == 'missing answer "zz"'
    assert out.request.data["question"] == "zz"


def test_tape_record_replay_and_miss():
    qs = [noul("x", "q")]
    live = static_classifier(({"s": 1}, qs, _noul(0.96)), model=MODEL)
    tape = Tape(live)
    run(ask(tape.call("plan"), {"s": 1}, qs))
    replay = Tape(recorded=json.loads(json.dumps(tape.recorded)), model=MODEL)
    assert run(ask(replay.call("plan"), {"s": 1}, qs))["x"].value() == 0.96
    with pytest.raises(ClassifierError, match='tape: no recorded decision for call "act"'):
        run(ask(replay.call("act"), {"s": 1}, qs))


def _batch_classifier():
    qs = questions([noul("x", "q")])
    recs = [({"i": i}, qs, _noul(p)) for i, p in [(0, 0.1), (2, 0.9)]]
    return static_classifier(*recs, model=MODEL), qs


def test_evaluate_batch_order():
    c, qs = _batch_classifier()
    c2 = static_classifier(({"i": 0}, qs, _noul(0.1)), ({"i": 1}, qs, _noul(0.5)),
                           ({"i": 2}, qs, _noul(0.9)), model=MODEL)
    ds = run(c2.evaluate_batch([{"i": 0}, {"i": 1}, {"i": 2}], qs))
    assert [d.noul("x").noul for d in ds] == [0.1, 0.5, 0.9]


def test_evaluate_batch_fails_closed_naming_index():
    c, qs = _batch_classifier()
    with pytest.raises(ClassifierError, match="state 1"):
        run(c.evaluate_batch([{"i": 0}, {"i": 1}, {"i": 2}], qs))


def test_evaluate_batch_empty_sends_nothing():
    calls = []
    c = create_classifier(style="custom", evaluate=lambda s, q: calls.append(s))
    with pytest.raises(ClassifierError):
        run(c.evaluate_batch([], questions([noul("x", "q")])))
    assert calls == []


def test_tape_miss_on_evaluate_not_construction_sends_nothing():
    replayer = Tape(recorded={}, model=MODEL).call("plan")  # never fails; replay has no live
    with pytest.raises(ClassifierError, match='^tape: no recorded decision for call "plan"$'):
        run(ask(replayer, {"s": 1}, [noul("x", "q")]))


def _delayed_batch(fail: set[int]):
    async def ev(st, q):
        await asyncio.sleep(0.01 * (3 - st["i"]))  # later states finish first
        if st["i"] in fail:
            raise ClassifierError(f"boom {st['i']}")
        from toolnexus.classifier import Decision, NoulAnswer
        return Decision(model=MODEL, answers={"x": NoulAnswer(noul=st["i"] / 10)})
    return create_classifier(style="custom", evaluate=ev)


def test_evaluate_batch_order_regardless_of_completion():
    ds = run(_delayed_batch(set()).evaluate_batch([{"i": 0}, {"i": 1}, {"i": 2}],
                                                  questions([noul("x", "q")])))
    assert [d.noul("x").noul for d in ds] == [0.0, 0.1, 0.2]


def test_evaluate_batch_names_lowest_failing_index():
    with pytest.raises(ClassifierError, match="state 0"):
        run(_delayed_batch({0, 2}).evaluate_batch([{"i": 0}, {"i": 1}, {"i": 2}],
                                                  questions([noul("x", "q")])))
