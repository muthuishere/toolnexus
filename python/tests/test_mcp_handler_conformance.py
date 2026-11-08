"""SPEC §7C.1 cross-port conformance: replays ../conformance/mcp_handler/cases.json over
real HTTP (harness contract in conformance/mcp_handler/README.md; Go reference
golang/mcphandler_conformance_test.go)."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Optional

import httpx
import pytest

from _mcp_handler_server import running
from toolnexus import (
    McpDecision,
    McpHandlerConfig,
    McpPrincipal,
    McpServerAuth,
    Tool,
    ToolAnnotations,
    ToolHints,
    ToolResult,
    create_toolkit,
    identity_from,
    load_mcp_skills,
    mcp_request_meta_from,
    pending,
)

CASES = Path(__file__).resolve().parents[2] / "conformance" / "mcp_handler" / "cases.json"
FILE = json.loads(CASES.read_text())
FIXTURE = FILE["fixture"]
BY_NAME = {t["name"]: t for t in FIXTURE["tools"]}
SKILLS_DIR = Path(__file__).resolve().parents[2] / "examples" / "skills"
V2026 = "2026-07-28"


def _identity_name(v: Any) -> str:
    """README identity-name rule: a string verbatim, a Principal's subject, else ""."""
    if isinstance(v, str):
        return v
    if isinstance(v, McpPrincipal):
        return v.subject
    return ""


def _stamp2026(body: Any) -> Any:
    m = dict(body)
    p = dict(m.get("params") or {})
    meta = dict(p.get("_meta") or {})
    meta.setdefault("io.modelcontextprotocol/protocolVersion", V2026)
    p["_meta"] = meta
    m["params"] = p
    return m


def _headers2026(body: Any) -> dict[str, str]:
    method = body.get("method", "")
    p = body.get("params") or {}
    h = {"MCP-Protocol-Version": V2026, "Mcp-Method": method}
    if method in ("tools/call", "prompts/get"):
        h["Mcp-Name"] = p.get("name", "")
    elif method == "resources/read":
        h["Mcp-Name"] = p.get("uri", "")
    return h


async def _not_found(scope: Any, receive: Any, send: Any) -> None:
    if scope["type"] != "http":
        return
    await send({"type": "http.response.start", "status": 404, "headers": []})
    await send({"type": "http.response.body", "body": b""})


def _result(r: dict[str, Any]) -> ToolResult:
    return ToolResult(
        output=r.get("output", ""), is_error=r.get("isError", False), structured=r.get("structured"), meta=r.get("meta")
    )


def _build_tools(executed: list[str]) -> list[Tool]:
    tools = []
    for ft in FIXTURE["tools"]:

        async def execute(args: dict[str, Any], ctx: Any = None, ft: dict[str, Any] = ft) -> ToolResult:
            executed.append(ft["name"])
            b = ft["behavior"]
            if b == "echo":
                return ToolResult(output=str(args.get("text", "")), is_error=False)
            if b == "whoami":
                meta = mcp_request_meta_from() or {}
                return ToolResult(output=f"{_identity_name(identity_from())}|{meta.get('trace', '')}", is_error=False)
            if b == "ask":
                a = getattr(ctx, "answer", None)
                if a is not None and a.ok:
                    return ToolResult(output="Hello, " + str((a.data or {}).get("name", "")) + "!", is_error=False)
                return pending(
                    id="name",
                    kind="input",
                    prompt="What is your name?",
                    data={
                        "schema": {
                            "type": "object",
                            "properties": {"name": {"type": "string"}},
                            "required": ["name"],
                        }
                    },
                )
            if b == "error":
                raise RuntimeError(ft["error"])
            return _result(ft["result"])

        ann = None
        if "annotations" in ft:
            a = ft["annotations"]
            ann = ToolAnnotations(
                title=a.get("title"),
                read_only_hint=a.get("readOnlyHint"),
                destructive_hint=a.get("destructiveHint"),
                idempotent_hint=a.get("idempotentHint"),
                open_world_hint=a.get("openWorldHint"),
            )
        hints = None
        if "hints" in ft:
            h = ft["hints"]
            hints = ToolHints(read_only=h["readOnly"], destructive=h["destructive"], open_world=h["openWorld"])
        tools.append(
            Tool(
                name=ft["name"],
                description=ft["description"],
                input_schema=ft["inputSchema"],
                source="native",
                execute=execute,
                hints=hints,
                output_schema=ft.get("outputSchema"),
                annotations=ann,
            )
        )
    return tools


def _path(v: Any, path: str) -> Any:
    for seg in path.split("."):
        if isinstance(v, dict):
            v = v.get(seg)
        elif isinstance(v, list):
            try:
                v = v[int(seg)]
            except (ValueError, IndexError):
                return None
        else:
            return None
    return v


