// usage: dotnet run -- npx -y @agentclientprotocol/claude-agent-acp@0.100.0   (dotnet add package Toolnexus)
using Toolnexus;
using Toolnexus.Acp;
using static Toolnexus.LlmClient;

var add = NativeTool.Of("add", "Add two numbers.",
    new Dictionary<string, object?>
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["a"] = new { type = "number" }, ["b"] = new { type = "number" } },
        ["required"] = new[] { "a", "b" },
    },
    args =>
    {
        double a = Convert.ToDouble(args["a"]?.ToString()), b = Convert.ToDouble(args["b"]?.ToString());
        Console.WriteLine($"\n[tool] add({a}, {b})");
        return $"{a + b}";
    });

// 1. Serve YOUR toolkit on loopback, behind a fresh bearer token.
await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = [add] });
await using var server = toolkit.AsMcpServer();

// 2. Start the agent; McpServers is sent to session/new exactly as written.
using var agent = await AcpClient.StartAsync(new AcpStartOptions
{
    Command = args[0], Arguments = args[1..],
    McpServers = [new Dictionary<string, object?>
    {
        ["type"] = "http", ["name"] = "toolnexus", ["url"] = server.Url,
        ["headers"] = new[] { new Dictionary<string, object?> { ["name"] = "Authorization", ["value"] = "Bearer " + server.Token } },
    }],
    PromptTimeout = TimeSpan.FromMinutes(5), // one turn of a real agent can take minutes
});

// 3. The agent runs the tools itself (over MCP), so the client's own toolkit is empty.
var client = InProcess.CreateClient(new InProcess.Options { Model = args[0], GenerateStream = agent.GenerateStreamAsync });
await using var empty = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
await client.StreamAsync("Use the add tool to add 17 and 25. Reply with just the sum.", empty, ev =>
{
    if (ev.Type == StreamKind.Text) Console.Write(ev.Delta);                 // live text as the agent writes it
    else if (ev.Type == StreamKind.ToolCall && ev.Via == "acp") Console.WriteLine($"\n[agent tool] {ev.Name}");
    else if (ev.Type == StreamKind.Done) Console.WriteLine($"\nfinal: {ev.Result!.Text}");
});
// `using` disposes the agent (its process) and then the server.
