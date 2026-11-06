// dotnet new console && dotnet add package Toolnexus && dotnet run   (this file as Program.cs)
using System.Net.Http.Headers;
using System.Text;
using Toolnexus;

var add = NativeTool.Of("add", "Add two numbers.",
    new Dictionary<string, object?>
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["a"] = new { type = "number" }, ["b"] = new { type = "number" } },
        ["required"] = new[] { "a", "b" },
    },
    args => $"{Convert.ToDouble(args["a"]?.ToString()) + Convert.ToDouble(args["b"]?.ToString())}");

// Any toolkit (MCP servers, skills, native, HTTP, builtins) can be served to a local agent.
await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = [add] });
await using var server = toolkit.AsMcpServer(); // an MCPServeConfig { Tools = ... } narrows what is exposed
Console.WriteLine($"MCP endpoint: {server.Url}"); // http://127.0.0.1:<port>/mcp, POST only

// Every request needs the bearer token; this is the entry an ACP agent takes in session/new.
var entry = new Dictionary<string, object?>
{
    ["type"] = "http", ["name"] = "toolnexus", ["url"] = server.Url,
    ["headers"] = new[] { new Dictionary<string, object?> { ["name"] = "Authorization", ["value"] = "Bearer " + server.Token } },
};
using var http = new HttpClient();
var req = new HttpRequestMessage(HttpMethod.Post, (string)entry["url"]!)
{
    Content = new StringContent("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""", Encoding.UTF8, "application/json"),
};
req.Headers.Accept.ParseAdd("application/json");
req.Headers.Accept.ParseAdd("text/event-stream");
req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", server.Token);
var res = await http.SendAsync(req);
Console.WriteLine($"tools/list: {(int)res.StatusCode}");
