// Example 02 — connect to a local MCP server over stdio (spawn a process, talk on stdin/stdout).
//
// Run: dotnet run --project examples/02-connect-stdio
//
// stdio carries one JSON-RPC message per line. stderr is the server's log stream: it is passed
// through (or captured via the stderr argument) and never parsed. There are no HTTP headers and no
// OAuth over stdio: a stdio server takes its credentials from its own environment.
using System.Diagnostics;
using System.Text.Json.Nodes;
using Mcp2026;

// Demo harness: this same program is the stdio server when started with SERVE_STDIO=1.
// In real use the command is your server, e.g. new ProcessStartInfo("npx", ["-y", "some-mcp-server"]).
if (Environment.GetEnvironmentVariable("SERVE_STDIO") == "1")
{
    await StdioServer.ServeAsync(Server.Fixture(), Console.In, Console.Out);
    return;
}
var self = Environment.ProcessPath!.EndsWith("dotnet") ? [typeof(Program).Assembly.Location] : Array.Empty<string>();

// 1. Describe the process to spawn (env, args, working dir: plain ProcessStartInfo).
var psi = new ProcessStartInfo(Environment.ProcessPath!, self);
psi.Environment["SERVE_STDIO"] = "1";

// 2. Start it and get a client. Same API as the HTTP client: CallAsync, ToolsAsync, CallToolAsync.
await using var c = McpClient.StartStdio(psi); // disposing stops the server process

// 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
var tools = await c.ToolsAsync();
Console.WriteLine("tool count: " + tools.Count);

var res = await c.CallToolAsync("echo", new JsonObject { ["text"] = "hello over stdio" });
Console.WriteLine("echo: " + Mcp.Text(res));
