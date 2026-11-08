# MCP authentication: provider notes

> **Elixir port of the Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API
> below is `spikes/mcp-2026/elixir` (modules `Mcp2026.*`). Every code block is copied verbatim from a script under
> `examples/` that `test/examples_test.exs` runs and checks; `test/docs_test.exs` fails if a block here stops
> matching its source.

| provider | client identity | tested (Elixir) | status |
|---|---|---|---|
| Keycloak 26.8.1 | pre-registered public client | live, docker compose | works |
| GitHub hosted MCP (`api.githubcopilot.com/mcp/`) | OAuth App (client id + secret); no DCR, no CIMD | live | works |
| Microsoft Entra ID | app registration | **UNTESTED** (simulated only) | see note |

## Keycloak

Runnable: `../golang/keycloak/docker-compose.yml` (realm `toolnexus`, client `toolnexus-mcp`, throwaway user
`alice`), example `examples/09-keycloak.exs`.

```sh
cd ../golang/keycloak && docker compose up -d && cd -
KEYCLOAK=1 HEADLESS_LOGIN=1 mix run examples/09-keycloak.exs
KEYCLOAK=1 mix test --include keycloak test/keycloak_test.exs test/examples_test.exs
```

<!-- from: examples/09-keycloak.exs -->
```elixir
server = %{server | auth: ServerAuth.new(
  issuer: issuer,
  audience: resource,  # = the audience mapper's included.custom.audience
  scopes: %{"*" => ["mcp:tools"], "greet" => ["mcp:greet"]}
)}
Server.serve(server, 7199)

# ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
open =
  if System.get_env("HEADLESS_LOGIN") == "1" do
    Code.require_file("support/keycloak_headless.exs", __DIR__)
    KeycloakHeadless.opener("alice", "alice-test-pw")  # throwaway local test user
  end  # nil = system browser

c = Client.new(resource, auth: OAuth.new(
  client_ids: %{issuer => "toolnexus-mcp"},  # client id bound to THIS issuer
  allow_http: true,  # dev Keycloak is plain HTTP; never in production
  authorizer: %LoopbackAuthorizer{open: open}
))

{:ok, res} = Client.call_tool(c, "whoami")
IO.puts("whoami: " <> Mcp2026.text(res))
```

```text
whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true
greet: Hello, Alice!
```

Setup checklist (public client + PKCE S256 + redirect `http://127.0.0.1/*`, one client scope per permission,
an `oidc-audience-mapper` whose audience equals `:audience`, re-declare `basic` for `sub`): see the Go page —
the realm file is shared. Elixir-specific: Keycloak marks its login cookies `Secure` even on
`http://localhost`, so `:httpc`'s cookie jar will not send them back; the headless test harness
(`examples/support/keycloak_headless.exs`) keeps its own jar. Real browsers are unaffected.

## GitHub

GitHub's hosted MCP server speaks 2026-07-28. Its AS supports neither DCR nor CIMD, so register an **OAuth App**
(callback URL `http://127.0.0.1/callback`; GitHub ignores the port on loopback).

<!-- from: examples/10-github.exs -->
```elixir
c = Client.new("https://api.githubcopilot.com/mcp/", auth: OAuth.new(
  client_id: id,
  client_secret: secret,  # GitHub OAuth Apps are confidential clients (client_secret_basic)
  authorizer: login
))

{:ok, tools} = Client.tools(c)
IO.puts("tools listed: #{tools != []}")

{:ok, res} = Client.call_tool(c, "get_me", %{})  # read-only: who am I
IO.puts("get_me: #{res["resultType"]} | isError: #{res["isError"] == true}")  # content withheld on purpose
```

```sh
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- mix run examples/10-github.exs
```

Quirks handled without configuration: the token endpoint answers form-encoded without `Accept:
application/json` (we send it and parse both); `serverInfo` arrives in result `_meta`; `isError` is omitted on
success (missing = false).

## Microsoft Entra ID — UNTESTED

Entra rejects `resource` on the `refresh_token` grant (python-sdk #2578), though the MCP spec says it MUST be
sent. Set `omit_refresh_resource: true`; authorize and code exchange still send `resource`. Only the simulation
(`DevAuthServer.set(as, :reject_refresh_resource, true)`, `test/auth_test.exs`) covers it.
