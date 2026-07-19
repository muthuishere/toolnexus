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
from typing import Any, Awaitable, Callable, Optional, Union

Message = dict[str, Any]

# Summarizer may be sync or async; count_tokens is sync.
Summarize = Callable[[list[Message]], Union[str, Awaitable[str]]]
CountTokens = Callable[[list[Message]], int]

__all__ = ["estimate_tokens", "compactor"]


def _serialize(message: Message) -> str:
    """JS ``JSON.stringify(message)``-equivalent bytes (compact, unspaced)."""
    return json.dumps(message, separators=(",", ":"), ensure_ascii=False)


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
    """
    count: CountTokens = count_tokens or estimate_tokens
    tail_budget = keep_tail if keep_tail is not None else max_tokens // 2

    async def before_llm(ev: dict[str, Any]) -> Optional[dict[str, Any]]:
        msgs: list[Message] = ev["messages"]
        if count(msgs) <= max_tokens:
            return None  # under budget → byte-identical no-op

        # Preserve a leading system prompt verbatim (identity/soul/skills — never summarized).
        head0 = 1 if msgs and msgs[0].get("role") == "system" else 0
        system = msgs[:head0]

        # Find the split: the LARGEST tail (from a clean USER boundary) that fits keep_tail.
        # Scanning to a user turn guarantees tool-pair safety — a tail starting at a user
        # message can never orphan a `tool` result from its `tool_calls`.
        split = len(msgs)
        for i in range(len(msgs) - 1, head0, -1):
            if msgs[i].get("role") == "user" and count(msgs[i:]) <= tail_budget:
                split = i
            if count(msgs[i:]) > tail_budget:
                break
        # If no clean user boundary fit in range, fall back to the last user turn so we
        # still never split a tool group (may keep more than keep_tail — safety over size).
        if split == len(msgs):
            for i in range(len(msgs) - 1, head0, -1):
                if msgs[i].get("role") == "user":
                    split = i
                    break
        if split <= head0:
            return None  # nothing safely compactible

        older = msgs[head0:split]
        tail = msgs[split:]
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
        return {"messages": [*system, summary_msg, *flush_note, *tail]}

    return before_llm
