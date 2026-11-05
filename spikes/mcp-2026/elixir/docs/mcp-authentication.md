# MCP authentication

> **Elixir port of the Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API
> below is `spikes/mcp-2026/elixir` (modules `Mcp2026.*`). Every code block is copied verbatim from a script under
> `examples/` that `test/examples_test.exs` runs and checks; `test/docs_test.exs` fails if a block here stops
> matching its source.

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
7. Expired → `refresh_token` grant (one at a time per `OAuth`). `403 insufficient_scope` → step-up with the
   union of scopes.

A call is re-sent **only** after a `401`, or a `403 insufficient_scope` with `WWW-Authenticate`: the server
rejected it before running the tool. A lost connection, timeout or 5xx is returned as `{:error, _}` and never
retried, because the tool may already have run.

## Who the client is: no DCR

| your authorization server supports | set | example |
|---|---|---|
| Client ID Metadata Documents | `client_metadata_url: "https://app.example/client.json"` | Keycloak 26.8 with `cimd` + client policy |
| no CIMD (most providers today) | **register an app** once and set `client_id` (+ `client_secret`) | GitHub OAuth App, Entra, Keycloak client |
| several ASes | `client_ids: %{issuer => client_id}`; each id is used only with its issuer | |

If neither is set the call fails with *register an app with <issuer> and set OAuth client_id*. A pre-registered
id wins over CIMD.

## Mode 1: automatic (CLI, desktop)

`%LoopbackAuthorizer{}` opens the system browser and listens on `http://127.0.0.1:<random>/callback`. The call
blocks until the user has logged in, then returns the tool's result. In the example the dev server is the browser.

<!-- from: examples/06-oauth-zero-config.exs -->
```elixir
store = MemoryTokenStore.new()  # implement Mcp2026.TokenStore to persist tokens (keyed by resource)

# The user code. A real app leaves :authorizer unset: %LoopbackAuthorizer{} opens the system
# browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
approve = fn url -> DevAuthServer.approve(as, url) end
c = Client.new(ts.url, auth: OAuth.new(
  client_id: "my-app",
  store: store,
  authorizer: %LoopbackAuthorizer{open: approve}
))

{:ok, res} = Client.call_tool(c, "echo", %{"text" => "hello after login"})
IO.puts("echo: " <> Mcp2026.text(res))

# Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
# re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
{:ok, res} = Client.call_tool(c, "greet", %{}, fn _key, _req ->
  %{"action" => "accept", "content" => %{"name" => "Ada"}}
end)
IO.puts("greet: " <> Mcp2026.text(res))
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

`:store` takes any `Mcp2026.TokenStore` implementation (`load/save/delete` keyed by resource URL); default
in-memory. Share one to avoid logging in again:

<!-- from: examples/06-oauth-zero-config.exs -->
```elixir
# A second client sharing the token store needs no new login.
c2 = Client.new(ts.url, auth: OAuth.new(client_id: "my-app", store: store, authorizer: %LoopbackAuthorizer{open: approve}))
{:ok, _} = Client.call_tool(c2, "echo", %{"text" => "again"})
{logins, _refreshes} = DevAuthServer.stats(as)
IO.puts("browser logins: #{logins}")  # 1 login + 1 step-up

{:ok, tok} = TokenStore.load(store, ts.url)
IO.puts("token stored for this issuer: #{tok.issuer == as.issuer}")  # inspect(tok) is redacted
```

After a restart the first request goes out without a token, to learn the issuer from the `401`; only then is the
stored token sent. `%Mcp2026.Token{}` inspects and prints as redacted.

## Mode 2: pause and resume (web apps, bots, job queues)

`%PauseAuthorizer{redirect: url}` makes the call return `{:error, %PendingError{pending: %Pending{id, kind:
"authorization", prompt, url, expires_at}}}` — the shape of toolnexus SPEC §10 `Request`. Send the user to
`url`; when the AS redirects back, `OAuth.resume/3` returns the **original call's** result. Pending
authorizations expire after 10 minutes.

### As one Plug

<!-- from: examples/07-oauth-web-pause-resume.exs -->
```elixir
c = Client.new(mcp.url, auth: OAuth.new(client_id: "my-web-app", authorizer: %PauseAuthorizer{redirect: app <> "/tool"}))

# (a) One Plug: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
# with ?code&state, WebHandler resumes, and the tool's text is written to the response.
Bandit.start_link(ip: {127, 0, 0, 1}, port: port, startup_log: false,
  plug: {OAuth.WebHandler, oauth: c.auth, call: fn conn ->
    Client.call_tool(c, "echo", %{"text" => Plug.Conn.fetch_query_params(conn).query_params["text"]})
  end})
```

### By hand

<!-- from: examples/07-oauth-web-pause-resume.exs -->
```elixir
c2 = Client.new(mcp.url, auth: OAuth.new(client_id: "my-web-app", authorizer: %PauseAuthorizer{redirect: "http://127.0.0.1/callback"}))
p = Mcp2026.pending_of(Client.call_tool(c2, "echo", %{"text" => "resumed by hand"}))  # nil when the call did not pause
IO.puts("(b) paused: #{p.kind} - #{p.prompt != ""} - has URL: #{p.url != ""}")

