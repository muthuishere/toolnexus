"""add-host-turn-durability (SPEC §7 "Interrupted turns are saved", §4A
``killGraceMs`` and "Edit diffs"): hermetic, scripted LLM, no network."""
from __future__ import annotations

import asyncio
import io
import json
import os
import signal
import statistics
import sys
import time
from typing import Any

import pytest

from toolnexus import InMemoryConversationStore, Tool, ToolResult, create_client, create_toolkit
from toolnexus.builtin import create_builtin_tools


# --------------------------------------------------------------------------- #
# Scripted provider: turn 0 = two tool calls (fast c1, slow c2); later = "done".
# --------------------------------------------------------------------------- #
def _sse(events: list[Any]) -> bytes:
    return "".join(f"data: {e if isinstance(e, str) else json.dumps(e)}\n\n" for e in events).encode()


class Script:
    def __init__(self, style: str, fail: bool = False) -> None:
        self.style = style
        self.fail = fail

    def post(self, url, headers, payload, timeout):
        if self.fail:
            raise ValueError("provider exploded")
        if self.style == "openai":
            return {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "fast", "arguments": "{}"}},
                {"id": "c2", "type": "function", "function": {"name": "slow", "arguments": "{}"}}]}}]}
        return {"content": [{"type": "tool_use", "id": "c1", "name": "fast", "input": {}},
                            {"type": "tool_use", "id": "c2", "name": "slow", "input": {}}],
                "stop_reason": "tool_use"}

    def open(self, url, headers, payload, timeout):  # noqa: A003
        if self.fail:
            raise ValueError("provider exploded")
        if self.style == "openai":
            return io.BytesIO(_sse([{"choices": [{"delta": {"tool_calls": [
                {"index": 0, "id": "c1", "function": {"name": "fast", "arguments": "{}"}},
                {"index": 1, "id": "c2", "function": {"name": "slow", "arguments": "{}"}}]}}]}, "[DONE]"]))
        return io.BytesIO(_sse([
            {"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": "c1", "name": "fast"}},
            {"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "{}"}},
            {"type": "content_block_start", "index": 1, "content_block": {"type": "tool_use", "id": "c2", "name": "slow"}},
            {"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "{}"}},
            {"type": "message_delta", "delta": {"stop_reason": "tool_use"}},
        ]))


def _tools(slow_started: asyncio.Event) -> list[Tool]:
    async def fast(args=None, ctx=None):
        return ToolResult(output="fast-ok", is_error=False)

    async def slow(args=None, ctx=None):
        slow_started.set()
        await asyncio.sleep(30)
        return ToolResult(output="never", is_error=False)

    schema = {"type": "object", "properties": {}}
    return [Tool(name="fast", description="f", input_schema=schema, source="native", execute=fast),
            Tool(name="slow", description="s", input_schema=schema, source="native", execute=slow)]


PRIOR_OPENAI = [{"role": "user", "content": "earlier"}, {"role": "assistant", "content": "earlier-answer"}]
PRIOR = PRIOR_OPENAI


async def _drive(c, tk, mode: str):
    if mode == "ask":
        return await c.ask("go", tk, id="t1")
    async for _ in c.stream("go", tk, id="t1"):
        pass


@pytest.mark.asyncio
@pytest.mark.parametrize("style", ["openai", "anthropic"])
@pytest.mark.parametrize("mode", ["ask", "stream"])
async def test_cancel_mid_second_tool_saves_prompt_pairs_and_interrupted(style, mode):
    store = InMemoryConversationStore()
    await store.save("t1", list(PRIOR))
    c = create_client(base_url="http://x/v1", style=style, model="m", api_key="k",
                      http_transport=Script(style), store=store, retries=0)
    started = asyncio.Event()
    tk = await create_toolkit(builtins=False, extra_tools=_tools(started))
    task = asyncio.ensure_future(_drive(c, tk, mode))
    await asyncio.wait_for(started.wait(), 5)
    await asyncio.sleep(0.05)  # the fast sibling has finished
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task  # the caller still receives the cancellation

    saved = await store.get("t1")
    assert saved[:2] == PRIOR
    assert saved[2] == {"role": "user", "content": "go"}
    if style == "openai":
        assert [tc["id"] for tc in saved[3]["tool_calls"]] == ["c1", "c2"]
        assert saved[4] == {"role": "tool", "tool_call_id": "c1", "content": "fast-ok"}
        assert saved[5] == {"role": "tool", "tool_call_id": "c2", "content": "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first."}
        assert len(saved) == 6
    else:
        assert [b["id"] for b in saved[3]["content"] if b["type"] == "tool_use"] == ["c1", "c2"]
        assert saved[4] == {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": "c1", "content": "fast-ok", "is_error": False},
            {"type": "tool_result", "tool_use_id": "c2", "content": "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.", "is_error": True},
        ]}
        assert len(saved) == 5


@pytest.mark.asyncio
@pytest.mark.parametrize("style", ["openai", "anthropic"])
@pytest.mark.parametrize("mode", ["ask", "stream"])
async def test_provider_error_saves_prior_plus_prompt(style, mode):
    store = InMemoryConversationStore()
    await store.save("t1", list(PRIOR))
    c = create_client(base_url="http://x/v1", style=style, model="m", api_key="k",
                      http_transport=Script(style, fail=True), store=store, retries=0)
    tk = await create_toolkit(builtins=False)
    with pytest.raises(Exception, match="provider exploded"):
        await _drive(c, tk, mode)
    assert await store.get("t1") == PRIOR + [{"role": "user", "content": "go"}]


