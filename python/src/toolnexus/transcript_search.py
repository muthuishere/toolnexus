"""``transcript_search_tool`` — read-only search over a conversation store (SPEC.md §7E
"Memory hierarchy" → transcript search).

The ground-truth tier of agent memory: a dreaming persona checks its notes against what
actually happened, not against its own notes.
"""
from __future__ import annotations

import json
import re
from typing import Any, Optional

from .types import Tool, ToolResult

TRANSCRIPT_SEARCH_DESCRIPTION = (
    "Search past conversation transcripts — the ground truth of what actually happened. "
    "Case-insensitive substring match over message text. Returns one line per matching "
    "message: <conversationId>#<index> <role>: <snippet>. Read-only."
)

_WS = re.compile(r"[ \t\n\r\f\v]+")
_ASCII_FOLD = str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz")


def _normalize(s: str) -> str:
    """Collapse every run of ASCII whitespace to one space and trim (§7E)."""
    s = _WS.sub(" ", s)
    if s.startswith(" "):
        s = s[1:]
    if s.endswith(" "):
        s = s[:-1]
    return s


def _fold(s: str) -> str:
    """ASCII-only case fold — never Unicode lowercasing (it changes lengths)."""
    return s.translate(_ASCII_FOLD)


def _parts_text(content: Any) -> list[str]:
    if isinstance(content, str):
        return [content]
    if not isinstance(content, list):
        return []
    out: list[str] = []
    for part in content:
        if not isinstance(part, dict):
            continue
        kind = part.get("type")
        if kind == "text" and isinstance(part.get("text"), str):
            out.append(part["text"])
        elif kind == "tool_use":
            inp = part.get("input")
            out.append(json.dumps({} if inp is None else inp, separators=(",", ":"), ensure_ascii=False, sort_keys=True))
        elif kind == "tool_result":
            out.extend(_parts_text(part.get("content")))
    return out


def message_text(msg: Any) -> str:
    """The searchable text of one stored message (§7E): string content, text parts,
    tool_use input as compact JSON, tool_result content, then each OpenAI tool call's
    ``arguments`` string — joined by "\\n", then whitespace-normalized."""
    if not isinstance(msg, dict):
        return ""
    pieces = _parts_text(msg.get("content"))
    calls = msg.get("tool_calls")
    if isinstance(calls, list):
        for tc in calls:
            fn = tc.get("function") if isinstance(tc, dict) else None
            a = fn.get("arguments") if isinstance(fn, dict) else None
            if isinstance(a, str):
                pieces.append(a)
    return _normalize("\n".join(pieces))


def _snippet(text: str, at: int, qlen: int, size: int) -> str:
    if len(text) <= size:
        return text
    start = at + qlen // 2 - size // 2
    start = max(0, min(start, len(text) - size))
    end = start + size
    return ("…" if start > 0 else "") + text[start:end] + ("…" if end < len(text) else "")


def _is_int(v: Any) -> bool:
    if isinstance(v, bool):
        return False
    return isinstance(v, int) or (isinstance(v, float) and v.is_integer())


def transcript_search_tool(
    store: Any,
    *,
    max_results: int = 20,
    snippet_chars: int = 200,
    name: str = "transcript_search",
) -> Tool:
    """A read-only :class:`Tool` over ``store`` (§7E). Construction raises unless the
    store has ``list()``. Input ``{query, conversationId?, limit?}``; output one line per
    hit, ``<conversationId>#<index> <role>: <snippet>``, ``no matches`` when none, and a
    trailing ``(+<k> more)`` when hits exceed the limit. Never writes."""
    if store is None or not callable(getattr(store, "list", None)):
        raise ValueError("transcriptSearchTool: the store must implement list()")
    if not _is_int(max_results) or max_results < 1:
        raise ValueError("transcriptSearchTool: maxResults must be an integer >= 1")
    if not _is_int(snippet_chars) or snippet_chars < 1:
        raise ValueError("transcriptSearchTool: snippetChars must be an integer >= 1")
    max_results = int(max_results)
    snippet_chars = int(snippet_chars)

    async def execute(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
        a = args or {}
        q_raw = a.get("query")
        query = _normalize(q_raw) if isinstance(q_raw, str) else ""
        if query == "":
            return ToolResult(output="query is required", is_error=True)
        limit = max_results
        if a.get("limit") is not None:
            lv = a["limit"]
            if not _is_int(lv) or lv < 1:
                return ToolResult(output="limit must be an integer >= 1", is_error=True)
            limit = min(int(lv), max_results)
        cid = a.get("conversationId")
        if isinstance(cid, str):
            ids = [cid]
        else:
            ids = [e.id if hasattr(e, "id") else e["id"] for e in await store.list()]
        q = _fold(query)
        hits: list[str] = []
        total = 0
        for id_ in ids:
            msgs = await store.get(id_)
            if not msgs:
                continue
            for index, msg in enumerate(msgs):
                text = message_text(msg)
                at = _fold(text).find(q)
                if at < 0:
                    continue
                total += 1
                if len(hits) < limit:
                    role = msg.get("role") if isinstance(msg, dict) else None
                    role = role if isinstance(role, str) else "unknown"
                    hits.append(f"{id_}#{index} {role}: {_snippet(text, at, len(q), snippet_chars)}")
        if total == 0:
            return ToolResult(output="no matches", is_error=False)
        if total > len(hits):
            hits.append(f"(+{total - len(hits)} more)")
        return ToolResult(output="\n".join(hits), is_error=False)

    return Tool(
        name=name,
        description=TRANSCRIPT_SEARCH_DESCRIPTION,
        input_schema={
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Text to find (case-insensitive substring)."},
                "conversationId": {"type": "string", "description": "Search only this conversation."},
                "limit": {"type": "integer", "description": f"Most hits to return (default and max {max_results})."},
            },
            "required": ["query"],
        },
        source="native",
        execute=execute,
    )


__all__ = ["TRANSCRIPT_SEARCH_DESCRIPTION", "message_text", "transcript_search_tool"]
