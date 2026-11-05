"""SPEC §7C.2 port-own tests (mirror golang/mcphandler2026_test.go): the default RS256/JWKS
verifier against the DEV-ONLY authorization server, the dev AS code + PKCE flow with a
pre-registered and a CIMD client, onCall/prompts/discover on the 2026 wire, loadMcpSkills."""
from __future__ import annotations

import base64
import hashlib
import json
import time
import urllib.parse
from pathlib import Path
from typing import Any, Optional

import httpx
import pytest

from _mcp_handler_server import running
from toolnexus import (
    MCP_PROTOCOL_2026,
    McpCallEvent,
    McpHandlerConfig,
    McpPrincipal,
    McpPrompt,
    McpPromptMessage,
    McpPromptResult,
    McpServerAuth,
    Tool,
    ToolResult,
    create_toolkit,
    identity_from,
    load_mcp_skills,
    start_mcp_dev_auth_server,
)

SKILLS = Path(__file__).resolve().parents[2] / "examples" / "skills"


async def post26(u: str, token: str, method: str, params: Optional[dict[str, Any]] = None) -> httpx.Response:
    params = dict(params or {})
    meta = dict(params.get("_meta") or {})
    meta["io.modelcontextprotocol/protocolVersion"] = MCP_PROTOCOL_2026
    params["_meta"] = meta
    h = {"Content-Type": "application/json", "MCP-Protocol-Version": MCP_PROTOCOL_2026, "Mcp-Method": method}
    if isinstance(params.get("name"), str):
        h["Mcp-Name"] = params["name"]
    if token:
        h["Authorization"] = "Bearer " + token
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
    async with httpx.AsyncClient() as c:
        return await c.post(u, content=body, headers=h)


def who_tool() -> Tool:
    async def execute(args: dict[str, Any], ctx: Any = None) -> ToolResult:
        p = identity_from()
        return ToolResult(output=p.subject if isinstance(p, McpPrincipal) else "", is_error=False)

    return Tool(name="who", description="who", input_schema={"type": "object"}, source="native", execute=execute)


def mount(handler: Any, meta: Any = None) -> Any:
    """A tiny router: /mcp → handler, /.well-known/oauth-protected-resource* → meta."""

    async def app(scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope["type"] == "http" and meta is not None and scope["path"].startswith("/.well-known/oauth-protected-resource"):
            await meta(scope, receive, send)
            return
        await handler(scope, receive, send)

    return app


async def test_default_jwt_verifier_with_dev_as():
    as_ = start_mcp_dev_auth_server()
    try:
        auth = McpServerAuth(as_.issuer, scopes={"*": ["mcp"], "who": ["mcp:who"]})
        h = (await create_toolkit()).mcp_handler(
            McpHandlerConfig(protocol_versions=[MCP_PROTOCOL_2026], list_tools=lambda ctx: [who_tool()], authenticate=auth.authenticate)
        )
        with running(mount(h, auth.metadata())) as base:
            base = base.rstrip("/")
            u = base + "/mcp"
            now = int(time.time())

            def mint(**over: Any) -> str:
                c = {"iss": as_.issuer, "sub": "dev-user", "aud": u, "scope": "mcp mcp:who", "exp": now + 60}
                c.update(over)
                return as_.mint(c)

            r = await post26(u, mint(), "tools/call", {"name": "who", "arguments": {}})
            assert r.status_code == 200, r.text
            assert r.json()["result"]["content"][0]["text"] == "dev-user"

            good = mint()
            for name, tok in {
                "wrong audience": mint(aud="https://other.example/mcp"),
                "aud array miss": mint(aud=["x"]),
                "expired": mint(exp=now - 1),
                "wrong issuer": mint(iss="https://evil.example"),
                "tampered": good[:20] + ("y" if good[20] == "x" else "x") + good[21:],
                "not a jwt": "opaque",
            }.items():
                r = await post26(u, tok, "server/discover")
                www = r.headers.get("WWW-Authenticate", "")
                assert r.status_code == 401, name
                assert 'error="invalid_token"' in www, name
                assert tok not in www, name
                assert r.json() == {"error": "unauthorized"}

            r = await post26(u, mint(scope="mcp"), "tools/call", {"name": "who", "arguments": {}})
            assert r.status_code == 403
            assert r.headers["WWW-Authenticate"] == (
                f'Bearer resource_metadata="{base}/.well-known/oauth-protected-resource/mcp", '
                'scope="mcp mcp:who", error="insufficient_scope"'
            )
            # no token at all ⇒ 401 with the base scope, no error code
            r = await post26(u, "", "server/discover")
            assert r.status_code == 401
            assert r.headers["WWW-Authenticate"] == (
                f'Bearer resource_metadata="{base}/.well-known/oauth-protected-resource/mcp", scope="mcp"'
            )

            async with httpx.AsyncClient() as c:
                prm = (await c.get(base + "/.well-known/oauth-protected-resource/mcp")).json()
                assert prm == {
                    "resource": u,
                    "authorization_servers": [as_.issuer],
                    "scopes_supported": ["mcp"],
                    "bearer_methods_supported": ["header"],
                }
                assert (await c.get(base + "/.well-known/oauth-protected-resource/other")).status_code == 404
    finally:
        as_.close()


async def pkce_login(issuer: str, client_id: str, redirect: str, resource: str, scope: str) -> dict[str, Any]:
    verifier = "v-" + "a" * 50
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    q = {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect,
        "state": "s1",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "resource": resource,
        "scope": scope,
    }
    async with httpx.AsyncClient(follow_redirects=False) as c:
        r = await c.get(issuer + "/authorize?" + urllib.parse.urlencode(q))
        assert r.status_code == 302, r.text
        cb = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(r.headers["Location"]).query))
        assert cb["iss"] == issuer and cb["state"] == "s1" and cb["code"]
        tr = await c.post(
            issuer + "/token",
            data={
                "grant_type": "authorization_code",
                "code": cb["code"],
                "code_verifier": verifier,
                "client_id": client_id,
                "redirect_uri": redirect,
                "resource": resource,
            },
        )
        assert tr.status_code == 200, tr.text
        # the code is single-use
        again = await c.post(
            issuer + "/token",
            data={
                "grant_type": "authorization_code",
                "code": cb["code"],
                "code_verifier": verifier,
                "client_id": client_id,
                "redirect_uri": redirect,
                "resource": resource,
            },
        )
        assert again.status_code == 400 and again.json() == {"error": "invalid_grant"}
        return tr.json()


