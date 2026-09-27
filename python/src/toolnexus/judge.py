"""Simple judgments over any :class:`Classifier` (SPEC §8B "Simple judgments").

    answers = await ask(c, state(role, {"message_received": msg}), [
        noul("is_appropriate", "Does message_received contain inappropriate language?"),
        noul("does_this_help", "Does message_received help donkey kong win?"),
    ])
    answers["is_appropriate"].band   # "yes" | "no" | "uncertain"

A thin layer: builders produce exactly the §8B ``evaluate`` inputs, so the wire
request is byte-identical to hand-written maps. Nothing here changes the wire.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Optional, Sequence, Union

from .classifier import (
    ChoiceAnswer,
    ChoiceQuestion,
    Classifier,
    ClassifierError,
    Decision,
    DecisionAnswer,
    NoulAnswer,
    NoulCriteria,
    NoulQuestion,
    Question,
    RecordedDecision,
    ScoreQuestion,
    create_classifier,
)
from .types import Request

# --------------------------------------------------------------------------- #
# Bands
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Bands:
    """Cut-points, EXCLUSIVE on the confident side: exactly ``low`` or ``high`` is uncertain."""

    low: float = 0.30
    high: float = 0.70

    def noul(self, p: float) -> str:
        return "no" if p < self.low else "yes" if p > self.high else "uncertain"

    def sure(self, confidence: float, near_uniform: bool = False) -> bool:
        return confidence > self.high and not near_uniform


DEFAULT_BANDS = Bands()

# --------------------------------------------------------------------------- #
# Builders
# --------------------------------------------------------------------------- #

NamedQuestion = tuple[str, Question]


def noul(name: str, instructions: str, true: str = "", false: str = "") -> NamedQuestion:
    crit = NoulCriteria(true=true, false=false) if (true or false) else None
    return name, NoulQuestion(instructions, crit)


def choice(name: str, instructions: str, options: Mapping[str, str]) -> NamedQuestion:
    return name, ChoiceQuestion(instructions, dict(options))


def score(name: str, instructions: str, levels: Sequence[str]) -> NamedQuestion:
    return name, ScoreQuestion(instructions, list(levels))


def questions(qs: Sequence[NamedQuestion]) -> dict[str, Question]:
    """Ordered list -> the §8B question map. A duplicate name is an error naming it."""
    out: dict[str, Question] = {}
    for name, q in qs:
        if name in out:
            raise ClassifierError(f'duplicate question name "{name}"')
        out[name] = q
    return out


def state(role: str, data: Any) -> dict[str, Any]:
    """``role`` next to the data's fields; a non-object value goes under ``data``."""
    if isinstance(data, Mapping):
        return {"role": role, **data}
    return {"role": role, "data": data}


def context(ctx: str, message: str, extra: Optional[Mapping[str, Any]] = None) -> dict[str, Any]:
    """Sugar: ``{context, message}`` merged with ``extra``."""
    return {"context": ctx, "message": message, **(extra or {})}


# --------------------------------------------------------------------------- #
# ask
# --------------------------------------------------------------------------- #


@dataclass
class JudgeAnswer:
    """One answer with its reading. Noul: ``band``. Choice/score: ``sure``.

    Named apart from §10 ``Answer`` (the suspension resolution)."""

    raw: DecisionAnswer
    band: Optional[str] = None  # noul only: yes | no | uncertain
    sure: Optional[bool] = None  # choice / score only

    @property
    def type(self) -> str:
        return self.raw.type

    @property
    def uncertain(self) -> bool:
        return self.band == "uncertain" if self.band is not None else not self.sure

    def value(self) -> float:
        """Noul probability, score value, or choice confidence."""
        a = self.raw
        if isinstance(a, NoulAnswer):
            return a.noul
        if isinstance(a, ChoiceAnswer):
            return a.confidence
        return a.score

    def choice(self) -> str:
        if not isinstance(self.raw, ChoiceAnswer):
            raise ClassifierError(f"judge: answer is a {self.raw.type} answer, not choice")
        return self.raw.choice


