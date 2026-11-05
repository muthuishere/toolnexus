"""Toolkit.mcp_handler (SPEC §7C.1, ADR 0039): the embeddable MCP server, exercised
with the ``mcp`` SDK CLIENT over real streamable HTTP (uvicorn on a local port), plus
raw HTTP checks for the protocol 2025-06-18 transport rules. Mirrors
golang/mcphandler_test.go test for test."""
from __future__ import annotations

import asyncio
import json
import time
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Optional

import httpx
import pytest
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
from mcp.shared.exceptions import McpError
from mcp.types import ElicitResult

from _mcp_handler_server import running
from toolnexus import (
    McpCallEvent,
    McpDecision,
    McpHandlerConfig,
    McpPrompt,
    McpPromptArgument,
    McpPromptMessage,
    McpPromptResult,
    McpResource,
    McpResourceContent,
    McpServerInfo,
    Tool,
    ToolAnnotations,
    ToolResult,
    create_toolkit,
    identity_from,
    mcp_request_meta_from,
    request_from,
)
from toolnexus.mcp_handler import mcp_wire_result, mcp_wire_tool
from toolnexus.mcp_serve import _mcp_tool_def, tool_result_to_call_result


class User:
    def __init__(self, name: str) -> None:
        self.name = name


def ttool(name: str, ran: Optional[list[int]] = None) -> Tool:
    """A tool that echoes the caller identity, counting executions."""

    async def execute(args: dict[str, Any], ctx: Any = None) -> ToolResult:
        if ran is not None:
            ran.append(1)
        u = identity_from()
        return ToolResult(output=f"{name}:{u.name if u else ''}", is_error=False)

    return Tool(
        name=name,
        description=name + " tool",
        input_schema={"type": "object", "properties": {"id": {"type": "string"}}},
        source="native",
        execute=execute,
    )


def bearer_auth(req: Any) -> User:
    tok = req.header("Authorization").removeprefix("Bearer ")
    if tok in ("alice", "bob"):
        return User(tok)
    raise ValueError("bad token")


async def tk():
    return await create_toolkit()


@asynccontextmanager
async def dial(url: str, token: str = "", elicit: Any = None, on_message: Any = None) -> AsyncIterator[ClientSession]:
    headers = {"Authorization": "Bearer " + token} if token else None
    async with streamablehttp_client(url, headers=headers) as (r, w, _):
        kw: dict[str, Any] = {}
        if elicit is not None:
            kw["elicitation_callback"] = elicit
        if on_message is not None:
            kw["message_handler"] = on_message
        async with ClientSession(r, w, **kw) as s:
            await s.initialize()
            yield s


def text(r: Any) -> str:
    return r.content[0].text if r is not None and r.content else ""


async def names(s: ClientSession) -> list[str]:
    return [t.name for t in (await s.list_tools()).tools]


def elicitor(action: str, asked: list[str]):
    async def cb(context: Any, params: Any) -> ElicitResult:
        asked.append(params.message)
        return ElicitResult(action=action, content={"confirm": action == "accept"})

    return cb


async def raw_post(url: str, sid: str, body: str, hdr: Optional[dict[str, str]] = None) -> httpx.Response:
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if sid:
        h["Mcp-Session-Id"] = sid
    h.update(hdr or {})
    async with httpx.AsyncClient() as c:
        return await c.post(url, content=body, headers=h)


async def test_auth_rejects_with_401_and_www_authenticate():
    h = (await tk()).mcp_handler(
        authenticate=bearer_auth,
        www_authenticate=lambda r, e: 'Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource"',
    )
    with running(h) as url:
        resp = await raw_post(url, "", '{"jsonrpc":"2.0","id":1,"method":"ping"}')
    assert resp.status_code == 401
    assert "resource_metadata=" in resp.headers["www-authenticate"]


async def test_per_identity_tools_and_not_found_never_executes():
    ran_admin: list[int] = []

    def list_tools(ctx):
        tools = [ttool("search")]  # runtime-synthesized: not registered in the toolkit
        if identity_from(ctx).name == "alice":
            tools.append(ttool("admin_reset", ran_admin))
        return tools

    h = (await tk()).mcp_handler(authenticate=bearer_auth, list_tools=list_tools)
    with running(h) as url:
        async with dial(url, "alice") as alice, dial(url, "bob") as bob:
            assert await names(alice) == ["search", "admin_reset"]
            assert await names(bob) == ["search"]
            assert text(await bob.call_tool("search", {})) == "search:bob"
            with pytest.raises(McpError, match="tool not found"):
                await bob.call_tool("admin_reset", {})
            assert ran_admin == []
            assert text(await alice.call_tool("admin_reset", {})) == "admin_reset:alice"


