using System.ClientModel;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using Microsoft.Agents.AI;
using Microsoft.Extensions.AI;
using ModelContextProtocol.Client;
using OpenAI;

// Microsoft Agent Framework (Microsoft.Agents.AI) benchmark runner. Same mock LLM
// (official OpenAI .NET client pointed at the mock) + the same shared stdio MCP
// server. Tools are REAL MCP: ModelContextProtocol McpClient over stdio,
// ListToolsAsync() -> McpClientTool (an AIFunction) handed to the ChatClientAgent.
//
//   cold init   = MCP stdio connect + initialize + tools/list + ChatClientAgent build
//   per-request = agent.RunAsync(question) (new thread per call; agent runs the tool loop)
//
//   -runs N   -warmup W

static class Bench
{
    public static async Task<int> Main(string[] args)
    {
        int runs = int.Parse(Arg(args, "-runs", "30"), CultureInfo.InvariantCulture);
        int warmup = int.Parse(Arg(args, "-warmup", "5"), CultureInfo.InvariantCulture);
        string mockUrl = Environment.GetEnvironmentVariable("MOCK_URL") ?? "http://127.0.0.1:8900";
        string repo = Environment.GetEnvironmentVariable("BENCH_REPO") ?? "";
        string mcpPy = Environment.GetEnvironmentVariable("MCP_PYTHON") ?? "python3";
        const string question = "What's the weather in Paris and what is 2+2?";

        // ---- cold init ----
        var sw = Stopwatch.StartNew();
        var mcp = await McpClient.CreateAsync(new StdioClientTransport(new StdioClientTransportOptions
        {
            Name = "bench",
            Command = mcpPy,
            Arguments = [repo + "/benchmarks/mcp_server.py"],
        }));
        var mcpTools = await mcp.ListToolsAsync();
        var tools = mcpTools.Cast<AITool>().ToList();

        var openAiClient = new OpenAIClient(new ApiKeyCredential("sk-mock-not-a-real-key"),
            new OpenAIClientOptions { Endpoint = new Uri(mockUrl) });
        AIAgent agent = openAiClient.GetChatClient("mock-model").AsIChatClient()
            .AsAIAgent(instructions: "You are a helpful assistant.", tools: tools);
        double initMs = sw.Elapsed.TotalMilliseconds;

        for (int i = 0; i < warmup; i++) await agent.RunAsync(question);

        var lat = new List<double>();
        string finalText = "";
        for (int i = 0; i < runs; i++)
        {
            var s = Stopwatch.StartNew();
            var resp = await agent.RunAsync(question);
            lat.Add(s.Elapsed.TotalMilliseconds);
            finalText = resp.Text;
        }
        await mcp.DisposeAsync();

        var sorted = lat.OrderBy(x => x).ToList();
        double Pct(int p) => sorted[Math.Min(sorted.Count - 1, (int)(p / 100.0 * (sorted.Count - 1) + 0.5))];
        string F(double v) => Math.Round(v, 3).ToString(CultureInfo.InvariantCulture);
        var sb = new StringBuilder();
        sb.Append("{\"name\":\"ms_agent_framework\",\"framework\":\"ms-agent-framework-mcp\",\"language\":\"csharp\"");
        sb.Append(",\"init_ms\":").Append(F(initMs));
        sb.Append(",\"tool_count\":").Append(tools.Count);
        sb.Append(",\"mean_ms\":").Append(F(lat.Average()));
        sb.Append(",\"p50_ms\":").Append(F(Pct(50)));
        sb.Append(",\"p95_ms\":").Append(F(Pct(95)));
        sb.Append(",\"n\":").Append(lat.Count);
        sb.Append(",\"final_ok\":").Append(finalText.StartsWith("The weather in Paris") ? "true" : "false");
        sb.Append('}');
        Console.WriteLine(sb.ToString());
        return 0;
    }

    static string Arg(string[] a, string k, string d)
    {
        int i = Array.IndexOf(a, k);
        return i >= 0 && i + 1 < a.Length ? a[i + 1] : d;
    }
}
