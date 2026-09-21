"""ACP (Agent Client Protocol) as a model source — ADR 0031, issue #96, ported to
Python.

Hermetic: no network, no real `devin`/agent binary. Drives
``toolnexus.tests.fixtures.acp_fake_server`` (a scripted fake ACP server, stdlib
only) as a real child process over real OS pipes, exactly the shape a real agent
uses. Mirrors ``spikes/acp/SPIKE.md``'s gates and the style of
``test_agents_inprocess.py`` / ``test_client_resilience.py``.
"""
from __future__ import annotations

import os
import sys
import threading
import time

import pytest

from toolnexus import ACPOptions, ACPPermissionTimeoutError, load_acp

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
        # The client answers with the FIRST allow-kind option: "allow-once".
        assert result["content"] == "PERMITTED:allow-once"

        diag = client._call("diagnostics/get", {})
        assert diag["last_option_id"] == "allow-once"
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