async def gated_handler(fallback: Optional[ToolResult] = None):
    ran: list[int] = []
    delete = ttool("delete_record", ran)
    delete.annotations = ToolAnnotations(
        title="Delete", destructive_hint=True, read_only_hint=False, idempotent_hint=True, open_world_hint=False
    )

    def before_call(ctx, tool, args):
        if tool.annotations is not None and tool.annotations.destructive_hint:
            if args.get("nonce") == "nonce-ok":
                return McpDecision(kind="allow")
            return McpDecision(kind="confirm", message="Delete the record?", result=fallback)
        if args.get("id") == "forbidden":
            return McpDecision(kind="deny", message="not allowed")
        return McpDecision()

    h = (await tk()).mcp_handler(
        authenticate=bearer_auth,
        list_tools=lambda ctx: [delete, ttool("read")],
        before_call=before_call,
    )
    return h, ran


async def test_confirm_via_elicitation():
    h, ran = await gated_handler()
    with running(h) as url:
        asked: list[str] = []
        async with dial(url, "alice", elicit=elicitor("accept", asked)) as c:
            r = await c.call_tool("delete_record", {"id": "1"})
            assert not r.isError and text(r) == "delete_record:alice"
            assert asked == ["Delete the record?"] and len(ran) == 1
        no: list[str] = []
        async with dial(url, "alice", elicit=elicitor("decline", no)) as c2:
            r = await c2.call_tool("delete_record", {"id": "1"})
            assert r.isError and text(r) == "declined" and len(ran) == 1 and len(no) == 1


async def test_confirm_without_elicitation():
    h, ran = await gated_handler()
    with running(h) as url:
        async with dial(url, "alice") as c:
            r = await c.call_tool("delete_record", {"id": "1"})
            assert r.isError and text(r) == "confirmation required: Delete the record?" and ran == []
    # host-shaped result (nonce re-call), then the re-call is allowed by the host
    h2, ran2 = await gated_handler(ToolResult(output='re-call with {"nonce":"nonce-ok"}', is_error=False))
    with running(h2) as url:
        async with dial(url, "alice") as c2:
            r = await c2.call_tool("delete_record", {"id": "1"})
            assert not r.isError and "nonce-ok" in text(r) and ran2 == []
            r = await c2.call_tool("delete_record", {"id": "1", "nonce": "nonce-ok"})
            assert text(r) == "delete_record:alice" and len(ran2) == 1


async def test_confirm_timeout_is_confirmation_failed():
    # Raw elicitation round trip: the POST is upgraded to SSE carrying
    # elicitation/create; nobody answers, so confirm_timeout ends it.
    t = ttool("x")
    h = (await tk()).mcp_handler(
        list_tools=lambda ctx: [t],
        before_call=lambda ctx, tool, args: McpDecision(kind="confirm", message="ok?"),
        confirm_timeout=0.2,
    )
    with running(h) as url:
        init = await raw_post(
            url, "", '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"elicitation":{}}}}'
        )
        sid = init.headers["mcp-session-id"]
        resp = await raw_post(url, sid, '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"x"}}')
    assert resp.headers["content-type"].startswith("text/event-stream")
    events = [json.loads(l[6:]) for l in resp.text.splitlines() if l.startswith("data: ")]
    assert events[0]["method"] == "elicitation/create"
    assert events[0]["params"]["requestedSchema"]["properties"]["confirm"]["type"] == "boolean"
    assert events[1]["result"] == {
        "content": [{"type": "text", "text": "confirmation failed: context deadline exceeded"}],
        "isError": True,
    }


async def test_raw_elicitation_round_trip_answer_delivered_by_later_post():
    t = ttool("x")
    h = (await tk()).mcp_handler(
        list_tools=lambda ctx: [t],
        before_call=lambda ctx, tool, args: McpDecision(kind="confirm", message="ok?"),
    )
    with running(h) as url:
        init = await raw_post(
            url, "", '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"elicitation":{}}}}'
        )
        sid = init.headers["mcp-session-id"]
        async with httpx.AsyncClient() as c:
            hdr = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Mcp-Session-Id": sid}
            async with c.stream("POST", url, content='{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"x"}}', headers=hdr) as resp:
                lines = resp.aiter_lines()
                req = None
                async for line in lines:
                    if line.startswith("data: "):
                        req = json.loads(line[6:])
                        break
                assert req["method"] == "elicitation/create" and req["params"]["message"] == "ok?"
                ans = await c.post(
                    url,
                    content=json.dumps({"jsonrpc": "2.0", "id": req["id"], "result": {"action": "accept", "content": {"confirm": True}}}),
                    headers=hdr,
                )
                assert ans.status_code == 202
                final = None
                async for line in lines:
                    if line.startswith("data: "):
                        final = json.loads(line[6:])
                        break
    assert final == {"jsonrpc": "2.0", "id": 2, "result": {"content": [{"type": "text", "text": "x:"}]}}