async def test_dev_as_pre_registered_and_cimd():
    doc: dict[str, Any] = {}

    async def cimd(scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope["type"] != "http":
            return
        await send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"application/json")]})
        await send({"type": "http.response.body", "body": json.dumps(doc).encode()})

    as_ = start_mcp_dev_auth_server(clients={"app": ["http://127.0.0.1/cb"]})
    try:
        with running(cimd) as cimd_url:
            doc_url = cimd_url + "client.json"
            doc.update({"client_id": doc_url, "client_name": "CIMD app", "redirect_uris": ["http://127.0.0.1/cb"]})
            auth = McpServerAuth(as_.issuer, scopes={"*": ["mcp"]})
            h = (await create_toolkit()).mcp_handler(
                McpHandlerConfig(
                    protocol_versions=[MCP_PROTOCOL_2026], list_tools=lambda ctx: [who_tool()], authenticate=auth.authenticate
                )
            )
            with running(mount(h)) as base:
                u = base.rstrip("/") + "/mcp"
                for cid in ("app", doc_url):
                    tok = await pkce_login(as_.issuer, cid, "http://127.0.0.1:5555/cb", u, "mcp")
                    r = await post26(u, tok["access_token"], "tools/call", {"name": "who", "arguments": {}})
                    assert r.status_code == 200 and '"text":"dev-user"' in r.text.replace(" ", ""), (cid, r.text)
                    async with httpx.AsyncClient() as c:
                        form = {"grant_type": "refresh_token", "refresh_token": tok["refresh_token"], "client_id": cid, "resource": u}
                        rr = await c.post(as_.issuer + "/token", data=form)
                        assert rr.status_code == 200, rr.text
                        # rotation: the old refresh token is spent
                        assert (await c.post(as_.issuer + "/token", data=form)).status_code == 400
                        r = await post26(u, rr.json()["access_token"], "tools/call", {"name": "who", "arguments": {}})
                        assert r.status_code == 200
        async with httpx.AsyncClient(follow_redirects=False) as c:
            # unknown client / unregistered redirect never redirects
            r = await c.get(as_.issuer + "/authorize?client_id=nobody&redirect_uri=http://127.0.0.1/cb")
            assert r.status_code == 400
            md = (await c.get(as_.issuer + "/.well-known/oauth-authorization-server")).json()
            assert md["client_id_metadata_document_supported"] is True
            assert md["code_challenge_methods_supported"] == ["S256"]
            assert "registration_endpoint" not in md
        assert as_.authorizations() == 3
    finally:
        as_.close()


