# Example 08 — serve MCP tools behind OAuth: one issuer line, per-tool scopes, the caller's
# identity in the handler, and a custom verify hook for opaque tokens.
#
# Run: mix run examples/08-serve-protected.exs   (hermetic: everything runs on 127.0.0.1)
#
# With :auth set the server: serves Protected Resource Metadata at
# /.well-known/oauth-protected-resource/mcp, answers 401 + WWW-Authenticate without a valid
# token, 403 insufficient_scope when a tool needs more, and verifies RS256 JWTs against the
# issuer's JWKS (signature, iss, aud = this server's URL, exp).
alias Mcp2026.{Client, DevAuthServer, HTTP, LoopbackAuthorizer, OAuth, Principal, Server, ServerAuth}

as = DevAuthServer.start()  # DEV/TEST ONLY. Production: your Keycloak / Entra / Auth0 issuer.
DevAuthServer.put_client(as, "my-app", ["http://127.0.0.1/callback"])

# ---- (1) the server: issuer one-liner + per-tool scope + principal ----
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
# ---- end server ----

# No token: 401 with WWW-Authenticate pointing at the resource metadata.
{:ok, resp} = HTTP.request(:post, ts.url,
  [{"content-type", "application/json"}, {"mcp-protocol-version", "2026-07-28"}, {"mcp-method", "tools/list"}],
  ~s({"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}))
IO.puts("no token: #{resp.status} | WWW-Authenticate has resource_metadata: " <>
  "#{String.contains?(HTTP.header(resp, "www-authenticate"), "resource_metadata=")}")

# The metadata a client discovers (RFC 9728): which AS to use and which scopes exist.
{:ok, prm} = HTTP.get_json(HTTP.default(), ts.base <> "/.well-known/oauth-protected-resource/mcp")
IO.puts("metadata: issuer ok: #{hd(prm["authorization_servers"]) == as.issuer} | scopes: [#{Enum.join(prm["scopes_supported"], " ")}]")

# A logged-in client (see example 06): whoami gets the principal; delete_all steps up.
c = Client.new(ts.url, auth: OAuth.new(client_id: "my-app",
  authorizer: %LoopbackAuthorizer{open: &DevAuthServer.approve(as, &1)}))
{:ok, res} = Client.call_tool(c, "whoami")
IO.puts("whoami: " <> Mcp2026.text(res))
{:ok, res} = Client.call_tool(c, "delete_all")
IO.puts("delete_all after step-up: " <> Mcp2026.text(res))
{:ok, res} = Client.call_tool(c, "whoami")
IO.puts("whoami: " <> Mcp2026.text(res))

# ---- (2) custom verify: opaque API keys / token introspection instead of JWT ----
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
