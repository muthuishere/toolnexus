# MCP authentication

> **C# port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/csharp` (project `src/Mcp2026`, namespace `Mcp2026`). Every code block is copied verbatim from a
> program under `examples/` that `ExamplesTests` runs and checks; `DocsTests` fails if a block here stops matching
> its source. Behaviour and example output are identical to the Go reference spike.

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
rejected it before running the tool. A lost connection, timeout or 5xx is thrown as `McpException` and never
retried, because the tool may already have run.

## Who the client is: no DCR

| your authorization server supports | set |
|---|---|
| Client ID Metadata Documents (`client_id_metadata_document_supported: true`) | `ClientMetadataUrl = "https://app.example/client.json"` |
| no CIMD (most providers today) | **register an app** once and set `ClientId` (+ `ClientSecret` for a confidential client) |
| several ASes | `ClientIds = new() { [issuer] = clientId }`; each id is used only with its issuer |

If neither is set, the call fails with *register an app with <issuer> and set OAuth.ClientId*. A pre-registered
id wins over CIMD.

## Mode 1: automatic (CLI, desktop)

`new LoopbackAuthorizer()` opens the system browser and listens on `http://127.0.0.1:<random>/callback`
(RFC 8252 §7.3). The call awaits the login, then returns the tool's result. In the example the in-process dev
server stands in for the browser.

<!-- from: examples/06-oauth-zero-config/Program.cs -->
```csharp
var store = new MemoryTokenStore(); // implement ITokenStore to persist tokens (keyed by resource)

// The user code. A real app leaves Authorizer null: new LoopbackAuthorizer() opens the system
// browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
var c = new McpClient(ts.Url + "/mcp")
{
    Auth = new OAuth
    {
        ClientId = "my-app",
        Store = store,
        Authorizer = new LoopbackAuthorizer { Open = auth.ApproveAsync },
    },
};

var res = await c.CallToolAsync("echo", new JsonObject { ["text"] = "hello after login" });
Console.WriteLine("echo: " + Mcp.Text(res));

// Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
// re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
res = await c.CallToolAsync("greet", [], (_, _) =>
    Task.FromResult(new JsonObject { ["action"] = "accept", ["content"] = new JsonObject { ["name"] = "Ada" } }));
Console.WriteLine("greet: " + Mcp.Text(res));
```

```text
echo: hello after login
greet: Hello, Ada!
browser logins: 2
token stored for this issuer: true
```

`greet` needs `mcp:greet`, so the second call is a **step-up**: `403 insufficient_scope` → a second authorization
for `mcp:read mcp:greet` → the paused call is sent once.

### Token store

`Store` takes any `ITokenStore` (`Load/Save/Delete` keyed by resource URL); null means in-memory. Share one to
avoid logging in again:

<!-- from: examples/06-oauth-zero-config/Program.cs -->
```csharp
// A second client sharing the token store needs no new login.
var c2 = new McpClient(ts.Url + "/mcp")
{
    Auth = new OAuth { ClientId = "my-app", Store = store, Authorizer = new LoopbackAuthorizer { Open = auth.ApproveAsync } },
};
await c2.CallToolAsync("echo", new JsonObject { ["text"] = "again" });
Console.WriteLine("browser logins: " + auth.Stats().Authorizations); // 1 login + 1 step-up

var tok = store.Load(ts.Url + "/mcp")!;
Console.WriteLine("token stored for this issuer: " + (tok.Issuer == auth.Issuer ? "true" : "false")); // tok.ToString() is redacted
```

After a restart the first request goes out without a token, to learn the issuer from the `401`; only then is
the stored token sent. `Token.ToString()` prints `Token{issuer=…, redacted}`.

## Mode 2: pause and resume (web apps, bots, job queues)

A server must never block a request on a login. `PauseAuthorizer` makes the call throw
`PendingAuthorizationException` whose `Pending` is `{Id, Kind: "authorization", Prompt, Url, ExpiresAt}`, the shape
of toolnexus SPEC §10 `Request`. Send the user to `Url`; when the AS redirects back, `ResumeAsync` returns the
**original call's** result. A pending authorization expires after 10 minutes.

### As one ASP.NET Core RequestDelegate

<!-- from: examples/07-oauth-web-pause-resume/Program.cs -->
```csharp
var c = new McpClient(mcpTS.Url + "/mcp")
{
    Auth = new OAuth { ClientId = "my-web-app", Authorizer = new PauseAuthorizer(app.Url + "/tool") },
};

// (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
// with ?code&state, WebHandler resumes, and the tool's text is written to the response.
tool = c.Auth.WebHandler(http =>
    c.CallToolAsync("echo", new JsonObject { ["text"] = http.Request.Query["text"].ToString() }));
```

### By hand

<!-- from: examples/07-oauth-web-pause-resume/Program.cs -->
```csharp
var c2 = new McpClient(mcpTS.Url + "/mcp")
{
    Auth = new OAuth { ClientId = "my-web-app", Authorizer = new PauseAuthorizer("http://127.0.0.1/callback") },
};
Pending p;
try
{
    await c2.CallToolAsync("echo", new JsonObject { ["text"] = "resumed by hand" });
    throw new Exception("expected a pending authorization");
}
catch (PendingAuthorizationException e) { p = e.Pending; }
Console.WriteLine($"(b) paused: {p.Kind} - {(p.Prompt != "" ? "true" : "false")} - has URL: {(p.Url != "" ? "true" : "false")}");

// The user logs in; you receive the callback URL (CaptureRedirectAsync stands in for the person).
var callbackUrl = await DevAuthServer.CaptureRedirectAsync(p.Url);
var res = await c2.Auth.ResumeAsync(p.Id, callbackUrl);
Console.WriteLine("(b) resumed: " + Mcp.Text(res));
```

