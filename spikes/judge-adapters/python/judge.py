"""Spike: a thin, simple judge layer over toolnexus.Classifier (§8B).

    d = await ask(c, {"role": role, "message_received": msg}, [
        noul("is_appropriate", "Does the message contain inappropriate language?"),
        noul("does_this_help", "Does this help donkey kong win?"),
    ])
    d["is_appropriate"].band   # "yes" | "no" | "uncertain"
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Optional, Sequence, Union

from toolnexus import ChoiceAnswer, ChoiceQuestion, NoulAnswer, NoulQuestion, ScoreAnswer, ScoreQuestion
from toolnexus.classifier import NoulCriteria
from toolnexus.types import Request


@dataclass(frozen=True)
class Bands:
    low: float = 0.30
    high: float = 0.70

    def noul(self, p: float) -> str:  # cut points exclusive on the confident side
        return "no" if p < self.low else "yes" if p > self.high else "uncertain"

    def sure(self, conf: float, near_uniform: bool = False) -> bool:
        return conf > self.high and not near_uniform


DEFAULT = Bands()


# --- builders: (name, question) pairs, an ordered list --------------------------
def noul(name: str, instructions: str, true: str = "", false: str = ""):
    crit = NoulCriteria(true=true, false=false) if (true or false) else None
    return name, NoulQuestion(instructions, crit)


def choice(name: str, instructions: str, options: Mapping[str, str]):
    return name, ChoiceQuestion(instructions, dict(options))


def score(name: str, instructions: str, levels: Sequence[str]):
    return name, ScoreQuestion(instructions, list(levels))


def questions(qs) -> dict:
    out: dict = {}
    for name, q in qs:
        if name in out:
            raise ValueError(f'duplicate question name "{name}"')
        out[name] = q
    return out


def state(context: str, message: str, **extra: Any) -> dict:
    return {"context": context, "message": message, **extra}


# --- ask --------------------------------------------------------------------------
@dataclass
class Answer:
    band: str  # yes | no | uncertain  (choice/score: yes = sure, uncertain = not)
    raw: Any
    value: Any = None  # noul prob, choice id, score


def _answer(a, b: Bands) -> Answer:
    if isinstance(a, NoulAnswer):
        return Answer(b.noul(a.noul), a, a.noul)
    if isinstance(a, ChoiceAnswer):
        return Answer("yes" if b.sure(a.confidence, a.near_uniform) else "uncertain", a, a.choice)
    return Answer("yes" if b.sure(a.confidence) else "uncertain", a, a.score)


async def ask(c, st: Mapping, qs, bands: Bands = DEFAULT) -> dict[str, Answer]:
    d = await c.evaluate(dict(st), questions(qs))
    return {k: _answer(a, bands) for k, a in d.answers.items()}


# --- gate -------------------------------------------------------------------------
@dataclass
class Rule:
    question: str
    action: str
    below: Optional[float] = None
    at_least: Optional[float] = None
    is_: Optional[str] = None
    target: str = ""


@dataclass
class Outcome:
    action: str = ""
    target: str = ""
    escalated: bool = False
    request: Optional[Request] = None
    answers: dict = field(default_factory=dict)


def _check(ans: Mapping[str, Answer], r: Rule) -> tuple[bool, str]:
    a = ans.get(r.question)
    if a is None:
        return False, "missing answer"
    if r.is_ is not None:
        if not isinstance(a.raw, ChoiceAnswer):
            return False, "is-rule on non-choice answer"
        return (False, f"choice {a.value!r} not sure") if a.band == "uncertain" else (a.value == r.is_, "")
    if isinstance(a.raw, ChoiceAnswer):
        return False, "numeric rule on choice answer"
    if a.band == "uncertain":
        return False, f"{r.question}={a.value} uncertain"
    return (a.value < r.below, "") if r.below is not None else (a.value >= r.at_least, "")


async def gate(c, st: Mapping, qs, rules: Sequence[Rule], bands: Bands = DEFAULT) -> Outcome:
    ans = await ask(c, st, qs, bands)
    for i, r in enumerate(rules):
        fired, reason = _check(ans, r)
        if reason:
            req = Request(id=f"gate:{i}:{r.question}", kind="input",
                          prompt=f"Classifier is unsure about {r.question!r} ({reason}).",
                          data={"question": r.question, "reason": reason, "answers": ans})
            return Outcome("needs_input", "", True, req, ans)
        if fired:
            return Outcome(r.action, r.target, False, None, ans)
    return Outcome(answers=ans)