@pytest.mark.asyncio
async def test_stateless_error_saves_nothing():
    store = InMemoryConversationStore()
    c = create_client(base_url="http://x/v1", style="openai", model="m", api_key="k",
                      http_transport=Script("openai", fail=True), store=store, retries=0)
    with pytest.raises(Exception):
        await c.ask("go")
    assert await store.list() == []


# --------------------------------------------------------------------------- #
# killGraceMs
# --------------------------------------------------------------------------- #
def _bash(cfg: dict[str, Any]) -> Tool:
    return next(t for t in create_builtin_tools(cfg) if t.name == "bash")


def _group_alive(pgid: int) -> bool:
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


async def _measure_once(tmp_path, grace: int) -> float:
    pidfile = tmp_path / f"pid-{time.monotonic_ns()}"
    # Parent and a background descendant both ignore SIGTERM.
    cmd = (f"trap '' TERM; (trap '' TERM; sleep 30) & echo $$ > {pidfile}; "
           "while :; do sleep 0.05; done")
    task = asyncio.ensure_future(_bash({"kill_grace_ms": grace}).execute({"command": cmd}))
    for _ in range(200):
        if pidfile.exists() and pidfile.read_text().strip():
            break
        await asyncio.sleep(0.01)
    pgid = int(pidfile.read_text().strip())
    await asyncio.sleep(0.05)
    t0 = time.monotonic()
    task.cancel()
    while _group_alive(pgid) and time.monotonic() - t0 < 5:
        await asyncio.sleep(0.005)
    elapsed = time.monotonic() - t0
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not _group_alive(pgid)
    return elapsed


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX process groups")
@pytest.mark.asyncio
async def test_kill_grace_100ms_kills_term_ignoring_group_fast(tmp_path):
    samples = [await _measure_once(tmp_path, 100) for _ in range(10)]
    print(f"\nkillGraceMs=100 cancel->group gone (s): min={min(samples):.3f} "
          f"median={statistics.median(samples):.3f} max={max(samples):.3f}")
    assert max(samples) < 1.0


def test_kill_grace_default_is_2000():
    from toolnexus.builtin import KILL_GRACE_MS, _BuiltinEnv
    assert KILL_GRACE_MS == 2000
    assert _BuiltinEnv({}).kill_grace_s == 2.0
    assert _BuiltinEnv({"killGraceMs": 0}).kill_grace_s == 2.0
    assert _BuiltinEnv({"killGraceMs": 100}).kill_grace_s == 0.1


# --------------------------------------------------------------------------- #
# Edit diffs
# --------------------------------------------------------------------------- #
def _tool(name: str, base: str) -> Tool:
    return next(t for t in create_builtin_tools({"base_dir": base}) if t.name == name)


@pytest.mark.asyncio
async def test_edit_one_line_is_modified_1_1(tmp_path):
    (tmp_path / "a.txt").write_text("one\ntwo\nthree\n")
    r = await _tool("edit", str(tmp_path)).execute({"path": "a.txt", "oldString": "two", "newString": "TWO"})
    assert not r.is_error and r.output == "Edited a.txt (1 replacement)"
    assert r.metadata["files"] == [{"path": "a.txt", "status": "modified", "additions": 1, "deletions": 1}]
    assert "-two\n" in r.metadata["diff"] and "+TWO\n" in r.metadata["diff"]
    assert "--- a/a.txt" in r.metadata["diff"]


@pytest.mark.asyncio
async def test_write_new_three_line_file_is_added_3_0(tmp_path):
    r = await _tool("write", str(tmp_path)).execute({"path": "sub/n.txt", "content": "a\nb\nc\n"})
    assert r.output == "Wrote 6 bytes to sub/n.txt"
    assert r.metadata["files"] == [{"path": "sub/n.txt", "status": "added", "additions": 3, "deletions": 0}]
    assert "+++ b/sub/n.txt" in r.metadata["diff"]


@pytest.mark.asyncio
async def test_apply_patch_add_and_delete(tmp_path):
    (tmp_path / "old.txt").write_text("x\ny\n")
    patch = "*** Begin Patch\n*** Add File: new.txt\n+hello\n+world\n*** Delete File: old.txt\n*** End Patch"
    r = await _tool("apply_patch", str(tmp_path)).execute({"patchText": patch})
    assert not r.is_error, r.output
    assert r.metadata["files"] == [
        {"path": "new.txt", "status": "added", "additions": 2, "deletions": 0},
        {"path": "old.txt", "status": "deleted", "additions": 0, "deletions": 2},
    ]
    assert "+hello" in r.metadata["diff"] and "-x" in r.metadata["diff"]
    assert not (tmp_path / "old.txt").exists()


@pytest.mark.asyncio
async def test_stream_tool_result_carries_metadata():
    async def meta(args=None, ctx=None):
        return ToolResult(output="o", is_error=False, metadata={"k": 1})

    class T(Script):
        n = 0

        def open(self, url, headers, payload, timeout):  # noqa: A003
            T.n += 1
            if T.n == 1:
                return io.BytesIO(_sse([{"choices": [{"delta": {"tool_calls": [
                    {"index": 0, "id": "c1", "function": {"name": "meta", "arguments": "{}"}}]}}]}, "[DONE]"]))
            return io.BytesIO(_sse([{"choices": [{"delta": {"content": "done"}}]}, "[DONE]"]))

    tk = await create_toolkit(builtins=False, extra_tools=[Tool(
        name="meta", description="m", input_schema={"type": "object", "properties": {}}, source="native", execute=meta)])
    c = create_client(base_url="http://x/v1", style="openai", model="m", api_key="k", http_transport=T("openai"))
    evs = [ev async for ev in c.stream("go", tk)]
    tr = next(ev for ev in evs if ev["type"] == "tool_result")
    assert tr["metadata"] == {"k": 1}
