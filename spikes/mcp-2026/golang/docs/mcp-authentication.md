# MCP authentication

> **Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/golang` (import path `toolnexus/spikes/mcp2026`). Every code block is copied
> verbatim from a program under `examples/` that `examples_test.go` runs and checks; `docs_test.go` fails if a
> block here stops matching its source.

MCP 2026-07-28 `basic/authorization` is OAuth 2.1 with the MCP server as a protected resource. The client
discovers everything from the server's `401`:

1. `WWW-Authenticate: Bearer resource_metadata=…` → Protected Resource Metadata (RFC 9728); falls back to
   `/.well-known/oauth-protected-resource/<path>`, then the root.
2. `authorization_servers[0]` → AS metadata (RFC 8414 path-insert, then OIDC path-insert, then OIDC append),
   with an `issuer` identity check.
3. Refuse the AS if it does not list PKCE `S256`.
4. Authorization code + PKCE S256, with `resource` (RFC 8707) on the authorize **and** token requests.
5. Callback: `iss` (RFC 9207) and `state` are checked before `code` or `error` is acted on.
6. The token is stored per resource and bound to the issuer; it is sent only once that issuer is confirmed.
7. Expired → `refresh_token` grant. `403 insufficient_scope` → step-up with the union of scopes.

A call is re-sent **only** after a `401`, or a `403 insufficient_scope` with `WWW-Authenticate`: the server
rejected it before running the tool. A lost connection, timeout or 5xx is returned as an error and never
retried, because the tool may already have run.

## Who the client is: no DCR

The spec deprecates Dynamic Client Registration and toolnexus does not implement it. A client identifies itself
in one of two ways:

| your authorization server supports | set | example |
|---|---|---|
| Client ID Metadata Documents (AS metadata has `client_id_metadata_document_supported: true`) | `ClientMetadataURL: "https://app.example/client.json"` (an HTTPS URL you host) | Keycloak 26.8 with the `cimd` feature + client policy |
| no CIMD (most providers today) | **register an app** with the AS once and set `ClientID` (+ `ClientSecret` for a confidential client) | GitHub OAuth App, Entra app registration, Keycloak client |
| several ASes | `ClientIDs: map[issuer]clientID`; each id is used only with its issuer | |

If neither is set, the call fails with *register an app with <issuer> and set OAuth.ClientID*. A pre-registered
id wins over CIMD.

## Mode 1: automatic (CLI, desktop)

`LoopbackAuthorizer{}` opens the system browser and listens on `http://127.0.0.1:<random>/callback`
(RFC 8252 §7.3: the AS must accept any port on a loopback redirect). The call blocks until the user has
logged in, then returns the tool's result. In the example the in-process dev server stands in for the browser.

<!-- from: examples/06-oauth-zero-config/main.go -->
```go
	store := &mcp.MemoryTokenStore{} // implement mcp.TokenStore to persist tokens (keyed by resource)

	// The user code. A real app leaves Authorizer nil: LoopbackAuthorizer{} opens the system
	// browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
	c := mcp.NewClient(ts.URL + "/mcp")
	c.Auth = &mcp.OAuth{
		ClientID:   "my-app",
		Store:      store,
		Authorizer: mcp.LoopbackAuthorizer{Open: as.Approve},
	}

	res, err := c.CallTool("echo", mcp.Obj{"text": "hello after login"}, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("echo:", mcp.Text(res))

	// Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
	// re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
	res, err = c.CallTool("greet", mcp.Obj{}, func(string, mcp.Obj) (mcp.Obj, error) {
		return mcp.Obj{"action": "accept", "content": mcp.Obj{"name": "Ada"}}, nil
	})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("greet:", mcp.Text(res))
```

```text
echo: hello after login
greet: Hello, Ada!
browser logins: 2
token stored for this issuer: true
```

`greet` needs `mcp:greet`, so the second call is a **step-up**: `403 insufficient_scope` → a second authorization
for `mcp:read mcp:greet` → the paused call is sent once. `browser logins: 2` is the login plus the step-up.

### Token store

`Store` takes any `TokenStore` (`Load/Save/Delete` keyed by resource URL); nil means in-memory. Share one to
avoid logging in again:

