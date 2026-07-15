using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using Microsoft.SemanticKernel;
using Microsoft.SemanticKernel.ChatCompletion;
using Microsoft.SemanticKernel.Connectors.OpenAI;

// Microsoft Semantic Kernel benchmark runner. Same mock LLM (OpenAI connector
// pointed at the mock base-url via a custom HttpClient) + the same fixed
// scenario. Tools are NATIVE KernelFunctions (get_weather + add) — SK's MCP
// plugin is not used here, so this runner is labelled "(native)". SK's automatic
// function-calling (FunctionChoiceBehavior.Auto) runs the full tool-calling loop.
//
//   -config native   -runs N   -warmup W

public sealed class BenchTools
{
    [KernelFunction("get_weather")]
    [Description("Get the current weather for a city.")]
    public string GetWeather([Description("city")] string city)
        => $"The weather in {city} is Sunny, 22C.";

    [KernelFunction("add")]
    [Description("Add two integers.")]
    public string Add([Description("a")] int a, [Description("b")] int b)
        => (a + b).ToString(CultureInfo.InvariantCulture);
}

static class Bench
{
    public static async Task<int> Main(string[] args)
    {
        string config = Arg(args, "-config", "native");
        int runs = int.Parse(Arg(args, "-runs", "30"), CultureInfo.InvariantCulture);
        int warmup = int.Parse(Arg(args, "-warmup", "5"), CultureInfo.InvariantCulture);
        string mockUrl = Environment.GetEnvironmentVariable("MOCK_URL") ?? "http://127.0.0.1:8900";
        const string question = "What's the weather in Paris and what is 2+2?";

        // ---- cold init ----
        var sw = Stopwatch.StartNew();
        var httpClient = new HttpClient { BaseAddress = new Uri(mockUrl) };
        var builder = Kernel.CreateBuilder();
        builder.AddOpenAIChatCompletion(
            modelId: "mock-model",
            apiKey: "sk-mock-not-a-real-key",
            httpClient: httpClient);
        builder.Plugins.AddFromType<BenchTools>("bench");
        var kernel = builder.Build();
        var chat = kernel.GetRequiredService<IChatCompletionService>();
        var settings = new OpenAIPromptExecutionSettings
        {
            FunctionChoiceBehavior = FunctionChoiceBehavior.Auto(),
            Temperature = 0,
        };
        double initMs = sw.Elapsed.TotalMilliseconds;
        int toolCount = kernel.Plugins.SelectMany(p => p).Count();

        async Task<string> RunOnce()
        {
            var history = new ChatHistory();
            history.AddUserMessage(question);
            var result = await chat.GetChatMessageContentAsync(history, settings, kernel);
            return result.Content ?? "";
        }

        // ---- warmup (JIT) ----
        for (int i = 0; i < warmup; i++) await RunOnce();

        // ---- measured ----
        var lat = new List<double>();
        string finalText = "";
        for (int i = 0; i < runs; i++)
        {
            long s = Stopwatch.GetTimestamp();
            finalText = await RunOnce();
            lat.Add(Stopwatch.GetElapsedTime(s).TotalMilliseconds);
        }

        var sb = new StringBuilder();
        sb.Append("{\"framework\":\"semantic-kernel-native\",");
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
        sb.Append("\"final_text\":\"").Append(finalText.Replace("\"", "\\\"").Replace("\n", " ")).Append("\"}");
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
