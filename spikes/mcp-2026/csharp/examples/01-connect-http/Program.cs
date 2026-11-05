// Example 01 — connect to an MCP server over Streamable HTTP and call a tool.
//
// Run: dotnet run --project examples/01-connect-http
//
// The MCP 2026-07-28 protocol is stateless: there is no initialize handshake and no session.
// Every request carries its own protocol version, so "connecting" is just new McpClient(url).
using System.Text.Json.Nodes;
using Mcp2026;

// A local MCP server to talk to (the spike fixture: tools `echo` and `greet`, skill `hello-world`).
// In real use this is someone else's server, e.g. "https://api.example.com/mcp".
await using var ts = await HttpHost.StartAsync(Server.Fixture());

// 1. Point a client at the server's MCP endpoint. No handshake happens here.
var c = new McpClient(ts.Url + "/mcp");

// 2. Optional: server/discover tells you which versions and capabilities the server has.
var info = await c.CallAsync("server/discover");
Console.WriteLine($"supported versions: [{string.Join(" ", J.Strings(info["supportedVersions"]))}]");

// 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
var tools = await c.ToolsAsync();
Console.WriteLine("tools: " + string.Join(", ", tools.Select(t => J.Str(t["name"]))));

// 4. Call a tool. Mcp.Text returns the first text content of the result.
var res = await c.CallToolAsync("echo", new JsonObject { ["text"] = "hello over HTTP" });
Console.WriteLine("echo: " + Mcp.Text(res));
