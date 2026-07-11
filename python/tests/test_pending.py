"""§10 Suspension — Pending / wait_for conformance test (the "Kite/OIDC auth" test).

Mirrors ``js/examples/pending.ts``: drives the REAL client loop against a stubbed
LLM (no network, no key) by monkeypatching the client's blocking JSON POST.

A ``get_balance`` tool returns ``auth_required(...)`` until authed. A fake LLM
(turn 1) calls the tool and (turn 2, once a tool result appears) returns final
text. We assert both host postures:

  A) with ``wait_for`` (sets authed, returns ok) → run completes, status=="done",
     final answer contains the balance;
  B) without ``wait_for`` → run halts, status=="pending", pending.kind=="authorization",
     pending.url contains the login URL.
"""
from __future__ import annotations

import pytest

import toolnexus.client as client_mod
from toolnexus import (
    Answer,
    auth_required,
    create_client,
    create_toolkit,
    define_tool,
    pending,
    pending_of,
)

LOGIN_URL = "https://example.com/login?token=abc"


async def _balance_toolkit_and_state():
    """A fresh toolkit + a mutable auth flag the tool and wait_for share."""
    state = {"authed": False}

    def get_balance(ctx=None):
        """Return the account balance. Requires login first."""
        if not state["authed"]:
            return auth_required(LOGIN_URL, "Log in to view your balance")
        aid = ctx.answer.id if ctx and ctx.answer else None
        return f"balance: ₹67,417 (resolved via answer {aid})"

    tool = define_tool(
        get_balance,
        name="get_balance",
        description="Return the account balance. Requires login first.",
        input_schema={"type": "object", "properties": {}, "additionalProperties": False},
    )
    tk = await create_toolkit(extra_tools=[tool], builtins=False)
    return tk, state


def _stub_llm(monkeypatch):
    """Patch the client's blocking POST: turn 1 calls get_balance; once a tool
    message appears in the transcript, turn 2 returns a final answer."""

    def fake_post(url, headers, payload, timeout):
        saw_tool = any(m.get("role") == "tool" for m in payload["messages"])
        if saw_tool:
            message = {"role": "assistant", "content": "Done — your balance is ₹67,417."}
        else:
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {"id": "c1", "type": "function", "function": {"name": "get_balance", "arguments": "{}"}}
                ],
            }
        return {
            "choices": [{"message": message}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }

    monkeypatch.setattr(client_mod, "_post", fake_post)


@pytest.mark.asyncio
async def test_wait_for_resolves_and_retries(monkeypatch):
    """(A) wait_for provided → the engine resolves + retries transparently."""
    _stub_llm(monkeypatch)
    tk, state = await _balance_toolkit_and_state()
    seen: list[str] = []

    async def wait_for(request):
        seen.append(request.url)
        state["authed"] = True  # the world changed out-of-band
        return Answer(id=request.id, ok=True)

    client = create_client(
        base_url="http://stub", style="openai", model="stub", api_key="x", wait_for=wait_for
    )
    result = await client.run("what is my balance?", tk)

    assert result.status == "done"
    assert "67,417" in result.tool_calls[0]["output"]
    assert "example.com/login" in seen[0]
    assert "67,417" in result.text


@pytest.mark.asyncio
async def test_no_wait_for_halts_pending(monkeypatch):
    """(B) no wait_for → durable: run() halts with the request to resume later."""
    _stub_llm(monkeypatch)
    tk, _state = await _balance_toolkit_and_state()

    client = create_client(base_url="http://stub", style="openai", model="stub", api_key="x")
    result = await client.run("what is my balance?", tk)

    assert result.status == "pending"
    assert result.pending is not None
    assert result.pending.kind == "authorization"
    assert "example.com/login" in result.pending.url


@pytest.mark.asyncio
async def test_question_suspension_halts_run(monkeypatch):
    """A `question` builtin suspension with no wait_for halts the run (status pending)."""

    def fake_post(url, headers, payload, timeout):
        # Model calls the `question` builtin; with no wait_for the loop halts (§10).
        return {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "c1",
                                "type": "function",
                                "function": {
                                    "name": "question",
                                    "arguments": '{"questions": [{"question": "Pick a color?", "options": ["red", "green"]}]}',
                                },
                            }
                        ],
                    }
                }
            ],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }

    monkeypatch.setattr(client_mod, "_post", fake_post)
    tk = await create_toolkit()  # builtins on → `question` exists

    client = create_client(base_url="http://stub", style="openai", model="stub", api_key="x")  # no wait_for
    result = await client.run("ask me", tk)

    assert result.status == "pending", "no wait_for ⇒ the run halts pending, does not loop forever"
    assert result.pending is not None
    assert result.pending.kind == "question"
    assert result.pending.prompt == "Pick a color? (options: red, green)"


