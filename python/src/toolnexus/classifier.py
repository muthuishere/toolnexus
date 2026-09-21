"""``Classifier`` — the contract for a JUDGMENT (``SPEC.md`` §8B).

``Tool`` is the contract for an **action**; ``Classifier`` is the contract for a
**judgment**. A System One model takes a state plus pre-declared, typed questions
and returns calibrated answers with no free text. It has no messages, no tool
calling and no streaming, so it never enters the §8 client loop and is never
selected as a model for ``run``/``ask``.

**A classifier interprets; it never authorises.** Schema validity is not
correctness: a decision can be confidently wrong, and "cannot hallucinate" means
only that the returned value is in the declared schema. Numeric limits,
permission checks and allowlists stay in code. Nothing here is a security
control.

Stdlib only — ``json`` + ``urllib``, exactly like ``client.py``. The wire is one
POST, so no port takes a vendor SDK for it. The §8 error tiers, the
``Retry-After`` delay-seconds rule and the ``on_metric`` sink are **reused**
verbatim: there is no second retry policy here.

A host that constructs no ``Classifier`` observes byte-identical behaviour to a
build without this module.
"""

from __future__ import annotations

import asyncio
import inspect
import json
import math
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Literal, Mapping, Optional, Protocol, Union

from .client import Client, ErrorClassifier, ErrorInfo, MetricEvent, OnMetric, _parse_retry_after, _RETRYABLE
from .toolkit import Toolkit

__all__ = [
    "DEFAULT_CLASSIFIER_BASE_URL",
    "DEFAULT_CLASSIFIER_MODEL",
    "DEFAULT_CLASSIFIER_API_KEY_ENV",
    "DEFAULT_CLASSIFIER_TIMEOUT",
    "MAX_CHOICE_OPTIONS",
    "MIN_SCORE_LEVELS",
    "MAX_SCORE_LEVELS",
    "NEAR_UNIFORM_TOLERANCE",
    "METRIC_CLASSIFIER_EVALUATE",
    "METRIC_CLASSIFIER_WARNING",
    "ClassifierError",
    "ClassifierStyle",
    "NoulCriteria",
    "NoulQuestion",
    "ChoiceQuestion",
    "ScoreQuestion",
    "Question",
    "choice_over",
    "canonical_request",
    "canonical_json",
    "near_uniform",
    "NoulAnswer",
    "ChoiceAnswer",
    "ScoreAnswer",
    "DecisionAnswer",
    "ClassifierUsage",
    "Decision",
    "RecordedDecision",
    "ClassifierResponse",
    "ClassifierTransport",
    "Classifier",
    "create_classifier",
]

# --------------------------------------------------------------------------- #
# Constants
# --------------------------------------------------------------------------- #

#: The System One endpoint base. OpenRouter (``https://openrouter.ai/api/v1``)
#: serves this wire today; self-hosted implementations speak it too.
DEFAULT_CLASSIFIER_BASE_URL = "https://api.typesafe.ai/v1"
#: The floating model alias. Pin it (e.g. ``jev-1.13.0``) once thresholds are tuned.
DEFAULT_CLASSIFIER_MODEL = "jev-latest"
#: The NAME of the env var holding the credential — never the value.
DEFAULT_CLASSIFIER_API_KEY_ENV = "TYPESAFE_API_KEY"
#: Seconds. Bounds ONE request: a classifier has no loop to bound.
DEFAULT_CLASSIFIER_TIMEOUT = 10.0

#: Client-side cap on a ``choice``'s named options (§8B).
MAX_CHOICE_OPTIONS = 255
#: Bounds on a ``score`` rubric (§8B).
MIN_SCORE_LEVELS = 2
MAX_SCORE_LEVELS = 10

#: ABSOLUTE tolerance on ``max|p - 1/n|``, compared INCLUSIVELY (§8B). Pinned
#: across every port; ``examples/judge/near-uniform.json`` pins both sides.
NEAR_UNIFORM_TOLERANCE = 0.05

#: The two events this module emits into the SAME §8 ``on_metric`` sink. Neither
#: is folded into the Prometheus registry, so ``Client.metrics()`` text is
#: unchanged by the existence of a classifier.
METRIC_CLASSIFIER_EVALUATE = "classifier.evaluate"
METRIC_CLASSIFIER_WARNING = "classifier.warning"

#: Default retry budget, matching Go's ``retries <= 0 ⇒ 2``.
_DEFAULT_RETRIES = 2

