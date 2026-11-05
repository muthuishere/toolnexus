using System.Text;
using System.Text.Json.Nodes;

namespace Mcp2026;

// Lazy tool search: with ToolSearch the LLM sees two small tools instead of all N:
// search_tools(query) -> ranked {name, description, inputSchema, server}, and
// call_tool(name, arguments) to run one it found. Ranking is BM25 over name (x2) + description.

public sealed partial class McpClient
{
    /// <summary>Tools() exposes search_tools + call_tool instead of every server tool.</summary>
    public bool ToolSearch { get; set; }
    /// <summary>What this server is for; shown in search_tools results.</summary>
    public string Description { get; set; } = "";

    private List<JsonObject>? _toolCache;
    private DateTime _toolExpiry;
    private readonly SemaphoreSlim _toolMu = new(1, 1);

    /// <summary>The tool definitions to give the model: every server tool, or (ToolSearch) the two meta-tools.
    /// Run them with <see cref="InvokeAsync"/>.</summary>
    public async Task<List<JsonObject>> ToolsAsync(CancellationToken ct = default)
    {
        if (!ToolSearch) return (await ListToolsAsync(ct)).Select(t => (JsonObject)t.DeepClone()).ToList();
        var about = Description != "" ? Description : "this MCP server";
        return
        [
            new JsonObject
            {
                ["name"] = "search_tools",
                ["description"] = "Search the tools of " + about + " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.",
                ["inputSchema"] = new JsonObject
                {
                    ["type"] = "object",
                    ["properties"] = new JsonObject { ["query"] = new JsonObject { ["type"] = "string" }, ["limit"] = new JsonObject { ["type"] = "integer" } },
                    ["required"] = J.Array(["query"]),
                },
            },
            new JsonObject
            {
                ["name"] = "call_tool",
                ["description"] = "Run a tool found with search_tools.",
                ["inputSchema"] = new JsonObject
                {
                    ["type"] = "object",
                    ["properties"] = new JsonObject { ["name"] = new JsonObject { ["type"] = "string" }, ["arguments"] = new JsonObject { ["type"] = "object" } },
                    ["required"] = J.Array(["name"]),
                },
            },
        ];
    }

    /// <summary>Runs a tool the model asked for: a server tool, or search_tools / call_tool in ToolSearch mode.</summary>
    public async Task<JsonObject> InvokeAsync(string name, JsonObject? args = null, Responder? respond = null, CancellationToken ct = default)
    {
        args ??= [];
        if (!ToolSearch) return await CallToolAsync(name, args, respond, ct);
        switch (name)
        {
            case "search_tools":
                var limit = J.Num(args["limit"]) is > 0 and var l ? (int)l : 5;
                var hits = await SearchToolsAsync(J.Str(args["query"], ""), limit, ct);
                var arr = new JsonArray(hits.Select(h => (JsonNode?)h).ToArray());
                return new JsonObject
                {
                    ["resultType"] = "complete",
                    ["content"] = new JsonArray(new JsonObject { ["type"] = "text", ["text"] = J.Serialize(arr) }),
                    ["structuredContent"] = new JsonObject { ["tools"] = arr.DeepClone() },
                    ["isError"] = false,
                };
            case "call_tool":
                return await CallToolAsync(J.Str(args["name"], ""), args["arguments"] as JsonObject, respond, ct);
        }
        throw new McpException($"unknown tool \"{name}\" (ToolSearch mode exposes search_tools and call_tool)");
    }

    /// <summary>Ranks the server's tools against query (BM25; name tokens count twice).</summary>
    public async Task<List<JsonObject>> SearchToolsAsync(string query, int limit = 5, CancellationToken ct = default)
    {
        var tools = await ListToolsAsync(ct);
        var docs = new List<List<string>>();
        var df = new Dictionary<string, int>();
        var total = 0;
        foreach (var t in tools)
        {
            var nt = Tokens(J.Str(t["name"], ""));
            var d = nt.Concat(nt).Concat(Tokens(J.Str(t["description"], ""))).ToList();
            docs.Add(d);
            total += d.Count;
            foreach (var w in d.Distinct()) df[w] = df.GetValueOrDefault(w) + 1;
        }
        var avg = (double)total / Math.Max(1, tools.Count);
        var q = Tokens(query);
        var hits = new List<(int I, double Score)>();
        for (var i = 0; i < docs.Count; i++)
        {
            var tf = docs[i].GroupBy(w => w).ToDictionary(g => g.Key, g => g.Count());
            var s = 0.0;
            foreach (var w in q)
            {
                double f = tf.GetValueOrDefault(w);
                if (f == 0) continue;
                var idf = Math.Log(1 + (tools.Count - df[w] + 0.5) / (df[w] + 0.5));
                s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * docs[i].Count / avg));
            }
            if (s > 0) hits.Add((i, s));
        }
        return hits.OrderByDescending(h => h.Score).Take(limit).Select(h => new JsonObject
        {
            ["name"] = tools[h.I]["name"]?.DeepClone(),
            ["description"] = tools[h.I]["description"]?.DeepClone(),
            ["inputSchema"] = tools[h.I]["inputSchema"]?.DeepClone(),
            ["server"] = Description,
        }).ToList();
    }

    /// <summary>tools/list, cached for the server's ttlMs.</summary>
    private async Task<List<JsonObject>> ListToolsAsync(CancellationToken ct)
    {
        await _toolMu.WaitAsync(ct);
        try
        {
            if (_toolCache != null && DateTime.UtcNow < _toolExpiry) return _toolCache;
            var r = await CallAsync("tools/list", null, ct);
            _toolCache = (r["tools"] as JsonArray ?? []).OfType<JsonObject>().ToList();
            _toolExpiry = DateTime.UtcNow.AddMilliseconds(J.Num(r["ttlMs"]) ?? 0);
            return _toolCache;
        }
        finally { _toolMu.Release(); }
    }

    /// <summary>Lowercases and splits on non-alphanumerics and camelCase boundaries.</summary>
    internal static List<string> Tokens(string s)
    {
        var outp = new List<string>();
        var cur = new StringBuilder();
        void Flush() { if (cur.Length > 0) { outp.Add(cur.ToString().ToLowerInvariant()); cur.Clear(); } }
        var prevLower = false;
        foreach (var r in s)
        {
            if (char.IsUpper(r) && prevLower) { Flush(); cur.Append(r); }
            else if (char.IsLetterOrDigit(r)) cur.Append(r);
            else Flush();
            prevLower = char.IsLower(r);
        }
        Flush();
        return outp;
    }
}