@pytest.mark.asyncio
async def test_suspension_is_pending_not_error_after_tool_resolved_only(monkeypatch):
    """G2: a suspension is pending, not a tool error; after_tool sees only the resolved result.

    The model calls ``question`` (a §10 suspension); a ``wait_for`` resolves it. The ``tool``
    metric for the suspension is tagged ``pending`` with ``is_error`` False (never counted as a
    tool error), and ``after_tool`` runs exactly once — on the resolved result, not the suspension.
    """

    def fake_post(url, headers, payload, timeout):
        saw_tool = any(m.get("role") == "tool" for m in payload["messages"])
        if saw_tool:
            message = {"role": "assistant", "content": "done"}
        else:
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {"id": "c1", "type": "function", "function": {"name": "question", "arguments": '{"questions": [{"question": "Pick?", "options": ["a", "b"]}]}'}}
                ],
            }
        return {"choices": [{"message": message}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}

    monkeypatch.setattr(client_mod, "_post", fake_post)
    tk = await create_toolkit()  # builtins on → `question` exists

    events: list[dict] = []
    after_tool_saw: list = []

    async def wait_for(request):
        return Answer(id=request.id, ok=True, data={"answers": ["a"]})

    def after_tool(ev):
        after_tool_saw.append(ev["result"])

    client = create_client(
        base_url="http://stub", style="openai", model="stub", api_key="x",
        wait_for=wait_for, hooks={"after_tool": after_tool}, on_metric=events.append,
    )
    result = await client.run("ask", tk)

    assert result.status == "done", "wait_for resolved the suspension and the run finished"
    tool_events = [e for e in events if e["event"] == "tool" and e["tool"] == "question"]
    suspend = next((e for e in tool_events if e.get("pending")), None)
    assert suspend is not None, "the suspension emitted a tool event tagged pending"
    assert suspend["is_error"] is False, "a suspension is not a tool error"
    assert len(after_tool_saw) == 1, "after_tool ran exactly once — not on the suspension"
    assert pending_of(after_tool_saw[0]) is None, "after_tool saw the resolved result, never the suspension"


@pytest.mark.asyncio
async def test_before_tool_guard_raised_pending_counted_pending_not_error(monkeypatch):
    """Path B: a ``before_tool`` guard raises a suspension with NO tool code running.

    On approval (a ``wait_for`` that oks it) the real tool runs; the ``tool`` metric for that
    call is tagged ``pending`` with ``is_error`` False, and the real tool's output lands.
    """

    def fake_post(url, headers, payload, timeout):
        saw_tool = any(m.get("role") == "tool" for m in payload["messages"])
        if saw_tool:
            message = {"role": "assistant", "content": "3"}
        else:
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {"id": "c1", "type": "function", "function": {"name": "add", "arguments": '{"a": 1, "b": 2}'}}
                ],
            }
        return {"choices": [{"message": message}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}

    monkeypatch.setattr(client_mod, "_post", fake_post)
    tk = await create_toolkit(builtins=False)
    tk.register(define_tool(
        lambda a, b: str(a + b),
        name="add", description="add",
        input_schema={"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]},
    ))

    events: list[dict] = []
    asked = {"v": False}

    # Guard raises a suspension with NO tool code running (path B).
    def before_tool(ev):
        if ev["name"] == "add" and not asked["v"]:
            return {"result": pending(kind="approval", prompt="approve add?")}
        return None

    async def wait_for(request):
        asked["v"] = True
        return Answer(id=request.id, ok=True)

    client = create_client(
        base_url="http://stub", style="openai", model="stub", api_key="x",
        hooks={"before_tool": before_tool}, wait_for=wait_for, on_metric=events.append,
    )
    result = await client.run("add them", tk)

    assert result.status == "done", "on approval the real tool ran and the run finished"
    tool_events = [e for e in events if e["event"] == "tool" and e["tool"] == "add"]
    suspend = next((e for e in tool_events if e.get("pending")), None)
    assert suspend is not None, "the guard-raised pending emitted a tool event tagged pending"
    assert suspend["is_error"] is False, "a guard-raised suspension is not a tool error"
    assert any(t["name"] == "add" and t["output"] == "3" for t in result.tool_calls), "the real tool ran after approval"


@pytest.mark.asyncio
async def test_two_concurrent_suspensions_surface_first_not_last(monkeypatch):
    """G3: two ``question`` tool_calls in one turn, no ``wait_for`` → the run halts on the FIRST
    suspension in call order (deterministic), and the second's placeholder never pollutes the
    transcript."""

    def fake_post(url, headers, payload, timeout):
        return {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {"id": "c1", "type": "function", "function": {"name": "question", "arguments": '{"questions": [{"question": "First?"}]}'}},
                            {"id": "c2", "type": "function", "function": {"name": "question", "arguments": '{"questions": [{"question": "Second?"}]}'}},
                        ],
                    }
                }
            ],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }

    monkeypatch.setattr(client_mod, "_post", fake_post)
    tk = await create_toolkit()  # builtins on, no wait_for

    client = create_client(base_url="http://stub", style="openai", model="stub", api_key="x")
    result = await client.run("ask two", tk)

    assert result.status == "pending"
    assert result.pending is not None
    assert result.pending.prompt == "First?", "the FIRST suspension in call order is surfaced, deterministically"
    c2msg = next((m for m in result.messages if m.get("tool_call_id") == "c2"), None)
    assert c2msg is None, "the second concurrent suspension does not pollute the transcript"