<!-- from: examples/06-oauth-zero-config/main.go -->
```go
	// A second client sharing the token store needs no new login.
	c2 := mcp.NewClient(ts.URL + "/mcp")
	c2.Auth = &mcp.OAuth{ClientID: "my-app", Store: store, Authorizer: mcp.LoopbackAuthorizer{Open: as.Approve}}
	if _, err := c2.CallTool("echo", mcp.Obj{"text": "again"}, nil); err != nil {
		log.Fatal(err)
	}
	logins, _ := as.Stats()
	fmt.Println("browser logins:", logins) // 1 login + 1 step-up

	tok, _ := store.Load(ts.URL + "/mcp")
	fmt.Println("token stored for this issuer:", tok.Issuer == as.Issuer) // fmt.Print(tok) is redacted
```

After a restart the first request goes out without a token, to learn the issuer from the `401`; only then is
the stored token sent. `Token` prints as `Token{issuer=…, redacted}`. Encrypting the store is up to you.

## Mode 2: pause and resume (web apps, bots, job queues)

A server must never block a request on a login. `PauseAuthorizer` makes the call return `*PendingError`
whose `Pending` has `{ID, Kind: "authorization", Prompt, URL, ExpiresAt}`, the shape of toolnexus SPEC §10
`Request`. Send the user to `URL`; when the AS redirects back, `Resume` returns the **original call's** result.
A pending authorization expires after 10 minutes.

### As one http.Handler

<!-- from: examples/07-oauth-web-pause-resume/main.go -->
```go
	c := mcp.NewClient(mcpTS.URL + "/mcp")
	c.Auth = &mcp.OAuth{ClientID: "my-web-app", Authorizer: mcp.PauseAuthorizer{Redirect: app + "/tool"}}

	mux := http.NewServeMux()
	// (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
	// with ?code&state, WebHandler resumes, and the tool's text is written to the response.
	mux.Handle("/tool", c.Auth.WebHandler(func(r *http.Request) (mcp.Obj, error) {
		return c.CallTool("echo", mcp.Obj{"text": r.URL.Query().Get("text")}, nil)
	}))
	go http.Serve(ln, mux)
```

### By hand

<!-- from: examples/07-oauth-web-pause-resume/main.go -->
```go
	c2 := mcp.NewClient(mcpTS.URL + "/mcp")
	c2.Auth = &mcp.OAuth{ClientID: "my-web-app", Authorizer: mcp.PauseAuthorizer{Redirect: "http://127.0.0.1/callback"}}
	_, err = c2.CallTool("echo", mcp.Obj{"text": "resumed by hand"}, nil)
	p := mcp.PendingOf(err) // nil when the call did not pause
	if p == nil {
		log.Fatalf("expected a pending authorization, got %v", err)
	}
	fmt.Println("(b) paused:", p.Kind, "-", p.Prompt != "", "- has URL:", p.URL != "")

	callbackURL := approveAndCaptureCallback(as, p.URL) // the user logs in; you receive the callback URL
	res, err := c2.Auth.Resume(context.Background(), p.ID, callbackURL)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("(b) resumed:", mcp.Text(res))
```

```text
(a) WebHandler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand
```

The paused request is sent for the first time on `Resume` (the `401` came before dispatch). A pause inside an
MRTR round resumes that same round without asking the user again.

## Client options

| field | default | meaning |
|---|---|---|
| `Authorizer` | `LoopbackAuthorizer{}` | automatic, or `PauseAuthorizer{Redirect}` |
| `ClientID` / `ClientSecret` | | pre-registered client; a secret makes it confidential |
| `TokenAuthMethod` | `client_secret_basic` if a secret, else `none` | or `client_secret_post` |
| `ClientIDs` | | issuer → client id |
| `ClientMetadataURL` | | CIMD client id |
| `Store` | in-memory | `TokenStore` |
| `OmitRefreshResource` | false | drop `resource` on refresh (Entra, see Providers) |
| `HTTP` | 30 s timeout client | for metadata and token calls |
| `AllowHTTP` | false | plain-HTTP AS for local dev; the spec requires HTTPS |

## Protecting your own server

One line: the issuer. The server then serves PRM at `/.well-known/oauth-protected-resource/mcp`, answers `401`
with `WWW-Authenticate`, `403 insufficient_scope` with the full scope set for the operation, and verifies RS256
JWTs against the issuer's discovered JWKS (signature, `iss`, `aud` = this server's URL, `exp`).

