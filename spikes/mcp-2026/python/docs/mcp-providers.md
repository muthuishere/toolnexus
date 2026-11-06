# MCP authentication: provider notes

> **Python port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/python` (package `mcp2026`, `import mcp2026 as mcp`); the Go spike is the reference.
> Every code block is copied verbatim from a program under `examples/` that `tests/test_examples.py` runs and
> checks; `tests/test_docs.py` fails if a block here stops matching its source.

| provider | client identity | tested (python) | status |
|---|---|---|---|
| Keycloak 26.8.1 | pre-registered public client, or CIMD (experimental) | live, docker compose | works |
| GitHub hosted MCP (`api.githubcopilot.com/mcp/`) | OAuth App (client id + secret); no DCR, no CIMD | live | see README |
| Microsoft Entra ID | app registration | **UNTESTED** (simulated only) | see note |

## Keycloak

Runnable: the Go spike's `golang/keycloak/docker-compose.yml` + `realm-toolnexus.json` (realm `toolnexus`,
client `toolnexus-mcp`, throwaway user `alice`), example `examples/09-keycloak`.

```sh
(cd ../golang/keycloak && docker compose up -d)
KEYCLOAK=1 python examples/09-keycloak/main.py                     # logs in through your browser
KEYCLOAK=1 ./run.sh test -k keycloak -s                            # the live suite, headless
```

<!-- from: examples/09-keycloak/main.py -->
```python
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
```

```text
whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true
greet: Hello, Alice!
```

### Setup checklist

1. **Client**: public, standard flow, PKCE method `S256`, redirect URI `http://127.0.0.1/*` (Keycloak matches any
   loopback port). Set `fullScopeAllowed: false` or every token request logs a deprecation warning.
2. **Client scopes**: one per permission (`mcp:tools` default, `mcp:greet` optional). Optional scopes are granted
   only when requested, which is what step-up asks for.
3. **Audience mapper**: Keycloak does **not** derive `aud` from `resource`. Add an `oidc-audience-mapper` with
   `included.custom.audience` = your MCP server's canonical URL and set `ServerAuth(audience=...)` to the same
   value. With the `resource-indicators` feature on, a `resource` that matches no audience gets `invalid_target`
   at the **token** endpoint, not at authorize.
4. **`basic` scope**: must exist, or tokens have no `sub`.
5. **CIMD (optional, experimental)**: needs the `cimd` feature flag **and** a client policy + profile (see the Go
   spike's `docs/mcp-providers.md` for the full recipe).

## GitHub

GitHub's hosted MCP server speaks 2026-07-28. Its AS supports neither DCR nor CIMD, so register an **OAuth App**:
github.com → Settings → Developer settings → OAuth Apps → New, callback URL `http://127.0.0.1/callback`
(GitHub ignores the port on loopback). Enable expiring user tokens to get refresh tokens.

<!-- from: examples/10-github/main.py -->
```python
c = mcp.Client("https://api.githubcopilot.com/mcp/")
c.auth = mcp.OAuth(
    client_id=client_id,
    client_secret=secret,  # GitHub OAuth Apps are confidential clients (client_secret_basic)
    authorizer=login,
)

tools = c.tools()
print("tools listed:", str(len(tools) > 0).lower())

res = c.call_tool("get_me", {})  # read-only: who am I
print("get_me:", res["resultType"], "| isError:", str(res.get("isError") is True).lower())  # content withheld on purpose
```

```sh
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- .venv/bin/python examples/10-github/main.py
```

Quirks the client handles without configuration:

- **Form-encoded token response.** Without `Accept: application/json` GitHub's token endpoint answers
  `application/x-www-form-urlencoded` (RFC 6749 requires JSON). The client sends the header and also parses a
  form-encoded body.
- `serverInfo` arrives in result `_meta` (`io.modelcontextprotocol/serverInfo`), not the result body.
- `isError` is omitted on successful results; treat missing as false.

## Microsoft Entra ID — UNTESTED

Not run against a real tenant. Known from python-sdk issue #2578: Entra rejects `resource` on the
`refresh_token` grant, though the MCP spec says it MUST be sent. Set `omit_refresh_resource=True` for Entra;
authorize and code exchange still send `resource`. Only the simulation
(`DevAuthServer.reject_refresh_resource`, `test_refresh_resource_configurable`) covers this.
