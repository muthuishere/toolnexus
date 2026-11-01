"""Tool tiers (SPEC §8 "Prompt caching", change add-prompt-cache phase 2).

With ``create_client(tool_tiers=...)`` only CORE tools are sent with full schemas.
LISTED tools appear as ``name — one line`` and DEFERRED tools by name only, both
inside the description of a ``tool_search`` tool that returns full schemas on demand
(as a tool result: the tools array itself never changes mid-session, so the cached
prefix survives). A loaded tool is called directly by name, or through ``call_tool``
``{name, arguments}``; the loop unwraps ``call_tool`` before hooks run, so hooks,
hints and permission gates always see the real tool. Mirrors ``golang/tool_tiers.go``.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from typing import Any, Callable, Literal, Optional

from .types import Tool, ToolHints, ToolResult

ToolTier = Literal["core", "listed", "deferred"]
TIER_CORE: ToolTier = "core"
TIER_LISTED: ToolTier = "listed"
TIER_DEFERRED: ToolTier = "deferred"


@dataclass
class ToolTierOptions:
    """``create_client(tool_tiers=...)``."""

    # Always sent with their full schema.
    core: list[str] = field(default_factory=list)
    # Named only. Every other tool is listed (name + one line). A tool registered
    # after the plan froze is deferred and found by search.
    deferred: list[str] = field(default_factory=list)
    # Caps the tokens sent up front (core schemas + tool_search + call_tool). 0 ⇒
    # 5% of context_window; both 0 ⇒ no budget. Over budget, the least-used core
    # tools move to listed, then the least-used listed tools to deferred.
    budget_tokens: int = 0
    context_window: int = 0
    # The host's per-tool usage stat (higher = more used), read only when the plan
    # is built. None ⇒ all zero (ties: larger schema first, then name).
    usage_count: Optional[Callable[[str], int]] = None
    # Token estimate of a JSON-encodable value; default ceil(utf-8 bytes / 4).
    count_tokens: Optional[Callable[[Any], int]] = None


def default_count_tokens(v: Any) -> int:
    # Compact, key-sorted JSON — the same bytes Go's encoding/json produces for a map.
    b = json.dumps(v, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return math.ceil(len(b) / 4)


def _tool_def(t: Tool) -> dict[str, Any]:
    return {"name": t.name, "description": t.description, "inputSchema": t.input_schema}


def _first_line(s: str) -> str:
    """First line, cut to the longest valid UTF-8 prefix of at most 120 BYTES (as Go)."""
    s = s.split("\n", 1)[0]
    b = s.encode("utf-8")
    if len(b) <= 120:
        return s
    return b[:120].decode("utf-8", errors="ignore") + "…"


_GO_ESCAPES = {"<": "\\u003c", ">": "\\u003e", "&": "\\u0026", "\u2028": "\\u2028", "\u2029": "\\u2029"}


def _go_html_escape(text: str) -> str:
    """Go's json escapes <, >, &, U+2028 and U+2029; they only ever occur inside JSON strings."""
    return "".join(_GO_ESCAPES.get(c, c) for c in text)


class ToolPlan:
    """The frozen tier assignment for one toolkit."""

    def __init__(self, tier: dict[str, ToolTier]) -> None:
        self.tier = tier
        self.core: list[str] = []
        self.listed: list[str] = []
        self.deferred: list[str] = []
        self.search: Tool
        self.call: Tool

    def rebuild(self, by_name: dict[str, Tool]) -> None:
        self.core = sorted(n for n, t in self.tier.items() if t == TIER_CORE)
        self.listed = sorted(n for n, t in self.tier.items() if t == TIER_LISTED)
        self.deferred = sorted(n for n, t in self.tier.items() if t == TIER_DEFERRED)
        desc = (
            "Load the full schema of tools that are not shown in full, then call them by name "
            '(or with call_tool). Query by keywords, or "select:a,b" for exact names.'
        )
        if self.listed:
            desc += "\n\nAvailable tools:"
            for n in self.listed:
                desc += "\n- " + n
                d = _first_line(by_name[n].description or "")
                if d:
                    desc += " — " + d
        if self.deferred:
            desc += "\n\nMore tools (name only): " + ", ".join(self.deferred)

        async def _never(args: Any = None, ctx: Any = None) -> ToolResult:  # the loop runs these
            return ToolResult(output="tool_search/call_tool run inside the client loop", is_error=True)

        self.search = Tool(
            name="tool_search",
            description=desc,
            input_schema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Keywords, or select:name1,name2"},
                    "limit": {"type": "number", "description": "Maximum results for a keyword query (default 5)"},
                },
                "required": ["query"],
                "additionalProperties": False,
            },
            source="builtin",
            execute=_never,
            hints=ToolHints(read_only=True),
        )
        self.call = Tool(
            name="call_tool",
            description="Call a tool loaded with tool_search, by name, with its arguments.",
            input_schema={
                "type": "object",
                "properties": {"name": {"type": "string"}, "arguments": {"type": "object"}},
                "required": ["name"],
                "additionalProperties": False,
            },
            source="builtin",
            execute=_never,
        )


