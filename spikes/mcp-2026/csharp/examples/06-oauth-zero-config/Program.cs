// Example 06 — call an OAuth-protected MCP server with zero auth configuration (auto mode).
//
// Run: dotnet run --project examples/06-oauth-zero-config   (hermetic: everything runs on 127.0.0.1)
//
// The client learns everything from the server's 401: Protected Resource Metadata -> the
// authorization server's metadata -> PKCE S256 login with `resource` -> token -> retry.
// You only say WHO the client is: a pre-registered ClientId, or a CIMD ClientMetadataUrl.
// There is no Dynamic Client Registration.
using System.Text.Json.Nodes;
using Mcp2026;

// ---- demo harness: an authorization server + a protected MCP server, both in-process ----
await using var auth = await DevAuthServer.StartAsync(); // DEV/TEST ONLY: auto-approves everyone as "dev-user"
auth.Clients["my-app"] = ["http://127.0.0.1/callback"]; // the AS admin registered "my-app"

var srv = Server.Fixture();
srv.Auth = new ServerAuth { Issuer = auth.Issuer,
    Scopes = new() { ["*"] = ["mcp:read"], ["greet"] = ["mcp:greet"] } }; // greet needs more
await using var ts = await HttpHost.StartAsync(srv);
// ---- end harness ----

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

// A second client sharing the token store needs no new login.
var c2 = new McpClient(ts.Url + "/mcp")
{
    Auth = new OAuth { ClientId = "my-app", Store = store, Authorizer = new LoopbackAuthorizer { Open = auth.ApproveAsync } },
};
await c2.CallToolAsync("echo", new JsonObject { ["text"] = "again" });
Console.WriteLine("browser logins: " + auth.Stats().Authorizations); // 1 login + 1 step-up

var tok = store.Load(ts.Url + "/mcp")!;
Console.WriteLine("token stored for this issuer: " + (tok.Issuer == auth.Issuer ? "true" : "false")); // tok.ToString() is redacted
