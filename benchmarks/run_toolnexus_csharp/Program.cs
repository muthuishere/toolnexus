using System.Diagnostics;
using System.Globalization;
using System.Text;
using Toolnexus;

// toolnexus (C# port) benchmark runner. Same mock LLM (OpenAI base-url) + same
// shared stdio MCP server (via the official ModelContextProtocol SDK) as every
// other framework. Cold init = build toolkit (MCP connect + tool discovery) +
// LlmClient. Per-request = agent.RunAsync over the fixed scenario. JIT warmup is
// handled with explicit warmup iterations before the measured loop.
//
//   -config mcp|full   -runs N   -warmup W

static class Bench
{
    // Native (annotated) tool for the "full" config — mirrors the Java runner.
    public sealed class NativeTools
    {
        [ToolMethod("multiply", "Multiply two integers locally.")]
        public string Multiply([Param("a", "a")] long a, [Param("b", "b")] long b)
            => (a * b).ToString(CultureInfo.InvariantCulture);
    }

    public static async Task<int> Main(string[] args)
    {
        string config = Arg(args, "-config", "mcp");
        int runs = int.Parse(Arg(args, "-runs", "30"), CultureInfo.InvariantCulture);
        int warmup = int.Parse(Arg(args, "-warmup", "5"), CultureInfo.InvariantCulture);

        string repo = Environment.GetEnvironmentVariable("BENCH_REPO")
                      ?? throw new InvalidOperationException("BENCH_REPO not set");
        string mcpPy = Environment.GetEnvironmentVariable("MCP_PYTHON")
                       ?? throw new InvalidOperationException("MCP_PYTHON not set");
        string mockUrl = Environment.GetEnvironmentVariable("MOCK_URL") ?? "http://127.0.0.1:8900";
        const string question = "What's the weather in Paris and what is 2+2?";

        // Same MCP config shape the Java runner uses: a single local (stdio) server
        // launched with the shared python + shared mcp_server.py.
        var server = new Dictionary<string, object?>
        {
            ["type"] = "local",
            ["command"] = new List<object?> { mcpPy, repo + "/benchmarks/mcp_server.py" },
            ["enabled"] = true,
            ["timeout"] = 30000,
        };
        var mcpConfig = new Dictionary<string, object?>
        {
            ["mcpServers"] = new Dictionary<string, object?> { ["bench-tools"] = server },
        };

        // ---- cold init ----
        var sw = Stopwatch.StartNew();
        var opts = new Toolkit.Options()
            .WithMcpConfig(mcpConfig)
            .WithBuiltins(false);
        if (config == "full")
            opts.WithSkillsDir(repo + "/examples/skills");
        if (config == "full")
            opts.WithAnnotatedObjects(new NativeTools());

        await using var tk = await Toolkit.CreateAsync(opts);
        var agent = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = mockUrl,
            Style = "openai",
            Model = "mock-model",
            ApiKey = "sk-mock-not-a-real-key",
        });
        double initMs = sw.Elapsed.TotalMilliseconds;
        int toolCount = tk.Tools().Count;

        // ---- warmup (JIT + class loading) ----
        for (int i = 0; i < warmup; i++) await agent.RunAsync(question, tk);

        // ---- measured ----
        var lat = new List<double>();
        string finalText = "";
        for (int i = 0; i < runs; i++)
        {
            long s = Stopwatch.GetTimestamp();
            var res = await agent.RunAsync(question, tk);
            lat.Add(Stopwatch.GetElapsedTime(s).TotalMilliseconds);
            finalText = res.Text;
        }

        var sb = new StringBuilder();
        sb.Append("{\"framework\":\"toolnexus-csharp-").Append(config).Append("\",");
        sb.Append("\"language\":\"csharp\",");
        sb.Append("\"init_ms\":").Append(Round(initMs)).Append(',');
        sb.Append("\"tool_count\":").Append(toolCount).Append(',');
        sb.Append("\"latencies_ms\":[");
        for (int i = 0; i < lat.Count; i++)
        {
            if (i > 0) sb.Append(',');
            sb.Append(Round(lat[i]));
        }
        sb.Append("],");
        sb.Append("\"final_text\":\"").Append(finalText.Replace("\"", "\\\"")).Append("\"}");
        Console.WriteLine(sb.ToString());
        return 0;
    }

    static string Arg(string[] a, string key, string def)
    {
        for (int i = 0; i < a.Length - 1; i++) if (a[i] == key) return a[i + 1];
        return def;
    }

    static string Round(double v) =>
        (Math.Round(v * 1000.0) / 1000.0).ToString(CultureInfo.InvariantCulture);
}
