"""MCP 2026-07-28 interop SERVER runner (interop/mcp2026/README.md) for the Python port.
Mirrors golang/internal/interop/mcp2026server. Test tooling only — not shipped.

    python server.py [--keycloak] <skillsDir>
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
from typing import Any

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tests"))

import uvicorn  # noqa: E402

from _mcp_dev_auth import start_mcp_dev_auth_server  # noqa: E402
from toolnexus import (  # noqa: E402
    MCP_PROTOCOL_2026,
    McpDecision,
    McpHandlerConfig,
    McpPrincipal,
    McpServerAuth,
    Tool,
    ToolResult,
    create_toolkit,
    identity_from,
    load_mcp_skills,
    pending,
)

KEYCLOAK_ISSUER = "http://localhost:8180/realms/toolnexus"
KEYCLOAK_PORT = 7199
OBJ = {"type": "object"}


def tools() -> list[Tool]:
    async def echo(a: Any = None, ctx: Any = None) -> ToolResult:
        s = (a or {}).get("text", "")
        return ToolResult(output=s, is_error=False, structured={"text": s})

    async def greet(a: Any = None, ctx: Any = None) -> ToolResult:
        ans = getattr(ctx, "answer", None)
        if ans is not None and ans.ok:
            return ToolResult(output="Hello, " + str((ans.data or {}).get("name", "")) + "!", is_error=False)
        return pending(id="name", kind="input", prompt="What is your name?", data={"schema": {
            "type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}})

    async def wipe(a: Any = None, ctx: Any = None) -> ToolResult:
        return ToolResult(output="wiped", is_error=False)

    async def whoami(a: Any = None, ctx: Any = None) -> ToolResult:
        p = identity_from()
        return ToolResult(output=p.subject if isinstance(p, McpPrincipal) else "", is_error=False)

    return [
        Tool(name="echo", description="Echo the given text back.", source="native", execute=echo,
             input_schema={"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}),
        Tool(name="greet", description="Greet the user; asks for their name.", source="native", input_schema=OBJ, execute=greet),
        Tool(name="wipe", description="Destructive; asks for confirmation first.", source="native", input_schema=OBJ, execute=wipe),
        Tool(name="whoami", description="Who is calling.", source="native", input_schema=OBJ, execute=whoami),
    ]


async def build_app(skills_dir: str, issuer: str, keycloak: bool) -> Any:
    ts = tools()
    tk = await create_toolkit(builtins=False)
    seen: list[str] = []
    calls: dict[str, int] = {}
    lock = threading.Lock()

    def before_call(ctx: Any, tool: Tool, args: Any) -> McpDecision:
        if tool.name == "wipe":
            return McpDecision(kind="confirm", message="Really wipe?")
        return McpDecision()

    def on_call(ev: Any) -> None:
        with lock:
            calls[ev.name] = calls.get(ev.name, 0) + 1

    def cfg(**extra: Any) -> McpHandlerConfig:
        return McpHandlerConfig(name="interop", version="1.0.0", protocol_versions=[MCP_PROTOCOL_2026],
                                list_tools=lambda ctx: ts, list_skills=lambda ctx: load_mcp_skills(skills_dir),
                                before_call=before_call, on_call=on_call, **extra)

    if keycloak:
        auth = McpServerAuth(issuer, audience=f"http://127.0.0.1:{KEYCLOAK_PORT}/mcp",
                             scopes={"*": ["mcp:tools"], "whoami": ["mcp:greet"]})
    else:
        auth = McpServerAuth(issuer, scopes={"*": ["mcp"], "whoami": ["mcp:whoami"]})
    open_h = tk.mcp_handler(cfg())
    auth_h = tk.mcp_handler(cfg(authenticate=auth.authenticate))
    legacy_h = tk.mcp_handler(McpHandlerConfig(stateless=True, list_tools=lambda ctx: ts))
    meta = auth.metadata()

    def hdr(scope: dict[str, Any], name: bytes) -> str:
        for k, v in scope.get("headers") or []:
            if k.lower() == name:
                return v.decode("latin-1")
        return ""

    def logged(mount: str, h: Any) -> Any:
        async def app(scope: Any, receive: Any, send: Any) -> None:
            with lock:
                seen.append(f"{mount} {hdr(scope, b'mcp-protocol-version')} {hdr(scope, b'mcp-method')}")
            await h(scope, receive, send)
        return app

    routes = {"/open/mcp": logged("open", open_h), "/mcp": logged("auth", auth_h), "/legacy/mcp": logged("legacy", legacy_h),
              "/.well-known/oauth-protected-resource/mcp": meta, "/.well-known/oauth-protected-resource": meta}

    async def app(scope: Any, receive: Any, send: Any) -> None:
        if scope["type"] != "http":
            return
        path = scope["path"]
        if path == "/log":
            with lock:
                body = json.dumps({"seen": list(seen), "calls": dict(calls)}).encode()
            await send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"application/json")]})
            await send({"type": "http.response.body", "body": body})
            return
        h = routes.get(path)
        if h is None:
            await send({"type": "http.response.start", "status": 404, "headers": []})
            await send({"type": "http.response.body", "body": b"not found"})
            return
        await h(scope, receive, send)

    return app


def main() -> None:
    args = sys.argv[1:]
    keycloak = bool(args) and args[0] == "--keycloak"
    if keycloak:
        args = args[1:]
    skills_dir = args[0] if args else "../examples/skills"
    issuer = KEYCLOAK_ISSUER
    as_ = None
    if not keycloak:
        as_ = start_mcp_dev_auth_server(clients={"interop-client": ["http://127.0.0.1/callback"]})
        issuer = as_.issuer

    import asyncio
    app = asyncio.run(build_app(skills_dir, issuer, keycloak))
    config = uvicorn.Config(app, host="127.0.0.1", port=KEYCLOAK_PORT if keycloak else 0, log_level="error", lifespan="off")
    server = uvicorn.Server(config)
    threading.Thread(target=server.run, daemon=True).start()
    deadline = time.time() + 15
    while not server.started:
        if time.time() > deadline:
            sys.exit("uvicorn did not start")
        time.sleep(0.01)
    port = server.servers[0].sockets[0].getsockname()[1]
    print(json.dumps({"base": f"http://127.0.0.1:{port}", "issuer": issuer}), flush=True)
    sys.stdin.read()  # serve until stdin closes
    if as_ is not None:
        as_.close()
    os._exit(0)


if __name__ == "__main__":
    main()
