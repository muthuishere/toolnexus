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
import base64
import hashlib
import inspect
import json
import math
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Iterable, Literal, Mapping, Optional, Protocol, Sequence, Union

from .client import (
    Client,
    ErrorClassifier,
    ErrorInfo,
    MetricEvent,
    OnMetric,
    _is_retryable_status,
    _parse_retry_after,
    capped_error_body,
)
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
    # add-classifier-backends (SPEC §8B "Backends, images, cache and validation")
    "ClassifierBackend",
    "ClassifierBackendPreset",
    "CLASSIFIER_BACKENDS",
    "ClassifierSupports",
    "ClassifierUnsupported",
    "IMAGES_HEADER",
    "RawQuestion",
    "RawAnswer",
    "ClassifierCache",
    "MemoryCache",
    "classifier_model_cache_key",
    "classifier_decision_cache_key",
    "validate_decision",
    "Thresholds",
    "DEFAULT_THRESHOLDS",
    "chunk_questions",
    "ClassifierState",
    "ClassifierStates",
    "BatchResult",
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
#: Default retry backoff base in ms — the same 500 as §8 ``ClientOptions.retry_base_ms``.
_DEFAULT_RETRY_BASE_MS = 500

#: ``"llm"`` is DEPRECATED (ADR 0038 D3: System One is the single product wire).
#: It keeps working byte-identically as an alias for at least one minor release;
#: there is no runtime warning.
ClassifierStyle = Literal["systemone", "llm", "custom", "static"]

#: A BACKEND is where the System One wire is served. Picking one sets base_url,
#: model and api_key_env AS A UNIT (D5, #91, ADR 0027) — mixing TypeSafe's model
#: alias with OpenRouter's base URL is the documented 404 in issue #91, and it
#: used to be discoverable only at request time.
ClassifierBackend = Literal["typesafe", "openrouter", "cloudflare", "vercel", "openai", "custom"]

#: TypeSafe's required opt-in for image ("danger zone") input.
IMAGES_HEADER = "TypeSafe-Danger-Zone-Images"

#: Image limits (docs.typesafe.ai/images). Outside them the API rejects and never
#: resizes, so they are checked client-side before any request.
CLASSIFIER_IMAGE_MIN_SIDE = 280
CLASSIFIER_IMAGE_MAX_SIDE = 1568
CLASSIFIER_IMAGE_MAX_BYTES = 16 * 1024 * 1024
CLASSIFIER_REQUEST_IMAGE_BYTES = 32 * 1024 * 1024


@dataclass(frozen=True)
class ClassifierSupports:
    """What a backend+model can take. DATA: a fail-fast for what the table says,
    never a claim about the vendor. Override per classifier with ``supports``."""

    images: bool = False


@dataclass(frozen=True)
class ClassifierBackendPreset:
    """One row of the backend table (§8B)."""

    base_url: str
    model: str
    api_key_env: str
    #: ``"systemone"``, ``"cloudflare"`` or ``"decisions"``.
    wire: str
    #: Send ``TypeSafe-Danger-Zone-Images: true`` when a request carries images.
    images_header: bool
    #: Default support for a model on this backend. ``None`` on ``custom``, which
    #: requires the ``supports`` option.
    supports: Optional[Callable[[str], ClassifierSupports]]

    def __getitem__(self, key: str) -> Any:  # the pre-0.25 dict-row spelling
        return getattr(self, key)


#: The backend table (researched and live-probed 2026-11-04, ADR 0038). Vercel and
#: Cloudflare-Jev images stay False until a live probe proves them.
CLASSIFIER_BACKENDS: dict[str, ClassifierBackendPreset] = {
    "typesafe": ClassifierBackendPreset(
        DEFAULT_CLASSIFIER_BASE_URL, DEFAULT_CLASSIFIER_MODEL, DEFAULT_CLASSIFIER_API_KEY_ENV,
        "systemone", True, lambda m: ClassifierSupports(images=True),
    ),
    "openrouter": ClassifierBackendPreset(
        "https://openrouter.ai/api/v1", "typesafe/jev-1.14", "OPENROUTER_API_KEY",
        "systemone", True, lambda m: ClassifierSupports(images=True),
    ),
    "cloudflare": ClassifierBackendPreset(
        "https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run",
        "typesafe/jev", "CLOUDFLARE_JEV_API_KEY",
        "cloudflare", False, lambda m: ClassifierSupports(images="clef" in m),
    ),
    "vercel": ClassifierBackendPreset(
        "https://ai-gateway.vercel.sh/typesafe/v1", "typesafe-ai/jev", "AI_GATEWAY_API_KEY",
        "systemone", True, lambda m: ClassifierSupports(images=False),
    ),
    # OpenAI's Decisions API (#157): its own wire, POST {base}/decisions.
    "openai": ClassifierBackendPreset(
        "https://api.openai.com/v1", "gpt-6-luna", "OPENAI_API_KEY",
        "decisions", False, lambda m: ClassifierSupports(images=True),
    ),
    "custom": ClassifierBackendPreset(
        "", DEFAULT_CLASSIFIER_MODEL, "", "systemone", True, None,
    ),
}


def classifier_key_env(backend: str, base_url: str) -> str:
    """The env var whose key may be sent to ``base_url`` (already ``${NAME}``-expanded):
    the backend's own variable, and only when ``base_url`` is that backend's own host
    over https (OpenRouter also its ``*.openrouter.ai`` subdomains, as in the §8
    client). ``""`` when none may — ``custom`` has no preset key at all. A key is
    never sent to a host the caller did not name (#155, SPEC §8B)."""
    p = CLASSIFIER_BACKENDS.get(backend)
    if p is None or not p.api_key_env or not p.base_url:
        return ""

    def host(u: str) -> str:
        try:
            sp = urllib.parse.urlsplit(u)
            return (sp.hostname or "").lower() if sp.scheme.lower() == "https" else ""
        except ValueError:
            return ""

    h, own = host(base_url), host(p.base_url)
    if not h or not own:
        return ""
    if h == own or (backend == "openrouter" and h.endswith(".openrouter.ai")):
        return p.api_key_env
    return ""