```text
(a) WebHandler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand
```

The paused request is sent for the first time on `ResumeAsync` (the `401` came before dispatch). A pause inside an
MRTR round resumes that same round without asking the user again.

## Client options (`OAuth`)

| property | default | meaning |
|---|---|---|
| `Authorizer` | `new LoopbackAuthorizer()` | automatic, or `new PauseAuthorizer(redirect)` |
| `ClientId` / `ClientSecret` | | pre-registered client; a secret makes it confidential |
| `TokenAuthMethod` | `client_secret_basic` if a secret, else `none` | or `client_secret_post` |
| `ClientIds` | | issuer → client id |
| `ClientMetadataUrl` | | CIMD client id |
| `Store` | in-memory | `ITokenStore` |
| `OmitRefreshResource` | false | drop `resource` on refresh (Entra) |
| `Http` | 30 s timeout client | for metadata and token calls |
| `AllowHttp` | false | plain-HTTP AS for local dev; the spec requires HTTPS |

## Protecting your own server

One line: the issuer. The server then serves PRM at `/.well-known/oauth-protected-resource/mcp`, answers `401`
with `WWW-Authenticate`, `403 insufficient_scope` with the full scope set, and verifies RS256 JWTs against the
issuer's discovered JWKS (signature, `iss`, `aud` = this server's URL, `exp`).

<!-- from: examples/08-serve-protected/Program.cs -->
```csharp
var srv = new Server()
    .Tool("whoami", "Who is calling", (ctx, _) =>
        $"hello {ctx.Principal!.Subject} ({string.Join(" ", ctx.Principal.Scopes)})") // Subject, Scopes, Claims of the verified token
    .Tool("delete_all", "Dangerous admin tool", (_, _) => "deleted");
srv.Auth = new ServerAuth
{
    Issuer = auth.Issuer, // the whole required config
    Scopes = new()
    {
        ["*"] = ["mcp:read"],           // every request
        ["delete_all"] = ["mcp:admin"], // additionally required for this tool
    },
};
await using var ts = await HttpHost.StartAsync(srv); // or map srv.HandleAsync into your ASP.NET Core app
```

`Scopes["*"]` is required on every request and advertised as `scopes_supported`; `Scopes[tool]` is also required
for that `tools/call`. Scopes match exactly. The handler reads the caller from `ctx.Principal`
(`Subject`, `Scopes`, `Claims`).

```text
no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
```

### Verify hook: opaque tokens, API keys, introspection

`Verify` replaces the JWT verifier entirely (throw to reject); a JWT does not bypass it.

<!-- from: examples/08-serve-protected/Program.cs -->
```csharp
var keys = new Dictionary<string, Principal> // e.g. a lookup in your DB, or an RFC 7662 introspection call
{
    ["svc-key-123"] = new("billing-service", ["mcp:read"]),
};
var srv2 = new Server().Tool("whoami", "Who is calling", (ctx, _) => "hello " + ctx.Principal!.Subject);
srv2.Auth = new ServerAuth
{
    Issuer = auth.Issuer, // still advertised in the resource metadata
    Scopes = new() { ["*"] = ["mcp:read"] },
    Verify = (token, _) => keys.TryGetValue(token, out var p)
        ? Task.FromResult(p)
        : throw new UnauthorizedAccessException("unknown key"), // -> 401 invalid_token
};
await using var ts2 = await HttpHost.StartAsync(srv2);

// A service caller with a static key: a plain HttpClient with a header-adding handler.
var svc = new McpClient(ts2.Url + "/mcp") { Http = new HttpClient(new Bearer("svc-key-123")) };
res = await svc.CallToolAsync("whoami");
Console.WriteLine("verify hook: " + Mcp.Text(res));

var bad = new McpClient(ts2.Url + "/mcp") { Http = new HttpClient(new Bearer("wrong")) };
var rejected = false;
try { await bad.CallToolAsync("whoami"); } catch (McpException) { rejected = true; }
Console.WriteLine("wrong key rejected: " + (rejected ? "true" : "false"));
```

## DevAuthServer (dev and tests only)

`await DevAuthServer.StartAsync()` starts an OAuth 2.1 AS on `https://127.0.0.1:<port>/dev` that **approves
everyone as `dev-user`**. Its self-signed certificate is trusted by default clients in the same process.
`auth.ApproveAsync` is a headless browser for `new LoopbackAuthorizer { Open = auth.ApproveAsync }`.
**Never use it in production.**

<!-- from: examples/06-oauth-zero-config/Program.cs -->
```csharp
await using var auth = await DevAuthServer.StartAsync(); // DEV/TEST ONLY: auto-approves everyone as "dev-user"
auth.Clients["my-app"] = ["http://127.0.0.1/callback"]; // the AS admin registered "my-app"

var srv = Server.Fixture();
srv.Auth = new ServerAuth { Issuer = auth.Issuer,
    Scopes = new() { ["*"] = ["mcp:read"], ["greet"] = ["mcp:greet"] } }; // greet needs more
```
