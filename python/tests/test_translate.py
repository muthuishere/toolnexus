"""Single-turn translation tests (SPEC.md §11, ADR-0011).

Ports ``golang/translate_test.go`` — Go's assertions are the cross-port oracle. Hermetic:
a local ``http.server`` stands in for the provider and records what it was actually sent.
"""

from __future__ import annotations

import http.server
import json
import threading
from typing import Any, Optional

import pytest

from toolnexus import Client, create_toolkit, define_tool


class _Upstream:
    """A provider stand-in that records the request body and replies with a canned response."""

    def __init__(self, reply: dict[str, Any]) -> None:
        self.reply = reply
        self.body: Optional[dict[str, Any]] = None
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("content-length") or 0)
                raw = self.rfile.read(length) if length else b"{}"
                try:
                    outer.body = json.loads(raw)
                except ValueError:
                    outer.body = {}
                payload = json.dumps(outer.reply).encode()
                self.send_response(200)
                self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args: Any) -> None:  # silence the test log
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        host, port = self.server.server_address[0], self.server.server_address[1]
        return f"http://{host}:{port}"

    def sent_json(self) -> str:
        return json.dumps(self.body or {})

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def upstream():
    made: list[_Upstream] = []

    def make(reply: dict[str, Any]) -> _Upstream:
        u = _Upstream(reply)
        made.append(u)
        return u

    yield make
    for u in made:
        u.close()


def _openai_tools() -> list[dict[str, Any]]:
    """The OpenAI ``tools`` array a client sends, verbatim."""
    return [
        {
            "type": "function",
            "function": {
                "name": "get_weather",
                "description": "Get the weather",
                "parameters": {
                    "type": "object",
                    "properties": {"city": {"type": "string"}},
                    "required": ["city"],
                },
            },
        }
    ]


def _client(url: str, style: str = "anthropic") -> Client:
    return Client(base_url=url, style=style, model="stub", api_key="k")


# ---- Anthropic upstream: the real translation ----


@pytest.mark.asyncio
async def test_tool_use_comes_back_as_an_openai_tool_call(upstream):
    up = upstream(
        {
            "content": [{"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {"city": "Chennai"}}],
            "stop_reason": "tool_use",
            "usage": {"input_tokens": 10, "output_tokens": 5},
        }
    )
    res = await _client(up.url).translate(
        [{"role": "user", "content": "weather in Chennai?"}], tools=_openai_tools()
    )
    assert res.finish_reason == "tool_calls"
    assert len(res.tool_calls) == 1
    assert res.tool_calls[0].id == "toolu_1"
    assert res.tool_calls[0].name == "get_weather"
    # arguments must be a JSON STRING (the OpenAI wire shape), not an object
    assert isinstance(res.tool_calls[0].arguments, str)
    assert json.loads(res.tool_calls[0].arguments)["city"] == "Chennai"
    assert res.usage["total_tokens"] > 0
    sent = up.sent_json()
    assert "input_schema" in sent
    assert "get_weather" in sent
    assert '"parameters"' not in sent, "OpenAI-shaped 'parameters' leaked to the Anthropic upstream"


@pytest.mark.asyncio
async def test_multi_turn_tool_exchange_survives(upstream):
    """The case a text-flattening translator gets wrong."""
    up = upstream(
        {
            "content": [{"type": "text", "text": "It is 31C in Chennai."}],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 20, "output_tokens": 8},
        }
    )
    res = await _client(up.url).translate(
        [
            {"role": "system", "content": "Be terse."},
            {"role": "user", "content": "weather in Chennai?"},
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_abc",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": '{"city":"Chennai"}'},
                    }
                ],
            },
            {"role": "tool", "tool_call_id": "call_abc", "content": "31C, clear"},
        ],
        tools=_openai_tools(),
    )
    assert res.finish_reason == "stop"
    assert res.text == "It is 31C in Chennai."

    assert up.body["system"] == "Be terse.", "system not hoisted out of messages"
    sent = up.sent_json()
    for want in ("tool_use", "tool_result", "call_abc", "31C, clear"):
        assert want in sent, f"multi-turn structure lost {want}"
    # the tool_use's input is an OBJECT upstream, re-parsed from the JSON string
    uses = [
        b
        for m in up.body["messages"]
        for b in (m["content"] if isinstance(m.get("content"), list) else [])
        if b.get("type") == "tool_use"
    ]
    assert uses, "no tool_use block reached the provider"
    assert uses[0]["input"]["city"] == "Chennai", "tool_use input not re-parsed to an object"