def _read(a: DecisionAnswer, b: Bands) -> JudgeAnswer:
    if isinstance(a, NoulAnswer):
        return JudgeAnswer(a, band=b.noul(a.noul))
    if isinstance(a, ChoiceAnswer):
        return JudgeAnswer(a, sure=b.sure(a.confidence, a.near_uniform))
    return JudgeAnswer(a, sure=b.sure(a.confidence))


def read(decision: Decision, bands: Optional[Bands] = None) -> dict[str, JudgeAnswer]:
    b = bands or DEFAULT_BANDS
    return {k: _read(a, b) for k, a in decision.answers.items()}


async def ask(
    classifier: Classifier,
    st: Mapping[str, Any],
    qs: Sequence[NamedQuestion],
    bands: Optional[Bands] = None,
) -> dict[str, JudgeAnswer]:
    d = await classifier.evaluate(dict(st), questions(qs))
    return read(d, bands)


# --------------------------------------------------------------------------- #
# gate / Policy
# --------------------------------------------------------------------------- #


@dataclass
class Rule:
    """``below`` / ``at_least`` (numeric on noul/score) or ``is_`` (choice)."""

    question: str
    action: str
    below: Optional[float] = None
    at_least: Optional[float] = None
    is_: Optional[str] = None
    target: str = ""


@dataclass
class Policy:
    rules: Sequence[Rule]
    default: str = ""
    bands: Optional[Bands] = None
    skip_uncertain: bool = False


@dataclass
class Outcome:
    action: str = ""
    target: str = ""
    escalated: bool = False
    request: Optional[Request] = None
    answers: dict[str, JudgeAnswer] = field(default_factory=dict)


def _check(ans: Mapping[str, JudgeAnswer], r: Rule) -> tuple[bool, str, bool]:
    """-> (fired, reason, uncertain). A non-empty reason means escalate."""
    a = ans.get(r.question)
    if a is None:
        return False, f"missing answer {r.question!r}", False
    if r.is_ is not None:
        if not isinstance(a.raw, ChoiceAnswer):
            return False, f"is-rule on {a.type} answer {r.question!r}", False
        if not a.sure:
            return False, f"choice {r.question}={a.raw.choice!r} not sure", True
        return a.raw.choice == r.is_, "", False
    if isinstance(a.raw, ChoiceAnswer):
        return False, f"numeric rule on choice answer {r.question!r}", False
    v = a.raw.noul if isinstance(a.raw, NoulAnswer) else a.raw.score
    if a.uncertain:
        return False, f"{r.question}={v} uncertain", True
    if r.below is not None:
        return v < r.below, "", False
    if r.at_least is not None:
        return v >= r.at_least, "", False
    return False, f"rule on {r.question!r} has no below / at_least / is", False


def _escalate(qname: str, reason: str, ans: dict[str, JudgeAnswer], rid: str) -> Outcome:
    req = Request(
        id=rid,
        kind="input",
        prompt=f"Classifier is unsure about {qname!r} ({reason}).",
        data={"question": qname, "reason": reason, "answers": ans},
    )
    return Outcome("needs_input", "", True, req, ans)


def decide(ans: dict[str, JudgeAnswer], policy: Policy) -> Outcome:
    """Apply a policy to already-read answers: first match wins, then the default."""
    for i, r in enumerate(policy.rules):
        fired, reason, uncertain = _check(ans, r)
        if reason:
            if uncertain and policy.skip_uncertain:
                continue
            return _escalate(r.question, reason, ans, f"gate:{i}:{r.question}")
        if fired:
            return Outcome(r.action, r.target, False, None, ans)
    return Outcome(answers=ans)


