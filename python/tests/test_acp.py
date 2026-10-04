"""ACP (Agent Client Protocol) as a model source — ADR 0031, issue #96, ported to
Python.

Hermetic: no network, no real `devin`/agent binary. Drives
``toolnexus.tests.fixtures.acp_fake_server`` (a scripted fake ACP server, stdlib
only) as a real child process over real OS pipes, exactly the shape a real agent
uses. Mirrors ``spikes/acp/SPIKE.md``'s gates and the style of
``test_agents_inprocess.py`` / ``test_client_resilience.py``.
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time

import pytest

from toolnexus import ACPOptions, ACPPermissionTimeoutError, create_in_process_client, create_toolkit, define_tool, load_acp
from toolnexus.acp import _PREAMBLE, _parse_reply, _render_prompt

_FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "acp_fake_server.py")


def _opts(scenario: str, **overrides) -> ACPOptions:
    kwargs = dict(command=sys.executable, args=[_FIXTURE, "--scenario", scenario], permission_timeout=2.0)
    kwargs.update(overrides)
    return ACPOptions(**kwargs)


# --------------------------------------------------------------------------- #
# session/new carries an absolute cwd + mcpServers — the real `devin acp` trap
# (ADR 0031 / spike: -32602 Invalid params without it).
# --------------------------------------------------------------------------- #
def test_session_new_sends_absolute_cwd_and_mcp_servers():
    client = load_acp(_opts("default"))
    try:
        result = client._call("diagnostics/get", {})
        assert result.get("received_cwd")
        assert os.path.isabs(result["received_cwd"])
        assert result["received_mcp_servers"] == []
    finally:
        client.close()


# --------------------------------------------------------------------------- #
# 1. Warm session reuse: one child, one session/new, many generate() calls.
# --------------------------------------------------------------------------- #
def test_warm_session_reused_across_multiple_generate_calls():
    client = load_acp(_opts("default"))
    try:
        r1 = client.generate({"messages": [{"role": "user", "content": "hi"}]})
        r2 = client.generate({"messages": [{"role": "user", "content": "again"}]})
        r3 = client.generate({"messages": [{"role": "user", "content": "third"}]})

        assert "echo:" in r1["content"]
        assert "echo:" in r2["content"]
        assert "echo:" in r3["content"]

        counts = client._call("diagnostics/get", {})
        assert counts["session_new_count"] == 1
        assert counts["prompt_count"] == 3
    finally:
        client.close()


# --------------------------------------------------------------------------- #
# 2. Only agent_message_chunk forms the reply; thought/tool narration is dropped.
# --------------------------------------------------------------------------- #
def test_thought_and_tool_narration_filtered_from_reply():
    client = load_acp(_opts("noisy"))
    try:
        result = client.generate({"messages": [{"role": "user", "content": "42"}]})
        # The fixture emits the message content split across two agent_message_chunk
        # notifications with agent_thought_chunk + tool_call/tool_call_update noise
        # interleaved between them; the reply must be exactly the concatenation of
        # the message chunks: the clean JSON-shaped wrapper from the fixture, with
        # none of the thought-chunk text mixed in (the corruption ADR 0031 gate
        # item 3 is about — unfiltered accumulation would wrap prose around this).
        content = result["content"]
        assert content.startswith('{"answer":"')
        assert content.endswith('42"}')
        assert "Let me think" not in content
        assert "double-checking" not in content
        assert "reading files" not in content
    finally:
        client.close()


# --------------------------------------------------------------------------- #
# 3. A permission request is answered, not awaited — the turn completes fast, and
#    the chosen optionId round-trips into the fixture's own reply.
# --------------------------------------------------------------------------- #
def test_permission_request_answered_inline_not_awaited():
    client = load_acp(_opts("permission", permission_timeout=10.0))
    try:
        start = time.monotonic()
        result = client.generate({"messages": [{"role": "user", "content": "delete the database"}]})
        elapsed = time.monotonic() - start

        assert elapsed < 1.0, f"expected the auto-answered permission turn to complete quickly, took {elapsed}s"
        # By default the client answers with the FIRST reject-kind option: toolnexus
        # executes the tools, the agent must not (SPEC §8, add-acp-tool-calling).
        assert result["content"] == "PERMITTED:reject"

        diag = client._call("diagnostics/get", {})
        assert diag["last_option_id"] == "reject"
    finally:
        client.close()


def test_permission_allowed_on_opt_in():
    client = load_acp(_opts("permission", permission_timeout=10.0, allow_agent_tools=True))
    try:
        start = time.monotonic()
        result = client.generate({"messages": [{"role": "user", "content": "go"}]})
        assert time.monotonic() - start < 1.0, "permission was awaited, not answered"
        # Opt-in selects the FIRST allow-kind option.
        assert result["content"] == "PERMITTED:allow-once"
        assert client._call("diagnostics/get", {})["last_option_id"] == "allow-once"
    finally:
        client.close()


def test_unanswered_turn_is_bounded_by_the_safety_net_timeout():
    # scenario="hang": the fixture never replies and never asks permission at all —
    # proving the safety-net timeout (not the permission-answering machinery) is
    # what ends the wait when an agent is simply broken.
    client = load_acp(_opts("hang", permission_timeout=0.5))
    try:
        start = time.monotonic()
        with pytest.raises(ACPPermissionTimeoutError):
            client.generate({"messages": [{"role": "user", "content": "hello"}]})
        elapsed = time.monotonic() - start
        assert 0.5 <= elapsed < 2.0
    finally:
        client.close()


# --------------------------------------------------------------------------- #
# 4. The supersedes marker (added by the library on every turn) keeps the agent
#    answering the fresh question, not a stale one from the growing transcript.
# --------------------------------------------------------------------------- #
def test_supersedes_marker_prevents_stale_answers():
    client = load_acp(_opts("stale"))
    try:
        r1 = client.generate({"messages": [{"role": "user", "content": "What is the capital of France?"}]})
        assert r1["content"] == "FRESH-ANSWER-TO:What is the capital of France?"

        # Turn 2 sends the FULL transcript (per ADR 0031's chosen default), which
        # now contains turn 1's question verbatim — the exact shape that made the
        # fixture's "stale" scenario answer the FIRST remembered question when no
        # marker is present (see spikes/acp/SPIKE.md gate 1). Because
        # toolnexus.acp always appends the supersedes marker naming the latest
        # user turn, the fixture answers fresh instead.
        r2 = client.generate(
            {
                "messages": [
                    {"role": "user", "content": "What is the capital of France?"},
                    {"role": "assistant", "content": r1["content"]},
                    {"role": "user", "content": "What is the capital of Japan?"},
                ]
            }
        )
        assert r2["content"] == "FRESH-ANSWER-TO:What is the capital of Japan?"
        assert "STALE-ANSWER-TO" not in r2["content"]
    finally:
        client.close()


# --------------------------------------------------------------------------- #
# 5. Turns on one session are serialised — concurrent generate() calls never
#    produce a re-entrant session/prompt on the wire.
# --------------------------------------------------------------------------- #
def test_concurrent_generate_calls_are_serialised():
    client = load_acp(_opts("serialize", permission_timeout=5.0))
    try:
        results: list[dict] = [None, None]  # type: ignore[list-item]
        errors: list[BaseException] = []

        def run(i: int, text: str) -> None:
            try:
                results[i] = client.generate({"messages": [{"role": "user", "content": text}]})
            except BaseException as exc:  # noqa: BLE001
                errors.append(exc)

        t1 = threading.Thread(target=run, args=(0, "alpha"))
        t2 = threading.Thread(target=run, args=(1, "beta"))
        t1.start()
        t2.start()
        t1.join(timeout=10)
        t2.join(timeout=10)

        assert not errors, errors
        assert results[0] is not None and results[1] is not None
        assert results[0]["content"].startswith("echo:") and "alpha" in results[0]["content"]
        assert results[1]["content"].startswith("echo:") and "beta" in results[1]["content"]

        diag = client._call("diagnostics/get", {})
        assert diag["reentrant_detected"] is False
        assert diag["prompt_count"] == 2
    finally:
        client.close()


# --------------------------------------------------------------------------- #
# 6. close() is idempotent, and the child process is actually gone afterward.
# --------------------------------------------------------------------------- #
def test_close_is_idempotent_and_kills_the_child():
    client = load_acp(_opts("default"))
    client.generate({"messages": [{"role": "user", "content": "hi"}]})

    client.close()
    assert client.is_alive() is False

    # Second close must not raise.
    client.close()
    assert client.is_alive() is False



# --------------------------------------------------------------------------- #
# 7. ACP as a real tool-calling model (SPEC §8 "ACP model source",
#    openspec/changes/add-acp-tool-calling): the OpenAI-shaped request reaches the
#    agent, its JSON reply becomes tool calls the loop executes. Mirrors
#    golang/acp_toolcall_test.go.
# --------------------------------------------------------------------------- #
def _add(a: float, b: float) -> str:
    """Add two numbers."""
    total = a + b
    return str(int(total)) if float(total).is_integer() else str(total)


_ADD = define_tool(_add, name="add", description="Add two numbers.")


def _split_prompt(prompt: str):
    i = prompt.find("\nREQUEST:\n")
    j = prompt.rfind("\n\nSUPERSEDES-ALL-PRIOR: ")
    assert i >= 0 and j > i, f"prompt does not split: {prompt!r}"
    return prompt[:i], json.loads(prompt[i + len("\nREQUEST:\n"):j])


async def test_tool_calling_loop_end_to_end():
    acp = load_acp(_opts("toolloop"))
    try:
        tk = await create_toolkit(builtins=False, extra_tools=[_ADD])
        client = create_in_process_client(model="acp", generate=acp.generate)
        r = await client.run("What is 2 + 3?", toolkit=tk)
        requests = acp._call("diagnostics/get", {})["requests"]
    finally:
        acp.close()

    assert r.text == "The answer is 5."
    assert len(r.tool_calls) == 1
    assert r.tool_calls[0]["name"] == "add"
    assert r.tool_calls[0]["output"] == "5"

    assert len(requests) == 2, "expected 2 prompts (ask, then answer)"
    # Turn 1: the tool schema reached the agent, OpenAI-shaped.
    assert any(
        (t.get("function") or {}).get("name") == "add" and (t.get("function") or {}).get("parameters") is not None
        for t in requests[0]["tools"]
    ), requests[0]["tools"]
    # Turn 2: the assistant tool_calls message and the tool result are both there.
    msgs = requests[1]["messages"]
    assert any(m.get("role") == "assistant" and m.get("tool_calls") for m in msgs), msgs
    assert any(
        m.get("role") == "tool" and m.get("tool_call_id") == "c1" and m.get("content") == "5" for m in msgs
    ), msgs


def test_prompt_shape():
    p = _render_prompt(
        {
            "messages": [
                {"role": "system", "content": "be terse"},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "a <b> & c"},
                        {"type": "image_url"},
                        {"type": "text", "text": "d"},
                    ],
                },
            ]
        }
    )
    pre, body = _split_prompt(p)
    assert pre == _PREAMBLE
    assert p.endswith("\n\nSUPERSEDES-ALL-PRIOR: a <b> & c d")
    assert "\\u003c" not in p, "REQUEST JSON is HTML-escaped"
    assert body["tools"] == [], "absent tools must render as []"
    assert "\nREQUEST:\n{\"messages\":" in p, "REQUEST JSON must lead with messages"


def test_prompt_latest_falls_back_to_last_message_then_empty():
    assert _render_prompt({"messages": [{"role": "system", "content": "sys"}]}).endswith("SUPERSEDES-ALL-PRIOR: sys")
    p = _render_prompt({})
    assert p.endswith("\n\nSUPERSEDES-ALL-PRIOR: ")
    assert _split_prompt(p)[1] == {"messages": [], "tools": []}


def test_preamble_matches_spec():
    # Byte-pinned by SPEC.md §8; read it back from the spec so the constant cannot
    # drift from the contract every port is held to.
    path = os.path.join(os.path.dirname(__file__), "..", "..", "SPEC.md")
    try:
        with open(path, encoding="utf-8") as f:
            spec = f.read()
    except OSError as exc:
        pytest.skip(f"SPEC.md not reachable: {exc}")
    i = spec.find("`PREAMBLE` is these seven lines")
    assert i >= 0, "SPEC.md has no ACP preamble block"
    s = spec[i:]
    start = s.index("```\n") + len("```\n")
    end = s.index("```", start)
    assert s[start:end] == _PREAMBLE


_ADD_OBJ = [{"id": "c1", "name": "add", "arguments": {"a": 2, "b": 3}}]
_ADD_STR = [{"id": "c1", "name": "add", "arguments": '{"a":2,"b":3}'}]


@pytest.mark.parametrize(
    "text, calls, content",
    [
        pytest.param("just text {not json", None, "just text {not json", id="plain prose passes through"),
        pytest.param('{"content":"The answer is 5."}', None, "The answer is 5.", id="content envelope"),
        pytest.param('{"content":null}', None, "", id="null content"),
        pytest.param('{"content":{"x":1}}', None, '{"x":1}', id="non-string content encodes"),
        pytest.param(
            '{"tool_calls":[{"id":"c1","type":"function","function":{"name":"add","arguments":"{\\"a\\":2,\\"b\\":3}"}}]}',
            _ADD_STR, None, id="string arguments pre-encoded",
        ),
        pytest.param(
            '{"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}',
            _ADD_OBJ, None, id="object arguments",
        ),
        pytest.param(
            '```json\n{"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}\n```',
            _ADD_OBJ, None, id="fenced",
        ),
        pytest.param(
            'Calling now: {"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]} done',
            _ADD_OBJ, None, id="prose around",
        ),
        pytest.param(
            '{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}}]}',
            _ADD_OBJ, None, id="choices envelope",
        ),
        pytest.param('{"message":{"content":"hi"}}', None, "hi", id="message envelope"),
        pytest.param('{"tool_calls":[{"name":"ping"}]}', [{"name": "ping", "arguments": {}}], None, id="flat call, no id, no arguments"),
        pytest.param(
            '{"tool_calls":[{"function":{"arguments":"{}"}}],"content":"fallback"}',
            None, "fallback", id="nameless call skipped, falls to content",
        ),
        pytest.param(' {"answer":true} ', None, ' {"answer":true} ', id="structured output passes through"),
    ],
)
def test_parse_reply(text, calls, content):
    got = _parse_reply(text)
    if calls is not None:
        assert got == {"tool_calls": calls}
    else:
        assert got == {"content": content}
