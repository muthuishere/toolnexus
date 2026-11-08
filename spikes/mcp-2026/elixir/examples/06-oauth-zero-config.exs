# Example 06 — call an OAuth-protected MCP server with zero auth configuration (auto mode).
#
# Run: mix run examples/06-oauth-zero-config.exs   (hermetic: everything runs on 127.0.0.1)
#
# The client learns everything from the server's 401: Protected Resource Metadata -> the
# authorization server's metadata -> PKCE S256 login with `resource` -> token -> retry.
# You only say WHO the client is: a pre-registered client_id, or a CIMD client_metadata_url.
# There is no Dynamic Client Registration.
alias Mcp2026.{Client, DevAuthServer, LoopbackAuthorizer, MemoryTokenStore, OAuth, Server, ServerAuth, TokenStore}

# ---- demo harness: an authorization server + a protected MCP server, both in-process ----
as = DevAuthServer.start()  # DEV/TEST ONLY: auto-approves everyone as "dev-user"
DevAuthServer.put_client(as, "my-app", ["http://127.0.0.1/callback"])  # the AS admin registered "my-app"

srv = %{Server.fixture() | auth: ServerAuth.new(issuer: as.issuer,
  scopes: %{"*" => ["mcp:read"], "greet" => ["mcp:greet"]})}  # greet needs more
ts = Server.serve(srv)
# ---- end harness ----

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

# A second client sharing the token store needs no new login.
c2 = Client.new(ts.url, auth: OAuth.new(client_id: "my-app", store: store, authorizer: %LoopbackAuthorizer{open: approve}))
{:ok, _} = Client.call_tool(c2, "echo", %{"text" => "again"})
{logins, _refreshes} = DevAuthServer.stats(as)
IO.puts("browser logins: #{logins}")  # 1 login + 1 step-up

{:ok, tok} = TokenStore.load(store, ts.url)
IO.puts("token stored for this issuer: #{tok.issuer == as.issuer}")  # inspect(tok) is redacted
