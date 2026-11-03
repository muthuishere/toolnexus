# Example 09 — a real authorization server: Keycloak 26.8.1 protecting an MCP server.
#
# Start Keycloak first (realm "toolnexus", user alice / alice-test-pw, client "toolnexus-mcp"):
#
#     cd ../golang/keycloak && docker compose up -d && cd -
#     KEYCLOAK=1 mix run examples/09-keycloak.exs                  # logs in through your system browser
#     KEYCLOAK=1 HEADLESS_LOGIN=1 mix run examples/09-keycloak.exs # scripted login (used by the test)
#
# The MCP server listens on 127.0.0.1:7199 because the realm's audience mapper puts
# aud = http://127.0.0.1:7199/mcp into tokens; the ServerAuth :audience must equal it.
alias Mcp2026.{Client, LoopbackAuthorizer, OAuth, Server, ServerAuth}

issuer = "http://localhost:8180/realms/toolnexus"  # exactly the `iss` Keycloak puts in tokens
resource = "http://127.0.0.1:7199/mcp"

if System.get_env("KEYCLOAK") != "1" do
  IO.puts("skipped: set KEYCLOAK=1 with keycloak/docker-compose.yml running")
  System.halt(0)
end

# ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
server =
  Server.fixture()
  |> Server.tool("whoami", "Who is calling", fn _args, ctx ->
    p = ctx.principal
    # subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
    "client=#{p.claims["azp"]} scopes=#{Enum.join(p.scopes, " ")} subject-set=#{p.subject != ""}"
  end)

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

# greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
{:ok, res} = Client.call_tool(c, "greet", %{}, fn _key, _req ->
  %{"action" => "accept", "content" => %{"name" => "Alice"}}
end)
IO.puts("greet: " <> Mcp2026.text(res))
