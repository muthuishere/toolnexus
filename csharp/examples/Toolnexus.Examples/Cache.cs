using System.Net;
using System.Text;
using System.Text.Json;

namespace Toolnexus.Examples;

/// <summary>
/// Prompt cache + tool tiers, hermetic: a local stub plays an Anthropic-style model, so no key
/// or network is needed.
/// <para><c>dotnet run --project examples/Toolnexus.Examples -- cache</c></para>
/// The client marks the stable prefix for the cache (<c>PromptCache</c>) and sends only the core
/// tool schemas (<c>ToolTiers</c>). The model loads <c>weather</c> with <c>tool_search</c>, calls it
/// through <c>call_tool</c>, and the tools array stays identical on every request, so the cached
/// prefix never breaks.
/// </summary>
internal static class Cache
{
    public static async Task<int> Run()
    {
        var requests = new List<JsonElement>();
        using var stub = new AnthropicStub(body =>
        {
            requests.Add(JsonDocument.Parse(body).RootElement.Clone());
            return requests.Count switch
            {
                1 => ToolUse("s1", "tool_search", """{"query":"select:weather"}""", cacheWrite: 1800),
                2 => ToolUse("w1", "call_tool", """{"name":"weather","arguments":{"city":"Paris"}}""", cacheRead: 1800),
                _ => Text("Sunny in Paris.", cacheRead: 1900),
            };
        });

        var tools = new List<ITool>
        {
            NativeTool.Of("read_note", "Read a saved note", null, (IDictionary<string, object?> a) => "note"),
            NativeTool.Of("weather", "Current weather for a city", null, (IDictionary<string, object?> a) => $"sunny in {a["city"]}"),
            NativeTool.Of("translate", "Translate text", null, (IDictionary<string, object?> a) => "hola"),
        };
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { ExtraTools = tools, Builtins = false });

        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = stub.BaseUrl,
            Style = "anthropic",
            Model = "claude-sonnet",
            ApiKey = "YOUR_KEY_HERE", // a local BaseUrl needs an explicit key (0.22.0)
            SystemPrompt = "You are a concise assistant.",
            PromptCache = new PromptCacheOptions(),
            ToolTiers = new ToolTierOptions { Core = new[] { "read_note" } },
            Hooks = new LlmClient.Hooks
            {
                BeforeTool = ev => { Console.WriteLine($"  beforeTool: {ev.Name} (readOnly={ev.Hints?.ReadOnly})"); return null; },
            },
        });

        Console.WriteLine("Tier plan: " + string.Join(", ", client.ToolTierPlan(tk)!.OrderBy(p => p.Key).Select(p => $"{p.Key}={p.Value}")));
        var res = await client.RunAsync("What's the weather in Paris?", tk);

        var sent = requests[0].GetProperty("tools").EnumerateArray().Select(t => t.GetProperty("name").GetString());
        var stable = requests.Select(r => r.GetProperty("tools").GetRawText()).Distinct().Count() == 1;
        var marks = requests[0].GetRawText().Split("\"cache_control\"").Length - 1;
        var u = res.Usage;

        Console.WriteLine($"Tools sent: [{string.Join(", ", sent)}], identical on all {requests.Count} requests: {stable}");
        Console.WriteLine($"Cache breakpoints per request: {marks}");
        Console.WriteLine($"Usage: prompt={u.PromptTokens} cacheRead={u.CacheReadTokens} cacheWrite={u.CacheWriteTokens} context={u.PromptTokens + u.CacheReadTokens + u.CacheWriteTokens}");
        Console.WriteLine($"Answer: {res.Text}");

        return stable && marks == 3 && u.CacheReadTokens > 0 && res.Text.Contains("Sunny") ? 0 : 1;
    }

    private static string Usage(int cacheRead, int cacheWrite) =>
        $$"""{"input_tokens":20,"output_tokens":5,"cache_read_input_tokens":{{cacheRead}},"cache_creation_input_tokens":{{cacheWrite}}}""";

    private static string ToolUse(string id, string name, string input, int cacheRead = 0, int cacheWrite = 0) =>
        $$"""{"id":"m","type":"message","role":"assistant","content":[{"type":"tool_use","id":"{{id}}","name":"{{name}}","input":{{input}}}],"stop_reason":"tool_use","usage":{{Usage(cacheRead, cacheWrite)}}}""";

    private static string Text(string text, int cacheRead = 0) =>
        $$"""{"id":"m","type":"message","role":"assistant","content":[{"type":"text","text":"{{text}}"}],"stop_reason":"end_turn","usage":{{Usage(cacheRead, 0)}}}""";

    /// <summary>A loopback HTTP server that answers every request with <c>reply(body)</c>.</summary>
    private sealed class AnthropicStub : IDisposable
    {
        private readonly HttpListener _listener = new();
        public string BaseUrl { get; }

        public AnthropicStub(Func<string, string> reply)
        {
            var probe = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
            probe.Start();
            var port = ((IPEndPoint)probe.LocalEndpoint).Port;
            probe.Stop();
            BaseUrl = $"http://127.0.0.1:{port}";
            _listener.Prefixes.Add(BaseUrl + "/");
            _listener.Start();
            _ = Task.Run(async () =>
            {
                while (_listener.IsListening)
                {
                    HttpListenerContext ctx;
                    try { ctx = await _listener.GetContextAsync(); } catch { break; }
                    var body = await new StreamReader(ctx.Request.InputStream).ReadToEndAsync();
                    var bytes = Encoding.UTF8.GetBytes(reply(body));
                    ctx.Response.ContentType = "application/json";
                    ctx.Response.ContentLength64 = bytes.Length;
                    await ctx.Response.OutputStream.WriteAsync(bytes);
                    ctx.Response.Close();
                }
            });
        }

        public void Dispose()
        {
            try { _listener.Stop(); _listener.Close(); } catch { }
        }
    }
}
