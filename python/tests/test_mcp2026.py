"""MCP 2026-07-28 client path (SPEC §2, §2A): hermetic, localhost only."""
from __future__ import annotations

import asyncio
import json
import os
import sys
from urllib.parse import parse_qs, urlsplit

import pytest

from toolnexus import Answer, create_toolkit, load_mcp, pending_of
from toolnexus.mcp2026.http import HttpClient
from toolnexus.mcp2026.wire import encode_header, parse_skill_uri
from tests.mcp2026_support.devauth import DevAuth
from tests.mcp2026_support.server import FIXTURE, REPO, Running, Server

EXPECTED = json.load(open(os.path.join(REPO, "examples", "mcp-2026", "expected.json")))
STDIO_SERVER = os.path.join(os.path.dirname(__file__), "mcp2026_support", "server.py")


@pytest.fixture
def plain():
    srv = Server()
    run = Running(srv)
    srv.url = run.base + "/mcp"
    yield srv
    run.close()


@pytest.fixture
def prot():
    as_ = DevAuth()
    srv = Server()
    srv.auth = as_
    run = Running(srv)
    srv.url = run.base + "/mcp"
    yield srv, as_
    run.close()
    as_.close()


def cfg(url, **extra):
    return {"mcpServers": {"fx": {"type": "remote", "url": url, "timeout": 5000, **extra}}}


def follow(url):
    """Test browser: GET the auth URL and follow redirects onto the loopback listener."""
    HttpClient(follow_redirects=True).get(url)


def visit(url):
    r = HttpClient().get(url)
    assert r.status == 302
    return r.header("Location")


# ---- discover + tools ------------------------------------------------------

async def test_http_discover_tools_and_echo(plain):
    src = await load_mcp(cfg(plain.url))
    try:
        assert src.status == {"fx": "connected"}
        names = sorted(t.name for t in src.tools)
        assert names == ["fx_" + n for n in EXPECTED["steps"][3]["expect"]["toolNames"]]
        echo = next(t for t in src.tools if t.name == "fx_echo")
        r = await echo.execute({"text": "hi toolnexus"})
        assert not r.is_error and json.loads(r.output) == {"text": "hi toolnexus"}
    finally:
        await src.close()


async def test_tools_filter_applies(plain):
    src = await load_mcp(cfg(plain.url, tools={"echo": True}))
    assert [t.name for t in src.tools] == ["fx_echo"]
    await src.close()


async def test_stdio_discover_and_echo():
    c = {"mcpServers": {"s": {"command": [sys.executable, STDIO_SERVER], "timeout": 5000,
                              "env": {"PYTHONPATH": REPO + os.pathsep + os.path.join(REPO, "python")}}}}
    src = await load_mcp(c)
    try:
        assert src.status == {"s": "connected"}
        echo = next(t for t in src.tools if t.name == "s_echo")
        rs = await asyncio.gather(*(echo.execute({"text": f"m{i}"}) for i in range(5)))
        assert [json.loads(r.output)["text"] for r in rs] == [f"m{i}" for i in range(5)]
    finally:
        await src.close()


async def test_stdio_child_exits_is_failure():
    src = await load_mcp({"mcpServers": {"x": {"command": [sys.executable, "-c", "pass"], "timeout": 3000}}})
    assert src.status == {"x": "failed"}
    await src.close()


async def test_http_5xx_on_discover_falls_back_and_refused_fails():
    def app(environ, start_response):
        start_response("500 X", [("Content-Length", "0")])
        return [b""]
    run = Running(app)
    src = await load_mcp(cfg(run.base + "/mcp"))  # fallback SDK path also fails on this toy, but via fallback
    assert src.status == {"fx": "failed"}
    await src.close()
    run.close()
    src = await load_mcp(cfg("http://127.0.0.1:9/mcp"))
    assert src.status == {"fx": "failed"}
    await src.close()


async def test_fallback_to_old_sdk_server_over_stdio():
    script = ("from mcp.server.fastmcp import FastMCP\nm=FastMCP('old')\n"
              "@m.tool()\ndef echo(text: str) -> str:\n    return text\nm.run()\n")
    src = await load_mcp({"mcpServers": {"old": {"command": [sys.executable, "-c", script], "timeout": 15000}}})
    try:
        assert src.status == {"old": "connected"}
        r = await src.tools[0].execute({"text": "legacy"})
        assert "legacy" in r.output
    finally:
        await src.close()


