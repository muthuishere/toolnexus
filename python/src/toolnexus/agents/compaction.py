"""Context compaction (SPEC.md §7F) — a ``beforeLLM`` helper, no loop change.

The shipped client loop (§8) already lets a ``before_llm`` hook REPLACE the
working transcript (``messages = ov["messages"]``), and that same list flows into
``RunResult.messages`` and the ``ConversationStore``. So compaction is a pure
``messages -> messages`` function wired through the existing seam — not a new
subsystem. Below ``max_tokens`` it is a byte-identical no-op (returns ``None``);
above it, it summarizes the head and keeps a recent tail, TOOL-PAIR-SAFE (the tail
always begins at a ``user`` turn, so a ``tool`` message is never orphaned from its
``tool_calls``). Optional pre-compact "flush to memory" nudge (§7E memory tool).

**Estimator convention (cross-port byte parity).** The default token estimate is
``ceil(len(serialized message) / 4)`` summed over messages, matching the JS port's
``ceil(JSON.stringify(m).length / 4)``. To reproduce JS ``JSON.stringify`` bytes we
serialize with ``json.dumps(m, separators=(",", ":"), ensure_ascii=False)`` — no
inter-token whitespace, non-ASCII left unescaped, insertion key order preserved.
Every port MUST use this same compact, unspaced serialization so the split point is
identical for the shared ``examples/compaction`` fixture.
"""
from __future__ import annotations

import inspect
import json
import math
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional, Union

Message = dict[str, Any]

# Summarizer may be sync or async; count_tokens is sync.
Summarize = Callable[[list[Message]], Union[str, Awaitable[str]]]
CountTokens = Callable[[list[Message]], int]

__all__ = ["estimate_tokens", "compactor", "CompactInfo"]


@dataclass
class CompactInfo:
    """One compaction, for ``compactor(on_compact=...)`` (§4C). Token counts come from
    ``count_tokens`` (default :func:`estimate_tokens`)."""

    tokens_before: int
    tokens_after: int
    messages_before: int
    messages_after: int
    # How many messages were folded into the summary.
    summarized: int
    # The text ``summarize`` returned (without the header line).
    summary: str
    # The summarized messages, verbatim and in order, so a host can persist them
    # (the loop saves only the compacted transcript).
    folded: list[Message] = field(default_factory=list)


OnCompact = Callable[[CompactInfo], Any]


def _serialize(message: Message) -> str:
    """JS ``JSON.stringify(message)``-equivalent bytes (compact, unspaced)."""
    return json.dumps(message, separators=(",", ":"), ensure_ascii=False)


def _carries_tool_result(message: Message) -> bool:
    """Does this message carry a tool result?

    Under the anthropic dialect a tool result is a ``user`` message whose content holds
    ``tool_result`` blocks, making it a tool-group member rather than a boundary.
    """
    content = message.get("content")
    if not isinstance(content, list):
        return False
    return any(
        isinstance(block, dict) and block.get("type") == "tool_result" for block in content
    )


def _is_boundary(message: Message) -> bool:
    """A valid tail boundary: a ``user`` turn that is not carrying a tool result."""
    return message.get("role") == "user" and not _carries_tool_result(message)


def estimate_tokens(messages: list[Message]) -> int:
    """Cheap, deterministic token estimate (chars/4). Override for a real tokenizer.

    Sums ``ceil(len(serialized message) / 4)`` over messages — identical math and
    serialization to the JS ``estimateTokens`` so every port splits at the same point.
    """
    total = 0
    for m in messages:
        total += math.ceil(len(_serialize(m)) / 4)
    return total


