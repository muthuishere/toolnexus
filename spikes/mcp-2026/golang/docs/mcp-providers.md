# MCP authentication: provider notes

> **Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/golang` (import path `toolnexus/spikes/mcp2026`). Every code block is copied
> verbatim from a program under `examples/` that `examples_test.go` runs and checks; `docs_test.go` fails if a
> block here stops matching its source.

| provider | client identity | tested | status |
|---|---|---|---|
| Keycloak 26.8.1 | pre-registered public client, or CIMD (experimental) | live, docker compose | works |
| GitHub hosted MCP (`api.githubcopilot.com/mcp/`) | OAuth App (client id + secret); no DCR, no CIMD | live | works |
| Microsoft Entra ID | app registration | **UNTESTED** (simulated only) | see note |

## Keycloak

Runnable: `keycloak/docker-compose.yml` + `keycloak/realm-toolnexus.json` (realm `toolnexus`, client
`toolnexus-mcp`, throwaway user `alice`), example `examples/09-keycloak`.

```sh
cd keycloak && docker compose up -d && cd ..
KEYCLOAK=1 go run ./examples/09-keycloak                  # logs in through your browser
KEYCLOAK=1 go test -count=1 -run Keycloak -v .            # the whole live suite, headless
cd keycloak && docker compose down -v
```

<!-- from: examples/09-keycloak/main.go -->
```go
	srv.Auth = &mcp.ServerAuth{
		Issuer:   issuer,
		Audience: resource, // = the audience mapper's included.custom.audience
		Scopes:   map[string][]string{"*": {"mcp:tools"}, "greet": {"mcp:greet"}},
	}
	ln, err := net.Listen("tcp", mcpAddr)
	if err != nil {
		log.Fatal(err)
	}
	go http.Serve(ln, srv)
	defer ln.Close()

	// ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
	open := mcp.LoopbackAuthorizer{} // nil Open = system browser
	if os.Getenv("HEADLESS_LOGIN") == "1" {
		open.Open = headlessLogin("alice", "alice-test-pw") // throwaway local test user
	}
	c := mcp.NewClient(resource)
	c.Auth = &mcp.OAuth{
		ClientIDs:  map[string]string{issuer: "toolnexus-mcp"}, // client id bound to THIS issuer
		AllowHTTP:  true,                                       // dev Keycloak is plain HTTP; never in production
		Authorizer: open,
	}

	res, err := c.CallTool("whoami", nil, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("whoami:", mcp.Text(res))
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
   `included.custom.audience` = your MCP server's canonical URL (here on the `mcp:tools` scope) and set
   `ServerAuth.Audience` to the same value. With the `resource-indicators` feature on, a `resource` that matches
   no audience gets `invalid_target` at the **token** endpoint, not at authorize.
4. **`basic` scope**: must exist, or tokens have no `sub`. Defining `clientScopes` in a realm import replaces
   the built-in ones, so the import must re-declare `basic`.
5. **CIMD (optional, experimental)**: the `cimd` feature flag is not enough. You also need a client **policy +
   profile**: condition `client-id-uri` (allowed schemes/domains) and executor `client-id-metadata-document` with
   `cimd-allow-permitted-domains` listing the document host **and the redirect host** (`127.0.0.1`) and
   `cimd-resource-indicator-allow-list` listing the MCP resource (else `invalid_target`); `cimd-allow-http-scheme`
   for dev only. A failed check shows a 400 **error page** instead of redirecting with `error=`, so the client
   never gets a callback. CIMD clients get a consent screen on first use. Without the policy: `client_not_found`.

Other behaviour seen: AS metadata answers at the RFC 8414 path-insert URL; `iss` is sent on every redirect;
`resource` on refresh is accepted; the `scope` claim is a space-separated string.

## GitHub

GitHub's hosted MCP server speaks 2026-07-28. Its AS supports neither DCR nor CIMD, so register an **OAuth App**:
github.com → Settings → Developer settings → OAuth Apps → New, callback URL `http://127.0.0.1/callback`
(GitHub ignores the port on loopback). Enable expiring user tokens to get refresh tokens.

<!-- from: examples/10-github/main.go -->
```go
	c := mcp.NewClient("https://api.githubcopilot.com/mcp/")
	c.Auth = &mcp.OAuth{
		ClientID:     id,
		ClientSecret: secret, // GitHub OAuth Apps are confidential clients (client_secret_basic)
		Authorizer:   login,
	}

	tools, err := c.Tools()
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("tools listed:", len(tools) > 0)

	res, err := c.CallTool("get_me", mcp.Obj{}, nil) // read-only: who am I
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("get_me:", res["resultType"], "| isError:", res["isError"] == true) // content withheld on purpose
```

```sh
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- go run ./examples/10-github
```

Quirks the client handles without configuration:

- **Form-encoded token response.** Without `Accept: application/json` GitHub's token endpoint answers
  `application/x-www-form-urlencoded` (RFC 6749 requires JSON). The client sends the header and also parses a
  form-encoded body.
- `serverInfo` arrives in result `_meta` (`io.modelcontextprotocol/serverInfo`), not the result body.
- `isError` is omitted on successful results; treat missing as false.

Verified live: auto mode, pause/resume, and a forced refresh (`TestGitHubOAuthLive`,
`TestGitHubPauseResumeAndRefreshLive`, `TestExampleGitHub`).

## Microsoft Entra ID — UNTESTED

Not run against a real tenant. Known from python-sdk issue #2578: Entra rejects `resource` on the
`refresh_token` grant, though the MCP spec says it MUST be sent. Set `OmitRefreshResource: true` for Entra;
authorize and code exchange still send `resource`. Only the simulation
(`DevAuthServer.RejectRefreshResource`, `TestRefreshResourceConfigurable`) covers this.