async def test_dev_as_missing_resource_and_secrets():
    as_ = start_mcp_dev_auth_server(clients={"conf": ["http://127.0.0.1/cb"]}, secrets={"conf": "s3cret"})
    try:
        async with httpx.AsyncClient(follow_redirects=False) as c:
            q = {"response_type": "code", "client_id": "conf", "redirect_uri": "http://127.0.0.1/cb", "state": "x",
                 "code_challenge": "abc", "code_challenge_method": "S256"}
            r = await c.get(as_.issuer + "/authorize?" + urllib.parse.urlencode(q))
            assert r.status_code == 302 and "error=invalid_target" in r.headers["Location"]
            r = await c.post(as_.issuer + "/token", data={"grant_type": "authorization_code", "client_id": "conf", "code": "x"})
            assert r.status_code == 401 and r.json() == {"error": "invalid_client"}
    finally:
        as_.close()


async def test_on_call_prompts_and_discover_on_2026():
    events: list[McpCallEvent] = []

    def on_call(ev: McpCallEvent) -> None:
        events.append(ev)
        raise RuntimeError("isolated")

    h = (await create_toolkit()).mcp_handler(
        McpHandlerConfig(
            protocol_versions=[MCP_PROTOCOL_2026],
            list_tools=lambda ctx: [who_tool()],
            on_call=on_call,
            list_prompts=lambda ctx: [McpPrompt(name="p", description="d")],
            get_prompt=lambda ctx, name, args: McpPromptResult(messages=[McpPromptMessage(role="user", text="hi " + name)]),
        )
    )
    with running(h) as u:
        r = await post26(u, "", "tools/call", {"name": "who", "arguments": {}})
        assert r.status_code == 200 and len(events) == 1 and events[0].name == "who"
        caps = (await post26(u, "", "server/discover")).json()["result"]["capabilities"]
        assert "prompts" in caps and "extensions" not in caps and "resources" not in caps
        g = (await post26(u, "", "prompts/get", {"name": "p"})).json()
        assert g["result"]["messages"][0]["content"]["text"] == "hi p"
        assert g["result"]["resultType"] == "complete"
        async with httpx.AsyncClient() as c:
            r = await c.get(u, headers={"Accept": "text/event-stream"})
            assert r.status_code == 405 and r.headers["Allow"] == "POST"


def test_load_mcp_skills():
    skills = load_mcp_skills(str(SKILLS))
    assert len(skills) == 1 and skills[0].name == "hello-world"
    assert [f.path for f in skills[0].files] == ["SKILL.md", "scripts/greet.sh"]
    assert skills[0].frontmatter is not None and skills[0].frontmatter["name"] == "hello-world"


def test_load_mcp_skills_skips_binary_and_nameless(tmp_path: Path):
    a = tmp_path / "a"
    a.mkdir()
    (a / "SKILL.md").write_text("---\nname: a\ndescription: d\n---\nbody\n")
    (a / "blob.bin").write_bytes(b"\xff\xfe\x00")
    b = tmp_path / "b"
    b.mkdir()
    (b / "SKILL.md").write_text("no frontmatter\n")
    skills = load_mcp_skills(str(tmp_path))
    assert [s.name for s in skills] == ["a"]
    assert [f.path for f in skills[0].files] == ["SKILL.md"]
    assert skills[0].frontmatter == {"name": "a", "description": "d"}


@pytest.mark.parametrize("tamper_kid", [False, True])
async def test_unknown_kid_is_invalid_token(tamper_kid: bool):
    as_ = start_mcp_dev_auth_server()
    try:
        auth = McpServerAuth(as_.issuer, audience="http://res.example/mcp")
        tok = as_.mint({"iss": as_.issuer, "aud": "http://res.example/mcp", "sub": "u", "exp": int(time.time()) + 60})
        if tamper_kid:
            hdr = base64.urlsafe_b64encode(b'{"alg":"RS256","kid":"other"}').rstrip(b"=").decode()
            tok = hdr + tok[tok.index(".") :]
        h = (await create_toolkit()).mcp_handler(
            McpHandlerConfig(protocol_versions=[MCP_PROTOCOL_2026], authenticate=auth.authenticate)
        )
        with running(h) as u:
            r = await post26(u, tok, "ping")
            assert r.status_code == (401 if tamper_kid else 200), r.text
    finally:
        as_.close()
