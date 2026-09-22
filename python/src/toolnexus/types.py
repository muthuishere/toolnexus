"""Shared contract types. See ../../SPEC.md.

Public API names are snake_case but conceptually identical to the JS reference
(``js/src/types.ts``).
"""
from __future__ import annotations

import itertools
import re
import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Literal, Optional, Protocol

# A JSON-Schema object: {"type": "object", "properties": {...}, ...}
JSONSchema = dict[str, Any]

ToolSource = Literal["mcp", "skill", "native", "http", "builtin", "a2a", "custom"]
McpStatus = Literal["connected", "disabled", "failed"]


@dataclass
class ToolResult:
    output: str
    is_error: bool
    metadata: Optional[dict[str, Any]] = None
    # §1B non-text output (image/file/audio parts). ``output`` stays required and stays
    # what the transcript, compaction, token estimation and text-only providers see;
    # ``parts`` absent ⇒ byte-identical to a pre-0.17 result. Appended AFTER ``metadata``
    # so every existing keyword construction keeps working.
    parts: Optional[list[dict[str, Any]]] = None


@dataclass
class Request:
    """§10 Suspension. Byte-identical wire data — it crosses languages, processes,
    and agents unchanged, so the keys are FIXED (``expiresAt`` stays camelCase even
    in Python, unlike the idiomatic snake_case RunResult). Mirrors ``js/src/types.ts``.
    """
    id: str
    kind: str  # "authorization" | "approval" | "input" | ... (open vocabulary)
    prompt: str
    url: Optional[str] = None
    data: Optional[dict[str, Any]] = None
    expiresAt: Optional[str] = None  # noqa: N815 — wire key, fixed across ports


@dataclass
class Answer:
    """§10 Suspension — the resolution of a :class:`Request`. Byte-identical wire data."""
    id: str
    ok: bool
    data: Optional[dict[str, Any]] = None
    # When ``ok`` is False, why (advisory — the loop rule branches only on ``ok``).
    # Distinguishes an explicit refusal from a dismissal/timeout; the MCP elicitation
    # bridge maps ``declined``→``decline`` and everything else→``cancel``. (R1)
    reason: Optional[Literal["declined", "cancelled", "expired"]] = None


@dataclass
class ToolContext:
    # cancellation token (e.g. an asyncio cancellation) — optional / advisory
    signal: Optional[Any] = None
    # timeout in seconds (Python convention; JS uses ms)
    timeout: Optional[float] = None
    # Present ONLY on a post-waitFor retry (§10): the resolution of a prior suspension.
    answer: Optional[Answer] = None


_pending_seq = itertools.count(1)


def pending(
    *,
    kind: str,
    prompt: str,
    id: Optional[str] = None,  # noqa: A002
    url: Optional[str] = None,
    data: Optional[dict[str, Any]] = None,
    expiresAt: Optional[str] = None,  # noqa: N803 — wire key
) -> ToolResult:
    """Producer helper (§10): return a suspension — a :class:`ToolResult` carrying
    ``metadata["pending"]`` = a :class:`Request`. Mirrors the JS ``pending``."""
    rid = id or f"pnd-{int(time.time() * 1000):x}-{next(_pending_seq)}"
    req = Request(id=rid, kind=kind, prompt=prompt, url=url, data=data, expiresAt=expiresAt)
    return ToolResult(
        output=prompt + (f"\n{url}" if url else ""),
        is_error=True,
        metadata={"pending": req},
    )


def answer_output(id: str, output: str) -> Answer:  # noqa: A002 — wire key
    """Build the §10 ``Answer`` that supplies a RESULT for an outstanding call (D4,
    #89, ADR 0026).

    ``Answer.data`` is a free-form dict, so hosts have been guessing which key the
    library reads (``value``? ``answers``? ``output``?) and a wrong guess was
    SILENT — the model was handed a fabricated result and the host was told
    ``done``. This constructor is the one spelling, and it refuses a non-string
    ``output`` loudly rather than degrading it to ``""``.
    """
    if not isinstance(output, str):
        raise TypeError(f"toolnexus: Answer output must be a str, got {type(output).__name__}")
    return Answer(id=id, ok=True, data={"output": output})


def answer_declined(id: str, reason: str = "declined") -> Answer:  # noqa: A002 — wire key
    """Build the §10 ``Answer`` that REFUSES an outstanding request (addendum A9).

    ``reason`` is the advisory §10 vocabulary — ``declined`` | ``cancelled`` |
    ``expired``; the loop rule itself branches only on ``ok``.
    """
    if reason not in ("declined", "cancelled", "expired"):
        raise ValueError(
            f'toolnexus: Answer reason must be "declined", "cancelled" or "expired", got {reason!r}'
        )
    return Answer(id=id, ok=False, reason=reason)  # type: ignore[arg-type]


def auth_required(url: str, prompt: str = "Authorization required to continue") -> ToolResult:
    """Sugar for the common case (§10): ``kind="authorization"`` at a login URL."""
    return pending(kind="authorization", prompt=prompt, url=url)


def pending_of(result: ToolResult) -> Optional[Request]:
    """Read the suspension off a result, if any (§10). Mirrors the JS ``pendingOf``."""
    if result.metadata is None:
        return None
    return result.metadata.get("pending")


# An async execute callable: (args, ctx) -> ToolResult
ExecuteFn = Callable[..., Awaitable["ToolResult"]]


@dataclass
class Tool:
    name: str
    description: str
    input_schema: JSONSchema
    source: ToolSource
    # async def execute(args: dict, ctx: ToolContext | None = None) -> ToolResult
    execute: ExecuteFn = field(repr=False)


_SANITIZE_RE = re.compile(r"[^a-zA-Z0-9_-]")


def sanitize(value: str) -> str:
    """Replace anything outside [a-zA-Z0-9_-] with "_" (same as opencode)."""
    return _SANITIZE_RE.sub("_", value)