def compactor(
    *,
    max_tokens: int,
    summarize: Summarize,
    keep_tail: Optional[int] = None,
    count_tokens: Optional[CountTokens] = None,
    flush_to_memory: bool = False,
    on_compact: Optional[OnCompact] = None,
) -> Callable[[dict[str, Any]], Awaitable[Optional[dict[str, Any]]]]:
    """Build a ``before_llm`` hook that compacts the transcript when it grows too large.

    Returns a coroutine hook that yields ``{"messages": [...]}`` only when it actually
    compacts; otherwise ``None`` (a byte-identical no-op).

    Args:
        max_tokens: compact only when the estimate exceeds this; at/below ⇒ no-op.
        summarize: ``summarize(older) -> str`` producing the summary. MAY call an LLM
            (may be sync or async). Required.
        keep_tail: keep at least this many tokens of the most recent tail
            (default ``max_tokens // 2``).
        count_tokens: token estimator; default :func:`estimate_tokens`.
        flush_to_memory: inject a pre-compact system reminder to persist durable facts
            via the §7E memory tool before summarizing. Default off.
        on_compact: ``on_compact(info: CompactInfo)`` (sync or async), called after
            each compaction — never on a no-op — so a host can tell its UI or log (§4C).
    """
    count: CountTokens = count_tokens or estimate_tokens
    tail_budget = keep_tail if keep_tail is not None else max_tokens // 2

    async def before_llm(ev: dict[str, Any]) -> Optional[dict[str, Any]]:
        msgs: list[Message] = ev["messages"]
        before = count(msgs)
        if before <= max_tokens:
            return None  # under budget → byte-identical no-op

        # Preserve a leading system prompt verbatim (identity/soul/skills — never summarized).
        head0 = 1 if msgs and msgs[0].get("role") == "system" else 0
        system = msgs[:head0]

        # Find the split: the LARGEST tail (from a clean USER boundary) that fits keep_tail.
        # A boundary is a user turn that does NOT carry a tool result: under the anthropic
        # dialect tool results ride inside a `user` message, so splitting there would
        # orphan them from the assistant `tool_use` about to be summarized away.
        split = len(msgs)
        for i in range(len(msgs) - 1, head0, -1):
            if _is_boundary(msgs[i]) and count(msgs[i:]) <= tail_budget:
                split = i
            if count(msgs[i:]) > tail_budget:
                break
        # If no clean boundary fit in range, fall back to the most recent one so we still
        # never split a tool group (may keep more than keep_tail — safety over size).
        if split == len(msgs):
            for i in range(len(msgs) - 1, head0, -1):
                if _is_boundary(msgs[i]):
                    split = i
                    break
        if split <= head0:
            return None  # nothing safely compactible

        older = msgs[head0:split]
        tail = msgs[split:]
        # The latest user prompt is never summarized away. When the only clean boundary
        # is the first message after the system prompt (one prompt followed by a long
        # agentic run), keep that prompt verbatim and summarize the work after it:
        # [system, prompt, summary].
        pinned: list[Message] = []
        if split == len(msgs) and head0 < len(msgs) and _is_boundary(msgs[head0]):
            pinned = msgs[head0 : head0 + 1]
            older = msgs[head0 + 1 :]
        if not older:
            return None

        flush_note: list[Message] = (
            [
                {
                    "role": "system",
                    "content": (
                        "Before continuing: if anything from earlier is worth keeping, "
                        "save it with the memory tool now — the earlier transcript is "
                        "about to be summarized."
                    ),
                }
            ]
            if flush_to_memory
            else []
        )

        result = summarize(older)
        if inspect.isawaitable(result):
            result = await result
        summary_msg: Message = {
            "role": "system",
            "content": f"[Summary of earlier conversation]\n{result}",
        }
        out = [*system, *pinned, summary_msg, *flush_note, *tail]
        after = count(out)
        if after >= before:
            return None  # compaction would not shrink the transcript → no-op
        if on_compact is not None:
            r = on_compact(
                CompactInfo(
                    tokens_before=before,
                    tokens_after=after,
                    messages_before=len(msgs),
                    messages_after=len(out),
                    summarized=len(older),
                    summary=str(result),
                    folded=list(older),
                )
            )
            if inspect.isawaitable(r):
                await r
        return {"messages": out}

    return before_llm
