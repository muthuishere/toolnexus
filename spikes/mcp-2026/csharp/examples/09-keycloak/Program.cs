// Example 09 — a real authorization server: Keycloak 26.8.1 protecting an MCP server.
//
// Start Keycloak first (realm "toolnexus", user alice / alice-test-pw, client "toolnexus-mcp"):
//
//	cd ../golang/keycloak && docker compose up -d && cd -
//	KEYCLOAK=1 dotnet run --project examples/09-keycloak                  # logs in through your system browser
//	KEYCLOAK=1 HEADLESS_LOGIN=1 dotnet run --project examples/09-keycloak # scripted login (used by the test)
//
// The MCP server listens on 127.0.0.1:7199 because the realm's audience mapper puts
// aud = http://127.0.0.1:7199/mcp into tokens; ServerAuth.Audience must equal it.
using System.Text.Json.Nodes;
using Mcp2026;

const string issuer = "http://localhost:8180/realms/toolnexus"; // exactly the `iss` Keycloak puts in tokens
const string resource = "http://127.0.0.1:7199/mcp";

if (Environment.GetEnvironmentVariable("KEYCLOAK") != "1")
{
    Console.WriteLine("skipped: set KEYCLOAK=1 with keycloak/docker-compose.yml running");
    return;
}

// ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
var srv = Server.Fixture().Tool("whoami", "Who is calling", (ctx, _) =>
    // Subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
    $"client={ctx.Principal!.Claims?["azp"]} scopes={string.Join(" ", ctx.Principal.Scopes)} subject-set={(ctx.Principal.Subject != "" ? "true" : "false")}");
srv.Auth = new ServerAuth
{
    Issuer = issuer,
    Audience = resource, // = the audience mapper's included.custom.audience
    Scopes = new() { ["*"] = ["mcp:tools"], ["greet"] = ["mcp:greet"] },
};
await using var host = await HttpHost.StartAsync(srv, port: 7199);

// ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
var open = new LoopbackAuthorizer // Open = null: system browser
{
    Open = Environment.GetEnvironmentVariable("HEADLESS_LOGIN") == "1" ? HeadlessLogin.Open("alice", "alice-test-pw") : null, // throwaway local test user
};
var c = new McpClient(resource)
{
    Auth = new OAuth
    {
        ClientIds = new() { [issuer] = "toolnexus-mcp" }, // client id bound to THIS issuer
        AllowHttp = true,                                  // dev Keycloak is plain HTTP; never in production
        Authorizer = open,
    },
};

var res = await c.CallToolAsync("whoami");
Console.WriteLine("whoami: " + Mcp.Text(res));

// greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
res = await c.CallToolAsync("greet", [], (_, _) =>
    Task.FromResult(new JsonObject { ["action"] = "accept", ["content"] = new JsonObject { ["name"] = "Alice" } }));
Console.WriteLine("greet: " + Mcp.Text(res));
