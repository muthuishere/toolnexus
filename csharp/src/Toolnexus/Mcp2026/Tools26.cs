using System.Text.Json.Nodes;

namespace Toolnexus.Mcp2026;

/// <summary>A 2026-path server tool as a uniform <see cref="ITool"/> (same naming / schema / hints as §2.F).</summary>
internal sealed class McpTool26 : ITool
{
    private readonly Server26 _server;
    private readonly string _mcpName;

    public string Name { get; }
    public string Description { get; }
    public IDictionary<string, object?> InputSchema { get; }
    public string Source => "mcp";
    public ToolHints? Hints { get; }

    public McpTool26(Server26 server, JsonObject def)
    {
        _server = server;
        _mcpName = J.Str(def["name"], "");
        Name = Tools.Sanitize(server.Name) + "_" + Tools.Sanitize(_mcpName);
        Description = J.Str(def["description"], "");
        InputSchema = BuildInputSchema(def["inputSchema"]);
        var a = def["annotations"] as JsonObject;
        Hints = McpSource.McpHints(Bool(a?["readOnlyHint"]), Bool(a?["destructiveHint"]), Bool(a?["openWorldHint"]));
    }

    private static bool? Bool(JsonNode? n) => n is JsonValue v && v.TryGetValue<bool>(out var b) ? b : null;

    /// <summary>Same as the frozen path: spread the server schema, force type:object, default properties, no extras.</summary>
    internal static Dictionary<string, object?> BuildInputSchema(JsonNode? raw)
    {
        var schema = J.ToClr(raw as JsonObject) as Dictionary<string, object?> ?? new Dictionary<string, object?>();
        schema["type"] = "object";
        schema["properties"] = schema.Get("properties") ?? new Dictionary<string, object?>();
        schema["additionalProperties"] = false;
        return schema;
    }

    public Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        => _server.CallToolAsync(_mcpName, args, ctx);
}

/// <summary>SPEC §2.8 lazy tool search: <c>&lt;server&gt;_search_tools</c> and <c>&lt;server&gt;_call_tool</c>.</summary>
internal sealed class SearchTool26 : ITool
{
    private readonly Server26 _server;
    private readonly bool _call;

    public string Name { get; }
    public string Description { get; }
    public IDictionary<string, object?> InputSchema { get; }
    public string Source => "mcp";
    public ToolHints? Hints => null;

    public SearchTool26(Server26 server, bool call)
    {
        _server = server;
        _call = call;
        var about = server.Description != "" ? server.Description : server.Name;
        Name = Tools.Sanitize(server.Name) + (call ? "_call_tool" : "_search_tools");
        Description = call
            ? "Run a tool found with search_tools."
            : "Search the tools of " + about + " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.";
        InputSchema = call
            ? new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["name"] = new Dictionary<string, object?> { ["type"] = "string" },
                    ["arguments"] = new Dictionary<string, object?> { ["type"] = "object" },
                },
                ["required"] = new List<object?> { "name" },
            }
            : new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["query"] = new Dictionary<string, object?> { ["type"] = "string" },
                    ["limit"] = new Dictionary<string, object?> { ["type"] = "integer" },
                },
                ["required"] = new List<object?> { "query" },
            };
    }

    public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
    {
        args ??= new Dictionary<string, object?>();
        var meta = new Dictionary<string, object?> { ["server"] = _server.Name };
        var ct = ctx?.CancellationToken ?? default;
        try
        {
            if (_call)
            {
                var name = args.Get("name") as string ?? "";
                var tools = await _server.ListToolsAsync(ct).ConfigureAwait(false);
                if (!tools.Any(t => J.Str(t["name"]) == name))
                    return new ToolResult($"unknown tool \"{name}\" on {_server.Name}", true, meta);
                return await _server.CallToolAsync(name, args.Get("arguments") as IDictionary<string, object?>, ctx).ConfigureAwait(false);
            }
            var limit = args.Get("limit") switch { long l when l > 0 => (int)l, int i when i > 0 => i, double d when d >= 1 => (int)d, _ => 5 };
            var hits = await _server.SearchToolsAsync(args.Get("query") as string ?? "", limit, ct).ConfigureAwait(false);
            var list = hits.Cast<object?>().ToList();
            return new ToolResult(Json.Stringify(list), false, meta);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception e) { return new ToolResult(e.Message, true, meta); }
    }
}
