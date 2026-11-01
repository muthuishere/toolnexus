"""Tool tiers (change add-prompt-cache phase 2; SPEC §8 "Prompt caching"): plan,
late register, budget demotion, call_tool unwrap + stable tools array, stream events.
Mirrors golang/tool_tiers_test.go."""
from __future__ import annotations

import io
import json

from toolnexus import (
    TIER_CORE,
    TIER_DEFERRED,
    TIER_LISTED,
    Tool,
    ToolHints,
    ToolResult,
    ToolTierOptions,
    create_client,
    create_toolkit,
)
from toolnexus.tool_tiers import _tool_def, build_plan, default_count_tokens, tool_search

SCHEMA = {"type": "object", "properties": {"x": {"type": "string"}}}


async def _tier_toolkit(ran: list[str]):
    def mk(name, desc, hints):
        async def run(args=None, ctx=None):
            ran.append(f"{name}({(args or {}).get('x')})")
            return ToolResult(output=f"{name} ok", is_error=False)

        return Tool(name=name, description=desc, input_schema=SCHEMA, source="native", execute=run, hints=hints)

    return await create_toolkit(builtins=False, extra_tools=[
        mk("zeta_read", "Read zeta.\nsecond line hidden", ToolHints(read_only=True)),
        mk("alpha_read", "Read alpha.", ToolHints(read_only=True)),
        mk("deploy", "Deploy the service to production.", ToolHints(destructive=True)),
        mk("lint", "Lint the code.", None),
    ])


async def test_tool_tier_plan_and_description():
    tk = await _tier_toolkit([])
    c = create_client(base_url="http://x", style="openai", model="m", api_key="k",
                      tool_tiers=ToolTierOptions(core=["zeta_read", "alpha_read"], deferred=["deploy"]))
    got = c._request_tools(tk)
    assert [t.name for t in got] == ["alpha_read", "zeta_read", "tool_search", "call_tool"]
    desc = got[2].description
    assert "- lint — Lint the code." in desc and "(name only): deploy" in desc and "Deploy the service" not in desc
    assert "- zeta_read" not in desc
    plan = c.tool_tier_plan(tk)
    assert plan["deploy"] == TIER_DEFERRED and plan["lint"] == TIER_LISTED and plan["alpha_read"] == TIER_CORE

    async def late(args=None, ctx=None):
        return ToolResult(output="late ok", is_error=False)

    tk.register(Tool(name="late", description="A late tool.", input_schema=SCHEMA, source="native", execute=late))
    again = c._request_tools(tk)
    assert len(again) == 4 and again[2].description == desc
    r = tool_search(tk.tools(), tk.get, {"query": "late"})
    assert '"name": "late"' in r.output
    c.reset_tool_tiers(tk)
    assert "late" in c.tool_tier_plan(tk)


async def test_tool_tier_budget_demotes_least_used():
    tk = await _tier_toolkit([])
    uses = {"alpha_read": 9, "zeta_read": 1, "lint": 5, "deploy": 0}
    base = build_plan(ToolTierOptions(core=["alpha_read", "zeta_read"]), tk.tools())
    fixed = default_count_tokens(_tool_def(base.search)) + default_count_tokens(_tool_def(base.call))
    one = default_count_tokens(_tool_def(tk.get("alpha_read")))
    p = build_plan(ToolTierOptions(core=["alpha_read", "zeta_read"], budget_tokens=fixed + one + 15,
                                   usage_count=lambda n: uses[n]), tk.tools())
    assert p.tier["alpha_read"] == TIER_CORE and p.tier["zeta_read"] == TIER_LISTED, p.tier
    p = build_plan(ToolTierOptions(core=["alpha_read"], budget_tokens=1, usage_count=lambda n: uses[n]), tk.tools())
    assert all(t == TIER_DEFERRED for t in p.tier.values()), p.tier
    # context_window ⇒ 5% budget
    p = build_plan(ToolTierOptions(core=["alpha_read"], context_window=20), tk.tools())
    assert p.tier["alpha_read"] != TIER_CORE