<!-- from: examples/08-serve-protected/main.go -->
```go
	srv := mcp.NewServer().
		Tool("whoami", "Who is calling", func(ctx context.Context, _ mcp.Obj) (any, error) {
			p, _ := mcp.PrincipalFrom(ctx) // Subject, Scopes, Claims of the verified token
			return "hello " + p.Subject + " (" + strings.Join(p.Scopes, " ") + ")", nil
		}).
		Tool("delete_all", "Dangerous admin tool", func(context.Context, mcp.Obj) (any, error) {
			return "deleted", nil
		})
	srv.Auth = &mcp.ServerAuth{
		Issuer: as.Issuer, // the whole required config
		Scopes: map[string][]string{
			"*":          {"mcp:read"},  // every request
			"delete_all": {"mcp:admin"}, // additionally required for this tool
		},
	}
	ts := httptest.NewServer(srv) // production: http.ListenAndServe(addr, srv)
```

`Scopes["*"]` is required on every request and is advertised as `scopes_supported`; `Scopes[tool]` is also
required for that `tools/call`. Scopes match exactly (no hierarchy). `Audience` defaults to
`scheme://host/path` of the request; set it when a proxy changes the host, or when your AS pins the audience.

The handler reads the caller with `mcp.PrincipalFrom(ctx)`: `Subject`, `Scopes`, `Claims`.

```text
no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
```

### Verify hook: opaque tokens, API keys, introspection

`Verify` replaces the JWT verifier entirely; a JWT does not bypass it.

<!-- from: examples/08-serve-protected/main.go -->
```go
	keys := map[string]mcp.Principal{ // e.g. a lookup in your DB, or an RFC 7662 introspection call
		"svc-key-123": {Subject: "billing-service", Scopes: []string{"mcp:read"}},
	}
	srv2 := mcp.NewServer().Tool("whoami", "Who is calling", func(ctx context.Context, _ mcp.Obj) (any, error) {
		p, _ := mcp.PrincipalFrom(ctx)
		return "hello " + p.Subject, nil
	})
	srv2.Auth = &mcp.ServerAuth{
		Issuer: as.Issuer, // still advertised in the resource metadata
		Scopes: map[string][]string{"*": {"mcp:read"}},
		Verify: func(_ context.Context, token string) (mcp.Principal, error) {
			if p, ok := keys[token]; ok {
				return p, nil
			}
			return mcp.Principal{}, errors.New("unknown key") // -> 401 invalid_token
		},
	}
	ts2 := httptest.NewServer(srv2)
	defer ts2.Close()

	// A service caller with a static key: plain http.Client with a header-adding transport.
	svc := mcp.NewClient(ts2.URL + "/mcp")
	svc.HTTP = &http.Client{Transport: bearer("svc-key-123")}
	res, err = svc.CallTool("whoami", nil, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("verify hook:", mcp.Text(res))

	bad := mcp.NewClient(ts2.URL + "/mcp")
	bad.HTTP = &http.Client{Transport: bearer("wrong")}
	_, err = bad.CallTool("whoami", nil, nil)
	fmt.Println("wrong key rejected:", err != nil)
```

<!-- from: examples/08-serve-protected/main.go -->
```go
// bearer adds a static Authorization header to every request.
type bearer string

func (b bearer) RoundTrip(r *http.Request) (*http.Response, error) {
	r = r.Clone(r.Context())
	r.Header.Set("Authorization", "Bearer "+string(b))
	return http.DefaultTransport.RoundTrip(r)
}
```

## DevAuthServer (dev and tests only)

`mcp.StartDevAuthServer()` starts an OAuth 2.1 AS on `https://127.0.0.1:<port>/dev` that **approves everyone as
`dev-user`** with no login. It implements RFC 8414 metadata, JWKS, code + PKCE S256, `resource`, `iss`,
refresh-token rotation, pre-registered (`Clients`, `Secrets`) and CIMD clients. Its TLS certificate is trusted
by default clients in the same process, so `ServerAuth{Issuer: as.Issuer}` needs no HTTP wiring.
`as.Approve` is a headless browser for `LoopbackAuthorizer{Open: as.Approve}`. **Never use it in production.**

<!-- from: examples/06-oauth-zero-config/main.go -->
```go
	as := mcp.StartDevAuthServer() // DEV/TEST ONLY: auto-approves everyone as "dev-user"
	defer as.Close()
	as.Clients["my-app"] = []string{"http://127.0.0.1/callback"} // the AS admin registered "my-app"

	srv := mcp.FixtureServer()
	srv.Auth = &mcp.ServerAuth{Issuer: as.Issuer,
		Scopes: map[string][]string{"*": {"mcp:read"}, "greet": {"mcp:greet"}}} // greet needs more
	ts := httptest.NewServer(srv)
	defer ts.Close()
```