@pytest.mark.parametrize("sc", FILE["scenarios"], ids=[s["name"] for s in FILE["scenarios"]])
async def test_mcp_handler_conformance(sc: dict[str, Any]) -> None:
    executed: list[str] = []
    on_calls: list[dict[str, Any]] = []
    tools = _build_tools(executed)
    conf = sc.get("config", {})

    def list_tools(ctx: Any) -> list[Tool]:
        ident = _identity_name(identity_from(ctx))
        return [t for t in tools if BY_NAME[t.name].get("visibleTo") is None or ident in BY_NAME[t.name]["visibleTo"]]

    def before_call(ctx: Any, tool: Tool, args: Any) -> McpDecision:
        d = BY_NAME[tool.name].get("decision")
        if d is None:
            return McpDecision()
        return McpDecision(
            kind=d.get("kind", ""), message=d.get("message", ""), result=_result(d["result"]) if "result" in d else None
        )

    def authenticate(req: Any) -> str:
        tok = req.header("Authorization")
        if tok in FIXTURE["auth"]["tokens"]:
            return FIXTURE["auth"]["tokens"][tok]
        raise PermissionError("unauthorized")

    cfg = McpHandlerConfig(
        name=conf.get("name", ""),
        title=conf.get("title", ""),
        version=conf.get("version", ""),
        instructions=conf.get("instructions", ""),
        stateless=conf.get("stateless", False),
        stateless_session_id=conf.get("statelessSessionId", ""),
        protocol_versions=conf.get("protocolVersions"),
        list_tools=list_tools,
        before_call=before_call,
        on_call=lambda ev: on_calls.append({"name": ev.name, "isError": ev.is_error, "decision": ev.decision}),
    )
    if conf.get("auth"):
        cfg.authenticate = authenticate
        cfg.www_authenticate = lambda req, err: FIXTURE["auth"]["wwwAuthenticate"]
    if conf.get("skills"):
        cfg.list_skills = lambda ctx: load_mcp_skills(str(SKILLS_DIR))
    meta_app: Any = _not_found
    if conf.get("serverAuth"):
        fa = FIXTURE["serverAuth"]

        def verify(req: Any, token: str, audience: str) -> McpPrincipal:
            pr = fa["principals"].get(token)
            if pr is None:
                raise PermissionError("bad token")
            return McpPrincipal(subject=pr["subject"], scopes=list(pr["scopes"]))

        auth = McpServerAuth(fa["issuer"], audience=fa.get("audience", ""), scopes=fa.get("scopes"), verify=verify)
        cfg.authenticate = auth.authenticate
        meta_app = auth.metadata()

    handler = (await create_toolkit()).mcp_handler(cfg)
    sid: Optional[str] = ""
    with running(handler) as url, running(meta_app) as meta_url:
        async with httpx.AsyncClient() as client:
            for i, st in enumerate(sc["steps"]):
                rq = st["request"]
                if rq.get("mcp2026") and "body" in rq:
                    rq = {**rq, "body": _stamp2026(rq["body"])}
                if "rawBody" in rq:
                    content: Optional[bytes] = rq["rawBody"].encode()
                elif "body" in rq:
                    content = json.dumps(rq["body"]).encode()
                else:
                    content = None
                headers: dict[str, str] = {}
                if rq["method"] == "POST":
                    headers["Content-Type"] = rq.get("contentType") or "application/json"
                headers["Accept"] = rq.get("accept") or "application/json, text/event-stream"
                tok = rq.get("token") or sc.get("token")
                if tok:
                    headers["Authorization"] = tok
                if rq.get("sessionId"):
                    headers["Mcp-Session-Id"] = rq["sessionId"]
                elif rq.get("session"):
                    headers["Mcp-Session-Id"] = sid or ""
                if rq.get("version"):
                    headers["MCP-Protocol-Version"] = rq["version"]
                if rq.get("mcp2026") and "body" in rq:
                    headers.update(_headers2026(rq["body"]))
                for k, v in (rq.get("headers") or {}).items():
                    for existing in [h for h in headers if h.lower() == k.lower()]:
                        del headers[existing]
                    if v != "":
                        headers[k] = v
                target = meta_url.rstrip("/") + rq["target"] if rq.get("target") else url
                resp = await client.request(rq["method"], target, content=content, headers=headers)
                ex = st["expect"]
                where = f"{sc['name']} step {i}"
                if "status" in ex:
                    assert resp.status_code == ex["status"], f"{where}: {resp.status_code} {resp.text}"
                for k, v in ex.get("headers", {}).items():
                    assert resp.headers.get(k) == v, f"{where}: header {k}"
                for k in ex.get("headersPresent", []):
                    assert resp.headers.get(k), f"{where}: header {k} missing"
                for k in ex.get("headersAbsent", []):
                    assert not resp.headers.get(k), f"{where}: header {k} present"
                if st.get("capture") == "sid":
                    sid = resp.headers.get("Mcp-Session-Id")
                if "json" in ex or "jsonPath" in ex:
                    got = resp.json()
                    if "json" in ex:
                        assert got == ex["json"], f"{where}: body {resp.text}"
                    for p, want in ex.get("jsonPath", {}).items():
                        assert _path(got, p) == want, f"{where}: {p}"
    if "expectExecuted" in sc:
        assert executed == sc["expectExecuted"]
    if "expectOnCall" in sc:
        assert on_calls == sc["expectOnCall"]