async def test_deny_and_annotations():
    h, _ = await gated_handler()
    with running(h) as url:
        async with dial(url, "alice") as c:
            r = await c.call_tool("read", {"id": "forbidden"})
            assert r.isError and text(r) == "not allowed"
            a = (await c.list_tools()).tools[0].annotations
            assert a.title == "Delete" and a.destructiveHint is True and a.readOnlyHint is False
            assert a.idempotentHint is True and a.openWorldHint is False


async def test_on_call_event():
    evs: list[McpCallEvent] = []

    async def boom(args, ctx=None):
        await asyncio.sleep(0.005)
        raise RuntimeError("kaput")

    def on_call(ev):
        evs.append(ev)
        raise RuntimeError("isolated")

    h = (await tk()).mcp_handler(
        authenticate=bearer_auth,
        list_tools=lambda ctx: [Tool(name="boom", description="", input_schema={"type": "object"}, source="native", execute=boom)],
        on_call=on_call,
    )
    with running(h) as url:
        async with dial(url, "bob") as c:
            r = await c.call_tool("boom", {})
            assert r.isError and text(r) == "kaput"
    assert len(evs) == 1
    ev = evs[0]
    assert ev.name == "boom" and ev.is_error and isinstance(ev.err, RuntimeError) and ev.result_bytes > 0
    assert ev.ms >= 5 and ev.identity.name == "bob" and ev.session_id and ev.decision == "allow"
    assert ev.source == "native"


async def test_list_changed_notification():
    h = (await tk()).mcp_handler(list_tools=lambda ctx: [ttool("a")])
    got: asyncio.Queue[str] = asyncio.Queue()

    async def on_message(m: Any) -> None:
        root = getattr(m, "root", None)
        if root is not None and getattr(root, "method", None):
            got.put_nowait(root.method)

    with running(h) as url:
        async with dial(url, on_message=on_message):
            deadline = time.time() + 5
            while True:
                h.notify_tools_changed()
                try:
                    m = await asyncio.wait_for(got.get(), 0.05)
                    assert m == "notifications/tools/list_changed"
                    break
                except asyncio.TimeoutError:
                    assert time.time() < deadline, "no list_changed received"


async def test_server_info_resources_prompts():
    h = (await tk()).mcp_handler(
        authenticate=bearer_auth,
        server_info=lambda ctx: McpServerInfo(
            name="srv-" + request_from(ctx).host[:9] + "-" + identity_from(ctx).name, version="9.9.9", instructions="be careful"
        ),
        list_resources=lambda ctx: [McpResource(uri="docs://api", name="api", mime_type="text/markdown")],
        read_resource=lambda ctx, uri: [McpResourceContent(uri=uri, mime_type="text/markdown", text="# API for " + identity_from(ctx).name)],
        list_prompts=lambda ctx: [McpPrompt(name="greet", arguments=[McpPromptArgument(name="who", required=True)])],
        get_prompt=lambda ctx, name, args: McpPromptResult(messages=[McpPromptMessage(role="user", text="hello " + args["who"])]),
    )
    with running(h) as url:
        async with streamablehttp_client(url, headers={"Authorization": "Bearer alice"}) as (r, w, _):
            async with ClientSession(r, w) as c:
                init = await c.initialize()
                assert init.serverInfo.name == "srv-127.0.0.1-alice" and init.serverInfo.version == "9.9.9"
                assert init.instructions == "be careful" and init.protocolVersion == "2025-06-18"
                assert init.capabilities.resources is not None and init.capabilities.prompts is not None
                rl = await c.list_resources()
                assert len(rl.resources) == 1 and str(rl.resources[0].uri) == "docs://api"
                rd = await c.read_resource("docs://api")
                assert rd.contents[0].text == "# API for alice"
                pr = await c.get_prompt("greet", {"who": "world"})
                assert pr.messages[0].content.text == "hello world"
                pl = await c.list_prompts()
                assert pl.prompts[0].arguments[0].name == "who" and pl.prompts[0].arguments[0].required


