"""Client seams for the §7D agent runtime.

1) Cooperative cancel (SPEC §7D python row): between-attempts minimum, plus the
   in-flight worker-thread request raced against the token — a fired cancel
   aborts the run promptly even while urllib is blocked (the thread finishes in
   the background, its result discarded).
2) `"incomplete"` RunStatus (SPEC §8 addendum): a maxTurns stop with no final
   text is loud, never a silent "done".
"""
from __future__ import annotations

import asyncio
import threading
import time

import pytest

from toolnexus import RunCancelled, create_client, create_toolkit
from toolnexus.types import Tool, ToolResult

from _agent_mocks import openai_body, tool_call


async def _echo_exec(args=None, ctx=None):
    return ToolResult(output="echo", is_error=False)


ECHO = Tool(
    name="echo",
    description="echo",
    input_schema={"type": "object", "properties": {}},
    source="native",
    execute=_echo_exec,
)


class _BlockingTransport:
    """Blocks in the worker thread until released — an un-abortable urllib stand-in."""

    def __init__(self) -> None:
        self.release = threading.Event()
        self.calls = 0

    def post(self, url, headers, payload, timeout):
        self.calls += 1
        self.release.wait(timeout=10)
        return openai_body({"content": "late answer"})

    def open(self, url, headers, payload, timeout):  # noqa: A003
        raise NotImplementedError


class _LoopingTransport:
    """Always returns another tool call — the model never gives a final answer."""

    def post(self, url, headers, payload, timeout):
        n = sum(1 for m in payload["messages"] if m.get("role") == "tool")
        return openai_body(tool_call("echo", {}, f"c{n}"))

    def open(self, url, headers, payload, timeout):  # noqa: A003
        raise NotImplementedError


def _client(transport, **kw):
    return create_client(
        base_url="http://mock.local",
        style="openai",
        model="m",
        api_key="unused",
        http_transport=transport,
        **kw,
    )


async def test_cancel_aborts_promptly_mid_request():
    """A fired cancel aborts the run while the transport is blocked mid-request
    (transport races the token; the worker thread is abandoned, not joined)."""
    transport = _BlockingTransport()
    toolkit = await create_toolkit(builtins=False, extra_tools=[ECHO])
    cancel = asyncio.Event()
    client = _client(transport)
    run = asyncio.ensure_future(client.run("hi", toolkit, cancel=cancel))
    await asyncio.sleep(0.05)
    assert transport.calls == 1 and not run.done()
    t0 = time.monotonic()
    cancel.set()
    with pytest.raises(RunCancelled):
        await run
    assert time.monotonic() - t0 < 2.0, "abort latency must not wait out the blocked request"
    transport.release.set()


async def test_cancel_between_attempts_pre_flight():
    """The between-attempts minimum: a token fired before the attempt starts is
    observed before any transport call is made."""
    transport = _BlockingTransport()
    toolkit = await create_toolkit(builtins=False, extra_tools=[ECHO])
    cancel = asyncio.Event()
    cancel.set()
    client = _client(transport)
    with pytest.raises(RunCancelled):
        await client.run("hi", toolkit, cancel=cancel)
    assert transport.calls == 0
    transport.release.set()


async def test_max_turns_stop_without_final_text_is_incomplete():
    """§8 addendum: exhausting max_turns while the model still emits tool calls
    yields status='incomplete' — never a silent 'done'."""
    toolkit = await create_toolkit(builtins=False, extra_tools=[ECHO])
    client = _client(_LoopingTransport(), max_turns=3)
    r = await client.run("loop forever", toolkit)
    assert r.status == "incomplete"
    assert r.turns == 3 and r.text == ""


async def test_normal_completion_stays_done():
    """A run that ends with final text (even on the last allowed turn) is 'done'."""

    class _OneShot:
        def post(self, url, headers, payload, timeout):
            return openai_body({"content": "final"})

        def open(self, url, headers, payload, timeout):  # noqa: A003
            raise NotImplementedError

    toolkit = await create_toolkit(builtins=False, extra_tools=[ECHO])
    client = _client(_OneShot(), max_turns=1)
    r = await client.run("hi", toolkit)
    assert r.status == "done" and r.text == "final"
