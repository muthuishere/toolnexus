// Example 08 — serve MCP tools behind OAuth: one Issuer line, per-tool scopes, the caller's
// identity in the handler, and a custom Verify hook for opaque tokens.
//
// Run: dotnet run --project examples/08-serve-protected   (hermetic: everything runs on 127.0.0.1)
//
// With Server.Auth set the server: serves Protected Resource Metadata at
// /.well-known/oauth-protected-resource/mcp, answers 401 + WWW-Authenticate without a valid
// token, 403 insufficient_scope when a tool needs more, and verifies RS256 JWTs against the
// issuer's JWKS (signature, iss, aud = this server's URL, exp).
using System.Net.Http.Json;
using System.Text.Json.Nodes;
using Mcp2026;

await using var auth = await DevAuthServer.StartAsync(); // DEV/TEST ONLY. Production: your Keycloak / Entra / Auth0 issuer.
auth.Clients["my-app"] = ["http://127.0.0.1/callback"];

// ---- (1) the server: Issuer one-liner + per-tool scope + Principal ----
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
// ---- end server ----

// No token: 401 with WWW-Authenticate pointing at the resource metadata.
using var http = new HttpClient();
var req = new HttpRequestMessage(HttpMethod.Post, ts.Url + "/mcp")
{
    Content = new StringContent("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}""",
        System.Text.Encoding.UTF8, "application/json"),
};
req.Headers.Add("MCP-Protocol-Version", "2026-07-28");
req.Headers.Add("Mcp-Method", "tools/list");
var resp = await http.SendAsync(req);
Console.WriteLine($"no token: {(int)resp.StatusCode} | WWW-Authenticate has resource_metadata: " +
    (resp.Headers.WwwAuthenticate.ToString().Contains("resource_metadata=") ? "true" : "false"));

// The metadata a client discovers (RFC 9728): which AS to use and which scopes exist.
var prm = (await http.GetFromJsonAsync<JsonObject>(ts.Url + "/.well-known/oauth-protected-resource/mcp"))!;
Console.WriteLine($"metadata: issuer ok: {(J.Str(prm["authorization_servers"]![0]) == auth.Issuer ? "true" : "false")} | scopes: [{string.Join(" ", J.Strings(prm["scopes_supported"]))}]");

// A logged-in client (see example 06): whoami gets the principal; delete_all steps up.
var c = new McpClient(ts.Url + "/mcp")
{
    Auth = new OAuth { ClientId = "my-app", Authorizer = new LoopbackAuthorizer { Open = auth.ApproveAsync } },
};
var res = await c.CallToolAsync("whoami");
Console.WriteLine("whoami: " + Mcp.Text(res));
res = await c.CallToolAsync("delete_all");
Console.WriteLine("delete_all after step-up: " + Mcp.Text(res));
res = await c.CallToolAsync("whoami");
Console.WriteLine("whoami: " + Mcp.Text(res));

// ---- (2) custom Verify: opaque API keys / token introspection instead of JWT ----
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

// Bearer adds a static Authorization header to every request.
sealed class Bearer(string key) : DelegatingHandler(new HttpClientHandler())
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage r, CancellationToken ct)
    {
        r.Headers.Authorization = new("Bearer", key);
        return base.SendAsync(r, ct);
    }
}
