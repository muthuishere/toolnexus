"""create_in_process_client — a model in this process, with no wire configuration.

openspec/changes/add-in-process-client. Mirrored in all seven ports.
"""
from __future__ import annotations

import json

import pytest

from toolnexus import create_in_process_client, create_toolkit, define_tool


def add_fn(a: float, b: float) -> str:
    """Add two numbers."""
    return str(a + b)


ADD = define_tool(add_fn, name="add", description="Add two numbers.")


@pytest.mark.asyncio
async def test_no_wire_configuration_is_required():
    tk = await create_toolkit(builtins=False)
    # No base_url. No api_key. No style. That is the whole point.
    client = create_in_process_client(model="my-local", generate=lambda req: {"content": "hello from in-process"})
    r = await client.run("hi", toolkit=tk)
    assert r.text == "hello from in-process"
    assert r.status == "done"


@pytest.mark.asyncio
async def test_generate_sees_the_assembled_request():
    tk = await create_toolkit(builtins=False, extra_tools=[ADD])
    seen = {}

    def generate(req):
        seen.update(req)
        return {"content": "ok"}

    client = create_in_process_client(model="my-local", generate=generate, system_prompt="You are terse.")
    await client.run("What is 2 + 3?", toolkit=tk)
    assert seen["model"] == "my-local"
    assert any(m.get("role") == "system" and "terse" in m.get("content", "") for m in seen["messages"])
    assert any(m.get("role") == "user" and "2 + 3" in m.get("content", "") for m in seen["messages"])
    assert seen["tools"][0]["function"]["name"] == "add"


@pytest.mark.asyncio
async def test_tool_calls_loop_back_with_the_result():
    tk = await create_toolkit(builtins=False, extra_tools=[ADD])
    state = {"n": 0}

    def generate(req):
        state["n"] += 1
        if state["n"] == 1:
            return {"tool_calls": [{"name": "add", "arguments": {"a": 2, "b": 3}}]}
        return {"content": f"the answer is {req['messages'][-1]['content']}"}

    r = await create_in_process_client(model="m", generate=generate).run("What is 2 + 3?", toolkit=tk)
    assert len(r.tool_calls) == 1
    assert r.tool_calls[0]["name"] == "add"
    assert r.tool_calls[0]["output"] == "5"
    assert "the answer is 5" in r.text


@pytest.mark.asyncio
@pytest.mark.parametrize("args", [{"a": 2, "b": 3}, json.dumps({"a": 2, "b": 3})])
async def test_arguments_structured_or_pre_encoded(args):
    tk = await create_toolkit(builtins=False, extra_tools=[ADD])
    state = {"n": 0}

    def generate(_req):
        state["n"] += 1
        return {"tool_calls": [{"name": "add", "arguments": args}]} if state["n"] == 1 else {"content": "done"}

    r = await create_in_process_client(model="m", generate=generate).run("go", toolkit=tk)
    assert r.tool_calls[0]["output"] == "5"


@pytest.mark.asyncio
async def test_usage_is_optional_and_reported_when_given():
    tk = await create_toolkit(builtins=False)
    bare = create_in_process_client(model="m", generate=lambda _r: {"content": "x"})
    assert (await bare.run("hi", toolkit=tk)).usage["total_tokens"] == 0

    counted = create_in_process_client(
        model="m",
        generate=lambda _r: {"content": "x", "usage": {"prompt_tokens": 11, "completion_tokens": 4}},
    )
    r = await counted.run("hi", toolkit=tk)
    assert r.usage["prompt_tokens"] == 11
    assert r.usage["total_tokens"] == 15, "total is derived when not given"


@pytest.mark.asyncio
async def test_streaming_is_refused_loudly():
    tk = await create_toolkit(builtins=False)
    client = create_in_process_client(model="m", generate=lambda _r: {"content": "x"})
    with pytest.raises(NotImplementedError, match="does not support streaming"):
        async for _ in client.stream("hi", toolkit=tk):
            pass


@pytest.mark.asyncio
async def test_failures_are_not_retried_by_default():
    """There is no wire, so there is no transient failure to ride out."""
    import time

    tk = await create_toolkit(builtins=False)
    calls = {"n": 0}

    def boom(_req):
        calls["n"] += 1
        raise RuntimeError("my model blew up")

    started = time.monotonic()
    with pytest.raises(RuntimeError, match="my model blew up"):
        await create_in_process_client(model="m", generate=boom).run("hi", toolkit=tk)
    assert calls["n"] == 1, "retrying a local function that threw is backoff over a bug"
    assert time.monotonic() - started < 0.5

    # NOT asserted: that an explicit `retries=N` re-runs a throwing generate. Whether a
    # raised exception is retryable at all is per-port — this port's loop catches
    # _HttpError/URLError/OSError/TimeoutError, so a RuntimeError from generate
    # propagates on the first attempt no matter what `retries` says, while js retries
    # any throw. That divergence predates this constructor; pinning it here in one port
    # would assert something false in another.


def test_generate_is_required_and_wire_options_are_refused():
    with pytest.raises(TypeError, match="callable `generate`"):
        create_in_process_client(model="m", generate=None)
    with pytest.raises(TypeError, match="has no wire to configure"):
        create_in_process_client(model="m", generate=lambda _r: {}, base_url="http://x")