async def test_protocol_conformance():
    h = (await tk()).mcp_handler(list_tools=lambda ctx: [])
    with running(h) as url:
        resp = await raw_post(
            url, "", '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}'
        )
        sid = resp.headers.get("mcp-session-id", "")
        assert resp.status_code == 200 and sid
        assert '"protocolVersion":"2025-06-18"' in resp.text and '"listChanged":true' in resp.text
        resp = await raw_post(url, "", '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}')
        assert '"protocolVersion":"2025-06-18"' in resp.text
        v = {"MCP-Protocol-Version": "2025-06-18"}
        assert (await raw_post(url, sid, '{"jsonrpc":"2.0","method":"notifications/initialized"}', v)).status_code == 202
        assert (await raw_post(url, "", '{"jsonrpc":"2.0","id":2,"method":"ping"}', v)).status_code == 400
        assert (await raw_post(url, "nope", '{"jsonrpc":"2.0","id":2,"method":"ping"}', v)).status_code == 404
        bad = {"MCP-Protocol-Version": "2099-01-01"}
        assert (await raw_post(url, sid, '{"jsonrpc":"2.0","id":2,"method":"ping"}', bad)).status_code == 400
        assert "-32600" in (await raw_post(url, sid, '[{"jsonrpc":"2.0","id":3,"method":"ping"}]', v)).text
        assert '"result":{}' in (await raw_post(url, sid, '{"jsonrpc":"2.0","id":4,"method":"ping"}', v)).text
        assert "-32601" in (await raw_post(url, sid, '{"jsonrpc":"2.0","id":5,"method":"resources/list"}', v)).text
        async with httpx.AsyncClient() as c:
            assert (await c.post(url, content="{}", headers={"Content-Type": "text/plain"})).status_code == 415
            assert (await c.get(url)).status_code == 405
            assert (await c.delete(url, headers={"Mcp-Session-Id": sid})).status_code == 200
        assert (await raw_post(url, sid, '{"jsonrpc":"2.0","id":6,"method":"ping"}', v)).status_code == 404


