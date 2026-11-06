# elixir — in-house MCP 2026-07-28 spike

Port of `../golang` (the reference). No MCP SDK. OTP `:httpc` for client HTTP, Elixir's built-in `JSON`,
`:public_key`/`:crypto` for RS256 + PKCE + the dev TLS cert. The only deps are `bandit` + `plug` (HTTP server).
Elixir ≥ 1.18 (built-in `JSON`); verified on 1.20.2 / OTP 29.

## Commands

```sh
./run.sh server                              # 127.0.0.1:${PORT:-7106}/mcp
./run.sh client http://127.0.0.1:7103/mcp    # the 8 cross-port checks against ANY port's server
./run.sh selftest                            # own client vs own server
mix test                                     # hermetic suite (35 tests: unit, auth, stdio, examples 01-08, docs)
KEYCLOAK=1 mix test --include keycloak test/keycloak_test.exs test/examples_test.exs   # needs ../golang/keycloak up
sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- mix test --include github --only github test/examples_test.exs
mix run examples/01-connect-http.exs         # 10 user-story examples; docs/ copies code from them
```

## Layout / LOC (`wc -l`)

| part | file | LOC |
|---|---|---|
| constants, errors, Pending | lib/mcp2026.ex | 54 |
| HTTP client (:httpc, dev-cert trust) | lib/mcp2026/http.ex | 89 |
| server (Plug) + fixture tools | lib/mcp2026/server.ex | 299 |
| client + MRTR + lazy tool search (BM25) | lib/mcp2026/client.ex | 334 |
| stdio client (GenServer + Port) + stdio server | lib/mcp2026/stdio.ex | 175 |
| skills: load, URI safety, download | lib/mcp2026/skills.ex | 140 |
| client OAuth (+ authorizers, token store, WebHandler) | lib/mcp2026/oauth.ex | 585 |
| server auth (PRM, 401/403, JWT/JWKS, verify hook) | lib/mcp2026/server_auth.ex | 142 |
| DevAuthServer (dev/test only) | lib/mcp2026/dev_auth_server.ex | 247 |
| checks + CLI | lib/mcp2026/checks.ex | 120 |
| **library total** | | **2185** (Go: 2382) |
| tests | test/*.exs | 578 |
| examples (10 + Keycloak headless harness) | examples/ | 497 |

## Proof (2026-11-04)

- `mix test`: 35 passed (7 gated excluded). Examples 01-08 match the **Go outputs byte for byte** (including
  `9721` / `519` bytes of tool schema in 05).
- Interop: our client → Go server (7103): 8/8 PASS; Go client → our server (7106): 8/8 PASS.
- Keycloak 26.8.1 (shared container): example 09 + 8 checks, refresh with/without resource, pause/resume,
  step-up, wrong-audience rejection — all pass.
- GitHub hosted MCP live (example 10, direct `mix run`): `tools listed: true`, `get_me: complete | isError: false`.
  A later run of the gated ExUnit test hung on GitHub's "Reauthorization required — unusually high number of
  requests" consent page (7 ports hitting the same OAuth App); not clicked, per the brief: **needs_human**.

## API in one screen

```elixir
c = Mcp2026.Client.new(url, auth: Mcp2026.OAuth.new(client_id: "my-app"))   # Loopback authorizer by default
{:ok, r} = Mcp2026.Client.call_tool(c, "greet", %{}, fn _key, _req -> %{"action" => "accept", "content" => %{"name" => "Ada"}} end)
case Mcp2026.Client.call_tool(c, "echo", %{"text" => "x"}) do
  {:error, %Mcp2026.PendingError{pending: p}} -> Mcp2026.OAuth.resume(c.auth, p.id, callback_url)  # pause mode
  other -> other
end
server = Mcp2026.Server.new() |> Mcp2026.Server.tool("whoami", "", fn _args, ctx -> ctx.principal.subject end)
Mcp2026.Server.serve(%{server | auth: Mcp2026.ServerAuth.new(issuer: iss, scopes: %{"*" => ["mcp:read"]})}, 8080)
```

## Deviations from Go, and why

1. **Results are `{:ok, _} | {:error, _}`**, errors are `%Mcp2026.RpcError{}`, `%Mcp2026.PendingError{}` or a
   message string — idiomatic Elixir instead of Go's `(obj, error)`.
2. **Tool handlers are `fn args, ctx -> ... end`** with `ctx.principal` (Go: `PrincipalFrom(ctx)`); they return a
   string, a full result map, `{:error, msg}` or any JSON value.
3. **Mutable state lives in small Agents** (client id counter + tools cache, OAuth issuers/flows, JWKS cache,
   memory token store, dev AS). Go's `authMu` single-flight is `:global.trans` on a per-OAuth lock id.
4. **Custom HTTP = a request function** `(method, url, headers, body)`, not an `http.Client`/transport.
   Example 08's static-key caller wraps `Mcp2026.HTTP.request/4`.
5. **stdio server stderr is inherited, not capturable.** Erlang ports cannot split stderr from stdout; Go lets
   you set `cmd.Stderr`. It is still never parsed. Kill-after-2s on close uses the OS pid.
6. **Example 07's web app mounts `OAuth.WebHandler` as the whole Bandit plug** (any path), where Go mounts it on
   `/tool` of a mux; the redirect URI still uses `/tool`.
7. **Examples are `.exs` scripts** (`mix run examples/NN-name.exs`); example 02 re-runs itself via `mix run`
   as the stdio server (slower start than Go's self-exec, ~1 s).
8. **Keycloak headless login keeps its own cookie jar**: Keycloak sets `Secure` cookies on `http://localhost`
   and `:httpc`'s jar refuses to send those back (Go's `cookiejar` does). Test harness only.
9. Frontmatter is the same flat `key: value` parser as Go (for parity), although the repo's `elixir/` port has
   `yaml_elixir`.

Not ported: Go's stress/fuzz harness (`./run.sh stress|fuzz`) and the Keycloak CIMD / resource-indicator probe
tests. Untested: Entra (simulated only, like Go).
