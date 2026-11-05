"""Example 06 — call an OAuth-protected MCP server with zero auth configuration (auto mode).

Run: python examples/06-oauth-zero-config/main.py   (hermetic: everything runs on 127.0.0.1)

The client learns everything from the server's 401: Protected Resource Metadata -> the authorization
server's metadata -> PKCE S256 login with `resource` -> token -> retry. You only say WHO the client is:
a pre-registered client_id, or a CIMD client_metadata_url. There is no Dynamic Client Registration.
"""
import mcp2026 as mcp

# ---- demo harness: an authorization server + a protected MCP server, both in-process ----
as_ = mcp.DevAuthServer.start()  # DEV/TEST ONLY: auto-approves everyone as "dev-user"
as_.clients["my-app"] = ["http://127.0.0.1/callback"]  # the AS admin registered "my-app"

srv = mcp.fixture_server()
srv.auth = mcp.ServerAuth(as_.issuer,
                          scopes={"*": ["mcp:read"], "greet": ["mcp:greet"]})  # greet needs more
ts = mcp.serve(srv)
# ---- end harness ----

store = mcp.MemoryTokenStore()  # implement mcp.TokenStore to persist tokens (keyed by resource)

# The user code. A real app leaves authorizer unset: LoopbackAuthorizer() opens the system
# browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
c = mcp.Client(ts.url + "/mcp")
c.auth = mcp.OAuth(
    client_id="my-app",
    store=store,
    authorizer=mcp.LoopbackAuthorizer(open=as_.approve),
)

res = c.call_tool("echo", {"text": "hello after login"})
print("echo:", mcp.text(res))

# Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
# re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
res = c.call_tool("greet", {}, lambda key, req: {"action": "accept", "content": {"name": "Ada"}})
print("greet:", mcp.text(res))

# A second client sharing the token store needs no new login.
c2 = mcp.Client(ts.url + "/mcp")
c2.auth = mcp.OAuth(client_id="my-app", store=store, authorizer=mcp.LoopbackAuthorizer(open=as_.approve))
c2.call_tool("echo", {"text": "again"})
logins, _ = as_.stats()
print("browser logins:", logins)  # 1 login + 1 step-up

tok = store.load(ts.url + "/mcp")
print("token stored for this issuer:", str(tok.issuer == as_.issuer).lower())  # print(tok) is redacted

ts.close()
as_.close()