LEGACY_STDIO = os.path.join(REPO, "examples", "mcp-2026", "legacy-stdio", "server.mjs")


@pytest.mark.parametrize("mode,timeout,spawns", [("error", 5000, 1), ("silent", 1500, 2)])
async def test_stdio_discover_probe_child_reused(tmp_path, mode, timeout, spawns):
    """§2.2a: a discover REPLY (error) continues into initialize on the same child; only
    discover silence kills the probe child and spawns a new one."""
    count = tmp_path / "spawns.txt"
    c = {"mcpServers": {"legacy": {"command": ["node", LEGACY_STDIO], "timeout": timeout,
                                   "env": {"SPAWN_COUNT_FILE": str(count), "DISCOVER_MODE": mode}}}}
    src = await load_mcp(c)
    try:
        assert src.status == {"legacy": "connected"}
        echo = next(t for t in src.tools if t.name == "legacy_echo")
        r = await echo.execute({"text": "hi"})
        assert "echo:hi" in r.output
        assert len(count.read_text().splitlines()) == spawns
    finally:
        await src.close()


# ---- MRTR ----------------------------------------------------------------

async def test_mrtr_inline_wait_for(plain):
    asked = []

    def wait_for(req):
        asked.append(req)
        return Answer(id=req.id, ok=True, data={"name": "toolnexus"})
    src = await load_mcp(cfg(plain.url), wait_for=wait_for)
    greet = next(t for t in src.tools if t.name == "fx_greet")
    r = await greet.execute({})
    assert r.output == "Hello, toolnexus!" and len(asked) == 1
    assert asked[0].kind == "input" and asked[0].prompt == "What is your name?"
    assert asked[0].data["schema"]["required"] == ["name"]
    assert plain.executions == 1
    await src.close()


async def test_mrtr_pending_then_resume(plain):
    src = await load_mcp(cfg(plain.url))
    greet = next(t for t in src.tools if t.name == "fx_greet")
    from toolnexus import ToolContext
    r = await greet.execute({})
    req = pending_of(r)
    assert req is not None and req.kind == "input" and plain.executions == 0
    r2 = await greet.execute({}, ToolContext(answer=Answer(id=req.id, ok=True, data={"name": "toolnexus"})))
    assert r2.output == "Hello, toolnexus!" and plain.executions == 1
    await src.close()


async def test_no_hidden_retry(plain):
    src = await load_mcp(cfg(plain.url))
    echo = next(t for t in src.tools if t.name == "fx_echo")
    for mode in ("502", "drop"):
        plain.executions = 0
        plain.fail_after_run = mode
        r = await echo.execute({"text": "x"})
        assert r.is_error and plain.executions == 1, mode
    await src.close()


def test_header_sentinel_and_skill_uris():
    assert encode_header("echo") == "echo"
    assert encode_header(" x").startswith("=?base64?")
    assert encode_header("héllo").startswith("=?base64?")
    for u in EXPECTED["skillUriRejected"]:
        with pytest.raises(ValueError):
            parse_skill_uri(u)


# ---- skills + tool search ------------------------------------------------

async def test_remote_skills_join_skill_tool(plain):
    tk = await create_toolkit(cfg(plain.url), builtins=False)
    try:
        r = await tk.execute("skill", {"name": "hello-world"})
        assert not r.is_error
        local = await create_toolkit(skills_dir=os.path.join(REPO, "examples", "skills"), builtins=False)
        lr = await local.execute("skill", {"name": "hello-world"})
        body = lambda o: o.split("\n\nBase directory")[0]  # noqa: E731
        assert body(r.output) == body(lr.output)
        assert "<file>scripts/greet.sh</file>" in r.output
    finally:
        await tk.close()


