"""Shared contract types. See ../../SPEC.md.

Public API names are snake_case but conceptually identical to the JS reference
(``js/src/types.ts``).
"""
from __future__ import annotations

import re
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


@dataclass
class ToolContext:
    # cancellation token (e.g. an asyncio cancellation) — optional / advisory
    signal: Optional[Any] = None
    # timeout in seconds (Python convention; JS uses ms)
    timeout: Optional[float] = None


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
