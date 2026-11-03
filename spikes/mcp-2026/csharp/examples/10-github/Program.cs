// Example 10 — GitHub's hosted MCP server (speaks 2026-07-28) with OAuth.
//
// GitHub supports neither DCR nor CIMD, so you register an OAuth App once
// (github.com/settings/developers -> New OAuth App, callback URL http://127.0.0.1/callback,
// enable "expiring user tokens" if you want refresh) and pass its client id + secret.
//
//	sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- dotnet run --project examples/10-github
//
// Credentials are read from the environment and never printed; neither is any token.
// BROWSER=chrome-agent opens the consent page in the chrome-agent profile (used by the test);
// otherwise the system browser opens.
using System.Diagnostics;
using System.Text.Json.Nodes;
using Mcp2026;

var id = Environment.GetEnvironmentVariable("GITHUB_MCP_CLIENT_ID") ?? "";
var secret = Environment.GetEnvironmentVariable("GITHUB_MCP_CLIENT_SECRET") ?? "";
if (id == "" || secret == "")
{
    Console.WriteLine("skipped: run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ...");
    return;
}

var login = new LoopbackAuthorizer // system browser + http://127.0.0.1:<random>/callback
{
    Open = Environment.GetEnvironmentVariable("BROWSER") == "chrome-agent"
        ? async u => await Process.Start(new ProcessStartInfo("chrome-agent", ["goto", u]) { RedirectStandardOutput = true })!.WaitForExitAsync()
        : null,
};

var c = new McpClient("https://api.githubcopilot.com/mcp/")
{
    Auth = new OAuth
    {
        ClientId = id,
        ClientSecret = secret, // GitHub OAuth Apps are confidential clients (client_secret_basic)
        Authorizer = login,
    },
};

var tools = await c.ToolsAsync();
Console.WriteLine("tools listed: " + (tools.Count > 0 ? "true" : "false"));

var res = await c.CallToolAsync("get_me", []); // read-only: who am I
Console.WriteLine($"get_me: {res["resultType"]} | isError: {(J.True(res["isError"]) ? "true" : "false")}"); // content withheld on purpose