#: The one mismatch worth failing at construction: a model alias only TypeSafe
#: serves, pointed at OpenRouter.
_TYPESAFE_ONLY_MODELS = ("jev-latest",)


class ClassifierError(Exception):
    """Every failure this module raises. Never carries a credential value, an
    expanded header value, or the body of a 401/403 (a gateway happily reflects a
    bad ``Authorization`` header into its own error text)."""


class ClassifierUnsupported(ClassifierError):
    """Raised BEFORE any network call when the backend's support table says it
    cannot take what the request carries (§8B, PD-5). Not raised for an image
    outside the limits: that is the caller's input, not a backend gap."""

    def __init__(self, backend: str, model: str, feature: str) -> None:
        super().__init__(f"classifier: {backend} ({model}) does not support {feature}")
        self.backend = backend
        self.model = model
        self.feature = feature


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


@dataclass
class RawQuestion:
    """The OPEN end of the question union (§8B): a System One question type the
    library does not model (e.g. Jev 1.14 ``box``), sent as written. It gets no
    client-side limits beyond a non-empty ``type``."""

    type: str
    instructions: str
    #: Sent verbatim when not ``None``; omitted when ``None``.
    criteria: Any = None


Question = Union[NoulQuestion, ChoiceQuestion, ScoreQuestion, RawQuestion]


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
    if isinstance(q, RawQuestion):
        out = {"type": q.type, "instructions": q.instructions}
        if q.criteria is not None:
            out["criteria"] = q.criteria
        return out
    raise ClassifierError(f"classifier: question {key!r} is not a noul, choice or score question")


def question_wire(q: Question) -> dict[str, Any]:
    """Public question -> §8B wire dict (the exact projection the request uses)."""
    return _question_wire("question", q)


def questions_wire(questions: Mapping[str, Question]) -> dict[str, dict[str, Any]]:
    """Public question map -> §8B wire ``questions`` map."""
    return {k: _question_wire(k, q) for k, q in questions.items()}


def _validate_question(key: str, q: Question) -> None:
    """Client-side limits, naming the offending question key and the limit.

    Enforced BEFORE the request: a caller finds out faster and more legibly than
    from the backend's own ``400 "Too many choices."``, which is still surfaced
    intact if it arrives.
    """
    if isinstance(q, RawQuestion):
        if not q.type:
            raise ClassifierError(f"classifier: question {key!r}: a raw question needs a type")
    elif isinstance(q, ChoiceQuestion):
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
@dataclass
class RawAnswer:
    """An answer of a type the library does not model (§8B open union). ``value``
    is the whole answer object as decoded, ``type`` included."""

    type: str
    value: dict[str, Any] = field(default_factory=dict)


