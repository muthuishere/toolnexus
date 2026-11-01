"""Prompt-cache replay (change add-prompt-cache, phase 1; SPEC §8 "Prompt caching").

A Karya-shaped session — fixed core tools, a ~6.6k-token skills catalog in the system
prompt, several turns, a deferred tool schema loaded mid-session as a tool result —
runs against a scripted anthropic transport that SIMULATES the provider's prefix
cache: a request reads the longest prefix that ends at a breakpoint an earlier request
wrote. Cache reads must start on request 2 and never shrink. Mirrors
golang/prompt_cache_test.go."""
from __future__ import annotations

import copy
import json
from typing import Any, Optional

from toolnexus import (
    InMemoryConversationStore,
    PromptCacheOptions,
    Tool,
    ToolResult,
    create_client,
    create_toolkit,
)
from toolnexus.client import _add_usage, _empty_usage
from toolnexus.prompt_cache import apply_prompt_cache


def _strip(v: Any) -> Any:
    if isinstance(v, dict):
        return {k: _strip(x) for k, x in v.items() if k != "cache_control"}
    if isinstance(v, list):
        return [_strip(x) for x in v]
    return v


def _has_cc(v: Any) -> bool:
    return '"cache_control"' in json.dumps(v)


def _dump(v: Any) -> str:
    return json.dumps(v, sort_keys=True, separators=(",", ":"))


class CacheSim:
    def __init__(self) -> None:
        self.written: set[str] = set()
        self.reads: list[int] = []
        self.bodies: list[dict[str, Any]] = []
        self.turn = 0

    def post(self, url, headers, payload, timeout):
        body = copy.deepcopy(payload)
        segs, marks = [], []
        for v in [body.get("tools"), body.get("system"), *body["messages"]]:
            segs.append(_dump(_strip(v)))
            marks.append(_has_cc(v))
        read = 0
        for i in range(len(segs), 0, -1):
            if "\x00".join(segs[:i]) in self.written:
                read = len("".join(segs[:i])) // 4
                break
        write = 0
        for i, m in enumerate(marks):
            key = "\x00".join(segs[: i + 1])
            if m and key not in self.written:
                self.written.add(key)
                write = len("".join(segs[: i + 1])) // 4 - read
        self.reads.append(read)
        self.bodies.append(body)
        last = body["messages"][-1]
        is_result = isinstance(last.get("content"), list) and any(
            isinstance(b, dict) and b.get("type") == "tool_result" for b in last["content"]
        )
        if is_result:
            content = [{"type": "text", "text": "ok"}]
        else:
            self.turn += 1
            name = "tool_search" if self.turn == 2 else "read_notes"  # turn 2 loads a deferred schema
            content = [{"type": "tool_use", "id": f"t{self.turn}", "name": name, "input": {}}]
        return {
            "content": content,
            "stop_reason": "end_turn" if is_result else "tool_use",
            "usage": {"input_tokens": 10, "output_tokens": 5,
                      "cache_read_input_tokens": read, "cache_creation_input_tokens": write},
        }


async def _karya_toolkit():
    async def notes(args=None, ctx=None):
        return ToolResult(output="notes", is_error=False)

    async def search(args=None, ctx=None):
        return ToolResult(output='{"name":"deploy","description":"Deploy.","inputSchema":{"type":"object"}}', is_error=False)

    schema = {"type": "object", "properties": {}}
    return await create_toolkit(extra_tools=[
        Tool(name="read_notes", description="Read the notes.", input_schema=schema, source="native", execute=notes),
        Tool(name="tool_search", description="Load a deferred tool's schema.", input_schema=schema, source="native", execute=search),
    ])


async def _session(cache: Optional[PromptCacheOptions]) -> CacheSim:
    sim = CacheSim()
    catalog = "skill: does a thing in a stable way. " * 700  # ~6.6k tokens
    c = create_client(base_url="http://sim.local", style="anthropic", model="m", api_key="k",
                      system_prompt="You are Karya.\n\n" + catalog, prompt_cache=cache,
                      store=InMemoryConversationStore(), http_transport=sim)
    tk = await _karya_toolkit()
    read_total = 0
    for p in ["first", "second", "third", "fourth"]:
        res = await c.ask(p, tk, id="session-1")
        read_total += res.usage.get("cache_read_tokens", 0)
    if cache is not None:
        assert read_total > 0, "usage never reported cache-read tokens"
    return sim


async def test_prompt_cache_replay_no_regression():
    sim = await _session(PromptCacheOptions(key="session-1"))
    assert len(sim.reads) == 8  # 4 turns x 2
    assert sim.reads[0] == 0, sim.reads
    floor = sim.reads[1]
    assert floor >= 6000, sim.reads
    for i in range(1, len(sim.reads)):
        assert sim.reads[i] >= floor and sim.reads[i] >= sim.reads[i - 1], sim.reads
    t0, s0 = _dump(sim.bodies[0]["tools"]), _dump(sim.bodies[0]["system"])
    for b in sim.bodies:
        assert _dump(b["tools"]) == t0 and _dump(b["system"]) == s0


async def test_prompt_cache_off_is_byte_identical():
    sim = await _session(None)
    for b in sim.bodies:
        assert not _has_cc(b)
        assert isinstance(b["system"], str)


def test_prompt_cache_openai_key_and_usage():
    body: dict[str, Any] = {"messages": []}
    apply_prompt_cache(body, "openai", PromptCacheOptions(key="s1"))
    assert body["prompt_cache_key"] == "s1"
    u = _empty_usage()
    _add_usage(u, {"prompt_tokens": 100, "completion_tokens": 5, "prompt_tokens_details": {"cached_tokens": 80}}, "openai")
    assert u["cache_read_tokens"] == 80 and u["prompt_tokens"] == 100
    u = _empty_usage()
    _add_usage(u, {"prompt_tokens": 1, "completion_tokens": 1}, "openai")
    assert u == {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}  # unchanged shape
    u = _empty_usage()
    _add_usage(u, {"input_tokens": 3, "output_tokens": 1, "cache_read_input_tokens": 7, "cache_creation_input_tokens": 2}, "anthropic")
    assert u["prompt_tokens"] == 3 and u["cache_read_tokens"] == 7 and u["cache_write_tokens"] == 2


def test_prompt_cache_does_not_mutate_transcript():
    msg = {"role": "user", "content": "hi"}
    tool = {"name": "x"}
    body = {"tools": [tool], "system": "s", "messages": [msg]}
    apply_prompt_cache(body, "anthropic", PromptCacheOptions(long_ttl=True))
    assert isinstance(msg["content"], str) and "cache_control" not in tool
    assert json.dumps(body, separators=(",", ":")).count('"ttl":"1h"') == 3
