// Example 05 — lazy tool search: give the model 2 tools instead of 100.
//
// Run: dotnet run --project examples/05-tool-search
//
// With ToolSearch = true, ToolsAsync() returns two meta-tools, search_tools(query, limit?) and
// call_tool(name, arguments). The model searches (BM25 over tool names and descriptions), then
// calls what it found. InvokeAsync runs whatever tool call the model emits.
using System.Text.Json.Nodes;
using Mcp2026;

// A server with 100 tools: 10 services x 10 verbs.
var srv = new Server();
foreach (var svc in new[] { "github", "slack", "jira", "gmail", "calendar", "drive", "stripe", "postgres", "s3", "notion" })
    foreach (var verb in new[] { "create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment" })
    {
        var name = svc + "_" + verb;
        srv.Tool(name, $"{verb} an item in {svc}.", (_, args) => name + " ran with " + J.Str(args["text"]));
    }
await using var ts = await HttpHost.StartAsync(srv);

var eager = new McpClient(ts.Url + "/mcp");
var all = await eager.ToolsAsync();

// 1. Turn on tool search; Description tells the model what this server is for.
var c = new McpClient(ts.Url + "/mcp") { ToolSearch = true, Description = "SaaS workspace (GitHub, Slack, Jira, ...)" };
var meta = await c.ToolsAsync();
Console.WriteLine($"eager: {all.Count} tools, {Size(all)} bytes of schema");
Console.WriteLine($"lazy:  {meta.Count} tools, {Size(meta)} bytes of schema");

// 2. The model calls search_tools ...
var hits = await c.SearchToolsAsync("send a slack message create", 3);
Console.WriteLine("top hit: " + hits[0]["name"]);

// 3. ... then call_tool with the name it found. InvokeAsync routes both meta-tools.
var res = await c.InvokeAsync("call_tool", new JsonObject { ["name"] = hits[0]["name"]!.DeepClone(), ["arguments"] = new JsonObject { ["text"] = "hi team" } });
Console.WriteLine("result: " + Mcp.Text(res));

static int Size(List<JsonObject> tools) => J.Serialize(new JsonArray(tools.Select(t => t.DeepClone()).ToArray())).Length;