DecisionAnswer = Union[NoulAnswer, ChoiceAnswer, ScoreAnswer, RawAnswer]


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
    #: True when the decision came from the classifier ``cache`` and no request was
    #: sent. Never on the wire.
    cached: bool = False

    # Typed accessors, so a wrong-type or absent read RAISES rather than returning
    # something wrong and no caller has to ``isinstance`` by hand.
    def noul(self, key: str) -> NoulAnswer:
        return self._typed(key, NoulAnswer, "noul")

    def choice(self, key: str) -> ChoiceAnswer:
        return self._typed(key, ChoiceAnswer, "choice")

    def score(self, key: str) -> ScoreAnswer:
        return self._typed(key, ScoreAnswer, "score")

    def raw(self, key: str) -> RawAnswer:
        return self._typed(key, RawAnswer, "raw")

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
                # The union is OPEN (§8B): a type the library does not model (e.g.
                # Jev 1.14 "box") decodes to a RawAnswer instead of failing the
                # whole decision.
                answers[key] = RawAnswer(type=kind if isinstance(kind, str) else "", value=dict(body))
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
        retry_base_ms: int = _DEFAULT_RETRY_BASE_MS,
        retryable_statuses: Optional[Iterable[int]] = None,
        on_error: Optional[ErrorClassifier] = None,
        request_params: Optional[Mapping[str, Any]] = None,
        body_transform: Optional[Callable[[dict[str, Any]], Optional[dict[str, Any]]]] = None,
        on_metric: Optional[OnMetric] = None,
        client: Optional[Client] = None,
        evaluate: Optional[Callable[..., Any]] = None,
        decisions: Optional[list[RecordedDecision]] = None,
        backend: str = "typesafe",
        supports: Optional[Union[ClassifierSupports, Mapping[str, Any]]] = None,
        cache: Optional["ClassifierCache"] = None,
        key_env_explicit: Optional[bool] = None,
    ) -> None:
        self.style = style
        self.backend = backend
        self.preset = CLASSIFIER_BACKENDS[backend]
        if supports is not None:
            self.supports = _as_supports(supports)
        elif self.preset.supports is not None:
            self.supports = self.preset.supports(model)
        else:
            self.supports = ClassifierSupports()
        #: Opt-in answer cache (§8B). ``None`` = no caching. Consulted by style
        #: ``systemone`` only; stores only replies that pass :func:`validate_decision`.
        self.cache = cache
        self.base_url = base_url
        self.model = model
        self.api_key_env = api_key_env
        # Did the caller NAME the key variable? A named one goes wherever base_url
        # points; the preset's own only to its own host (classifier_key_env, #155).
        if key_env_explicit is None:
            key_env_explicit = api_key_env not in (self.preset.api_key_env, DEFAULT_CLASSIFIER_API_KEY_ENV)
        self.key_env_explicit = key_env_explicit
        self.headers = dict(headers or {})
        self.timeout = timeout
        self.http_transport: ClassifierTransport = http_transport or _UrllibTransport()
        self.retries = retries
        # Base of the retry backoff in ms (``base * 2 ** attempt``, no jitter — a
        # ``Retry-After`` header still wins). 0 or negative ⇒ 500, the §8
        # ``ClientOptions.retry_base_ms`` default this mirrors.
        self.retry_base_ms = retry_base_ms
        # Extra HTTP statuses to treat as retryable, ADDED to the default set
        # (429/500/502/503/504/529, plus 408 here). It can only widen: a host cannot remove
        # 429 and lose ``Retry-After`` handling with it. This sets the DEFAULT classification;
        # ``on_error`` still runs per attempt and has the final say, so ``on_error`` returning
        # "fail" overrides a status listed here. Example: a Cloudflare-fronted origin
        # answering 520–527.
        self.retryable_statuses = frozenset(retryable_statuses) if retryable_statuses is not None else None
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
        return await self._evaluate(state, questions, False)

    def state(self, value: Any) -> "ClassifierState":
        """Bind a state; nothing is sent until ``.evaluate(*questions)`` (§8B)."""
        return ClassifierState(self, value)

    def states(self, *values: Any) -> "ClassifierStates":
        """Bind a batch of states; see :meth:`ClassifierStates.evaluate`."""
        return ClassifierStates(self, list(values))

    async def _evaluate(self, state: Any, questions: Mapping[str, Question], validate: bool) -> Decision:
        """The one path. ``validate`` = fail closed on :func:`validate_decision` (the
        value builders); plain :meth:`evaluate` keeps its old behaviour."""
        t0 = time.monotonic()
        # L1: count + hash of the canonical request on every evaluate event.
        try:
            q_hash = hashlib.sha256(canonical_request(self.model, questions)).hexdigest()
        except Exception:  # noqa: BLE001 — an unencodable question set hashes to ""
            q_hash = ""
        n_questions = len(questions)

        def emit_error(err: BaseException) -> None:
            self._emit_error(t0, err, n_questions, q_hash)

        if not questions:
            err = ClassifierError("classifier: no questions to evaluate")
            emit_error(err)
            raise err
        # Limits are enforced CLIENT-SIDE, before the request. Keys are walked in
        # sorted order so the same malformed set always names the same key first.
        for key in sorted(questions):
            try:
                _validate_question(key, questions[key])
            except ClassifierError as err:
                emit_error(err)
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
                d = await self._evaluate_wire(state, questions, validate)
            if validate and self.style != "systemone":
                verr = validate_decision(questions, d)
                if verr is not None:
                    raise verr
        except Exception as err:  # noqa: BLE001 — re-raised verbatim after the metric
            emit_error(err)
            raise
        self._emit(
            {
                "event": METRIC_CLASSIFIER_EVALUATE,
                "model": d.model or self.model,
                "status": "ok",
                "ms": int((time.monotonic() - t0) * 1000),
                "prompt_tokens": d.usage.input_tokens,
                "completion_tokens": d.usage.output_tokens,
                "questions": n_questions,
                "questions_hash": q_hash,
                "cached": d.cached,
            }
        )
        return d

    async def evaluate_batch(
        self, states: Sequence[Any], questions: Mapping[str, Question], *, concurrency: int = 16
    ) -> list[Decision]:
        """The same questions over many states (§8B Batch).

        Each state goes through :meth:`evaluate`, at most ``concurrency`` in flight,
        decisions returned in STATE order. Fails closed: the first failing state's
        index is named and no decisions are returned. No states is an error and
        sends nothing.
        """
        states = list(states)
        if not states:
            raise ClassifierError("classifier: evaluate_batch: no states to evaluate")
        sem = asyncio.Semaphore(max(1, concurrency))

        async def one(st: Any) -> Decision:
            async with sem:
                return await self.evaluate(st, questions)

        results = await asyncio.gather(*(one(st) for st in states), return_exceptions=True)
        for i, r in enumerate(results):
            if isinstance(r, BaseException):
                raise ClassifierError(f"classifier: evaluate_batch: state {i}: {r}") from r
        return list(results)  # type: ignore[arg-type]

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
                    # ``warning``, never ``error``: a degenerate-criteria report is
                    # advisory, and a consumer counting "has an error" as a failure
                    # must not count this one.
                    "warning": (
                        f"classifier: question {key!r} has degenerate criteria ({reason}) — "
                        "every option reads the same to the model and the answer ranks at "
                        "chance; describe what picking each option would MEAN (SPEC.md §8B)"
                    ),
                }
            )

    # ----------------------------------------------------------------------- #
    # Backends
    # ----------------------------------------------------------------------- #

    def _body(
        self, state: Any, questions: Mapping[str, Question], images: Optional[list["_LiftedImage"]] = None
    ) -> bytes:
        """Assemble the request: the canonical ``model`` + ``questions``, plus
        ``state`` VERBATIM as the host supplied it (after image lifting, when it
        carried parts), then the §8 Gap 1 pipeline in §8 order (base →
        ``request_params`` merge → ``body_transform`` → marshal).

        The ``cloudflare`` wire nests the same fields under ``input`` and carries
        images as data URLs in collection order.
        """
        qs = {k: _question_wire(k, q) for k, q in questions.items()}
        body: dict[str, Any]
        if self.preset.wire == "decisions":
            body = {
                "model": self.model,
                "input": _decisions_input(state, images),
                "questions": _decisions_questions(self.backend, self.model, questions),
            }
        elif self.preset.wire == "cloudflare":
            inp: dict[str, Any] = {"questions": qs, "state": state}
            if images:
                inp["images"] = [f"data:image/{im.format};base64,{im.data}" for im in images]
            body = {"model": self.model, "input": inp}
        else:
            body = {"model": self.model, "questions": qs, "state": state}
            if images:
                body["assets"] = _assets(images)
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

    async def _evaluate_wire(self, state: Any, questions: Mapping[str, Question], validate: bool) -> Decision:
        """Style ``systemone`` (every backend): lift, check, cache, post, decode."""
        sent, images = _lift_images(state)
        if images and not self.supports.images:
            raise ClassifierUnsupported(self.backend, self.model, "images")
        _check_images(images)
        assets = _assets(images) if images else None
        model_key = ""
        if self.cache is not None:
            model_key = classifier_model_cache_key(self.base_url, self.model)
            served = await _maybe_await(self.cache.get(model_key))
            if isinstance(served, str):
                dk = classifier_decision_cache_key(self.base_url, served, sent, assets, questions)
                hit = await _maybe_await(self.cache.get(dk))
                if isinstance(hit, str):
                    try:
                        d = _decision_from_wire(json.loads(hit))
                    except Exception:  # noqa: BLE001 — a corrupt entry is a miss
                        d = None
                    if d is not None and validate_decision(questions, d) is None:
                        d.cached = True
                        return d
        extra = {IMAGES_HEADER: "true"} if images and self.preset.images_header else None
        payload = await self._post(self._body(sent, questions, images), extra)
        try:
            parsed = json.loads(payload)
        except ValueError as e:
            if self.preset.wire == "cloudflare":
                raise ClassifierError("classifier: cloudflare: envelope not successful") from None
            raise ClassifierError(f"classifier: unparseable response: {e}") from None
        if self.preset.wire == "cloudflare":
            parsed = _unwrap_cloudflare(parsed)
            payload = canonical_json(parsed)
        elif self.preset.wire == "decisions":
            payload = _decisions_reply(parsed, questions)
            parsed = json.loads(payload)
        d = _decision_from_wire(parsed)
        verr = validate_decision(questions, d)
        if validate and verr is not None:
            raise verr
        if self.cache is not None and verr is None:
            served = d.model or self.model
            dk = classifier_decision_cache_key(self.base_url, served, sent, assets, questions)
            await _maybe_await(self.cache.set(model_key, served))
            await _maybe_await(self.cache.set(dk, payload.decode("utf-8", errors="replace")))
        return d

    async def _post(self, raw: bytes, extra_headers: Optional[Mapping[str, str]] = None) -> bytes:
        """The one POST, with the §8 retry budget — the same ``ErrorInfo``/tier
        classifier and the same ``Retry-After`` delay-seconds rule. There is no
        second retry policy, and no ``"suspend"`` tier here either.

        NO CREDENTIAL VALUE AND NO EXPANDED HEADER VALUE LEAVES THIS METHOD: an
        authentication failure names the status and the endpoint and nothing else,
        and a 401/403 body is never echoed back.
        """
        # ``endpoint`` is what errors name: the CONFIGURED base, ${ENV} unexpanded,
        # so an account id in a templated URL never reaches an error. ``url`` is
        # what is dialled, expanded at call time.
        endpoint = self.base_url.rstrip("/")
        if self.preset.wire == "decisions":
            endpoint += "/decisions"
        elif self.preset.wire != "cloudflare":
            endpoint += "/systemone"
        url = _expand_env(endpoint)
        retries = self.retries if self.retries > 0 else _DEFAULT_RETRIES
        base = (self.retry_base_ms if self.retry_base_ms > 0 else _DEFAULT_RETRY_BASE_MS) / 1000.0
        classify = self.on_error or (lambda info: "retry" if info.get("retryable") else "fail")
        attempt = 0
        while True:
            status = 0
            err: Optional[Exception] = None
            retry_after_header: Optional[str] = None
            try:
                res = await asyncio.to_thread(
                    self.http_transport.post, url, self._headers(extra_headers, url), raw, self.timeout
                )
                status, retry_after_header = res.status, res.retry_after
                if status < 300:
                    return res.body
                last = ClassifierError(f"classifier: POST {endpoint}: HTTP {status}{_cause(status, res.body)}")
            except Exception as e:  # noqa: BLE001 — a transport fault, not a status
                err = e
                msg = str(e)
                if url != endpoint:
                    msg = msg.replace(url, endpoint)
                last = ClassifierError(f"classifier: POST {endpoint}: {type(e).__name__}: {msg}")
            retryable = err is not None or status == 408 or _is_retryable_status(status, self.retryable_statuses)
            if attempt >= retries:
                raise last
            info: ErrorInfo = {"error": err, "status": status, "attempt": attempt, "retryable": retryable}
            if classify(info) != "retry":
                raise last
            delay = _parse_retry_after(retry_after_header)
            await asyncio.sleep(delay if delay is not None else base * (2**attempt))
            attempt += 1

    def _headers(self, extra: Optional[Mapping[str, str]] = None, url: str = "") -> dict[str, str]:
        """Built fresh per attempt: the credential is read from the NAMED env var
        at call time, and ``${ENV_VAR}`` header references expand at call time.
        ``extra`` (the images opt-in) goes before host headers, which win."""
        h = {"Content-Type": "application/json"}
        key_env = self.api_key_env if self.key_env_explicit else classifier_key_env(self.backend, url)
        key = os.environ.get(key_env) if key_env else None
        if key:
            h["Authorization"] = f"Bearer {key}"
        h.update(extra or {})
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

    def _emit_error(self, t0: float, err: BaseException, questions: int = 0, questions_hash: str = "") -> None:
        self._emit(
            {
                "event": METRIC_CLASSIFIER_EVALUATE,
                "model": self.model,
                "status": "error",
                "ms": int((time.monotonic() - t0) * 1000),
                "error": str(err),
                "questions": questions,
                "questions_hash": questions_hash,
                "cached": False,
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
    was sent, so it never reaches a log, a metric, an error or a return value.

    The policy now lives in :func:`toolnexus.client.safe_error_body` — the same
    cap, the same 401/403 blanking and the same account-identifier redaction on
    the §8 client path, which had none of it (D5)."""
    text = capped_error_body(status, body.decode("utf-8", errors="replace"))
    return ": " + text if text else ""


def _static_key(model: str, state: Any, questions: Mapping[str, Question]) -> bytes:
    """Identify a recorded decision by the canonical request AND the state. See
    :class:`RecordedDecision` for why the state is load-bearing here."""
    return canonical_request(model, questions) + b"\x00" + canonical_json(state)


def create_classifier(
    *,
    style: ClassifierStyle = "systemone",
    backend: Optional[ClassifierBackend] = None,
    base_url: Optional[str] = None,
    model: Optional[str] = None,
    api_key_env: Optional[str] = None,
    headers: Optional[Mapping[str, str]] = None,
    timeout: Optional[float] = None,
    http_transport: Optional[ClassifierTransport] = None,
    retries: int = _DEFAULT_RETRIES,
    retry_base_ms: int = _DEFAULT_RETRY_BASE_MS,
    retryable_statuses: Optional[Iterable[int]] = None,
    on_error: Optional[ErrorClassifier] = None,
    request_params: Optional[Mapping[str, Any]] = None,
    body_transform: Optional[Callable[[dict[str, Any]], Optional[dict[str, Any]]]] = None,
    on_metric: Optional[OnMetric] = None,
    client: Optional[Client] = None,
    evaluate: Optional[Callable[..., Any]] = None,
    decisions: Optional[list[RecordedDecision]] = None,
    supports: Optional[Union[ClassifierSupports, Mapping[str, Any]]] = None,
    cache: Optional["ClassifierCache"] = None,
) -> Classifier:
    """Build a :class:`Classifier`, applying the §8B defaults and rejecting a style
    whose required option is missing BEFORE any call is made.

    Mirrors §8 ``ClientOptions`` field-for-field wherever a field makes sense, so a
    host that has configured one has configured the other.
    """
    if style not in ("systemone", "llm", "custom", "static"):
        raise ClassifierError(f"classifier: unknown style {style!r}")
    backend_name = backend or "typesafe"
    if backend_name not in CLASSIFIER_BACKENDS:
        raise ClassifierError(
            f'classifier: unknown backend "{backend}" (want "typesafe", "openrouter", '
            '"cloudflare", "vercel", "openai" or "custom")'
        )
    if backend_name == "custom" and style == "systemone" and (not base_url or supports is None):
        raise ClassifierError('classifier: backend "custom" requires baseUrl and supports')
    preset = CLASSIFIER_BACKENDS[backend_name]
    # The preset is a UNIT; an explicit field still wins over it.
    effective_base = base_url or preset.base_url
    effective_model = model or preset.model
    explicit_key_env = bool(api_key_env)
    api_key_env = api_key_env or preset.api_key_env
    if "openrouter.ai" in effective_base and effective_model in _TYPESAFE_ONLY_MODELS:
        raise ClassifierError(
            f'classifier: model "{effective_model}" is TypeSafe\'s spelling; '
            f'on openrouter.ai use "{CLASSIFIER_BACKENDS["openrouter"].model}"'
        )
    if style == "llm" and client is None:
        raise ClassifierError(f"classifier: style {style!r} requires client")
    if style == "custom" and evaluate is None:
        raise ClassifierError(f"classifier: style {style!r} requires evaluate")
    return Classifier(
        style=style,
        base_url=effective_base,
        model=effective_model,
        api_key_env=api_key_env or DEFAULT_CLASSIFIER_API_KEY_ENV,
        key_env_explicit=explicit_key_env,
        headers=headers,
        timeout=timeout if timeout else DEFAULT_CLASSIFIER_TIMEOUT,
        http_transport=http_transport,
        retries=retries,
        retry_base_ms=retry_base_ms,
        retryable_statuses=retryable_statuses,
        on_error=on_error,
        request_params=request_params,
        body_transform=body_transform,
        on_metric=on_metric,
        client=client,
        evaluate=evaluate,
        decisions=decisions,
        backend=backend_name,
        supports=supports,
        cache=cache,
    )


# --------------------------------------------------------------------------- #
# Backends, images, cache and validation (SPEC §8B, change add-classifier-backends,
# ADR 0038). Every default here is a PROPOSED default, owner to confirm
# (openspec/changes/add-classifier-backends/design.md PD-1..PD-15).
# --------------------------------------------------------------------------- #


def _as_supports(v: Union[ClassifierSupports, Mapping[str, Any]]) -> ClassifierSupports:
    if isinstance(v, ClassifierSupports):
        return v
    return ClassifierSupports(images=bool(v.get("images", False)))


async def _maybe_await(v: Any) -> Any:
    """A cache's ``get``/``set`` may be sync or async."""
    if inspect.isawaitable(v):
        return await v
    return v


@dataclass
class _LiftedImage:
    ref: str
    #: The declared MIME type before :func:`_check_images`, the wire format after.
    format: str
    data: str


def _assets(images: Sequence[_LiftedImage]) -> dict[str, Any]:
    return {im.ref: {"type": "image", "format": im.format, "data_base64": im.data} for im in images}


def _is_part(v: Any) -> bool:
    from .content import ContentPart

    return isinstance(v, ContentPart)


def _contains_part(v: Any) -> bool:
    if _is_part(v):
        return True
    if isinstance(v, Mapping):
        return any(_contains_part(e) for e in v.values())
    if isinstance(v, (list, tuple)):
        return any(_contains_part(e) for e in v)
    return False


def _lift_images(state: Any) -> tuple[Any, list[_LiftedImage]]:
    """The §8B lifting walk. A state with no content part is returned VERBATIM
    (the same object), so a text-only state is byte-identical to before.

    Depth-first, object keys in ASCII order, arrays in order: an image part with
    inline data becomes a reference string (its key if unused, else the smallest
    unused ``image<n>``); a text part becomes its text; any other part is an error.
    """
    if not _contains_part(state):
        return state, []
    used: set[str] = set()
    images: list[_LiftedImage] = []

    def next_ref() -> str:
        n = 1
        while f"image{n}" in used:
            n += 1
        return f"image{n}"

    def walk(v: Any, key: str) -> Any:
        if _is_part(v):
            if v.type == "text":
                return v.text or ""
            if v.type == "image":
                if not v.data:
                    raise ClassifierError(
                        "classifier: state: an image part needs inline data (a URL image cannot be sent)"
                    )
                ref = key if key and key not in used else next_ref()
                used.add(ref)
                images.append(_LiftedImage(ref=ref, format=v.mimeType or "", data=v.data))
                return ref
            raise ClassifierError(f"classifier: state: {v.type} parts are not supported")
        if isinstance(v, Mapping):
            return {k: walk(v[k], k) for k in sorted(v)}
        if isinstance(v, (list, tuple)):
            return [walk(e, "") for e in v]
        return v

    return walk(state, ""), images


_IMAGE_FORMATS = {"image/png": "png", "image/jpeg": "jpeg", "image/jpg": "jpeg"}


def _check_images(images: list[_LiftedImage]) -> None:
    """Format, per-image and per-request limits, in collection order; normalises
    ``format`` in place. Errors name the reference. Never resizes."""
    total = 0
    for im in images:
        fmt = _IMAGE_FORMATS.get(im.format)
        if fmt is None:
            raise ClassifierError(
                f'classifier: image "{im.ref}": type "{im.format}" is not supported (PNG or JPEG only)'
            )
        im.format = fmt
        try:
            raw = base64.b64decode(im.data, validate=True)
        except Exception:  # noqa: BLE001
            raise ClassifierError(f'classifier: image "{im.ref}": data is not base64') from None
        if len(raw) > CLASSIFIER_IMAGE_MAX_BYTES:
            raise ClassifierError(f'classifier: image "{im.ref}": {len(raw)} bytes, over 16 MiB')
        size = _image_size(fmt, raw)
        if size is None:
            raise ClassifierError(f'classifier: image "{im.ref}": not a readable {fmt.upper()}')
        w, h = size
        lo, hi = CLASSIFIER_IMAGE_MIN_SIDE, CLASSIFIER_IMAGE_MAX_SIDE
        if not (lo <= w <= hi and lo <= h <= hi):
            raise ClassifierError(f'classifier: image "{im.ref}": {w}x{h}; each side must be {lo}..{hi} px')
        total += len(raw)
    if total > CLASSIFIER_REQUEST_IMAGE_BYTES:
        raise ClassifierError(
            f'classifier: image "{images[-1].ref}": request images total {total} bytes, over 32 MiB'
        )


def _image_size(fmt: str, b: bytes) -> Optional[tuple[int, int]]:
    """Width/height from a PNG IHDR or the first JPEG SOF marker; ``None`` when
    the bytes are not a readable header of that format."""
    if fmt == "png":
        if len(b) < 24 or b[:8] != b"\x89PNG\r\n\x1a\n" or b[12:16] != b"IHDR":
            return None
        return int.from_bytes(b[16:20], "big"), int.from_bytes(b[20:24], "big")
    if len(b) < 4 or b[0] != 0xFF or b[1] != 0xD8:
        return None
    i = 2
    while i + 3 < len(b):
        if b[i] != 0xFF:
            return None
        m = b[i + 1]
        if m == 0xFF:  # fill byte
            i += 1
            continue
        if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
            if i + 8 >= len(b):
                return None
            return (b[i + 7] << 8) | b[i + 8], (b[i + 5] << 8) | b[i + 6]
        i += 2 + ((b[i + 2] << 8) | b[i + 3])
    return None


# The Decisions API wire (SPEC §8B "Decisions wire", issue #157). OpenAI's POST
# /v1/decisions takes the same three question kinds under other names (predicate
# / choice / score) as an ORDERED LIST and answers in that order. These map a §8B
# question set onto that list and read the answers back into a System One-shaped
# body, so decode, validation and the cache stay one path.

#: The Decisions API's per-request question cap.
MAX_DECISIONS_QUESTIONS = 400
_MAX_DECISIONS_CHOICES = 255


def _decisions_questions(backend: str, model: str, questions: Mapping[str, Question]) -> list[dict[str, Any]]:
    """The question list, in ASCII key order. Caller keys are NOT sent: they are
    addressing, not content. Every check fires before any request."""
    if len(questions) > MAX_DECISIONS_QUESTIONS:
        raise ClassifierError(
            f"classifier: the decisions wire takes at most {MAX_DECISIONS_QUESTIONS} questions, got {len(questions)}"
        )
    out: list[dict[str, Any]] = []
    for k in sorted(questions):
        q = questions[k]
        if isinstance(q, NoulQuestion):
            instr = q.instructions
            if q.criteria is not None:
                instr += f"\n\nTrue means: {q.criteria.true}\nFalse means: {q.criteria.false}"
            out.append({"type": "predicate", "instructions": instr})
        elif isinstance(q, ChoiceQuestion):
            if len(q.criteria) < 2:
                raise ClassifierError(
                    f'classifier: question "{k}": the decisions wire needs 2..{_MAX_DECISIONS_CHOICES} '
                    f"choice options, got {len(q.criteria)}"
                )
            choices: list[dict[str, Any]] = []
            for cid in sorted(q.criteria):
                c: dict[str, Any] = {"value": cid}
                if q.criteria[cid]:
                    c["description"] = q.criteria[cid]
                choices.append(c)
            out.append({"type": "choice", "instructions": q.instructions, "choices": choices})
        elif isinstance(q, ScoreQuestion):
            levels = [{"label": lv} for lv in q.criteria]
            out.append({"type": "score", "instructions": q.instructions, "levels": levels})
        elif isinstance(q, RawQuestion):
            raise ClassifierUnsupported(backend, model, f'raw question type "{q.type}"')
        else:
            raise ClassifierError(f'classifier: question "{k}": unsupported question')
    return out


def _decisions_input(state: Any, images: Optional[Sequence["_LiftedImage"]]) -> Any:
    """A string state verbatim, any other state as its canonical JSON text; with
    images, one user message holding that text then the images as data URLs."""
    txt = state if isinstance(state, str) else canonical_json(state).decode("utf-8")
    if not images:
        return txt
    content: list[dict[str, Any]] = [{"type": "input_text", "text": txt}]
    for im in images:
        content.append({"type": "input_image", "image_url": f"data:image/{im.format};base64,{im.data}"})
    return [{"role": "user", "content": content}]


def _decisions_value(v: Any) -> str:
    """A choice value or level index as the §8B key: a string as written, a bool
    as ``true``/``false``, a number in its shortest form."""
    if isinstance(v, str):
        return v
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v)


def _num(v: Any) -> Any:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return 0
    return v


def _decisions_reply(reply: Any, questions: Mapping[str, Question]) -> bytes:
    """A Decisions API reply as a System One-shaped body (canonical JSON).
    ``calibrated`` is False: a Decisions model is not a System One model."""
    malformed = ClassifierError("classifier: decisions: malformed reply")
    if not isinstance(reply, dict) or not isinstance(reply.get("answers"), list):
        raise malformed
    keys = sorted(questions)
    got = reply["answers"]
    if len(got) != len(keys):
        raise ClassifierError(f"classifier: decisions: {len(got)} answers for {len(keys)} questions")
    want_type = {"noul": "predicate", "choice": "choice", "score": "score"}
    answers: dict[str, Any] = {}
    for k, a in zip(keys, got):
        if not isinstance(a, dict):
            raise malformed
        t = a.get("type")
        if t == "refusal":
            raise ClassifierError(f'classifier: decisions: question "{k}" was refused')
        q = questions[k]
        want = want_type.get(getattr(q, "type", ""), "")
        if t != want:
            raise ClassifierError(f'classifier: decisions: answer "{k}" is a {t} answer, not {want}')
        probs = {
            _decisions_value(p.get("value")): _num(p.get("probability"))
            for p in (a.get("probabilities") or [])
            if isinstance(p, dict)
        }
        if isinstance(q, NoulQuestion):
            answers[k] = {"type": "noul", "noul": _num(a.get("probability"))}
        elif isinstance(q, ChoiceQuestion):
            answers[k] = {"type": "choice", "choice": _decisions_value(a.get("choice")),
                          "probabilities": probs, "confidence": _num(a.get("confidence"))}
        elif isinstance(q, ScoreQuestion):
            answers[k] = {"type": "score", "score": _num(a.get("score")),
                          "legend": {str(i): lv for i, lv in enumerate(q.criteria)},
                          "probabilities": probs, "confidence": _num(a.get("confidence"))}
    usage = reply.get("usage") if isinstance(reply.get("usage"), dict) else {}
    model = reply.get("model")
    return canonical_json({
        "answers": answers,
        "calibrated": False,
        "model": model if isinstance(model, str) else "",
        "usage": {"input_tokens": int(_num(usage.get("input_tokens"))),
                  "output_tokens": int(_num(usage.get("output_tokens")))},
    })


def _cloudflare_cause(env: Any) -> str:
    """``": <code> <message>"`` from ``errors[0]`` of a failed envelope (SPEC §8B).
    Cloudflare reports a missing balance, a bad model id and a malformed request
    alike as HTTP 200 with ``success:false``, so this is the only thing that tells
    them apart. ``""`` when there is nothing to show."""
    errs = env.get("errors") if isinstance(env, Mapping) else None
    if not isinstance(errs, list) or not errs or not isinstance(errs[0], Mapping):
        return ""
    code, msg = errs[0].get("code"), errs[0].get("message")
    parts = []
    if code is not None and not isinstance(code, (bool, dict, list)) and str(code) != "":
        parts.append(str(code))
    if isinstance(msg, str) and msg:
        parts.append(msg)
    text = capped_error_body(200, " ".join(parts))
    return ": " + text if text else ""


def _unwrap_cloudflare(env: Any) -> Any:
    """The System One reply inside a Cloudflare ``/ai/run`` envelope. A failed
    envelope surfaces only ``errors[0]`` as ``<code> <message>``, through the same
    redaction and cap as :func:`_cause` (it can carry an account id)."""
    if not isinstance(env, Mapping) or env.get("success") is not True:
        raise ClassifierError("classifier: cloudflare: envelope not successful" + _cloudflare_cause(env))
    res = env.get("result")
    if isinstance(res, Mapping) and isinstance(res.get("state"), str):
        if res["state"] != "Completed":
            raise ClassifierError(f'classifier: cloudflare: run state "{res["state"]}"')
        res = res.get("result")
    return res


# --------------------------------------------------------------------------- #
# Cache
# --------------------------------------------------------------------------- #


class ClassifierCache(Protocol):
    """An injected answer cache (§8B). Values are opaque strings: the served model
    under a model key, the System One reply body under a decision key. ``get``
    returns ``None`` when absent. Either method may be sync or async."""

    def get(self, key: str) -> Any: ...

    def set(self, key: str, value: str) -> Any: ...


class MemoryCache:
    """In-process TTL cache with a size cap (oldest-inserted out first), safe for
    concurrent use. ``ttl`` in seconds, ``0`` = no expiry; ``max`` ``0`` =
    unbounded. toolnexus persists nothing itself."""

    def __init__(self, ttl: float = 0, max: int = 0) -> None:  # noqa: A002 — the §8B name
        self.ttl = ttl
        self.max = max
        self._lock = threading.Lock()
        self._m: dict[str, tuple[str, float]] = {}  # insertion order = age
        self._now: Callable[[], float] = time.monotonic

    def get(self, key: str) -> Optional[str]:
        with self._lock:
            e = self._m.get(key)
            if e is None:
                return None
            if self.ttl > 0 and self._now() - e[1] > self.ttl:
                del self._m[key]
                return None
            return e[0]

    def set(self, key: str, value: str) -> None:
        with self._lock:
            self._m.pop(key, None)  # a re-set counts as a fresh insertion
            self._m[key] = (value, self._now())
            while self.max > 0 and len(self._m) > self.max:
                del self._m[next(iter(self._m))]


def classifier_model_cache_key(endpoint: str, model: str) -> str:
    """§8B model key: which model an endpoint+alias served last. ``endpoint`` is
    the configured ``base_url``, ``${ENV}`` unexpanded."""
    b = canonical_json({"endpoint": endpoint, "kind": "model", "model": model})
    return hashlib.sha256(b).hexdigest()


def classifier_decision_cache_key(
    endpoint: str,
    served_model: str,
    state: Any,
    assets: Optional[Mapping[str, Any]],
    questions: Mapping[str, Question],
) -> str:
    """§8B decision key, on the SERVED model. Empty/``None`` assets ⇒ the
    ``assets`` member is omitted."""
    m: dict[str, Any] = {
        "endpoint": endpoint,
        "kind": "decision",
        "model": served_model,
        "questions": {k: _question_wire(k, q) for k, q in questions.items()},
        "state": state,
    }
    if assets:
        m["assets"] = dict(assets)
    return hashlib.sha256(canonical_json(m)).hexdigest()


# --------------------------------------------------------------------------- #
# V1 / T1 / C1
# --------------------------------------------------------------------------- #


def _question_type(q: Question) -> str:
    return q.type


def validate_decision(questions: Mapping[str, Question], decision: Decision) -> Optional[ClassifierError]:
    """Fail closed on a reply that does not answer exactly the questions asked,
    within their schema (ADR 0038 V1). Returns the FIRST failure in the §8B order,
    or ``None`` when valid. Every message starts ``classifier: invalid decision: ``."""

    def bad(msg: str) -> ClassifierError:
        return ClassifierError("classifier: invalid decision: " + msg)

    def in01(p: float) -> bool:
        return not math.isnan(p) and 0 <= p <= 1

    answers = decision.answers
    keys = sorted(questions)
    for k in keys:
        if k not in answers:
            return bad(f'no answer "{k}"')
    extra = sorted(k for k in answers if k not in questions)
    if extra:
        return bad(f'unexpected answer "{extra[0]}"')

    def dist(k: str, probs: Mapping[str, float], offered: Callable[[str], bool]) -> Optional[ClassifierError]:
        total = 0.0
        for o in sorted(probs):
            if not offered(o):
                return bad(f'answer "{k}": probability for "{o}", which was not offered')
            if not in01(probs[o]):
                return bad(f'answer "{k}": probability {probs[o]} outside [0,1]')
            total += probs[o]
        if probs and abs(total - 1) > 0.02:
            return bad(f'answer "{k}": probabilities sum to {total:.4f}')
        return None

    for k in keys:
        q, a = questions[k], answers[k]
        qt = _question_type(q)
        if a.type != qt:
            return bad(f'answer "{k}" is a {a.type} answer, not {qt}')
        if isinstance(a, NoulAnswer):
            if not in01(a.noul):
                return bad(f'answer "{k}": noul {a.noul} outside [0,1]')
        elif isinstance(a, ChoiceAnswer):
            crit = q.criteria if isinstance(q, ChoiceQuestion) else {}
            offered = crit.__contains__
            if not offered(a.choice):
                return bad(f'answer "{k}": choice "{a.choice}" was not offered')
            err = dist(k, a.probabilities, offered)
            if err is not None:
                return err
            if not in01(a.confidence):
                return bad(f'answer "{k}": confidence {a.confidence} outside [0,1]')
        elif isinstance(a, ScoreAnswer):
            n = len(q.criteria) if isinstance(q, ScoreQuestion) else 0
            if math.isnan(a.score) or a.score < 0 or a.score > n - 1:
                return bad(f'answer "{k}": score {a.score} outside [0,{n - 1}]')
            levels = {str(i) for i in range(n)}
            err = dist(k, a.probabilities, levels.__contains__)
            if err is not None:
                return err
            if not in01(a.confidence):
                return bad(f'answer "{k}": confidence {a.confidence} outside [0,1]')
    return None


@dataclass(frozen=True)
class Thresholds:
    """jevx's INCLUSIVE cut-points (ADR 0038 T1), alongside the exclusive
    :class:`toolnexus.judge.Bands`, which stays the default of ``ask``/``gate``."""

    yes: float = 0.8
    no: float = 0.2
    min_confidence: float = 0.6

    def band(self, answer: DecisionAnswer) -> str:
        """noul ``p >= yes`` ⇒ yes, ``p <= no`` ⇒ no, else uncertain; choice/score
        ``confidence >= min_confidence`` ⇒ yes, else uncertain (no near-uniform
        demotion). A raw answer is uncertain."""
        if isinstance(answer, NoulAnswer):
            if answer.noul >= self.yes:
                return "yes"
            if answer.noul <= self.no:
                return "no"
        elif isinstance(answer, (ChoiceAnswer, ScoreAnswer)):
            if answer.confidence >= self.min_confidence:
                return "yes"
        return "uncertain"


#: 0.8 / 0.2 / 0.6 (jevx's builtin profile).
DEFAULT_THRESHOLDS = Thresholds()


def chunk_questions(questions: Mapping[str, Question], size: int) -> list[dict[str, Question]]:
    """Split a question set into chunks of ``size``, keys in ASCII order. A helper,
    not automatic: joint calibration across a split is unproven (§8B)."""
    if size < 1:
        raise ClassifierError(f"classifier: chunk size must be at least 1, got {size}")
    keys = sorted(questions)
    return [{k: questions[k] for k in keys[i : i + size]} for i in range(0, len(keys), size)]


# --------------------------------------------------------------------------- #
# Value builders
# --------------------------------------------------------------------------- #

#: At most this many states in flight in :meth:`ClassifierStates.evaluate`.
_STATES_CONCURRENCY = 16


def _named_questions(qs: Sequence[tuple[str, Question]]) -> dict[str, Question]:
    out: dict[str, Question] = {}
    for name, q in qs:
        if name in out:
            raise ClassifierError(f'duplicate question name "{name}"')
        out[name] = q
    return out


@dataclass
class BatchResult:
    """One state's outcome: exactly one of ``decision`` / ``error`` is set."""

    decision: Optional[Decision] = None
    error: Optional[BaseException] = None


class ClassifierState:
    """A bound state — a VALUE, not a session: nothing is sent until :meth:`evaluate`."""

    def __init__(self, classifier: Classifier, value: Any) -> None:
        self.classifier = classifier
        self.value = value

    async def evaluate(self, *qs: tuple[str, Question]) -> Decision:
        """Ask the named questions (``judge.noul(...)`` etc.) in one request, and
        validate the reply fail-closed (:func:`validate_decision`)."""
        return await self.classifier._evaluate(self.value, _named_questions(qs), True)


class ClassifierStates:
    """A batch of bound states."""

    def __init__(self, classifier: Classifier, values: list[Any]) -> None:
        self.classifier = classifier
        self.values = values

    async def evaluate(self, *qs: tuple[str, Question]) -> list[BatchResult]:
        """``state(v).evaluate`` per state, at most 16 in flight, one result per state
        in order. One state's failure never fails the batch; only a question-set
        error (duplicate name) raises, before any request."""
        questions = _named_questions(qs)
        if not self.values:
            return []
        sem = asyncio.Semaphore(_STATES_CONCURRENCY)

        async def one(v: Any) -> BatchResult:
            async with sem:
                try:
                    return BatchResult(decision=await self.classifier._evaluate(v, questions, True))
                except Exception as e:  # noqa: BLE001 — carried per item
                    return BatchResult(error=e)

        return list(await asyncio.gather(*(one(v) for v in self.values)))
