"""Embeddable MCP server (SPEC §7C.1): mount toolkit.mcp_handler on your own ASGI app,
authenticate with a bearer token, give each caller its own tool list, and gate a
destructive tool behind a confirmation (elicitation when the client supports it, a
host-shaped "re-call with a nonce" result otherwise).

    python examples/mcp_handler.py

Self-contained: it starts uvicorn on an ephemeral port and drives it with three MCP
clients (alice with elicitation, alice without, bob). Mirrors golang/examples/mcphandler.
"""
from __future__ import annotations

import asyncio
import threading
import time
from dataclasses import dataclass
from typing import Any

import httpx
import uvicorn
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
from mcp.shared.exceptions import McpError
from mcp.types import ElicitResult

from toolnexus import (
    McpDecision,
    Tool,
    ToolAnnotations,
    ToolResult,
    create_toolkit,
    identity_from,
)


@dataclass
class User:
    name: str
    role: str


# Demo tokens only. A real host verifies a JWT/OAuth token here.
TOKENS = {"token-alice": User("alice", "admin"), "token-bob": User("bob", "viewer")}


def authenticate(req: Any) -> User:
    user = TOKENS.get(req.header("Authorization").removeprefix("Bearer "))
    if user is None:
        raise PermissionError("invalid token")
    return user


async def list_records(args: dict, ctx: Any = None) -> ToolResult:
    return ToolResult(output=f"records for {identity_from().name}: [1, 2]", is_error=False)


async def delete_record(args: dict, ctx: Any = None) -> ToolResult:
    return ToolResult(output=f"deleted {args.get('id')}", is_error=False)


def tools(ctx: Any) -> list[Tool]:
    out = [
        Tool(
            name="list_records",
            description="List records",
            input_schema={"type": "object"},
            source="native",
            execute=list_records,
            annotations=ToolAnnotations(read_only_hint=True),
        )
    ]
    if identity_from(ctx).role == "admin":
        out.append(
            Tool(
                name="delete_record",
                description="Delete a record",
                input_schema={"type": "object", "properties": {"id": {"type": "string"}, "nonce": {"type": "string"}}},
                source="native",
                execute=delete_record,
                annotations=ToolAnnotations(title="Delete record", destructive_hint=True),
            )
        )
    return out


def before_call(ctx: Any, tool: Tool, args: dict) -> McpDecision:
    if tool.annotations is None or not tool.annotations.destructive_hint:
        return McpDecision(kind="allow")
    if args.get("nonce") == "n-123":  # the host's own nonce check
        return McpDecision(kind="allow")
    return McpDecision(
        kind="confirm",
        message="Really delete this record?",
        result=ToolResult(output='Confirmation needed: call delete_record again with "nonce":"n-123".', is_error=False),
    )


def on_call(ev: Any) -> None:
    print(f"  [on_call] {ev.name} by {ev.identity.name} decision={ev.decision} is_error={ev.is_error} {ev.result_bytes}B")


async def accept(context: Any, params: Any) -> ElicitResult:
    print(f"  [client asked] {params.message} -> accept")
    return ElicitResult(action="accept", content={"confirm": True})


async def session_for(url: str, token: str, elicit: Any = None):
    """Open an initialized client session (returns the context managers to close)."""
    http = streamablehttp_client(url, headers={"Authorization": "Bearer " + token})
    r, w, _ = await http.__aenter__()
    kw = {"elicitation_callback": elicit} if elicit else {}
    s = ClientSession(r, w, **kw)
    await s.__aenter__()
    await s.initialize()
    return s, http


async def call_text(s: ClientSession, name: str, args: dict) -> str:
    try:
        return (await s.call_tool(name, args)).content[0].text
    except McpError as e:
        return "error: " + str(e)


async def main() -> None:
    tk = await create_toolkit()
    mcp_app = tk.mcp_handler(
        name="records",
        version="1.0.0",
        authenticate=authenticate,
        www_authenticate=lambda req, err: 'Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource"',
        list_tools=tools,
        before_call=before_call,
        on_call=on_call,
    )

    async def app(scope, receive, send):  # the host's own router: /mcp -> the handler
        if scope["type"] == "http" and scope["path"] == "/mcp":
            return await mcp_app(scope, receive, send)
        if scope["type"] == "http":
            await send({"type": "http.response.start", "status": 404, "headers": []})
            await send({"type": "http.response.body", "body": b""})

    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=0, log_level="error", lifespan="off"))
    threading.Thread(target=server.run, daemon=True).start()
    while not server.started:
        time.sleep(0.01)
    url = f"http://127.0.0.1:{server.servers[0].sockets[0].getsockname()[1]}/mcp"

    async with httpx.AsyncClient() as c:
        resp = await c.post(url, content='{"jsonrpc":"2.0","id":1,"method":"ping"}', headers={"Content-Type": "application/json"})
        print(f"no token -> {resp.status_code}, WWW-Authenticate: {resp.headers.get('www-authenticate')}")

    opened = []
    print("alice (admin, client supports elicitation):")
    alice, h = await session_for(url, "token-alice", accept)
    opened.append((alice, h))
    print("  tools:", [t.name for t in (await alice.list_tools()).tools])
    print("  delete_record ->", await call_text(alice, "delete_record", {"id": "7"}))

    print("alice (admin, client WITHOUT elicitation):")
    alice2, h = await session_for(url, "token-alice")
    opened.append((alice2, h))
    print("  delete_record ->", await call_text(alice2, "delete_record", {"id": "7"}))
    print("  delete_record+nonce ->", await call_text(alice2, "delete_record", {"id": "7", "nonce": "n-123"}))

    print("bob (viewer):")
    bob, h = await session_for(url, "token-bob")
    opened.append((bob, h))
    print("  tools:", [t.name for t in (await bob.list_tools()).tools])
    print("  delete_record ->", await call_text(bob, "delete_record", {"id": "7"}))

    for s, h in reversed(opened):
        await s.__aexit__(None, None, None)
        await h.__aexit__(None, None, None)
    server.should_exit = True


if __name__ == "__main__":
    asyncio.run(main())