ClassifierStyle = Literal["systemone", "llm", "custom", "static"]


class ClassifierError(Exception):
    """Every failure this module raises. Never carries a credential value, an
    expanded header value, or the body of a 401/403 (a gateway happily reflects a
    bad ``Authorization`` header into its own error text)."""


# --------------------------------------------------------------------------- #
# Questions
# --------------------------------------------------------------------------- #
#
# The set is CLOSED: noul, choice, score. They differ only in what ``criteria`` is
# on the wire — absent, an object, or an ordered array — so each carries a literal
# ``type`` discriminant and a projection to a plain dict.


@dataclass
class NoulCriteria:
    """Labels the true and false cases of a noul question.

    Absent and empty are DIFFERENT values and both are preserved on the wire,
    which is why ``NoulQuestion.criteria`` is ``Optional``: ``None`` omits
    ``criteria`` entirely, while ``NoulCriteria()`` emits ``{"false":"","true":""}``.
    """

    true: str = ""
    false: str = ""


@dataclass
class NoulQuestion:
    """The probability that a statement holds, one number in ``0..1``.

    It reports NO confidence: the number *is* the answer.
    """

    instructions: str
    #: Optional. ``None`` ⇒ the field is ABSENT from the request, not ``null`` and
    #: not ``{}``.
    criteria: Optional[NoulCriteria] = None
    type: Literal["noul"] = "noul"


@dataclass
class ChoiceQuestion:
    """One option from a named set, 1..255 options.

    THE ENCODING OBLIGATION IS THE CALLER'S (§8B, ``docs/adr/0021``):
    ``criteria[id]`` is the only thing that differentiates one option from another
    to the model. Passing the id itself, an empty string, or one value repeated is
    schema-valid, returns HTTP 200 and a well-formed distribution — and ranks at
    chance (measured: 17 apples described by consequence, 0/1/0 described by id).
    """

    instructions: str
    #: option id -> what picking it would MEAN.
    criteria: dict[str, str] = field(default_factory=dict)
    type: Literal["choice"] = "choice"


@dataclass
class ScoreQuestion:
    """A rating against an ORDERED rubric of 2..10 levels.

    The list order IS the level numbering, so it is never sorted — a "sort
    everything" canonicaliser silently renumbers the rubric.
    """

    instructions: str
    criteria: list[str] = field(default_factory=list)
    type: Literal["score"] = "score"


Question = Union[NoulQuestion, ChoiceQuestion, ScoreQuestion]


def choice_over(instructions: str, items: Mapping[str, str]) -> ChoiceQuestion:
    """Build a :class:`ChoiceQuestion` from any ``(name, description)`` pairs — a
    tool, a skill, an agent, an A2A card skill. The description must say what
    picking that option would MEAN; see the encoding obligation above."""
    return ChoiceQuestion(instructions=instructions, criteria=dict(items))


def _question_wire(key: str, q: Question) -> dict[str, Any]:
    """Project a question dataclass onto its wire dict.

    A PROJECTION, not ``dataclasses.asdict``: ``asdict`` cannot tell an ABSENT
    field from an EMPTY one, and the wire carries that distinction. A noul with no
    criteria emits no ``criteria`` key at all; a noul with ``NoulCriteria()`` emits
    both keys with empty strings.
    """
    if isinstance(q, NoulQuestion):
        out: dict[str, Any] = {"type": "noul", "instructions": q.instructions}
        if q.criteria is not None:
            out["criteria"] = {"true": q.criteria.true, "false": q.criteria.false}
        return out
    if isinstance(q, ChoiceQuestion):
        return {"type": "choice", "instructions": q.instructions, "criteria": dict(q.criteria)}
    if isinstance(q, ScoreQuestion):
        return {"type": "score", "instructions": q.instructions, "criteria": list(q.criteria)}
    raise ClassifierError(f"classifier: question {key!r} is not a noul, choice or score question")


def _validate_question(key: str, q: Question) -> None:
    """Client-side limits, naming the offending question key and the limit.

    Enforced BEFORE the request: a caller finds out faster and more legibly than
    from the backend's own ``400 "Too many choices."``, which is still surfaced
    intact if it arrives.
    """
    if isinstance(q, ChoiceQuestion):
        n = len(q.criteria)
        if n < 1 or n > MAX_CHOICE_OPTIONS:
            raise ClassifierError(
                f"classifier: question {key!r}: a choice needs 1..{MAX_CHOICE_OPTIONS} options, got {n}"
            )
    elif isinstance(q, ScoreQuestion):
        n = len(q.criteria)
        if n < MIN_SCORE_LEVELS or n > MAX_SCORE_LEVELS:
            raise ClassifierError(
                f"classifier: question {key!r}: a score needs "
                f"{MIN_SCORE_LEVELS}..{MAX_SCORE_LEVELS} ordered levels, got {n}"
            )


