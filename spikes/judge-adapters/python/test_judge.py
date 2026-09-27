import asyncio, json, pathlib
import pytest
from toolnexus import create_classifier
from toolnexus.classifier import RecordedDecision
import judge as j

SHARED = pathlib.Path(__file__).parent.parent / "shared"
STATE = json.loads((SHARED / "state-cases.json").read_text())
GATE = json.loads((SHARED / "gate-cases.json").read_text())


def build(q):
    k = q["kind"]
    if k == "noul": return j.noul(q["name"], q["instructions"])
    if k == "choice": return j.choice(q["name"], q["instructions"], q["options"])
    return j.score(q["name"], q["instructions"], q["levels"])


def wire(q):
    w = {"type": q.type, "instructions": q.instructions}
    if q.type == "noul":
        if q.criteria is not None: w["criteria"] = {"true": q.criteria.true, "false": q.criteria.false}
    else:
        w["criteria"] = q.criteria
    return w


@pytest.mark.parametrize("case", STATE["cases"], ids=lambda c: c["name"])
def test_state(case):
    qs = [build(q) for q in case["questions"]]
    if "wantError" in case:
        with pytest.raises(ValueError, match=case["wantError"]): j.questions(qs)
        return
    st = case["state"] if "state" in case else j.state(case["context"]["context"], case["context"]["message"], **case["context"].get("extra", {}))
    assert st == case["wantState"]
    got = j.questions(qs)
    assert list(got) == [q["name"] for q in case["questions"]]
    assert {k: wire(v) for k, v in got.items()} == case["wantQuestions"]


def from_wire(name, w):
    if w["type"] == "noul":
        c = w.get("criteria") or {}
        return j.noul(name, w["instructions"], c.get("true", ""), c.get("false", ""))
    if w["type"] == "choice": return j.choice(name, w["instructions"], w["criteria"])
    return j.score(name, w["instructions"], w["criteria"])


QS = [from_wire(k, v) for k, v in GATE["questions"].items()]
RULES = [j.Rule(r["question"], r["action"], r.get("below"), r.get("at_least"), r.get("is"), r.get("target", "")) for r in GATE["rules"]]
ST = {"report": "checkout 500"}


def test_default_bands():
    assert (j.DEFAULT.low, j.DEFAULT.high) == (GATE["defaultBands"]["low"], GATE["defaultBands"]["high"])


@pytest.mark.parametrize("case", GATE["cases"], ids=lambda c: c["name"])
def test_gate(case):
    c = create_classifier(style="static", decisions=[RecordedDecision(ST, j.questions(QS), {"model": "m", "answers": case["answers"]})])
    b = j.Bands(**case["bands"]) if case["bands"] else j.DEFAULT
    out = asyncio.run(j.gate(c, ST, QS, RULES, b))
    assert {"action": out.action, "target": out.target, "escalated": out.escalated} == case["want"]
    if out.escalated:
        assert out.request.kind == "input" and set(out.request.data) == {"question", "reason", "answers"}


def test_ask_video():
    qs = [j.noul("is_appropriate", "inappropriate?"), j.noul("does_this_help", "help DK win?")]
    st = {"role": "DK", "message_received": "jump off the stage now"}
    c = create_classifier(style="static", decisions=[RecordedDecision(st, j.questions(qs), {"answers": {
        "is_appropriate": {"type": "noul", "noul": 0.05}, "does_this_help": {"type": "noul", "noul": 0.5}}})])
    d = asyncio.run(j.ask(c, st, qs))
    assert (d["is_appropriate"].band, d["does_this_help"].band) == ("no", "uncertain")
