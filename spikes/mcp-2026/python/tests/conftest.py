import json

import pytest

import mcp2026 as mcp
from mcp2026.http import serve


class Env:
    """A dev AS + a TLS-protected fixture MCP server (+ whoami) + a CIMD document host."""

    def __init__(self, cfg=()):
        self.as_ = mcp.DevAuthServer.start()
        self.hc = self.as_.client()  # trusts the dev certs, follows redirects
        self._cimd_url = ""

        def cimd(environ, start_response):
            b = json.dumps({"client_id": self._cimd_url, "client_name": "toolnexus spike",
                            "redirect_uris": ["http://127.0.0.1/callback"],
                            "grant_types": ["authorization_code", "refresh_token"], "response_types": ["code"],
                            "token_endpoint_auth_method": "none"}).encode()
            start_response("200 OK", [("Content-Type", "application/json")])
            return [b]

        self.cimd = serve(cimd, tls=True)
        self.cimd_url = self._cimd_url = self.cimd.url + "/client.json"
        self.server = mcp.fixture_server()
        self.server.auth = mcp.ServerAuth(self.as_.issuer, scopes={"*": ["mcp:read"], "greet": ["mcp:greet"]})

        def whoami(args, ctx):
            if ctx.principal is None:
                raise RuntimeError("no principal")
            return ctx.principal.subject + " " + ",".join(ctx.principal.scopes)

        self.server.tool("whoami", "who is calling", whoami)
        for f in cfg:
            f(self.server)
        self.mcp = serve(self.server, tls=True)
        self.url = self.mcp.url + "/mcp"

    def close(self):
        self.mcp.close()
        self.cimd.close()
        self.as_.close()

    def follow(self, url):
        """Test browser: GET the auth URL, follow redirects (auto mode lands on the loopback listener)."""
        self.hc.get(url)

    def visit(self, url) -> str:
        """The AS redirect Location without following it (pause mode: the host receives the callback)."""
        r = mcp.HttpClient().get(url)
        assert r.status == 302, f"authorize: HTTP {r.status}"
        return r.header("Location")

    def auto_client(self) -> mcp.Client:
        c = mcp.Client(self.url)
        c.auth = mcp.OAuth(client_metadata_url=self.cimd_url, authorizer=mcp.LoopbackAuthorizer(open=self.follow))
        return c

    def pause_client(self) -> mcp.Client:
        self.as_.clients["spike-prereg"] = ["http://127.0.0.1:9/callback"]
        c = mcp.Client(self.url)
        c.auth = mcp.OAuth(client_ids={self.as_.issuer: "spike-prereg"},
                           authorizer=mcp.PauseAuthorizer("http://127.0.0.1:9/callback"))
        return c


@pytest.fixture
def make_env():
    envs = []

    def make(*cfg):
        e = Env(cfg)
        envs.append(e)
        return e

    yield make
    for e in envs:
        e.close()


@pytest.fixture
def env(make_env):
    return make_env()


@pytest.fixture
def plain():
    """The unprotected fixture server over plain HTTP."""
    with serve(mcp.fixture_server()) as s:
        yield s


def pending(fn) -> mcp.Pending:
    try:
        fn()
    except mcp.PendingError as e:
        return e.pending
    raise AssertionError("want PendingError")