# --------------------------------------------------------------------------- #
# The canonical request
# --------------------------------------------------------------------------- #


def canonical_json(value: Any) -> bytes:
    """The canonical JSON form: keys sorted recursively in ASCII order, arrays
    NEVER reordered, compact separators, and ``<>&'"`` plus non-ASCII transmitted
    RAW.

    ``json.dumps`` is canonical natively with these three flags, and ``sort_keys``
    is objects-only — so a score rubric's array order survives untouched.
    """
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def canonical_request(model: str, questions: Mapping[str, Question]) -> bytes:
    """The bytes the byte-identity claim covers: ``model`` + ``questions``.

    ``state`` is deliberately NOT here. It is transmitted verbatim as the host
    supplied it and is outside the claim, because numbers do not canonicalise
    across languages (``-0.0`` renders four ways across our own seven runtimes).
    Do not re-widen this: a caller who needs their state pinned canonicalises it
    themselves before handing it over.
    """
    wire = {k: _question_wire(k, q) for k, q in questions.items()}
    return canonical_json({"model": model, "questions": wire})


# --------------------------------------------------------------------------- #
# Answers
# --------------------------------------------------------------------------- #


@dataclass
class NoulAnswer:
    """The probability that a statement holds. Carries NO confidence."""

    noul: float
    type: Literal["noul"] = "noul"


@dataclass
class ChoiceAnswer:
    """One option from the offered set, with a probability for every offered option."""

    choice: str
    probabilities: dict[str, float]
    confidence: float
    #: DERIVED from ``probabilities`` on decode and NEVER read from the wire (§8B).
    #:
    #: ADVISORY, not a correctness signal: it detects an encoding that gave the
    #: model nothing to rank on, and cannot distinguish a good encoding from a
    #: subtly wrong one. ``calibrated`` carries the same caveat.
    near_uniform: bool = False
    type: Literal["choice"] = "choice"


@dataclass
class ScoreAnswer:
    """A rating against the ordered rubric. ``score`` MAY fall between levels
    (``1.21`` is a real answer) and is always within the rubric's bounds."""

    score: float
    legend: dict[str, str]
    probabilities: dict[str, float]
    confidence: float
    type: Literal["score"] = "score"

    def levels(self) -> list[str]:
        """The legend in LEVEL order, which the map itself loses.

        Sorted numerically-by-length-then-lexicographically, so ``"2" < "10"`` as
        levels, unlike as strings.
        """
        keys = sorted(self.legend, key=lambda k: (len(k), k))
        return [self.legend[k] for k in keys]


#: One typed answer. The set is CLOSED and discriminated by the wire's ``type``.
#: Named ``DecisionAnswer`` because §10 already owns ``Answer`` (the suspension
#: resolution) — same idea, different seam.
DecisionAnswer = Union[NoulAnswer, ChoiceAnswer, ScoreAnswer]


@dataclass
class ClassifierUsage:
    """Mirrors the wire's usage block. ``cost`` is absent on some backends. Named
    apart from the §8 run usage, which counts a client run."""

    input_tokens: int = 0
    output_tokens: int = 0
    cost: Optional[float] = None


def near_uniform(probabilities: Mapping[str, float]) -> bool:
    """Whether a choice answer's distribution is indistinguishable from flat::

        nearUniform  ⇔  max over i of |p_i - 1/n|  <=  0.05

    ``n`` is the number of ENTRIES IN THE MAP and the values are taken AS
    RETURNED — not renormalised, not sorted, not rounded; an offered option absent
    from the map counts as ``0`` by not being an entry. The tolerance is ABSOLUTE
    (a relative band collapses below the wire's two-decimal rounding on a
    255-option roster) and the comparison is INCLUSIVE. ``n == 1`` is trivially
    uniform. An empty map has no distribution at all and is reported ``False``.
    """
    n = len(probabilities)
    if n == 0:
        return False
    if n == 1:
        return True
    target = 1.0 / n
    return all(abs(float(p) - target) <= NEAR_UNIFORM_TOLERANCE for p in probabilities.values())


