# Keycloak live test for the Go MCP 2026-07-28 OAuth spike

Real Keycloak **26.8.1** (dev mode, `127.0.0.1:8180`, features `cimd,resource-indicators`), realm `toolnexus`
imported from `realm-toolnexus.json`. Local-only throwaway values: user `alice` / `alice-test-pw`,
admin `admin` / `admin-test-only`.

```sh
cd keycloak && docker compose up -d && cd .. && KEYCLOAK=1 go test -count=1 -run Keycloak -v ./...
cd keycloak && docker compose down -v
```

Without `KEYCLOAK=1` the tests skip, so `go test -race ./...` stays hermetic. The login is headless: `kcBrowser`
in `../keycloak_test.go` follows the authorize URL with `net/http` + a cookie jar, posts Keycloak's
`kc-form-login`, accepts the consent page when one appears, and stops at the `127.0.0.1` redirect.
The MCP server runs on the fixed `127.0.0.1:7199`, because the realm's audience mapper pins
`aud = http://127.0.0.1:7199/mcp`.

## What was verified (all PASS)

| test | covers |
|---|---|
| EndToEnd | PRM `/.well-known/oauth-protected-resource/mcp` → AS metadata → PKCE S256 login → `iss` in callback → code exchange with `resource` → `whoami` |
| EightSteps | the 8 self-test checks with a protected server; the greet step does a step-up mid-suite |
| Refresh (×2) | expired token → `refresh_token` grant **with** and **without** `resource`; both accepted, no second login |
| StepUp | `greet` → 403 `insufficient_scope` → re-authorize `mcp:tools mcp:greet` (SSO cookie, no 2nd form) → MRTR → "Hello, Alice!" |
| PauseResume | `PauseAuthorizer` → `*PendingError` → headless login → `Resume` returns the original call's result |
| WrongAudienceRejected | a valid Keycloak token on a server whose resource is `:7200` → 401 `invalid_token`; control on `:7199` → 200 |
| CIMD | `client_id = http://host.docker.internal:7198/client.json`; Keycloak fetches it, login completes |
| ResourceIndicatorProbe | logs what Keycloak does with a `resource` it has no mapping for (it rejects it, see below) |

JWT verification uses Keycloak's real JWKS (RS256, `kid` from the discovered `jwks_uri`).

## Keycloak quirks

- **AS metadata:** Keycloak serves RFC 8414 at the path-insert URL
  `/.well-known/oauth-authorization-server/realms/toolnexus`, so the client takes the first candidate. The OIDC path-insert
  form returns 404. The OIDC append form `/realms/toolnexus/.well-known/openid-configuration` also works.
- **`iss` (RFC 9207):** sent on every redirect, and `authorization_response_iss_parameter_supported: true` is advertised,
  so the client's missing-`iss` check is active.
- **PKCE:** advertises `plain` and `S256`; the client pins `pkce.code.challenge.method=S256`.
- **Audience:** Keycloak does **not** derive `aud` from `resource`. You need an `oidc-audience-mapper`; here it is on
  the `mcp:tools` client scope, so `aud` appears only when that scope is granted. With `resource-indicators` on, a
  `resource` that does not match the client's audiences gets `invalid_target` at the **token** endpoint, not at authorize.
  For a pre-registered client the authorize step accepted it.
- **`resource` on refresh:** accepted when it matches. No Entra-style rejection, so `OmitRefreshResource` is not needed.
- **Scopes:** the `scope` claim is a space-separated string. Optional scopes (`mcp:greet`) are granted only when requested.
  `basic` must exist or tokens have no `sub`. Defining `clientScopes` in an import replaces the built-in ones, so this
  realm re-declares `basic`.
- **Loopback redirect:** `http://127.0.0.1/*` matches any port (RFC 8252 §7.3), both for a pre-registered client and for
  a CIMD `redirect_uris: ["http://127.0.0.1/callback"]`.
- **CIMD works** in 26.8.1, but it is experimental and takes more than the feature flag. You also need a client
  **policy + profile** (see `clientPolicies`/`clientProfiles` in the realm), which require:
  - condition `client-id-uri` with the allowed schemes and domains;
  - executor `client-id-metadata-document` with `cimd-allow-http-scheme` (dev only);
  - `cimd-allow-permitted-domains`, which must include the **redirect URI host** (`127.0.0.1`) as well as the document host;
  - `cimd-resource-indicator-allow-list` listing the MCP resource. Without it you get `invalid_target`.
  
  If either check fails, Keycloak shows a 400 **error page** instead of redirecting with `error=`, so the client never
  sees a callback. CIMD clients get a **consent screen** on first use. Without the policy you get `client_not_found`.
- **Logs:** `fullScopeAllowed` must be `false`, or every token request logs a deprecation warning.

## Use toolnexus (spike API) with Keycloak

Keycloak setup: a public client with standard flow, PKCE S256 and redirect `http://127.0.0.1/*`. Add a client scope
per permission, plus an audience mapper (`included.custom.audience` = your MCP server's canonical URL) on the base scope.

```go
// server
srv.Auth = &ServerAuth{
    Issuer:   "https://kc.example.com/realms/myrealm", // exactly the token's iss
    Audience: "https://mcp.example.com/mcp",           // must equal the audience mapper value
    Scopes:   map[string][]string{"*": {"mcp:tools"}, "greet": {"mcp:greet"}},
}

// client
c := NewClient("https://mcp.example.com/mcp")
c.Auth = &OAuth{
    ClientIDs: map[string]string{"https://kc.example.com/realms/myrealm": "toolnexus-mcp"},
    // or CIMD (needs the client policy above): ClientMetadataURL: "https://app.example.com/client.json",
}
res, err := c.CallTool("echo", obj{"text": "hi"}, nil) // opens the browser once, then refreshes silently
```

Use `AllowHTTP: true` only for a plain-HTTP dev Keycloak like this one.
