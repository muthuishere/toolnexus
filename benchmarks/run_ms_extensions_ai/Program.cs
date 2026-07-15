using System.ClientModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using Microsoft.Extensions.AI;
using OpenAI;

// Microsoft.Extensions.AI benchmark runner. Same mock LLM (official OpenAI .NET
// client pointed at the mock endpoint) + the same fixed scenario. Tools are
// NATIVE AIFunctions (get_weather + add) — no MCP here, so this runner is
// labelled "(native)". ChatClientBuilder().UseFunctionInvocation() runs the full
// tool-calling loop until a final answer.
//
//   -config native   -runs N   -warmup W

static class Bench
{
    [Description("Get the current weather for a city.")]
    static string GetWeather([Description("city")] string city)
        => $"The weather in {city} is Sunny, 22C.";

    [Description("Add two integers.")]
    static string Add([Description("a")] int a, [Description("b")] int b)
        => (a + b).ToString(CultureInfo.InvariantCulture);

    public static async Task<int> Main(string[] args)
    {
        string config = Arg(args, "-config", "native");
        int runs = int.Parse(Arg(args, "-runs", "30"), CultureInfo.InvariantCulture);
        int warmup = int.Parse(Arg(args, "-warmup", "5"), CultureInfo.InvariantCulture);
        string mockUrl = Environment.GetEnvironmentVariable("MOCK_URL") ?? "http://127.0.0.1:8900";
        const string question = "What's the weather in Paris and what is 2+2?";

        // ---- cold init ----
        var sw = Stopwatch.StartNew();
        var openAiOptions = new OpenAIClientOptions { Endpoint = new Uri(mockUrl) };
        var openAiClient = new OpenAIClient(new ApiKeyCredential("sk-mock-not-a-real-key"), openAiOptions);
        IChatClient client = new ChatClientBuilder(
                openAiClient.GetChatClient("mock-model").AsIChatClient())
            .UseFunctionInvocation()
            .Build();

        var tools = new List<AITool>
        {
            AIFunctionFactory.Create(GetWeather, name: "get_weather"),
            AIFunctionFactory.Create(Add, name: "add"),
        };
        var options = new ChatOptions { Tools = tools, Temperature = 0f };
        double initMs = sw.Elapsed.TotalMilliseconds;
        int toolCount = tools.Count;

        // ---- warmup (JIT) ----
        for (int i = 0; i < warmup; i++) await client.GetResponseAsync(question, options);

        // ---- measured ----
        var lat = new List<double>();
        string finalText = "";
        for (int i = 0; i < runs; i++)
        {
            long s = Stopwatch.GetTimestamp();
            var resp = await client.GetResponseAsync(question, options);
            lat.Add(Stopwatch.GetElapsedTime(s).TotalMilliseconds);
            finalText = resp.Text;
        }

        var sb = new StringBuilder();
        sb.Append("{\"framework\":\"ms-extensions-ai-native\",");
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
        sb.Append("\"final_text\":\"").Append((finalText ?? "").Replace("\"", "\\\"").Replace("\n", " ")).Append("\"}");
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