async def gate(
    classifier: Classifier,
    st: Mapping[str, Any],
    qs: Sequence[NamedQuestion],
    rules: Union[Sequence[Rule], Policy],
    bands: Optional[Bands] = None,
) -> Outcome:
    """Rules (first-match) or a :class:`Policy`. With a bare rule list no-rule-fired is
    ``action == ""``; a Policy declares it (non-empty ``default``, else escalate)."""
    if isinstance(rules, Policy):
        ans = await ask(classifier, st, qs, bands or rules.bands)
        out = decide(ans, rules)
        if out.action or out.escalated:
            return out
        if rules.default:
            return Outcome(rules.default, "", False, None, ans)
        return _escalate("", "no rule fired", ans, "gate:default")
    ans = await ask(classifier, st, qs, bands)
    return decide(ans, Policy(rules=list(rules)))


# --------------------------------------------------------------------------- #
# Static helper + Tape
# --------------------------------------------------------------------------- #


def static_classifier(
    *records: tuple[Mapping[str, Any], Union[Mapping[str, Question], Sequence[NamedQuestion]], Any],
    model: Optional[str] = None,
) -> Classifier:
    """One line from recorded decisions: each record is ``(state, questions, response)``;
    ``questions`` may be the §8B map or a builder list."""
    decs = [
        RecordedDecision(
            state=dict(s), questions=q if isinstance(q, Mapping) else questions(q), response=resp
        )
        for s, q, resp in records
    ]
    return create_classifier(style="static", model=model, decisions=decs)


def decision_wire(d: Decision) -> dict[str, Any]:
    """A decision back to its §8B response shape (``near_uniform`` is re-derived on decode)."""
    answers: dict[str, Any] = {}
    for k, a in d.answers.items():
        if isinstance(a, NoulAnswer):
            answers[k] = {"type": "noul", "noul": a.noul}
        elif isinstance(a, ChoiceAnswer):
            answers[k] = {"type": "choice", "choice": a.choice,
                          "probabilities": dict(a.probabilities), "confidence": a.confidence}
        else:
            answers[k] = {"type": "score", "score": a.score, "legend": dict(a.legend),
                          "probabilities": dict(a.probabilities), "confidence": a.confidence}
    return {"model": d.model, "answers": answers, "calibrated": d.calibrated}


class Tape:
    """Record live decisions by call name; replay them offline through ``static``.

    ``Tape(live)`` records; ``Tape(recorded=tape.recorded)`` replays. A replay miss is
    an error naming the key and sends no request."""

    def __init__(self, live: Optional[Classifier] = None,
                 recorded: Optional[Mapping[str, Mapping[str, Any]]] = None,
                 model: Optional[str] = None) -> None:
        self.live = live
        self.model = model or (live.model if live else None)
        #: call name -> {"state": ..., "questions": {wire}, "response": {wire}}
        self.recorded: dict[str, dict[str, Any]] = dict(recorded or {})

    def call(self, name: str) -> Classifier:
        """The classifier to use for call ``name``: recording (live) or replaying."""
        if self.live is not None:
            live = self.live

            async def rec(st: Any, qs: Mapping[str, Question]) -> Decision:
                from .classifier import questions_wire
                d = await live.evaluate(st, qs)
                self.recorded[name] = {"state": st, "questions": questions_wire(qs),
                                       "response": decision_wire(d)}
                return d

            return create_classifier(style="custom", model=self.model, evaluate=rec)
        entry = self.recorded.get(name)
        if entry is None:
            async def miss(st: Any, qs: Mapping[str, Question]) -> Decision:
                raise ClassifierError(f"judge: tape has no recorded decision for call {name!r}")

            return create_classifier(style="custom", model=self.model, evaluate=miss)
        return static_classifier(
            (entry["state"], {k: _q_from_wire(v) for k, v in entry["questions"].items()},
             entry["response"]),
            model=self.model,
        )


def _q_from_wire(w: Mapping[str, Any]) -> Question:
    t = w["type"]
    if t == "noul":
        c = w.get("criteria")
        return NoulQuestion(w["instructions"],
                            NoulCriteria(true=c["true"], false=c["false"]) if c is not None else None)
    if t == "choice":
        return ChoiceQuestion(w["instructions"], dict(w["criteria"]))
    return ScoreQuestion(w["instructions"], list(w["criteria"]))


question_from_wire = _q_from_wire