# The user logs in; you receive the callback URL (here: the AS's redirect, not followed).
{:ok, %{status: 302} = r} = HTTP.request(:get, p.url, [], nil)
callback_url = HTTP.header(r, "location")
{:ok, res} = OAuth.resume(c2.auth, p.id, callback_url)
IO.puts("(b) resumed: " <> Mcp2026.text(res))
```

```text
(a) WebHandler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand
```

The paused request is sent for the first time on resume (the `401` came before dispatch). A pause inside an
MRTR round resumes that same round without asking the user again.

## Client options (`OAuth.new/1`)

| option | default | meaning |
|---|---|---|
| `:authorizer` | `%LoopbackAuthorizer{}` | automatic, or `%PauseAuthorizer{redirect: url}`; any struct whose module has `begin/1` |
| `:client_id` / `:client_secret` | | pre-registered client; a secret makes it confidential |
| `:token_auth_method` | `client_secret_basic` if a secret, else `none` | or `client_secret_post` |
| `:client_ids` | | issuer → client id |
| `:client_metadata_url` | | CIMD client id |
| `:store` | in-memory | `Mcp2026.TokenStore` |
| `:omit_refresh_resource` | false | drop `resource` on refresh (Entra, see Providers) |
| `:http` | `Mcp2026.HTTP.request/4` (30 s) | request function for metadata and token calls |
| `:allow_http` | false | plain-HTTP AS for local dev; the spec requires HTTPS |

## Protecting your own server

One line: the issuer. The server then serves PRM at `/.well-known/oauth-protected-resource/mcp`, answers `401`
with `WWW-Authenticate`, `403 insufficient_scope` with the full scope set for the operation, and verifies RS256
JWTs against the issuer's discovered JWKS (signature, `iss`, `aud` = this server's URL, `exp`).

<!-- from: examples/08-serve-protected.exs -->
```elixir
server =
  Server.new()
  |> Server.tool("whoami", "Who is calling", fn _args, ctx ->
    p = ctx.principal  # subject, scopes, claims of the verified token
    "hello #{p.subject} (#{Enum.join(p.scopes, " ")})"
  end)
  |> Server.tool("delete_all", "Dangerous admin tool", fn _args, _ctx -> "deleted" end)

server = %{server | auth: ServerAuth.new(
  issuer: as.issuer,  # the whole required config
  scopes: %{
    "*" => ["mcp:read"],  # every request
    "delete_all" => ["mcp:admin"]  # additionally required for this tool
  }
)}
ts = Server.serve(server)  # production: Server.serve(server, 8080) or plug it into your router
```

`scopes["*"]` is required on every request and advertised as `scopes_supported`; `scopes[tool]` is also required
for that `tools/call`. Scopes match exactly. `:audience` defaults to `scheme://host/mcp` of the request. The
handler reads the caller as `ctx.principal` (`%Mcp2026.Principal{subject, scopes, claims}`).

```text
no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
```

### Verify hook: opaque tokens, API keys, introspection

`:verify` replaces the JWT verifier entirely; a JWT does not bypass it.

<!-- from: examples/08-serve-protected.exs -->
```elixir
keys = %{  # e.g. a lookup in your DB, or an RFC 7662 introspection call
  "svc-key-123" => %Principal{subject: "billing-service", scopes: ["mcp:read"]}
}
server2 = Server.new() |> Server.tool("whoami", "Who is calling", fn _args, ctx -> "hello " <> ctx.principal.subject end)
server2 = %{server2 | auth: ServerAuth.new(
  issuer: as.issuer,  # still advertised in the resource metadata
  scopes: %{"*" => ["mcp:read"]},
  verify: fn token ->
    case Map.fetch(keys, token) do
      {:ok, p} -> {:ok, p}
      :error -> {:error, "unknown key"}  # -> 401 invalid_token
    end
  end
)}
ts2 = Server.serve(server2)

# A service caller with a static key: wrap the request function to add the header.
bearer = fn key -> fn m, u, h, b -> HTTP.request(m, u, [{"authorization", "Bearer " <> key} | h], b) end end
svc = Client.new(ts2.url, http: bearer.("svc-key-123"))
{:ok, res} = Client.call_tool(svc, "whoami")
IO.puts("verify hook: " <> Mcp2026.text(res))

bad = Client.new(ts2.url, http: bearer.("wrong"))
IO.puts("wrong key rejected: #{match?({:error, _}, Client.call_tool(bad, "whoami"))}")
```

## DevAuthServer (dev and tests only)

`Mcp2026.DevAuthServer.start/1` starts an OAuth 2.1 AS on `https://127.0.0.1:<port>/dev` that **approves
everyone as `dev-user`** with no login: RFC 8414 metadata, JWKS, code + PKCE S256, `resource`, `iss`,
refresh-token rotation, pre-registered (`put_client/3`, `:secrets`) and CIMD clients. Its self-signed
certificate is trusted by `Mcp2026.HTTP` in the same VM, so `ServerAuth.new(issuer: as.issuer)` needs no TLS
wiring. `DevAuthServer.approve/2` is a headless browser. **Never use it in production.**

<!-- from: examples/06-oauth-zero-config.exs -->
```elixir
as = DevAuthServer.start()  # DEV/TEST ONLY: auto-approves everyone as "dev-user"
DevAuthServer.put_client(as, "my-app", ["http://127.0.0.1/callback"])  # the AS admin registered "my-app"
```
