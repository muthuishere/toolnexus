"""Live tests against a real Keycloak (../golang/keycloak/docker-compose.yml). Skipped unless KEYCLOAK=1.
The realm's audience mapper pins aud = http://127.0.0.1:7199/mcp, so the MCP server must listen there."""
import json
import os
import sys
import time

import pytest

import mcp2026 as mcp
from mcp2026.http import HttpClient, b64url_decode, serve

pytestmark = pytest.mark.skipif(os.environ.get("KEYCLOAK") != "1", reason="set KEYCLOAK=1 with Keycloak running")

ISSUER = "http://localhost:8180/realms/toolnexus"
RESOURCE = "http://127.0.0.1:7199/mcp"
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "examples", "09-keycloak"))
from headless import headless_login  # noqa: E402  (test harness: plays the person at the browser)


@pytest.fixture(scope="module")
def kc():
    srv = mcp.fixture_server().tool("whoami", "who", lambda a, ctx: " ".join(ctx.principal.scopes))
    srv.auth = mcp.ServerAuth(ISSUER, audience=RESOURCE, scopes={"*": ["mcp:tools"], "greet": ["mcp:greet"]})
    for _ in range(120):  # other ports' suites may hold 7199 for a moment
        try:
            run = serve(srv, port=7199)
            break
        except OSError:
            time.sleep(1)
    else:
        pytest.fail("127.0.0.1:7199 stayed busy")
    yield srv
    run.close()


def client(**kw) -> mcp.Client:
    c = mcp.Client(RESOURCE)
    kw.setdefault("authorizer", mcp.LoopbackAuthorizer(open=headless_login("alice", "alice-test-pw")))
    c.auth = mcp.OAuth(client_ids={ISSUER: "toolnexus-mcp"}, allow_http=True, **kw)
    return c


def claims(tok: str) -> dict:
    return json.loads(b64url_decode(tok.split(".")[1]))


def test_eight_steps(kc):
    store = mcp.MemoryTokenStore()  # one login; every client the checks create reuses the token
    assert mcp.run_checks(RESOURCE, lambda c: client(store=store))


def test_end_to_end_token_shape(kc):
    c = client()
    assert mcp.text(c.call_tool("whoami")) == "mcp:tools"
    t = c.auth.store.load(RESOURCE)
    cl = claims(t.access_token)
    assert cl["aud"] == RESOURCE or RESOURCE in cl["aud"]
    assert cl["iss"] == ISSUER and cl["azp"] == "toolnexus-mcp" and t.issuer == ISSUER


@pytest.mark.parametrize("omit", [False, True])
def test_refresh(kc, omit):
    c = client(omit_refresh_resource=omit)
    c.call_tool("echo", {"text": "1"})
    t = c.auth.store.load(RESOURCE)
    assert t.refresh_token
    c.auth.store.save(RESOURCE, mcp.Token(**{**t.__dict__, "expiry": time.time() - 60}))
    c.auth.authorizer = mcp.PauseAuthorizer("http://127.0.0.1:9/callback")  # a new login would pause: must not
    assert mcp.text(c.call_tool("echo", {"text": "2"})) == "2"
    assert c.auth.store.load(RESOURCE).access_token != t.access_token


def test_step_up(kc):
    c = client()
    c.call_tool("echo", {"text": "1"})
    r = c.call_tool("greet", {}, lambda k, req: {"action": "accept", "content": {"name": "Alice"}})
    assert mcp.text(r) == "Hello, Alice!"
    assert {"mcp:tools", "mcp:greet"} <= set(c.auth.store.load(RESOURCE).scopes)


def test_pause_resume(kc):
    c = client(authorizer=mcp.PauseAuthorizer("http://127.0.0.1:9/callback"))
    try:
        c.call_tool("echo", {"text": "paused"})
        pytest.fail("want PendingError")
    except mcp.PendingError as e:
        p = e.pending
    cb = headless_login("alice", "alice-test-pw", deliver=False)(p.url)
    assert "iss=" in cb
    assert mcp.text(c.auth.resume(p.id, cb)) == "paused"


def test_wrong_audience_rejected(kc):
    """A Keycloak token minted for 127.0.0.1:7199 is refused by a server with a different audience."""
    c = client()
    c.call_tool("echo", {"text": "x"})
    tok = c.auth.store.load(RESOURCE).access_token
    other = mcp.fixture_server()
    other.auth = mcp.ServerAuth(ISSUER, audience="http://127.0.0.1:7198/mcp", scopes={"*": ["mcp:tools"]})
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list",
                       "params": {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}})
    h = {"Content-Type": "application/json", "MCP-Protocol-Version": "2026-07-28", "Mcp-Method": "tools/list",
         "Authorization": "Bearer " + tok}
    with serve(other) as s:
        r = HttpClient().request("POST", s.url + "/mcp", body, h)
        assert r.status == 401 and 'error="invalid_token"' in r.header("WWW-Authenticate")
    assert HttpClient().request("POST", RESOURCE, body, h).status == 200  # control: right audience
