// Example 07 — a web app that calls a protected MCP server for its users (pause/resume mode).
//
// Run: dotnet run --project examples/07-oauth-web-pause-resume   (hermetic: everything runs on 127.0.0.1)
//
// A server-side app must never block a request waiting for a login. With PauseAuthorizer the
// call throws PendingAuthorizationException instead; its Pending is {Id, Kind:"authorization",
// Prompt, Url, ExpiresAt}, the shape of toolnexus SPEC §10 Request. The app sends the browser to
// Pending.Url; the authorization server redirects back; the app resumes and gets the ORIGINAL
// call's result.
//
// WebHandler packages that whole dance as one ASP.NET Core RequestDelegate. The manual version is shown too.
using System.Text.Json.Nodes;
using Mcp2026;
using Microsoft.AspNetCore.Http;

// ---- demo harness: dev authorization server + protected MCP server ----
await using var auth = await DevAuthServer.StartAsync(); // DEV/TEST ONLY
auth.Clients["my-web-app"] = ["http://127.0.0.1/tool", "http://127.0.0.1/callback"];
var srv = Server.Fixture();
srv.Auth = new ServerAuth { Issuer = auth.Issuer };
await using var mcpTS = await HttpHost.StartAsync(srv);
// ---- end harness ----

RequestDelegate? tool = null;
await using var app = await HttpHost.StartAsync(http => http.Request.Path == "/tool" ? tool!(http) : Task.CompletedTask);

// ---- the web app ----
var c = new McpClient(mcpTS.Url + "/mcp")
{
    Auth = new OAuth { ClientId = "my-web-app", Authorizer = new PauseAuthorizer(app.Url + "/tool") },
};

// (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
// with ?code&state, WebHandler resumes, and the tool's text is written to the response.
tool = c.Auth.WebHandler(http =>
    c.CallToolAsync("echo", new JsonObject { ["text"] = http.Request.Query["text"].ToString() }));
// ---- end web app ----

// A person's browser opens /tool?text=... (BrowseAsync follows every redirect and trusts the dev TLS).
var (status, body) = await DevAuthServer.BrowseAsync(app.Url + "/tool?text=resumed+after+consent");
Console.WriteLine($"(a) WebHandler: {status} {body}");

// (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
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
