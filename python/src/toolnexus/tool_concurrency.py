"""Tool scheduling (SPEC §8 "Tool scheduling").

The calls of one model response run in emitted order as consecutive runs: safe calls overlap
under a cap, each unsafe call runs alone. Results keep emitted order.
"""

from __future__ import annotations

import asyncio
import os
import re
from typing import Any, Awaitable, Callable, Optional, Sequence, TypeVar

from .types import Tool

T = TypeVar("T")
R = TypeVar("R")

#: The cap when neither the option nor TOOLNEXUS_MAX_TOOL_CONCURRENCY sets one.
DEFAULT_MAX_TOOL_CONCURRENCY = 10

_INT_RE = re.compile(r"^\s*\+?\d+\s*$")


def resolve_max_tool_concurrency(option: Optional[int] = None) -> int:
    """option > env TOOLNEXUS_MAX_TOOL_CONCURRENCY > 10. Non-positive or non-numeric values fall back."""
    if isinstance(option, int) and not isinstance(option, bool) and option > 0:
        return option
    raw = os.environ.get("TOOLNEXUS_MAX_TOOL_CONCURRENCY")
    if raw is not None and _INT_RE.match(raw):
        n = int(raw)
        if n > 0:
            return n
    return DEFAULT_MAX_TOOL_CONCURRENCY


def is_concurrency_safe(tool: Tool, args: dict[str, Any]) -> bool:
    """Whether a call to ``tool`` with ``args`` may run alongside other safe calls. The tool's
    own ``concurrency_safe`` predicate wins; otherwise only a tool whose hints say read_only is
    safe. A tool without hints is unsafe."""
    if tool.concurrency_safe is not None:
        return bool(tool.concurrency_safe(args))
    return tool.hints is not None and tool.hints.read_only


async def schedule_calls(
    items: Sequence[T],
    safe: Callable[[T], bool],
    cap: int,
    fn: Callable[[T], Awaitable[R]],
) -> list[R]:
    """Run ``fn`` over ``items`` in order: safe items overlap (at most ``cap``); an unsafe item
    waits for everything before it and runs alone. Results keep item order; the first
    exception propagates, as ``asyncio.gather`` did."""
    out: list[Any] = [None] * len(items)
    running: set[asyncio.Future[None]] = set()
    sem = asyncio.Semaphore(cap)

    async def one(i: int, item: T) -> None:
        try:
            out[i] = await fn(item)
        finally:
            sem.release()

    for i, item in enumerate(items):
        if not safe(item):
            if running:
                await asyncio.gather(*running)
            out[i] = await fn(item)
            continue
        await sem.acquire()
        task = asyncio.ensure_future(one(i, item))
        running.add(task)
        task.add_done_callback(running.discard)
    if running:
        await asyncio.gather(*running)
    return out