@pytest.mark.asyncio
async def test_three_consecutive_tool_results_merge_into_one_user_turn(upstream):
    up = upstream({"content": [{"type": "text", "text": "done"}], "stop_reason": "end_turn"})
    await _client(up.url).translate(
        [
            {"role": "user", "content": "do three things"},
            {
                "role": "assistant",
                "tool_calls": [
                    {"id": "a", "function": {"name": "f", "arguments": "{}"}},
                    {"id": "b", "function": {"name": "f", "arguments": "{}"}},
                    {"id": "c", "function": {"name": "f", "arguments": "{}"}},
                ],
            },
            {"role": "tool", "tool_call_id": "a", "content": "ra"},
            {"role": "tool", "tool_call_id": "b", "content": "rb"},
            {"role": "tool", "tool_call_id": "c", "content": "rc"},
        ]
    )
    msgs = up.body["messages"]
    result_turns = [
        m
        for m in msgs
        if isinstance(m.get("content"), list) and any(b.get("type") == "tool_result" for b in m["content"])
    ]
    assert len(result_turns) == 1, "tool results spread over more than one user turn"
    assert len([b for b in result_turns[0]["content"] if b.get("type") == "tool_result"]) == 3
    uses = [
        b
        for m in msgs
        for b in (m["content"] if isinstance(m.get("content"), list) else [])
        if b.get("type") == "tool_use"
    ]
    assert len(uses) == 3, "want 3 tool_use blocks upstream"


@pytest.mark.asyncio
async def test_parallel_tool_calls_all_returned_in_provider_order(upstream):
    up = upstream(
        {
            "content": [
                {"type": "text", "text": "calling three"},
                {"type": "tool_use", "id": "t1", "name": "alpha", "input": {"n": 1}},
                {"type": "tool_use", "id": "t2", "name": "beta", "input": {"n": 2}},
                {"type": "tool_use", "id": "t3", "name": "gamma", "input": {"n": 3}},
            ],
            "stop_reason": "tool_use",
        }
    )
    res = await _client(up.url).translate([{"role": "user", "content": "go"}], tools=_openai_tools())
    assert [tc.name for tc in res.tool_calls] == ["alpha", "beta", "gamma"]
    assert res.text == "calling three", "text alongside tool calls was lost"
    assert res.finish_reason == "tool_calls"
    envelope = res.tool_calls_json()
    assert len(envelope) == 3
    assert envelope[0]["type"] == "function"


@pytest.mark.asyncio
async def test_executes_nothing_and_keeps_no_state(upstream):
    ran = 0
    up = upstream(
        {"content": [{"type": "tool_use", "id": "t1", "name": "danger", "input": {}}], "stop_reason": "tool_use"}
    )

    async def handler(args):
        nonlocal ran
        ran += 1
        return "RAN"

    tk = await create_toolkit(
        builtins=False,
        extra_tools=[define_tool(handler, name="danger", description="must not run")],
    )
    try:
        c = _client(up.url)
        for _ in range(3):
            res = await c.translate([{"role": "user", "content": "go"}], toolkit=tk)
            assert len(res.tool_calls) == 1
            assert res.tool_calls[0].name == "danger"
        assert ran == 0, "translate EXECUTED a tool — it must never execute anything"
        # no history accumulated between the three independent calls
        assert len(up.body["messages"]) == 1, "state leaked between translate calls"
    finally:
        await tk.close()


@pytest.mark.asyncio
async def test_toolkit_is_declared_but_never_executed(upstream):
    """The generality case: a real toolkit works, not only OpenAI JSON."""
    ran = False
    up = upstream(
        {
            "content": [{"type": "tool_use", "id": "tu_9", "name": "my_native_tool", "input": {"x": 1}}],
            "stop_reason": "tool_use",
        }
    )

    async def handler(args):
        nonlocal ran
        ran = True
        return "SHOULD NOT RUN"

    tk = await create_toolkit(
        builtins=False,
        extra_tools=[
            define_tool(
                handler,
                name="my_native_tool",
                description="an ordinary executable tool",
                input_schema={"type": "object", "properties": {"x": {"type": "number"}}},
            )
        ],
    )
    try:
        res = await _client(up.url).translate([{"role": "user", "content": "use the tool"}], toolkit=tk)
        assert ran is False, "translate executed a toolkit tool"
        assert res.tool_calls[0].name == "my_native_tool"
        assert res.tool_calls[0].id == "tu_9"
        sent = up.sent_json()
        assert "input_schema" in sent
        assert "my_native_tool" in sent
    finally:
        await tk.close()