async def test_tool_search(plain):
    src = await load_mcp(cfg(plain.url, toolSearch=True, description="the fixture"))
    try:
        names = [t.name for t in src.tools]
        assert names == ["fx_search_tools", "fx_call_tool"]
        s = src.tools[0]
        assert s.description.startswith("Search the tools of the fixture by keywords.")
        assert s.input_schema == {"type": "object", "properties": {"query": {"type": "string"},
                                  "limit": {"type": "integer"}}, "required": ["query"]}
        r = await s.execute({"query": "echo text"})
        hits = json.loads(r.output)
        assert hits[0]["name"] == "echo" and list(hits[0]) == EXPECTED["toolSearch"]["hitKeys"]
        assert hits[0]["server"] == "the fixture" and r.output.startswith('[{"name":"echo","description"')
        assert (await s.execute({"query": "zzz"})).output == "[]"
        c = await src.tools[1].execute({"name": "echo", "arguments": {"text": "via"}})
        assert json.loads(c.output) == {"text": "via"}
        bad = await src.tools[1].execute({"name": "nope"})
        assert bad.is_error and bad.output == 'unknown tool "nope" on fx'
    finally:
        await src.close()


# ---- OAuth -----------------------------------------------------------------

def _prereg(as_, redirect="http://127.0.0.1/callback"):
    as_.clients["tnx"] = [redirect]
    return {"clientId": "tnx", "allowHttp": True}


async def test_oauth_auto_login(prot):
    srv, as_ = prot
    store_seen = []

    class Store:
        def __init__(self):
            self.m = {}

        def load(self, r):
            return self.m.get(r)

        def save(self, r, t):
            store_seen.append(r)
            self.m[r] = t

        def delete(self, r):
            self.m.pop(r, None)
    src = await load_mcp(cfg(srv.url, auth=_prereg(as_)), open_url=follow, token_store=Store())
    try:
        assert src.status == {"fx": "connected"}
        assert as_.last_authorize["code_challenge_method"] == "S256"
        assert as_.last_authorize["resource"] == srv.url
        assert store_seen == [srv.url]
        tok = Store  # noqa: F841
        echo = next(t for t in src.tools if t.name == "fx_echo")
        assert not (await echo.execute({"text": "a"})).is_error
    finally:
        await src.close()


async def test_oauth_token_repr_redacted(prot):
    from toolnexus import Token
    t = Token("SECRET-AT", "SECRET-RT", issuer="i")
    assert "SECRET" not in repr(t) and "SECRET" not in str(t)


async def test_oauth_pause_during_load_without_wait_for_fails(prot):
    srv, as_ = prot
    a = _prereg(as_, "http://127.0.0.1:9/callback") | {"mode": "pause", "redirectUri": "http://127.0.0.1:9/callback"}
    src = await load_mcp(cfg(srv.url, auth=a))
    assert src.status == {"fx": "failed"}
    await src.close()


async def test_oauth_pause_resume_and_step_up(prot):
    srv, as_ = prot
    a = _prereg(as_, "http://127.0.0.1:9/callback") | {"mode": "pause", "redirectUri": "http://127.0.0.1:9/callback"}
    asked = []

    def wait_for(req):
        asked.append(req)
        if req.kind == "authorization" and req.id.startswith("auth-"):
            return Answer(id=req.id, ok=True, data={"callbackUrl": visit(req.url)})
        return Answer(id=req.id, ok=True, data={"name": "toolnexus"})
    src = await load_mcp(cfg(srv.url, auth=a), wait_for=wait_for)
    try:
        assert src.status == {"fx": "connected"}
        assert asked[0].prompt == "Authorize access to " + srv.url and asked[0].expiresAt
        greet = next(t for t in src.tools if t.name == "fx_greet")
        r = await greet.execute({})
        assert r.output == "Hello, toolnexus!", r.output
        assert as_.last_authorize["scope"] == "mcp:read mcp:greet"  # step-up union
        assert srv.executions == 1
    finally:
        await src.close()