@dataclass
class Decision:
    """One answer per question, keyed by the CALLER's keys.

    The keys are addressing, not content: they are never transmitted to the model,
    so a key MAY be a tool, skill or agent name verbatim, and two evaluations
    differing only in their keys send identical content.
    """

    #: Echoes what actually answered, which may be more specific than what was asked for.
    model: str = ""
    answers: dict[str, DecisionAnswer] = field(default_factory=dict)
    usage: ClassifierUsage = field(default_factory=ClassifierUsage)
    #: ``systemone`` reports True; ``llm`` reports False unless it derived the
    #: probabilities from provider token probabilities. A THRESHOLD TUNED AGAINST
    #: ONE BACKEND DOES NOT TRANSFER TO ANOTHER.
    calibrated: bool = True

    # Typed accessors, so a wrong-type or absent read RAISES rather than returning
    # something wrong and no caller has to ``isinstance`` by hand.
    def noul(self, key: str) -> NoulAnswer:
        return self._typed(key, NoulAnswer, "noul")

    def choice(self, key: str) -> ChoiceAnswer:
        return self._typed(key, ChoiceAnswer, "choice")

    def score(self, key: str) -> ScoreAnswer:
        return self._typed(key, ScoreAnswer, "score")

    def _typed(self, key: str, cls: type, want: str) -> Any:
        a = self.answers.get(key)
        if a is None:
            raise ClassifierError(f"classifier: no answer {key!r} in this decision")
        if not isinstance(a, cls):
            raise ClassifierError(f"classifier: answer {key!r} is a {a.type} answer, not {want}")
        return a


def _decision_from_wire(raw: Mapping[str, Any]) -> Decision:
    """The discriminated decode, and the single place ``near_uniform`` is derived.

    Never repairs a malformed answer (ADR 0020): an invalid distribution means no
    action, not a patched one.
    """
    if not isinstance(raw, Mapping):
        raise ClassifierError("classifier: response is not a JSON object")
    answers: dict[str, DecisionAnswer] = {}
    for key, body in (raw.get("answers") or {}).items():
        if not isinstance(body, Mapping):
            raise ClassifierError(f"classifier: answer {key!r} is not a JSON object")
        kind = body.get("type")
        try:
            if kind == "noul":
                answers[key] = NoulAnswer(noul=float(body["noul"]))
            elif kind == "choice":
                probs = {k: float(v) for k, v in (body.get("probabilities") or {}).items()}
                answers[key] = ChoiceAnswer(
                    choice=str(body["choice"]),
                    probabilities=probs,
                    confidence=float(body.get("confidence", 0.0)),
                    # Derived here and only here; never read from the wire.
                    near_uniform=near_uniform(probs),
                )
            elif kind == "score":
                answers[key] = ScoreAnswer(
                    score=float(body["score"]),
                    legend={k: str(v) for k, v in (body.get("legend") or {}).items()},
                    probabilities={k: float(v) for k, v in (body.get("probabilities") or {}).items()},
                    confidence=float(body.get("confidence", 0.0)),
                )
            else:
                raise ClassifierError(f"classifier: answer {key!r}: unknown type {kind!r}")
        except (KeyError, TypeError, ValueError) as e:
            raise ClassifierError(f"classifier: answer {key!r}: malformed {kind} answer: {e}") from None
    u = raw.get("usage") or {}
    usage = ClassifierUsage(
        input_tokens=int(u.get("input_tokens", 0) or 0),
        output_tokens=int(u.get("output_tokens", 0) or 0),
        cost=float(u["cost"]) if u.get("cost") is not None else None,
    )
    calibrated = raw.get("calibrated")
    return Decision(
        model=str(raw.get("model", "")),
        answers=answers,
        usage=usage,
        # Absent ⇒ True: the systemone wire reports calibration by being itself. A
        # backend that is not calibrated says so explicitly.
        calibrated=True if calibrated is None else bool(calibrated),
    )


# --------------------------------------------------------------------------- #
# Degenerate criteria — detect and report, NEVER repair
# --------------------------------------------------------------------------- #