def build_plan(o: ToolTierOptions, tools: list[Tool]) -> ToolPlan:
    count = o.count_tokens or default_count_tokens

    def usage(n: str) -> int:
        return o.usage_count(n) if o.usage_count is not None else 0

    by_name = {t.name: t for t in tools}
    tier: dict[str, ToolTier] = {t.name: TIER_LISTED for t in tools}
    for n in o.deferred:
        if n in tier:
            tier[n] = TIER_DEFERRED
    for n in o.core:
        if n in tier:
            tier[n] = TIER_CORE
    p = ToolPlan(tier)
    p.rebuild(by_name)

    budget = o.budget_tokens
    if budget == 0 and o.context_window > 0:
        budget = o.context_window // 20
    if budget > 0:

        def cost() -> int:
            return count(_tool_def(p.search)) + count(_tool_def(p.call)) + sum(
                count(_tool_def(by_name[n])) for n in p.core
            )

        def order(names: list[str]) -> list[str]:
            # least used first; ties: bigger schema first, then name
            return sorted(names, key=lambda n: (usage(n), -count(_tool_def(by_name[n])), n))

        for n in order(p.core):
            if cost() <= budget:
                break
            tier[n] = TIER_LISTED
            p.rebuild(by_name)
        for n in order(p.listed):
            if cost() <= budget:
                break
            tier[n] = TIER_DEFERRED
            p.rebuild(by_name)
    return p


def tool_search(tools: list[Tool], get: Callable[[str], Optional[Tool]], args: dict[str, Any]) -> ToolResult:
    """Run tool_search over every tool of the toolkit, whatever its tier."""
    q = str(args.get("query") or "").strip()
    if not q:
        return ToolResult(output="tool_search: query is required", is_error=True)
    picked: list[Tool] = []
    if q.startswith("select:"):
        missing: list[str] = []
        for n in q[len("select:"):].split(","):
            n = n.strip()
            if not n:
                continue
            t = get(n)
            if t is not None:
                picked.append(t)
            else:
                missing.append(n)
        if missing and not picked:
            return ToolResult(output="tool_search: no such tool: " + ", ".join(missing), is_error=True)
    else:
        lim = args.get("limit")
        limit = int(lim) if isinstance(lim, (int, float)) and not isinstance(lim, bool) and lim >= 1 else 5
        terms = q.lower().split()
        hits: list[tuple[int, Tool]] = []
        for t in tools:
            name, desc = t.name.lower(), (t.description or "").lower()
            s = sum((3 if term in name else 0) + (1 if term in desc else 0) for term in terms)
            if s > 0:
                hits.append((s, t))
        hits.sort(key=lambda h: (-h[0], h[1].name))
        picked = [t for _, t in hits[:limit]]
    if not picked:
        return ToolResult(output="No matching tools.", is_error=False, metadata={"count": 0})
    defs = [_tool_def(t) for t in picked]
    return ToolResult(
        output="Loaded tools (call by name, or with call_tool):\n"
        + _go_html_escape(json.dumps(defs, indent=2, sort_keys=True, ensure_ascii=False)),
        is_error=False,
        metadata={"count": len(picked), "tools": [t.name for t in picked]},
    )