@pytest.mark.asyncio
async def test_g3_also_holds_on_anthropic_non_streaming_path(monkeypatch):
    """G3 on the Anthropic non-streaming path: two ``question`` tool_use blocks, no ``wait_for``
    → the run halts on the FIRST suspension in call order, and only its tool_result block enters
    the single ``{role:"user", content:[...]}`` message (no second-suspension leak)."""

    def fake_post(url, headers, payload, timeout):
        return {
            "content": [
                {"type": "tool_use", "id": "t1", "name": "question", "input": {"questions": [{"question": "First?"}]}},
                {"type": "tool_use", "id": "t2", "name": "question", "input": {"questions": [{"question": "Second?"}]}},
            ],
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 10, "output_tokens": 5},
        }

    monkeypatch.setattr(client_mod, "_post", fake_post)
    tk = await create_toolkit()  # builtins on, no wait_for

    client = create_client(base_url="http://stub", style="anthropic", model="claude-x", api_key="k")
    result = await client.run("ask two", tk)

    assert result.status == "pending"
    assert result.pending is not None
    assert result.pending.prompt == "First?", "first tool_use in order is surfaced"
    user_msg = next(
        (m for m in reversed(result.messages) if m.get("role") == "user" and isinstance(m.get("content"), list)),
        None,
    )
    assert user_msg is not None
    ids = [b.get("tool_use_id") for b in user_msg["content"]]
    assert "t1" in ids and "t2" not in ids, "only the first tool_result is recorded, no second-suspension leak"