async def test_oauth_pause_in_tool_call_resumes_same_round(prot):
    srv, as_ = prot
    a = _prereg(as_, "http://127.0.0.1:9/callback") | {"mode": "pause", "redirectUri": "http://127.0.0.1:9/callback"}
    calls = {"n": 0}

    def load_wait(req):  # only for the load-time login
        calls["n"] += 1
        return Answer(id=req.id, ok=True, data={"callbackUrl": visit(req.url)})
    src = await load_mcp(cfg(srv.url, auth=a), wait_for=None)
    assert src.status == {"fx": "failed"}  # sanity: load needs wait_for
    await src.close()
    src = await load_mcp(cfg(srv.url, auth=a), wait_for=load_wait)
    src_tools = {t.name: t for t in src.tools}
    # drop wait_for for the call: MRTR then auth pause come back as pendings
    for t in src_tools.values():
        pass
    from toolnexus import ToolContext
    sess = src._sessions["fx"][0]
    sess.wait_for = None
    greet = src_tools["fx_greet"]
    r = await greet.execute({})
    q1 = pending_of(r)  # 403 insufficient_scope => step-up pause (tool not run)
    assert q1 is not None and q1.kind == "authorization" and q1.id.startswith("auth-"), r.output
    r = await greet.execute({}, ToolContext(answer=Answer(id=q1.id, ok=True, data={"callbackUrl": visit(q1.url)})))
    q2 = pending_of(r)
    assert q2 is not None and q2.kind == "input" and srv.executions == 0
    r = await greet.execute({}, ToolContext(answer=Answer(id=q2.id, ok=True, data={"name": "toolnexus"})))
    assert r.output == "Hello, toolnexus!" and srv.executions == 1
    r = await greet.execute({}, ToolContext(answer=Answer(id="auth-nope", ok=True, data={})))
    assert pending_of(r) is not None  # unknown id => a fresh call, not a crash
    await src.close()


async def test_oauth_errors(prot):
    srv, as_ = prot
    from toolnexus.mcp2026.auth import OAuth
    from toolnexus.mcp2026.conn import Conn

    def run(auth, opener=follow):
        c = Conn(url=srv.url, timeout=5, oauth=OAuth(auth, open_url=opener))
        with pytest.raises(Exception) as ei:
            c.call("tools/list")
        return str(ei.value)
    as_.iss_override = "https://evil"
    assert run(_prereg(as_)) == "authorization response iss mismatch (possible mix-up attack)"
    as_.iss_override = ""

    def bad_state(url):
        loc = visit(url)
        u = urlsplit(loc)
        q = {k: v[0] for k, v in parse_qs(u.query).items()} | {"state": "x"}
        from urllib.parse import urlencode
        HttpClient().get(f"{u.scheme}://{u.netloc}{u.path}?{urlencode(q)}")
    assert run(_prereg(as_), bad_state) == "authorization response state mismatch"
    msg = run({"allowHttp": True})
    assert "register an app with" in msg and "dynamic client registration is not supported" in msg
    as_.no_s256 = True
    assert run(_prereg(as_)) == "authorization server does not advertise PKCE S256; refusing"


async def test_oauth_form_token_refresh_single_flight_and_omit_resource(prot):
    srv, as_ = prot
    from toolnexus.mcp2026.auth import OAuth
    from toolnexus.mcp2026.conn import Conn
    as_.form_encoded = True
    as_.access_ttl = 1  # expires immediately (10 s skew)
    oauth = OAuth(_prereg(as_) | {"omitRefreshResource": True}, open_url=follow)
    c = Conn(url=srv.url, timeout=5, oauth=oauth)
    c.call("tools/list")  # login; form-encoded token parsed
    as_.reject_refresh_resource = True
    as_.refreshes = 0
    oauth.store.save(srv.url, oauth.store.load(srv.url))
    tok = oauth.store.load(srv.url)
    tok.expires_at = 1.0  # force expiry
    as_.access_ttl = 3600
    res = await asyncio.gather(*(asyncio.to_thread(c.call, "tools/list") for _ in range(20)))
    assert len(res) == 20 and as_.refreshes == 1
    assert "resource" not in as_.last_token_form


async def test_tool_search_respects_filter_and_disable(plain):
    src = await load_mcp(cfg(plain.url, toolSearch=True, tools={"greet": False}))
    r = await src.tools[0].execute({"query": "echo greet"})
    assert [h["name"] for h in json.loads(r.output)] == ["echo"]
    bad = await src.tools[1].execute({"name": "greet"})
    assert bad.is_error and bad.output == 'unknown tool "greet" on fx'
    await src.close()
    tk = await create_toolkit(cfg(plain.url, toolSearch=True), builtins=False, disable_tools=["fx_echo"])
    bad = await tk.execute("fx_call_tool", {"name": "echo", "arguments": {"text": "x"}})
    assert bad.is_error and bad.output == 'unknown tool "echo" on fx'
    await tk.close()