class _Script:
    def __init__(self):
        self.arrays: list[str] = []
        self.step = 0

    def post(self, url, headers, payload, timeout):
        self.arrays.append(json.dumps(payload.get("tools"), sort_keys=True))
        self.step += 1
        content = {
            1: [{"type": "tool_use", "id": "s1", "name": "tool_search", "input": {"query": "select:deploy"}}],
            2: [{"type": "tool_use", "id": "c1", "name": "call_tool", "input": {"name": "deploy", "arguments": {"x": "prod"}}}],
            3: [{"type": "tool_use", "id": "d1", "name": "lint", "input": {"x": "direct"}}],
        }.get(self.step, [{"type": "text", "text": "done"}])
        return {"content": content, "stop_reason": "tool_use", "usage": {"input_tokens": 1, "output_tokens": 1}}


async def test_tool_tier_loop_call_tool_unwraps():
    ran: list[str] = []
    tk = await _tier_toolkit(ran)
    gated: list[str] = []

    async def before_tool(ev):
        h = ev["hints"]
        gated.append(f"{ev['name']}:{'?' if h is None else str(h.destructive).lower()}")
        return None

    script = _Script()
    c = create_client(base_url="http://x", style="anthropic", model="m", api_key="k", http_transport=script,
                      tool_tiers=ToolTierOptions(core=["alpha_read"], deferred=["deploy"]),
                      hooks={"before_tool": before_tool})
    res = await c.run("ship it", tk)
    assert res.status == "done"
    assert '"name": "deploy"' in res.tool_calls[0]["output"]
    assert ran == ["deploy(prod)", "lint(direct)"]
    assert gated == ["deploy:true", "lint:?"]  # tool_search is engine-internal; call_tool unwrapped
    assert all(a == script.arrays[0] for a in script.arrays)
    assert json.loads(script.arrays[0])[-2]["name"] == "tool_search"


def _sse(events):
    return io.BytesIO("".join(f"data: {e if isinstance(e, str) else json.dumps(e)}\n\n" for e in events).encode())


class _StreamScript:
    def __init__(self):
        self.step = 0

    def open(self, url, headers, payload, timeout):  # noqa: A003
        self.step += 1
        if self.step == 1:
            args = json.dumps({"name": "deploy", "arguments": {"x": "prod"}})
            return _sse([{"choices": [{"delta": {"tool_calls": [
                {"index": 0, "id": "c1", "type": "function", "function": {"name": "call_tool", "arguments": args}}]}}]},
                {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]}, "[DONE]"])
        return _sse([{"choices": [{"delta": {"content": "done"}, "finish_reason": "stop"}]}, "[DONE]"])


async def test_tool_tier_stream_names_real_tool():
    ran: list[str] = []
    tk = await _tier_toolkit(ran)
    c = create_client(base_url="http://x", style="openai", model="m", api_key="k", http_transport=_StreamScript(),
                      tool_tiers=ToolTierOptions(core=["alpha_read"]))
    got = []
    async for ev in c.stream("go", tk):
        if ev["type"] in ("tool_call", "tool_result"):
            got.append(f"{ev['type']}:{ev['name']}:{ev.get('via')}:{(ev.get('args') or {}).get('x')}")
    assert got == ["tool_call:deploy:call_tool:prod", "tool_result:deploy:call_tool:None"]
    assert ran == ["deploy(prod)"]


def test_tool_search_json_escapes_like_go():
    # Same bytes as golang TestToolSearchJSONIsHTMLEscaped.
    async def run(args=None, ctx=None):
        return ToolResult(output="", is_error=False)

    amp = Tool(name="amp", description="Use <a & b> here", input_schema=SCHEMA, source="native", execute=run)
    r = tool_search([amp], lambda n: amp if n == "amp" else None, {"query": "select:amp"})
    want = (
        "Loaded tools (call by name, or with call_tool):\n[\n  {\n"
        '    "description": "Use \\u003ca \\u0026 b\\u003e here",\n'
        '    "inputSchema": {\n      "properties": {\n        "x": {\n          "type": "string"\n'
        '        }\n      },\n      "type": "object"\n    },\n    "name": "amp"\n  }\n]'
    )
    assert r.output == want


def test_first_line_cuts_on_a_character_boundary():
    from toolnexus.tool_tiers import _first_line

    assert _first_line("a" * 119 + "é tail") == "a" * 119 + "…"
    assert _first_line("a" * 117 + "€z") == "a" * 117 + "€…"
