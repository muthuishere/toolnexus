"""Example 09 — a real authorization server: Keycloak 26.8.1 protecting an MCP server.

Start Keycloak first (realm "toolnexus", user alice / alice-test-pw, client "toolnexus-mcp"):

    cd ../golang/keycloak && docker compose up -d && cd -
    KEYCLOAK=1 python examples/09-keycloak/main.py                  # logs in through your system browser
    KEYCLOAK=1 HEADLESS_LOGIN=1 python examples/09-keycloak/main.py # scripted login (used by the test)

The MCP server listens on 127.0.0.1:7199 because the realm's audience mapper puts
aud = http://127.0.0.1:7199/mcp into tokens; ServerAuth audience must equal it.
"""
import os
import sys

import mcp2026 as mcp

ISSUER = "http://localhost:8180/realms/toolnexus"  # exactly the `iss` Keycloak puts in tokens
RESOURCE = "http://127.0.0.1:7199/mcp"

if os.environ.get("KEYCLOAK") != "1":
    print("skipped: set KEYCLOAK=1 with keycloak/docker-compose.yml running")
    sys.exit(0)

# ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
srv = mcp.fixture_server().tool("whoami", "Who is calling", lambda args, ctx: (
    # subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
    f"client={ctx.principal.claims.get('azp')} scopes={' '.join(ctx.principal.scopes)} "
    f"subject-set={str(ctx.principal.subject != '').lower()}"))
srv.auth = mcp.ServerAuth(
    ISSUER,
    audience=RESOURCE,  # = the audience mapper's included.custom.audience
    scopes={"*": ["mcp:tools"], "greet": ["mcp:greet"]},
)
ts = mcp.serve(srv, port=7199)

# ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
login = mcp.LoopbackAuthorizer()  # open=None = system browser
if os.environ.get("HEADLESS_LOGIN") == "1":
    sys.path.insert(0, os.path.dirname(__file__))
    from headless import headless_login
    login.open = headless_login("alice", "alice-test-pw")  # throwaway local test user
c = mcp.Client(RESOURCE)
c.auth = mcp.OAuth(
    client_ids={ISSUER: "toolnexus-mcp"},  # client id bound to THIS issuer
    allow_http=True,  # dev Keycloak is plain HTTP; never in production
    authorizer=login,
)

res = c.call_tool("whoami")
print("whoami:", mcp.text(res))

# greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
res = c.call_tool("greet", {}, lambda key, req: {"action": "accept", "content": {"name": "Alice"}})
print("greet:", mcp.text(res))
ts.close()
