import threading
import time

import pytest

import mcp2026 as mcp
from mcp2026.http import HttpClient
from mcp2026.protocol import META_VERSION, PROTOCOL_VERSION
from conftest import pending


def test_existing_steps_unprotected(plain):
    assert mcp.run_checks(plain.url + "/mcp")


def test_existing_steps_protected_auto_mode(env):
    assert mcp.run_checks(env.url, lambda c: env.auto_client())


def test_pause_resume(env):
    c = env.pause_client()
    p = pending(lambda: c.call_tool("echo", {"text": "after login"}))
    assert p.kind == "authorization" and p.id and p.prompt and p.url.startswith(env.as_.issuer + "/authorize?")
    cb = env.visit(p.url)
    q = env.as_.last_authorize_query
    assert (q["resource"], q["code_challenge_method"], q["scope"]) == (env.url, "S256", "mcp:read")
    assert mcp.text(c.auth.resume(p.id, cb)) == "after login"
    with pytest.raises(KeyError):
        c.auth.resume(p.id, cb)  # second resume of the same id fails
    assert mcp.text(c.call_tool("echo", {"text": "again"})) == "again"  # reuses the token
    assert env.as_.authorizations == 1


def test_auto_mode_whoami_principal(env):
    assert mcp.text(env.auto_client().call_tool("whoami")) == "dev-user mcp:read"


def test_iss_mismatch_rejected(env):
    env.as_.iss_override = "https://evil.example"
    c = env.auto_client()
    with pytest.raises(ValueError, match="iss mismatch"):
        c.call_tool("echo", {"text": "x"})
    assert c.auth.store.load(env.url) is None  # no token stored


def test_state_mismatch_rejected(env):
    c = env.pause_client()
    p = pending(lambda: c.call_tool("echo", {"text": "x"}))
    cb = env.visit(p.url)
    i = cb.index("state=")
    cb = cb[:i] + "state=forged&" + cb[i + 6:]
    with pytest.raises(ValueError, match="state mismatch"):
        c.auth.resume(p.id, cb)


def test_pkce_verified_by_as(env):
    c = env.pause_client()
    p = pending(lambda: c.call_tool("echo", {"text": "x"}))
    cb = env.visit(p.url)
    c.auth._flows[p.id].verifier = "not-the-verifier"  # the AS must reject the exchange
    with pytest.raises(PermissionError, match="invalid_grant"):
        c.auth.resume(p.id, cb)


def expire(c, url):
    t = c.auth.store.load(url)
    c.auth.store.save(url, mcp.Token(**{**t.__dict__, "expiry": time.time() - 60}))


def test_refresh_on_expiry(env):
    c = env.auto_client()
    c.call_tool("echo", {"text": "1"})
    old = c.auth.store.load(env.url).access_token
    expire(c, env.url)
    assert mcp.text(c.call_tool("echo", {"text": "2"})) == "2"
    assert env.as_.stats() == (1, 1) and c.auth.store.load(env.url).access_token != old


def test_insufficient_scope_step_up(env):
    c = env.auto_client()
    c.call_tool("echo", {"text": "1"})
    r = c.call_tool("greet", {}, lambda k, req: {"action": "accept", "content": {"name": "scoped"}})
    assert mcp.text(r) == "Hello, scoped!"
    sc = env.as_.last_authorize_query["scope"].split()
    assert env.as_.authorizations == 2 and sorted(sc) == ["mcp:greet", "mcp:read"]  # union
    c.call_tool("echo", {"text": "still"})
    assert env.as_.authorizations == 2  # previous scope kept


def test_prm_well_known_fallback_and_no_client_identity(env):
    m = mcp.OAuth()._fetch_prm(env.url, "")
    assert m["resource"] == env.url
    c = mcp.Client(env.url)
    c.auth = mcp.OAuth(authorizer=mcp.PauseAuthorizer("http://127.0.0.1:9/callback"))
    with pytest.raises(ValueError, match="register an app with " + env.as_.issuer):
        c.call("tools/list")


def test_loopback_opener_error_surfaces(env):
    """A failing browser opener must surface, not park the caller for the 5-minute callback timeout."""
    c = env.auto_client()

    def no_browser(url):
        raise OSError("no browser")

    c.auth.authorizer = mcp.LoopbackAuthorizer(open=no_browser)
    t0 = time.time()
    with pytest.raises(OSError, match="no browser"):
        c.call_tool("echo", {"text": "x"})
    assert time.time() - t0 < 5


def test_concurrent_401s_one_login(env):
    c = env.auto_client()
    errs = []

    def go(i):
        try:
            assert mcp.text(c.call_tool("echo", {"text": str(i)})) == str(i)
        except Exception as e:  # pragma: no cover
            errs.append(e)

    ts = [threading.Thread(target=go, args=(i,)) for i in range(10)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert not errs and env.as_.authorizations == 1


# ---- server side ----

def raw_call(e, token, tool):
    import json
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": tool, "arguments": {"text": "x"}, "_meta": {META_VERSION: PROTOCOL_VERSION}}})
    h = {"Content-Type": "application/json", "MCP-Protocol-Version": PROTOCOL_VERSION, "Mcp-Method": "tools/call",
         "Mcp-Name": tool}
    if token:
        h["Authorization"] = "Bearer " + token
    r = HttpClient().request("POST", e.url, body, h)
    return r.status, r.header("WWW-Authenticate"), r.body.decode()


def claims(e, **mut):
    c = {"iss": e.as_.issuer, "sub": "alice", "aud": e.url, "scope": "mcp:read", "exp": int(time.time()) + 3600}
    c.update(mut)
    return e.as_.mint(c)


def test_server_jwt_via_jwks_and_principal(env):
    st, _, body = raw_call(env, claims(env), "whoami")
    assert st == 200 and "alice mcp:read" in body


def test_server_rejects(env):
    cases = {"missing": "", "wrong audience": claims(env, aud="https://other.example/mcp"),
             "expired": claims(env, exp=int(time.time()) - 60), "wrong issuer": claims(env, iss="https://evil.example"),
             "tampered": claims(env) + "x"}
    for name, tok in cases.items():
        st, www, _ = raw_call(env, tok, "echo")
        assert st == 401 and f'resource_metadata="{env.mcp.url}/.well-known/oauth-protected-resource/mcp' in www, name
        if tok:
            assert 'error="invalid_token"' in www, name


def test_server_per_tool_scope_403(env):
    st, www, _ = raw_call(env, claims(env), "greet")
    assert st == 403 and 'error="insufficient_scope"' in www and 'scope="mcp:read mcp:greet"' in www
    st, _, _ = raw_call(env, claims(env, scope="mcp:read mcp:greet"), "greet")
    assert st == 200


def test_server_custom_verify(env):
    def verify(tok):
        if tok != "opaque-ok":
            raise PermissionError("nope")
        return mcp.Principal("svc", ["mcp:read"])

    env.server.auth.verify = verify
    st, _, body = raw_call(env, "opaque-ok", "whoami")
    assert st == 200 and "svc mcp:read" in body
    assert raw_call(env, claims(env), "whoami")[0] == 401  # a JWT must not bypass a custom verify


def test_token_repr_is_redacted():
    t = mcp.Token("secret-access", "secret-refresh", issuer="https://as.example")
    assert "secret" not in repr(t) and "secret" not in str(t) and "redacted" in repr(t)
