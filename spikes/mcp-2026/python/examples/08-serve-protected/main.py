"""Example 08 — serve MCP tools behind OAuth: one issuer line, per-tool scopes, the caller's identity
in the handler, and a custom verify hook for opaque tokens.

Run: python examples/08-serve-protected/main.py   (hermetic: everything runs on 127.0.0.1)

With server.auth set the server: serves Protected Resource Metadata at
/.well-known/oauth-protected-resource/mcp, answers 401 + WWW-Authenticate without a valid token,
403 insufficient_scope when a tool needs more, and verifies RS256 JWTs against the issuer's JWKS
(signature, iss, aud = this server's URL, exp).
"""
import json

import mcp2026 as mcp

as_ = mcp.DevAuthServer.start()  # DEV/TEST ONLY. Production: your Keycloak / Entra / Auth0 issuer.
as_.clients["my-app"] = ["http://127.0.0.1/callback"]

# ---- (1) the server: issuer one-liner + per-tool scope + principal ----
srv = (mcp.Server()
       .tool("whoami", "Who is calling",
             # ctx.principal: subject, scopes, claims of the verified token
             lambda args, ctx: f"hello {ctx.principal.subject} ({' '.join(ctx.principal.scopes)})")
       .tool("delete_all", "Dangerous admin tool", lambda args, ctx: "deleted"))
srv.auth = mcp.ServerAuth(
    as_.issuer,  # the whole required config
    scopes={
        "*": ["mcp:read"],  # every request
        "delete_all": ["mcp:admin"],  # additionally required for this tool
    },
)
ts = mcp.serve(srv)  # production: mount srv (a WSGI app) in any WSGI server
# ---- end server ----

# No token: 401 with WWW-Authenticate pointing at the resource metadata.
resp = mcp.HttpClient().request("POST", ts.url + "/mcp", json.dumps(
    {"jsonrpc": "2.0", "id": 1, "method": "tools/list",
     "params": {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}}),
    {"Content-Type": "application/json", "MCP-Protocol-Version": "2026-07-28", "Mcp-Method": "tools/list"})
print("no token:", resp.status, "| WWW-Authenticate has resource_metadata:",
      str("resource_metadata=" in resp.header("WWW-Authenticate")).lower())

# The metadata a client discovers (RFC 9728): which AS to use and which scopes exist.
prm = mcp.HttpClient().get(ts.url + "/.well-known/oauth-protected-resource/mcp").json()
print("metadata: issuer ok:", str(prm["authorization_servers"][0] == as_.issuer).lower(),
      "| scopes:", "[" + " ".join(prm["scopes_supported"]) + "]")

# A logged-in client (see example 06): whoami gets the principal; delete_all steps up.
c = mcp.Client(ts.url + "/mcp")
c.auth = mcp.OAuth(client_id="my-app", authorizer=mcp.LoopbackAuthorizer(open=as_.approve))
print("whoami:", mcp.text(c.call_tool("whoami")))
print("delete_all after step-up:", mcp.text(c.call_tool("delete_all")))
print("whoami:", mcp.text(c.call_tool("whoami")))

# ---- (2) custom verify: opaque API keys / token introspection instead of JWT ----
keys = {  # e.g. a lookup in your DB, or an RFC 7662 introspection call
    "svc-key-123": mcp.Principal(subject="billing-service", scopes=["mcp:read"]),
}


def verify(token: str) -> mcp.Principal:
    if token in keys:
        return keys[token]
    raise PermissionError("unknown key")  # -> 401 invalid_token


srv2 = mcp.Server().tool("whoami", "Who is calling", lambda args, ctx: "hello " + ctx.principal.subject)
srv2.auth = mcp.ServerAuth(
    as_.issuer,  # still advertised in the resource metadata
    scopes={"*": ["mcp:read"]},
    verify=verify,
)
ts2 = mcp.serve(srv2)

# A service caller with a static key: an HttpClient that adds the header to every request.
svc = mcp.Client(ts2.url + "/mcp", http=mcp.HttpClient(headers={"Authorization": "Bearer svc-key-123"}))
print("verify hook:", mcp.text(svc.call_tool("whoami")))

bad = mcp.Client(ts2.url + "/mcp", http=mcp.HttpClient(headers={"Authorization": "Bearer wrong"}))
try:
    bad.call_tool("whoami")
    rejected = False
except Exception:
    rejected = True
print("wrong key rejected:", str(rejected).lower())

for s in (ts, ts2, as_):
    s.close()