def _degenerate_reason(criteria: Mapping[str, str]) -> Optional[str]:
    """The §8B predicate. Degenerate ⇔ ANY of:

    1. every value is empty (empty string or absent), or
    2. every value equals its own key, or
    3. every value is identical to every other value (``n >= 2``).

    A single-option choice (``n == 1``) is NEVER reported — there is nothing to
    differentiate. Note the ordering: with ``n == 1`` rules 1 and 2 can still hold
    and rule 3 is vacuous, so the ``n < 2`` gate comes first for all three.
    """
    if len(criteria) < 2:
        return None
    values = list(criteria.values())
    if all(v == "" for v in values):
        return "every description is empty"
    if all(v == k for k, v in criteria.items()):
        return "every description is just its own option id"
    if all(v == values[0] for v in values):
        return "every description is identical"
    return None


# --------------------------------------------------------------------------- #
# Transport
# --------------------------------------------------------------------------- #


@dataclass
class ClassifierResponse:
    """One raw HTTP response: the status, the body bytes, and the ``Retry-After``
    header verbatim (parsed by the §8 delay-seconds rule, not here)."""

    status: int
    body: bytes
    retry_after: Optional[str] = None


class ClassifierTransport(Protocol):
    """The §8 Gap 2 injectable transport, scoped to the classifier path only.

    It takes BYTES rather than a dict: the body is the canonical form this module
    produced, and re-serialising it through a transport would throw those bytes
    away.
    """

    def post(self, url: str, headers: dict[str, str], body: bytes, timeout: float) -> ClassifierResponse:
        ...