@pytest.mark.asyncio
async def test_toolkit_and_openai_tools_compose(upstream):
    up = upstream({"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"})

    async def handler(args):
        return "x"

    tk = await create_toolkit(
        builtins=False,
        extra_tools=[define_tool(handler, name="server_side_tool", description="gateway's own")],
    )
    try:
        await _client(up.url).translate(
            [{"role": "user", "content": "go"}], toolkit=tk, tools=_openai_tools()
        )
        sent = up.sent_json()
        for want in ("server_side_tool", "get_weather"):
            assert want in sent, f"composed declaration missing {want}"
    finally:
        await tk.close()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "choice,want",
    [
        (None, None),
        ("auto", None),
        ("required", '"type": "any"'),
        ("none", '"type": "none"'),
        ({"type": "function", "function": {"name": "get_weather"}}, '"name": "get_weather"'),
    ],
)
async def test_tool_choice_mapping(upstream, choice, want):
    up = upstream({"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"})
    await _client(up.url).translate(
        [{"role": "user", "content": "go"}], tools=_openai_tools(), tool_choice=choice
    )
    if want is None:
        assert "tool_choice" not in up.body, f"tool_choice {choice} should be omitted"
    else:
        assert "tool_choice" in up.body, f"tool_choice {choice} missing"
        assert want in json.dumps(up.body["tool_choice"]), f"tool_choice did not map to {want}"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "stop,want",
    [("end_turn", "stop"), ("max_tokens", "length"), ("refusal", "content_filter"), ("stop_sequence", "stop")],
)
async def test_finish_reason_mapping(upstream, stop, want):
    up = upstream({"content": [{"type": "text", "text": "x"}], "stop_reason": stop})
    res = await _client(up.url).translate([{"role": "user", "content": "go"}])
    assert res.finish_reason == want, f"stop_reason {stop}"


@pytest.mark.asyncio
async def test_arguments_accepted_as_an_object_too(upstream):
    """Some clients send ``arguments`` as an object rather than a JSON string."""
    up = upstream({"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"})
    await _client(up.url).translate(
        [
            {"role": "user", "content": "go"},
            {"role": "assistant", "tool_calls": [{"id": "z", "function": {"name": "f", "arguments": {"city": "Madurai"}}}]},
            {"role": "tool", "tool_call_id": "z", "content": "done"},
        ]
    )
    uses = [
        b
        for m in up.body["messages"]
        for b in (m["content"] if isinstance(m.get("content"), list) else [])
        if b.get("type") == "tool_use"
    ]
    assert uses, "no tool_use block upstream"
    assert uses[0]["input"]["city"] == "Madurai", "object-form arguments were not carried through"


@pytest.mark.asyncio
async def test_content_parts_are_flattened(upstream):
    up = upstream({"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"})
    await _client(up.url).translate(
        [
            {
                "role": "user",
                "content": [{"type": "text", "text": "part one "}, {"type": "text", "text": "part two"}],
            }
        ]
    )
    assert "part one part two" in up.sent_json()


@pytest.mark.asyncio
async def test_llm_hooks_fire_once_and_no_tool_hook_fires(upstream):
    up = upstream(
        {"content": [{"type": "tool_use", "id": "t1", "name": "get_weather", "input": {}}], "stop_reason": "tool_use"}
    )
    counts = {"before": 0, "after": 0, "tool": 0}

    async def before_llm(ev):
        counts["before"] += 1
        return None

    async def after_llm(ev):
        counts["after"] += 1

    async def tool_hook(ev):
        counts["tool"] += 1
        return None

    c = Client(
        base_url=up.url,
        style="anthropic",
        model="stub",
        api_key="k",
        hooks={
            "before_llm": before_llm,
            "after_llm": after_llm,
            "before_tool": tool_hook,
            "after_tool": tool_hook,
        },
    )
    await c.translate([{"role": "user", "content": "go"}], tools=_openai_tools())
    assert counts["before"] == 1, "before_llm did not fire exactly once"
    assert counts["after"] == 1, "after_llm did not fire exactly once"
    assert counts["tool"] == 0, "a tool hook fired, but no tool runs in translate"


# ---- OpenAI upstream: near-passthrough ----


@pytest.mark.asyncio
async def test_openai_upstream_passes_tools_and_arguments_through(upstream):
    up = upstream(
        {
            "choices": [
                {
                    "message": {
                        "content": "",
                        "tool_calls": [
                            {
                                "id": "call_1",
                                "type": "function",
                                "function": {"name": "get_weather", "arguments": '{"city":"Madurai"}'},
                            }
                        ],
                    },
                    "finish_reason": "tool_calls",
                }
            ],
            "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7},
        }
    )
    res = await _client(up.url, style="openai").translate(
        [{"role": "user", "content": "weather?"}], tools=_openai_tools()
    )
    assert res.finish_reason == "tool_calls"
    assert res.tool_calls[0].arguments == '{"city":"Madurai"}', "arguments not byte-for-byte"
    assert res.usage["total_tokens"] == 7
    assert '"parameters"' in up.sent_json(), "OpenAI tools were altered on an OpenAI upstream"
