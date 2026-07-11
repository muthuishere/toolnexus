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
from toolnexus import Answer, auth_required, create_client, create_toolkit, define_tool

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
