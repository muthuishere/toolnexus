"""Side effects run exactly once across auth pauses, refresh-after-401 and step-up; a lost or failed
response is never retried (spec issue #3394)."""
import socket
import socketserver
import threading
import time
from io import BytesIO

import pytest

import mcp2026 as mcp
from conftest import pending
from test_auth import expire


class Counter:
    def __init__(self):
        self.n, self._lock = 0, threading.Lock()

    def inc(self):
        with self._lock:
            self.n += 1


def counted(tick: Counter, charge: Counter):
    """Registers tick (base scope) and charge (needs mcp:charge), each counting executions."""
    def cfg(s):
        s.tool("tick", "counts", lambda a, c: (tick.inc(), "tick")[1])
        s.tool("charge", "counts, extra scope", lambda a, c: (charge.inc(), "charged")[1])
        if s.auth:
            s.auth.scopes["charge"] = ["mcp:charge"]
    return cfg


def test_pause_resume_executes_once(make_env):
    tick, charge = Counter(), Counter()
    e = make_env(counted(tick, charge))
    c = e.pause_client()
    p = pending(lambda: c.call_tool("tick"))
    assert tick.n == 0  # the paused call has not executed
    cb = e.visit(p.url)
    assert mcp.text(c.auth.resume(p.id, cb)) == "tick"
    with pytest.raises(KeyError):
        c.auth.resume(p.id, cb)
    assert tick.n == 1


def test_refresh_after_401_executes_once(make_env):
    tick, charge = Counter(), Counter()
    e = make_env(counted(tick, charge))
    c = e.auto_client()
    c.call_tool("tick")
    t = c.auth.store.load(e.url)
    # not expired client-side: the server's 401 invalid_token drives the refresh
    c.auth.store.save(e.url, mcp.Token(**{**t.__dict__, "access_token": "revoked-by-server"}))
    assert mcp.text(c.call_tool("tick")) == "tick"
    assert (tick.n, *e.as_.stats()) == (2, 1, 1)


def test_step_up_executes_once(make_env):
    tick, charge = Counter(), Counter()
    e = make_env(counted(tick, charge))
    # 401 (base scope) -> login -> 403 insufficient_scope -> step-up login -> run.
    assert mcp.text(e.auto_client().call_tool("charge")) == "charged"
    assert charge.n == 1 and e.as_.stats()[0] == 2


def test_pause_mid_mrtr_resumes_same_round(make_env):
    """A pause in a later MRTR round resumes THAT round: the user is not re-asked, round 0 is not replayed."""
    e = make_env(lambda s: s.auth.scopes.pop("greet"))
    c = e.pause_client()
    p = pending(lambda: c.call_tool("echo", {"text": "login"}))
    c.auth.resume(p.id, e.visit(p.url))
    asked = []

    def respond(k, req):
        asked.append(k)
        c.auth.store.delete(e.url)  # token lost between rounds -> round 1 gets 401
        return {"action": "accept", "content": {"name": "once"}}

    p = pending(lambda: c.call_tool("greet", {}, respond))
    r = c.auth.resume(p.id, e.visit(p.url))
    assert mcp.text(r) == "Hello, once!" and len(asked) == 1


@pytest.mark.parametrize("mode", ["connection dropped", "502 after execution"])
def test_lost_or_failed_response_not_retried(mode):
    tick, charge = Counter(), Counter()
    s = mcp.fixture_server()
    counted(tick, charge)(s)

    class H(socketserver.StreamRequestHandler):
        def handle(self):  # the tool runs; its response is lost
            head = b""
            while not head.endswith(b"\r\n\r\n"):
                head += self.rfile.read(1)
            lines = head.decode().split("\r\n")
            hdrs = {k.lower(): v.strip() for k, _, v in (l.partition(":") for l in lines[1:] if l)}
            body = self.rfile.read(int(hdrs.get("content-length", 0)))
            env = {"REQUEST_METHOD": "POST", "PATH_INFO": "/mcp", "CONTENT_LENGTH": str(len(body)),
                   "wsgi.input": BytesIO(body), "wsgi.url_scheme": "http", "HTTP_HOST": "x"}
            env.update({"HTTP_" + k.upper().replace("-", "_"): v for k, v in hdrs.items()})
            s(env, lambda *a: None)
            if mode == "502 after execution":
                self.wfile.write(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 11\r\n\r\nbad gateway")
            else:
                self.request.shutdown(socket.SHUT_RDWR)

    srv = socketserver.ThreadingTCPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        c = mcp.Client(f"http://127.0.0.1:{srv.server_address[1]}/mcp")
        with pytest.raises(Exception):
            c.call_tool("tick")
        time.sleep(0.05)  # a hidden retry would land here
        assert tick.n == 1
    finally:
        srv.shutdown()
        srv.server_close()


# ---- RFC 8707 resource on refresh (python-sdk #2578) ----

@pytest.mark.parametrize("omit", [False, True])
def test_refresh_resource_configurable(make_env, omit):
    e = make_env()
    e.as_.reject_refresh_resource = True  # Entra-like
    c = e.auto_client()
    c.auth.omit_refresh_resource = omit
    c.call_tool("echo", {"text": "1"})
    assert e.as_.last_authorize_query["resource"] == e.url
    assert e.as_.last_token_form["resource"] == e.url  # code exchange always sends it
    expire(c, e.url)
    assert mcp.text(c.call_tool("echo", {"text": "2"})) == "2"
    a, rf = e.as_.stats()
    if omit:
        assert (rf, a) == (1, 1) and "resource" not in e.as_.last_token_form
    else:  # default sends resource; Entra rejects -> falls back to a new login
        assert (rf, a) == (0, 2)


def test_refresh_sends_resource_by_default(env):
    c = env.auto_client()
    c.call_tool("echo", {"text": "1"})
    expire(c, env.url)
    c.call_tool("echo", {"text": "2"})
    f = env.as_.last_token_form
    assert f["grant_type"] == "refresh_token" and f["resource"] == env.url


# ---- pre-registered client id (+ secret) as the alternative to CIMD; no DCR ----

@pytest.mark.parametrize("method", ["", "client_secret_basic", "client_secret_post"])
def test_pre_registered_confidential_client(make_env, method):
    e = make_env()
    e.as_.no_cimd = True
    e.as_.clients["conf"] = ["http://127.0.0.1/callback"]
    e.as_.secrets = {"conf": "test-only-secret"}
    c = mcp.Client(e.url)
    c.auth = mcp.OAuth(client_id="conf", client_secret="test-only-secret", token_auth_method=method,
                       authorizer=mcp.LoopbackAuthorizer(open=e.follow))
    assert mcp.text(c.call_tool("echo", {"text": "ok"})) == "ok"
    assert ("client_secret" in e.as_.last_token_form) == (method == "client_secret_post")
    c.auth.client_secret = "wrong"
    expire(c, e.url)
    c.auth.authorizer = mcp.PauseAuthorizer("http://127.0.0.1:9/callback")
    # a wrong secret fails the refresh and falls back to a new authorization
    pending(lambda: c.call_tool("echo", {"text": "x"}))


def test_no_cimd_and_no_client_id_is_a_clear_error(env):
    env.as_.no_cimd = True
    with pytest.raises(ValueError) as ei:
        env.auto_client().call_tool("echo", {"text": "x"})  # has a CIMD URL, the AS can't use it
    assert "does not support Client ID Metadata Documents" in str(ei.value)
    assert f"register an app with {env.as_.issuer} and set OAuth.client_id" in str(ei.value)
    assert env.as_.stats()[0] == 0  # no authorization started