class _UrllibTransport:
    """The default transport — stdlib only, blocking; the caller runs it off the
    event loop."""

    def post(self, url: str, headers: dict[str, str], body: bytes, timeout: float) -> ClassifierResponse:
        req = urllib.request.Request(url, data=body, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return ClassifierResponse(
                    status=resp.status, body=resp.read(), retry_after=resp.headers.get("Retry-After")
                )
        except urllib.error.HTTPError as e:  # a status, not a transport fault
            return ClassifierResponse(
                status=e.code,
                body=e.read(),
                retry_after=e.headers.get("Retry-After") if e.headers else None,
            )


_ENV_REF = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _expand_env(value: str) -> str:
    """Expand ``${ENV_VAR}`` references at CALL time, identically to remote-MCP
    headers (§2). The expanded value is never logged."""
    return _ENV_REF.sub(lambda m: os.environ.get(m.group(1), ""), value)


# --------------------------------------------------------------------------- #
# Options + the classifier
# --------------------------------------------------------------------------- #


@dataclass
class RecordedDecision:
    """One entry of the ``static`` corpus.

    Keyed on the canonical request AND the state: several recorded entries
    legitimately share one questions payload and differ only in state (the three
    guard bands of ``examples/judge/decisions.json`` do exactly that), so a corpus
    keyed on the canonical request alone cannot tell them apart.
    """

    state: Any
    questions: Mapping[str, Question]
    #: The recorded backend response body, verbatim (a parsed object or raw bytes).
    response: Union[Mapping[str, Any], bytes, str]


class Classifier:
    """The whole seam: one verb, :meth:`evaluate`.

    Build it with :func:`create_classifier`, which applies the §8B defaults and
    rejects a style whose required option is missing before any call is made.
    """

    def __init__(
        self,
        *,
        style: ClassifierStyle = "systemone",
        base_url: str = DEFAULT_CLASSIFIER_BASE_URL,
        model: str = DEFAULT_CLASSIFIER_MODEL,
        api_key_env: str = DEFAULT_CLASSIFIER_API_KEY_ENV,
        headers: Optional[Mapping[str, str]] = None,
        timeout: float = DEFAULT_CLASSIFIER_TIMEOUT,
        http_transport: Optional[ClassifierTransport] = None,
        retries: int = _DEFAULT_RETRIES,
        on_error: Optional[ErrorClassifier] = None,
        request_params: Optional[Mapping[str, Any]] = None,
        body_transform: Optional[Callable[[dict[str, Any]], Optional[dict[str, Any]]]] = None,
        on_metric: Optional[OnMetric] = None,
        client: Optional[Client] = None,
        evaluate: Optional[Callable[..., Any]] = None,
        decisions: Optional[list[RecordedDecision]] = None,
    ) -> None:
        self.style = style
        self.base_url = base_url
        self.model = model
        self.api_key_env = api_key_env
        self.headers = dict(headers or {})
        self.timeout = timeout
        self.http_transport: ClassifierTransport = http_transport or _UrllibTransport()
        self.retries = retries
        self.on_error = on_error
        self.request_params = dict(request_params or {})
        self.body_transform = body_transform
        self.on_metric = on_metric
        self.client = client
        self.evaluate_fn = evaluate
        self.decisions = list(decisions or [])
        # The once-per-question-key set for the degenerate-criteria warning, so a
        # per-turn judge does not flood the sink.
        self._warned: set[str] = set()
        self._static: dict[bytes, Any] = {}
        if style == "static":
            for i, rec in enumerate(self.decisions):
                try:
                    self._static[_static_key(model, rec.state, rec.questions)] = rec.response
                except ClassifierError as e:
                    raise ClassifierError(f"classifier: recorded decision {i}: {e}") from None

    # ----------------------------------------------------------------------- #

    async def evaluate(self, state: Any, questions: Mapping[str, Question]) -> Decision:
        """A state plus typed questions in, a :class:`Decision` out.

        Questions are INDEPENDENT: one answer is never context for another. A
        backend that cannot guarantee that reports ``calibrated=False``, which
        carries both caveats.
        """
        t0 = time.monotonic()
        if not questions:
            err = ClassifierError("classifier: no questions to evaluate")
            self._emit_error(t0, err)
            raise err
        # Limits are enforced CLIENT-SIDE, before the request. Keys are walked in
        # sorted order so the same malformed set always names the same key first.
        for key in sorted(questions):
            try:
                _validate_question(key, questions[key])
            except ClassifierError as err:
                self._emit_error(t0, err)
                raise
        # Detection, never repair (ADR 0020/0021): the request goes out
        # BYTE-UNCHANGED and the warning is the entire observable effect.
        self._report_degenerate(questions)

        try:
            if self.style == "custom":
                d = self.evaluate_fn(state, questions)  # type: ignore[misc]
                if inspect.isawaitable(d):
                    d = await d
            elif self.style == "static":
                d = self._evaluate_static(state, questions)
            elif self.style == "llm":
                d = await self._evaluate_llm(state, questions)
            else:
                d = await self._evaluate_systemone(state, questions)
        except Exception as err:  # noqa: BLE001 — re-raised verbatim after the metric
            self._emit_error(t0, err)
            raise
        self._emit(
            {
                "event": METRIC_CLASSIFIER_EVALUATE,
                "model": d.model or self.model,
                "status": "ok",
                "ms": int((time.monotonic() - t0) * 1000),
                "prompt_tokens": d.usage.input_tokens,
                "completion_tokens": d.usage.output_tokens,
            }
        )
        return d

    # ----------------------------------------------------------------------- #
    # Degenerate criteria
    # ----------------------------------------------------------------------- #

    def _report_degenerate(self, questions: Mapping[str, Question]) -> None:
        """Emit ONE warning per degenerate question key per classifier, NAMING the
        key, and change nothing about the request.

        Repairing would invent option descriptions the caller did not write, and
        the library has no way to know what the options mean.
        """
        for key in sorted(questions):
            q = questions[key]
            if not isinstance(q, ChoiceQuestion):
                continue
            reason = _degenerate_reason(q.criteria)
            if reason is None or key in self._warned:
                continue
            self._warned.add(key)
            self._emit(
                {
                    "event": METRIC_CLASSIFIER_WARNING,
                    "question": key,
                    "error": (
                        f"classifier: question {key!r} has degenerate criteria ({reason}) — "
                        "every option reads the same to the model and the answer ranks at "
                        "chance; describe what picking each option would MEAN (SPEC.md §8B)"
                    ),
                }
            )

    # ----------------------------------------------------------------------- #
    # Backends
    # ----------------------------------------------------------------------- #

    def _body(self, state: Any, questions: Mapping[str, Question]) -> bytes:
        """Assemble the request: the canonical ``model`` + ``questions``, plus
        ``state`` VERBATIM as the host supplied it, then the §8 Gap 1 pipeline in
        §8 order (base → ``request_params`` merge → ``body_transform`` → marshal).
        """
        body: dict[str, Any] = {
            "model": self.model,
            "questions": {k: _question_wire(k, q) for k, q in questions.items()},
            "state": state,
        }
        body.update(self.request_params)  # a request_params key WINS on collision
        if self.body_transform is not None:
            out = self.body_transform(body)
            if out is not None:
                body = out
        return canonical_json(body)

    def _evaluate_static(self, state: Any, questions: Mapping[str, Question]) -> Decision:
        raw = self._static.get(_static_key(self.model, state, questions))
        if raw is None:
            # Errors rather than guessing: a static backend that fell back to the
            # nearest recorded answer would make every test a lie.
            raise ClassifierError("classifier: static: no recorded decision for this request+state")
        if isinstance(raw, (bytes, bytearray, str)):
            raw = json.loads(raw)
        return _decision_from_wire(raw)

    async def _evaluate_systemone(self, state: Any, questions: Mapping[str, Question]) -> Decision:
        payload = await self._post(self._body(state, questions))
        try:
            parsed = json.loads(payload)
        except ValueError as e:
            raise ClassifierError(f"classifier: unparseable response: {e}") from None
        return _decision_from_wire(parsed)

    async def _post(self, raw: bytes) -> bytes:
        """The one POST, with the §8 retry budget — the same ``ErrorInfo``/tier
        classifier and the same ``Retry-After`` delay-seconds rule. There is no
        second retry policy, and no ``"suspend"`` tier here either.

        NO CREDENTIAL VALUE AND NO EXPANDED HEADER VALUE LEAVES THIS METHOD: an
        authentication failure names the status and the endpoint and nothing else,
        and a 401/403 body is never echoed back.
        """
        endpoint = self.base_url.rstrip("/") + "/systemone"
        retries = self.retries if self.retries > 0 else _DEFAULT_RETRIES
        classify = self.on_error or (lambda info: "retry" if info.get("retryable") else "fail")
        attempt = 0
        while True:
            status = 0
            err: Optional[Exception] = None
            retry_after_header: Optional[str] = None
            try:
                res = await asyncio.to_thread(
                    self.http_transport.post, endpoint, self._headers(), raw, self.timeout
                )
                status, retry_after_header = res.status, res.retry_after
                if status < 300:
                    return res.body
                last = ClassifierError(f"classifier: POST {endpoint}: HTTP {status}{_cause(status, res.body)}")
            except Exception as e:  # noqa: BLE001 — a transport fault, not a status
                err = e
                last = ClassifierError(f"classifier: POST {endpoint}: {type(e).__name__}: {e}")
            retryable = err is not None or status == 408 or status in _RETRYABLE
            if attempt >= retries:
                raise last
            info: ErrorInfo = {"error": err, "status": status, "attempt": attempt, "retryable": retryable}
            if classify(info) != "retry":
                raise last
            delay = _parse_retry_after(retry_after_header)
            await asyncio.sleep(delay if delay is not None else 0.5 * (2**attempt))
            attempt += 1

    def _headers(self) -> dict[str, str]:
        """Built fresh per attempt: the credential is read from the NAMED env var
        at call time, and ``${ENV_VAR}`` header references expand at call time."""
        h = {"Content-Type": "application/json"}
        key = os.environ.get(self.api_key_env)
        if key:
            h["Authorization"] = f"Bearer {key}"
        for k, v in self.headers.items():
            h[k] = _expand_env(v)
        return h

    async def _evaluate_llm(self, state: Any, questions: Mapping[str, Question]) -> Decision:
        """Render the three question types as ONE structured-output call on any §8
        client — the vendor-neutral fallback, so a host with no System One
        credential runs the same questions on a cheap chat model.

        ``calibrated`` is FALSE: the numbers are the model's self-report, not token
        probabilities, and nothing here derives calibration.
        """
        state_json = canonical_json(state).decode("utf-8")
        q_json = canonical_json({k: _question_wire(k, q) for k, q in questions.items()}).decode("utf-8")
        prompt = (
            "Answer every question about the state below. Questions are INDEPENDENT: "
            "one answer is never context for another.\n\n"
            f"STATE:\n{state_json}\n\nQUESTIONS:\n{q_json}\n\n"
            "Reply with JSON only, no prose and no code fence, shaped exactly:\n"
            '{"answers":{"<key>":{"type":"noul","noul":0.0}}}\n'
            'A "noul" answer is {"type":"noul","noul":<0..1>}. A "choice" answer is '
            '{"type":"choice","choice":"<one offered option id>",'
            '"probabilities":{"<every offered option id>":<0..1>},"confidence":<0..1>}. '
            'A "score" answer is {"type":"score","score":<a number within the rubric bounds, '
            'fractional allowed>,"legend":{"0":"<level 0>",…},"probabilities":{"0":<0..1>,…},'
            '"confidence":<0..1>}.'
        )
        run = await self.client.run(prompt, _empty_toolkit())  # type: ignore[union-attr]
        payload = _first_json_object(run.text)
        try:
            parsed = json.loads(payload)
        except ValueError as e:
            raise ClassifierError(f"classifier: llm: {e}") from None
        d = _decision_from_wire(parsed)
        d.calibrated = False
        if not d.model:
            d.model = self.model
        d.usage = ClassifierUsage(
            input_tokens=run.usage.get("prompt_tokens", 0),
            output_tokens=run.usage.get("completion_tokens", 0),
        )
        return d

    # ----------------------------------------------------------------------- #
    # Metrics
    # ----------------------------------------------------------------------- #

    def _emit(self, ev: MetricEvent) -> None:
        if self.on_metric is not None:
            self.on_metric(ev)

    def _emit_error(self, t0: float, err: Exception) -> None:
        self._emit(
            {
                "event": METRIC_CLASSIFIER_EVALUATE,
                "model": self.model,
                "status": "error",
                "ms": int((time.monotonic() - t0) * 1000),
                "error": str(err),
            }
        )


def _empty_toolkit() -> Toolkit:
    """A classifier has no tools: it asks for a judgment, never an action."""
    return Toolkit(None, None, [], [], [])


def _first_json_object(s: str) -> str:
    """The outermost JSON object from a model reply, which may arrive wrapped in a
    code fence or prose. It does NOT repair malformed JSON — an unparseable answer
    is no answer (ADR 0020)."""
    start, end = s.find("{"), s.rfind("}")
    if start < 0 or end <= start:
        raise ClassifierError("classifier: llm: no JSON object in the reply")
    return s[start : end + 1]


def _cause(status: int, body: bytes) -> str:
    """Surface a backend's reported cause intact, EXCEPT on an authentication
    status: a 401/403 body routinely reflects the credential or the header that
    was sent, so it never reaches a log, a metric, an error or a return value."""
    if status in (401, 403):
        return ""
    s = body.decode("utf-8", errors="replace").strip()
    if not s:
        return ""
    return ": " + (s[:200] + "…" if len(s) > 200 else s)


def _static_key(model: str, state: Any, questions: Mapping[str, Question]) -> bytes:
    """Identify a recorded decision by the canonical request AND the state. See
    :class:`RecordedDecision` for why the state is load-bearing here."""
    return canonical_request(model, questions) + b"\x00" + canonical_json(state)


def create_classifier(
    *,
    style: ClassifierStyle = "systemone",
    base_url: Optional[str] = None,
    model: Optional[str] = None,
    api_key_env: Optional[str] = None,
    headers: Optional[Mapping[str, str]] = None,
    timeout: Optional[float] = None,
    http_transport: Optional[ClassifierTransport] = None,
    retries: int = _DEFAULT_RETRIES,
    on_error: Optional[ErrorClassifier] = None,
    request_params: Optional[Mapping[str, Any]] = None,
    body_transform: Optional[Callable[[dict[str, Any]], Optional[dict[str, Any]]]] = None,
    on_metric: Optional[OnMetric] = None,
    client: Optional[Client] = None,
    evaluate: Optional[Callable[..., Any]] = None,
    decisions: Optional[list[RecordedDecision]] = None,
) -> Classifier:
    """Build a :class:`Classifier`, applying the §8B defaults and rejecting a style
    whose required option is missing BEFORE any call is made.

    Mirrors §8 ``ClientOptions`` field-for-field wherever a field makes sense, so a
    host that has configured one has configured the other.
    """
    if style not in ("systemone", "llm", "custom", "static"):
        raise ClassifierError(f"classifier: unknown style {style!r}")
    if style == "llm" and client is None:
        raise ClassifierError(f"classifier: style {style!r} requires client")
    if style == "custom" and evaluate is None:
        raise ClassifierError(f"classifier: style {style!r} requires evaluate")
    return Classifier(
        style=style,
        base_url=base_url or DEFAULT_CLASSIFIER_BASE_URL,
        model=model or DEFAULT_CLASSIFIER_MODEL,
        api_key_env=api_key_env or DEFAULT_CLASSIFIER_API_KEY_ENV,
        headers=headers,
        timeout=timeout if timeout else DEFAULT_CLASSIFIER_TIMEOUT,
        http_transport=http_transport,
        retries=retries,
        on_error=on_error,
        request_params=request_params,
        body_transform=body_transform,
        on_metric=on_metric,
        client=client,
        evaluate=evaluate,
        decisions=decisions,
    )
