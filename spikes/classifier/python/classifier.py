"""Spike — `Classifier` (ADR 0020), Python port idiom. THROWAWAY: nothing imports this.

Stdlib only (``json`` + ``urllib``), mirroring ``python/src/toolnexus/client.py``:
dataclasses for the wire types, ``Literal`` discriminants, the key read from the
environment by NAME at call time and never logged.
"""
from __future__ import annotations

import json
import os
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal, Mapping, Optional, Protocol, Union

# --------------------------------------------------------------------------- questions


@dataclass
class Noul:
    """A probability question. ``criteria`` is ABSENT on the wire."""
    instructions: str
    criteria: Optional[dict[Literal["true", "false"], str]] = None
    type: Literal["noul"] = "noul"


@dataclass
class Choice:
    """A pick-one question. ``criteria`` is an OBJECT {name: description}."""
    instructions: str
    criteria: dict[str, str] = field(default_factory=dict)
    type: Literal["choice"] = "choice"


@dataclass
class Score:
    """A graded question. ``criteria`` is an ARRAY — order IS the level numbering."""
    instructions: str
    criteria: list[str] = field(default_factory=list)
    type: Literal["score"] = "score"


Question = Union[Noul, Choice, Score]


def question_to_wire(q: Question) -> dict[str, Any]:
    """dataclass -> wire dict. ``criteria`` is omitted when None (the Noul case)."""
    out: dict[str, Any] = {"instructions": q.instructions, "type": q.type}
    if q.criteria is not None and (q.type == "noul" or q.criteria):
        out["criteria"] = q.criteria
    return out


# --------------------------------------------------------------------------- canonical


def canonical_json(value: Any) -> bytes:
    """§ Canonical request form: keys sorted recursively, arrays untouched, compact
    separators, UTF-8, no trailing newline."""
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def build_request(model: str, state: Any, questions: Mapping[str, Question]) -> bytes:
    return canonical_json(
        {
            "model": model,
            "state": state,
            "questions": {k: question_to_wire(q) for k, q in questions.items()},
        }
    )


# --------------------------------------------------------------------------- answers


@dataclass
class NoulAnswer:
    noul: float
    type: Literal["noul"] = "noul"


@dataclass
class ChoiceAnswer:
    choice: str
    probabilities: dict[str, float] = field(default_factory=dict)
    confidence: Optional[float] = None
    type: Literal["choice"] = "choice"


@dataclass
class ScoreAnswer:
    score: float
    legend: dict[str, str] = field(default_factory=dict)
    probabilities: dict[str, float] = field(default_factory=dict)
    confidence: Optional[float] = None
    type: Literal["score"] = "score"


Answer = Union[NoulAnswer, ChoiceAnswer, ScoreAnswer]


@dataclass
class Decision:
    model: str
    answers: dict[str, Answer]
    usage: dict[str, Any] = field(default_factory=dict)


class DecisionError(Exception):
    pass


def _answer_from_wire(raw: Mapping[str, Any]) -> Answer:
    kind = raw.get("type")
    if kind == "noul":
        return NoulAnswer(noul=float(raw["noul"]))
    if kind == "choice":
        return ChoiceAnswer(
            choice=raw["choice"],
            probabilities=dict(raw.get("probabilities") or {}),
            confidence=raw.get("confidence"),
        )
    if kind == "score":
        return ScoreAnswer(
            score=float(raw["score"]),
            legend=dict(raw.get("legend") or {}),
            probabilities=dict(raw.get("probabilities") or {}),
            confidence=raw.get("confidence"),
        )
    raise DecisionError(f"unknown answer type: {kind!r}")


def decision_from_wire(raw: Mapping[str, Any]) -> Decision:
    return Decision(
        model=raw["model"],
        answers={k: _answer_from_wire(v) for k, v in (raw.get("answers") or {}).items()},
        usage=dict(raw.get("usage") or {}),
    )


# --------------------------------------------------------------------------- backends


class Classifier(Protocol):
    def evaluate(self, state: Any, questions: Mapping[str, Question]) -> Decision: ...


@dataclass
class StaticClassifier:
    """`static` backend — a recorded Decision per canonical request. What CI runs."""
    model: str
    recordings: dict[bytes, Any]

    @classmethod
    def from_dir(cls, directory: Path, model: str, pairs: list[tuple[str, str]]) -> "StaticClassifier":
        rec: dict[bytes, Any] = {}
        for req, resp in pairs:
            key = (directory / req).read_bytes().rstrip(b"\n")
            rec[key] = json.loads((directory / resp).read_text())
        return cls(model=model, recordings=rec)

    def evaluate(self, state: Any, questions: Mapping[str, Question]) -> Decision:
        body = build_request(self.model, state, questions)
        try:
            return decision_from_wire(self.recordings[body])
        except KeyError:
            raise DecisionError("no recording for this request") from None


@dataclass
class SystemOneClassifier:
    """`systemone` backend over raw urllib — no SDK, no third-party dependency."""
    model: str = "typesafe/jev-1.13"
    base_url: str = "https://openrouter.ai/api/v1"
    api_key_env: str = "OPENROUTER_API_KEY"
    timeout: float = 10.0

    def evaluate(self, state: Any, questions: Mapping[str, Question]) -> Decision:
        body = build_request(self.model, state, questions)
        req = urllib.request.Request(
            f"{self.base_url}/systemone",
            data=body,
            method="POST",
            headers={
                "content-type": "application/json",
                # read by NAME at call time; never logged
                "authorization": f"Bearer {os.environ[self.api_key_env]}",
            },
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            return decision_from_wire(json.loads(resp.read().decode("utf-8")))


def timed_evaluate(c: Classifier, state: Any, questions: Mapping[str, Question]) -> tuple[Decision, float]:
    t0 = time.monotonic()
    d = c.evaluate(state, questions)
    return d, (time.monotonic() - t0) * 1000.0
