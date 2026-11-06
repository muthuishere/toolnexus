// usage: dotnet run -- npx -y @agentclientprotocol/claude-agent-acp@0.100.0   (dotnet add package Toolnexus)
using System.Text.Json;
using Toolnexus;
using Toolnexus.Acp;

var add = NativeTool.Of("add", "Add two numbers.",
    new Dictionary<string, object?>
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?> { ["a"] = new { type = "number" }, ["b"] = new { type = "number" } },
        ["required"] = new[] { "a", "b" },
    },
    args => $"{Convert.ToDouble(args["a"]?.ToString()) + Convert.ToDouble(args["b"]?.ToString())}");
await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = [add] });
await using var server = toolkit.AsMcpServer();

// Permission policy: allow tool calls whose title names one of your tools, reject the rest.
// Return an optionId to pick it; return null to answer with the first reject option.
static Func<JsonElement, string?> AllowOnly(IEnumerable<string> names) => p =>
{
    var title = p.TryGetProperty("toolCall", out var tc) && tc.TryGetProperty("title", out var t) ? t.GetString() ?? "" : "";
    var ok = names.Any(title.Contains);
    foreach (var o in p.GetProperty("options").EnumerateArray())
        if (o.GetProperty("kind").GetString()!.StartsWith(ok ? "allow" : "reject")) return o.GetProperty("optionId").GetString();
    return null;
};

using var agent = await AcpClient.StartAsync(new AcpStartOptions
{
    Command = args[0], Arguments = args[1..],
    McpServers = [new Dictionary<string, object?>
    {
        ["type"] = "http", ["name"] = "toolnexus", ["url"] = server.Url,
        ["headers"] = new[] { new Dictionary<string, object?> { ["name"] = "Authorization", ["value"] = "Bearer " + server.Token } },
    }],
    // Agent-specific knobs are opaque data: this one isolates Claude Code (see the per-agent notes).
    SessionMeta = new Dictionary<string, object?>
    {
        ["claudeCode"] = new { options = new { settingSources = Array.Empty<string>(), strictMcpConfig = true,
                                               plugins = Array.Empty<string>(), skills = Array.Empty<string>(), tools = Array.Empty<string>() } },
    },
    Model = Environment.GetEnvironmentVariable("ACP_MODEL"), // e.g. "haiku"; passed through, never validated
    OnPermission = AllowOnly(toolkit.Tools().Select(t => t.Name).ToList()),
    OnUpdate = u =>
    {
        if (u.TryGetProperty("update", out var up) && up.TryGetProperty("sessionUpdate", out var k) && k.GetString() == "usage_update")
            Console.Error.WriteLine($"[usage] {up.GetRawText()}");
    },
    PromptTimeout = TimeSpan.FromMinutes(5),
});

var client = InProcess.CreateClient(new InProcess.Options { Model = args[0], Generate = agent.Generate });
await using var empty = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
var result = await client.RunAsync("Use the add tool to add 17 and 25. Reply with just the sum.", empty);
Console.WriteLine(result.Text);