async def test_max_body_bytes():
    h = (await tk()).mcp_handler(stateless=True, max_body_bytes=64)
    with running(h) as url:
        resp = await raw_post(url, "", json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping", "params": {"pad": "x" * 100}}))
    assert resp.status_code == 413


class HostIds:
    def __init__(self) -> None:
        self.gen = self.val = self.term = 0

    def generate(self, ctx):
        self.gen += 1
        return "host-session-1"

    async def validate(self, ctx, id):
        self.val += 1
        return id == "host-session-1"

    def terminate(self, ctx, id):
        self.term += 1


async def test_custom_session_ids_and_stateless():
    ids = HostIds()
    h = (await tk()).mcp_handler(session_ids=ids, list_tools=lambda ctx: [ttool("a")])
    with running(h) as url:
        async with dial(url) as c:
            assert text(await c.call_tool("a", {})) == "a:"
    assert ids.gen == 1 and ids.val > 0

    st = (await tk()).mcp_handler(stateless=True, list_tools=lambda ctx: [ttool("a")])
    with running(st) as url:
        resp = await raw_post(url, "", '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}')
        assert "mcp-session-id" not in resp.headers
        assert "a:" in (await raw_post(url, "", '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"a"}}')).text


async def test_defaults_to_toolkit_tools():
    t = await tk()
    t.register(ttool("registered"))
    with running(t.mcp_handler()) as url:
        async with dial(url) as c:
            assert "registered" in await names(c)


# ── PR #140 review follow-ups ─────────────────────────────────────────────


async def test_structured_content_and_output_schema():
    async def stats(args, ctx=None):
        return ToolResult(output="", is_error=False, structured={"count": 3})

    st = Tool(
        name="stats",
        description="",
        input_schema={"type": "object"},
        source="native",
        execute=stats,
        output_schema={"type": "object", "properties": {"count": {"type": "integer"}}},
    )
    plain = ttool("plain")
    h = (await tk()).mcp_handler(list_tools=lambda ctx: [st, plain])
    with running(h) as url:
        async with dial(url) as c:
            lt = await c.list_tools()
            assert lt.tools[0].outputSchema["type"] == "object"
            r = await c.call_tool("stats", {})
            assert r.structuredContent == {"count": 3} and text(r) == '{"count":3}'
    raw = json.dumps(mcp_wire_result(ToolResult(output="x", is_error=False)))
    assert "structuredContent" not in raw and "_meta" not in raw
    assert "outputSchema" not in mcp_wire_tool(plain)


async def test_meta_passthrough():
    seen: dict[str, Any] = {}

    async def m(args, ctx=None):
        seen["tool"] = mcp_request_meta_from()
        return ToolResult(output="ok", is_error=False, meta={"usage": {"units": 2}})

    def before_call(ctx, tool, args):
        seen["hook"] = mcp_request_meta_from(ctx)
        return McpDecision()

    h = (await tk()).mcp_handler(
        stateless=True,
        list_tools=lambda ctx: [Tool(name="m", description="", input_schema={"type": "object"}, source="native", execute=m)],
        before_call=before_call,
    )
    with running(h) as url:
        b = (await raw_post(url, "", '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"m","_meta":{"clientCaps":{"elicitation":{}}}}}')).text
    assert '"_meta":{"usage":{"units":2}}' in b
    assert "clientCaps" in seen["tool"] and "clientCaps" in seen["hook"]


async def test_protocol_versions_per_handler():
    def init_body(v: str) -> str:
        return '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"' + v + '"}}'

    default = (await tk()).mcp_handler(stateless=True)
    newer = (await tk()).mcp_handler(
        McpHandlerConfig(
            stateless=True,
            protocol_versions=["2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26"],
            list_tools=lambda ctx: [ttool("a")],
        )
    )
    assert newer.versions == ["2025-11-25", "2025-06-18"]
    with running(default) as d, running(newer) as n:
        assert '"protocolVersion":"2025-06-18"' in (await raw_post(d, "", init_body("2025-11-25"))).text
        assert (await raw_post(d, "", '{"jsonrpc":"2.0","id":2,"method":"ping"}', {"MCP-Protocol-Version": "2025-11-25"})).status_code == 400
        assert '"protocolVersion":"2025-11-25"' in (await raw_post(n, "", init_body("2025-11-25"))).text
        assert '"protocolVersion":"2025-11-25"' in (await raw_post(n, "", init_body("2026-07-28"))).text
        assert (await raw_post(n, "", '{"jsonrpc":"2.0","id":2,"method":"ping"}', {"MCP-Protocol-Version": "2025-03-26"})).status_code == 400
        call = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"a","arguments":[1]}}'
        b = (await raw_post(n, "", call, {"MCP-Protocol-Version": "2025-11-25"})).text
        assert '"isError":true' in b and "-32602" not in b
        assert "-32602" in (await raw_post(n, "", call, {"MCP-Protocol-Version": "2025-06-18"})).text


async def test_stateless_session_id_and_title():
    h = (await tk()).mcp_handler(stateless=True, stateless_session_id="stateless", title="Records API")
    with running(h) as url:
        r = await raw_post(url, "", '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}')
        assert r.headers["mcp-session-id"] == "stateless" and '"title":"Records API"' in r.text
        assert '"result":{}' in (await raw_post(url, "whatever", '{"jsonrpc":"2.0","id":2,"method":"ping"}')).text
    with running((await tk()).mcp_handler(stateless=True)) as url:
        assert '"title"' not in (await raw_post(url, "", '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}')).text


def test_serve_structured_result_via_mcp_server():
    # The §7C serve path maps structured/meta/output_schema too, and stays
    # byte-identical when they are absent.
    async def noop(args, ctx=None):
        return ToolResult(output="", is_error=False)

    base = dict(name="x", description="", input_schema={"type": "object"}, source="native", execute=noop)
    dump = lambda m: json.dumps(m.model_dump(by_alias=True, exclude_none=True, mode="json"), separators=(",", ":"))  # noqa: E731
    assert "outputSchema" not in dump(_mcp_tool_def(Tool(**base)))
    assert '"outputSchema":{"type":"object"}' in dump(_mcp_tool_def(Tool(**base, output_schema={"type": "object"})))
    b = dump(tool_result_to_call_result(ToolResult(output="hi", is_error=False, structured={"a": 1}, meta={"k": "v"})))
    assert '"structuredContent":{"a":1}' in b and '"_meta":{"k":"v"}' in b and '"text":"hi"' in b
    # absent ⇒ byte-identical to the pre-§7C.1 result
    assert dump(tool_result_to_call_result(ToolResult(output="hi", is_error=False))) == (
        '{"content":[{"type":"text","text":"hi"}],"isError":false}'
    )
