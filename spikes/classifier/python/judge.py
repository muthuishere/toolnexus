"""Spike — `judge` (ADR 0020 D4), Python port idiom. THROWAWAY.

``judge(on=..., ask=..., rule=bands(...))`` -> a Judge whose ``as_guardrail()``
returns the shape ``python/src/toolnexus/agents/loop.py:guarded_hooks`` already
consumes: a ``(event) -> str`` callable where ``""``/``"allow"`` means allow and any
other string is the deny reason.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Literal, Mapping, Optional

from classifier import Classifier, Question, ScoreAnswer

Band = Literal["allow", "ask", "deny"]


@dataclass
class Verdict:
    kept: Band
    evidence: dict[str, Any] = field(default_factory=dict)
    model: str = ""
    calibrated: bool = True
    reason: str = ""


# --------------------------------------------------------------------------- rules

Rule = Callable[["Judge", Any, Mapping[str, Any]], Verdict]


def bands(key: str, cuts: list[tuple[float, Band]], *, escalate: Optional[dict[str, float]] = None) -> Rule:
    """`bands` rule: put one score answer in a band; ``escalate`` lifts allow->ask when
    a noul answer exceeds its threshold. First matching cut wins (cuts are ordered)."""

    def rule(judge: "Judge", state: Any, questions: Mapping[str, Question]) -> Verdict:
        decision = judge.classifier.evaluate(state, questions)
        ans = decision.answers[key]
        assert isinstance(ans, ScoreAnswer)
        kept: Band = cuts[-1][1]
        for upper, band in cuts:
            if ans.score < upper:
                kept = band
                break
        evidence: dict[str, Any] = {key: ans.score, f"{key}.confidence": ans.confidence}
        for nkey, threshold in (escalate or {}).items():
            got = getattr(decision.answers[nkey], "noul", 0.0)
            evidence[nkey] = got
            if got >= threshold and kept == "allow":
                kept = "ask"
        reason = "" if kept == "allow" else f"{kept}: {key}={ans.score} ({_level(ans, ans.score)})"
        return Verdict(kept=kept, evidence=evidence, model=decision.model, reason=reason)

    return rule


def _level(ans: ScoreAnswer, score: float) -> str:
    return ans.legend.get(str(int(round(score))), "")


# --------------------------------------------------------------------------- judge


@dataclass
class Judge:
    classifier: Classifier
    on: Callable[[Any], Any]
    ask: Mapping[str, Question]
    rule: Rule
    fail_closed: bool = True

    def verdict(self, event: Any) -> Verdict:
        try:
            return self.rule(self, self.on(event), self.ask)
        except Exception as exc:  # fail-closed: an unreachable classifier denies
            if not self.fail_closed:
                return Verdict(kept="allow")
            return Verdict(kept="deny", reason=f"deny: classifier unavailable ({type(exc).__name__})")

    def as_guardrail(self, only: tuple[Band, ...] = ("ask", "deny")) -> Callable[[Any], str]:
        """-> the port's Guardrail shape. A judge can only move a call toward ask/deny:
        it never returns anything that could widen an earlier denial."""

        def guardrail(event: Any) -> str:
            v = self.verdict(event)
            return v.reason if v.kept in only else ""

        return guardrail


def judge(*, on: Callable[[Any], Any], ask: Mapping[str, Question], rule: Rule, fail_closed: bool = True):
    def build(classifier: Classifier) -> Judge:
        return Judge(classifier=classifier, on=on, ask=ask, rule=rule, fail_closed=fail_closed)

    return build
