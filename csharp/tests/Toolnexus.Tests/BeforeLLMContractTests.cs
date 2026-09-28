using System.Net;
using System.Text;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>SPEC §8 beforeLLM contract (change add-judge-batteries): a failing hook stops the call
/// with no provider request, and an overriding <c>model</c> is what gets transmitted AND reported
/// (the <c>llm</c>/<c>run</c> metric events, <c>RunResult.Model</c>, translate's <c>Model</c>) —
/// in every entry point: run + stream × openai + anthropic, the agent run, and translate.</summary>
public class BeforeLLMContractTests
{
    /// <summary>Records every request body; answers with a final text in the requested style/mode.</summary>
    private sealed class Wire : HttpMessageHandler
    {
        public readonly List<Dictionary<string, object?>> Bodies = new();

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var body = Json.ParseObjectLoose(await request.Content!.ReadAsStringAsync(ct));
            lock (Bodies) Bodies.Add(body);
            var anthropic = request.RequestUri!.AbsolutePath.EndsWith("/messages");
            var stream = body.Get("stream") is true;
            string text, type;
            if (stream)
            {
                type = "text/event-stream";
                text = anthropic
                    ? "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n" +
                      "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                      "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n" +
                      "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n" +
                      "data: {\"type\":\"message_stop\"}\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            }
            else
            {
                type = "application/json";
                text = anthropic
                    ? "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
            }
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(text, Encoding.UTF8, type) };
        }
    }

    private sealed class HookBoom : Exception { public HookBoom() : base("hook boom") { } }

    private static LlmClient Client(Wire w, string style, LlmClient.Hooks? hooks, List<MetricEvent>? metrics = null)
        => LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://stub.invalid/v1", Style = style, Model = "configured", ApiKey = "k",
            HttpClient = new HttpClient(w), Hooks = hooks, MaxTurns = 3,
            OnMetric = metrics == null ? null : ev => { lock (metrics) metrics.Add(ev); },
        });

    public static IEnumerable<object[]> Entries()
    {
        foreach (var style in new[] { "openai", "anthropic" })
            foreach (var mode in new[] { "run", "stream", "translate" })
                yield return new object[] { style, mode };
    }

    private static async Task<string> Call(LlmClient c, string mode)
    {
        switch (mode)
        {
            case "run": return (await c.RunAsync("hi", null)).Model;
            case "stream": return (await c.StreamAsync("hi", null, _ => { })).Model;
            default:
                return (await c.TranslateAsync(new Translate.Request
                {
                    Messages = new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = "hi" } },
                })).Model;
        }
    }

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task FailingBeforeLLM_StopsTheCall_NoRequestSent(string style, string mode)
    {
        var w = new Wire();
        var c = Client(w, style, new LlmClient.Hooks { BeforeLLM = _ => throw new HookBoom() });
        await Assert.ThrowsAsync<HookBoom>(() => Call(c, mode));
        Assert.Empty(w.Bodies);
    }

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task ModelOverride_IsTransmittedAndReported(string style, string mode)
    {
        var w = new Wire();
        var metrics = new List<MetricEvent>();
        var c = Client(w, style, new LlmClient.Hooks { BeforeLLM = _ => new LlmClient.LLMOverride(Model: "small-fast") }, metrics);
        var reported = await Call(c, mode);
        Assert.Single(w.Bodies);
        Assert.Equal("small-fast", w.Bodies[0]["model"]);
        Assert.Equal("small-fast", reported);
        Assert.All(metrics.Where(m => m.Event == "llm"), m => Assert.Equal("small-fast", m.Model));
        Assert.Contains(metrics, m => m.Event == "llm");
        if (mode != "translate") Assert.Equal("small-fast", Assert.Single(metrics, m => m.Event == "run").Model);
    }

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task NoOverride_IsConfiguredVerbatim(string style, string mode)
    {
        var w = new Wire();
        var metrics = new List<MetricEvent>();
        var c = Client(w, style, new LlmClient.Hooks { BeforeLLM = _ => null }, metrics);
        var reported = await Call(c, mode);
        Assert.Equal("configured", w.Bodies[0]["model"]);
        Assert.Equal("configured", reported);
        Assert.All(metrics.Where(m => m.Event is "llm" or "run"), m => Assert.Equal("configured", m.Model));
    }

    /// <summary>A run that fails in turn 1's hook reports the model of the last call made (turn 0's
    /// override) on the error <c>run</c> event; a hook failing at turn 0 reports the configured one.</summary>
    [Fact]
    public async Task FailedRun_ReportsLastCallModel()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(NativeTool.Of("deploy", "deploy", null, (IDictionary<string, object?> _) => (object)ToolResult.Ok("D")));
        var handler = new ToolThenText();
        var metrics = new List<MetricEvent>();
        var c = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://stub.invalid/v1", Style = "openai", Model = "configured", ApiKey = "k",
            HttpClient = new HttpClient(handler), MaxTurns = 3, OnMetric = metrics.Add,
            Hooks = new LlmClient.Hooks
            {
                BeforeLLM = ev => ev.Turn == 0 ? new LlmClient.LLMOverride(Model: "small-fast") : throw new HookBoom(),
            },
        });
        await Assert.ThrowsAsync<HookBoom>(() => c.RunAsync("go", tk));
        Assert.Equal(1, handler.Count);
        var run = Assert.Single(metrics, m => m.Event == "run");
        Assert.Equal("small-fast", run.Model);
        Assert.NotNull(run.Error);

        metrics.Clear();
        var c0 = Client(new Wire(), "openai", new LlmClient.Hooks { BeforeLLM = _ => throw new HookBoom() }, metrics);
        await Assert.ThrowsAsync<HookBoom>(() => c0.RunAsync("go", null));
        Assert.Equal("configured", Assert.Single(metrics, m => m.Event == "run").Model);
    }

    /// <summary>Override on turn 0 only: RunResult.Model is the LAST call's model (configured).</summary>
    [Fact]
    public async Task RunResultModel_IsLastCallModel()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(NativeTool.Of("deploy", "deploy", null, (IDictionary<string, object?> _) => (object)ToolResult.Ok("D")));
        var metrics = new List<MetricEvent>();
        var c = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://stub.invalid/v1", Style = "openai", Model = "configured", ApiKey = "k",
            HttpClient = new HttpClient(new ToolThenText()), MaxTurns = 3, OnMetric = metrics.Add,
            Hooks = new LlmClient.Hooks { BeforeLLM = ev => ev.Turn == 1 ? new LlmClient.LLMOverride(Model: "big") : null },
        });
        var r = await c.RunAsync("go", tk);
        Assert.Equal("big", r.Model);
        Assert.Equal(new[] { "configured", "big" }, metrics.Where(m => m.Event == "llm").Select(m => m.Model));
        Assert.Equal("big", Assert.Single(metrics, m => m.Event == "run").Model);
    }

    private sealed class ToolThenText : HttpMessageHandler
    {
        public int Count;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var n = Interlocked.Increment(ref Count);
            var body = n == 1
                ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"deploy\",\"arguments\":\"{}\"}}]}}]}"
                : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}";
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") });
        }
    }

    /// <summary>§7D agent run: a failing beforeLLM surfaces as the turn's failure (an isError result
    /// across the handle boundary) and no request reaches the provider.</summary>
    [Fact]
    public async Task AgentRun_FailingBeforeLLM_NoRequest()
    {
        var w = new Wire();
        var rt = new AgentRuntime(new RuntimeOptions
        {
            Handler = w, ApiKey = "test",
            Registry = new Dictionary<string, AgentDef> { ["a"] = new AgentDef { Name = "a", Does = "x", Model = "m-a" } },
            Hooks = new LlmClient.Hooks { BeforeLLM = _ => throw new HookBoom() },
        });
        var h = rt.Spawn(rt.Root, "a").Handle!;
        var r = await rt.RunTurnAsync(h, "hello");
        Assert.True(r.IsError);
        Assert.Contains("hook boom", r.Text);
        Assert.Empty(w.Bodies);
        await rt.CloseAsync(rt.Root);
    }

    /// <summary>§7D agent run: the override reaches the wire.</summary>
    [Fact]
    public async Task AgentRun_ModelOverride_Transmitted()
    {
        var w = new Wire();
        var rt = new AgentRuntime(new RuntimeOptions
        {
            Handler = w, ApiKey = "test",
            Registry = new Dictionary<string, AgentDef> { ["a"] = new AgentDef { Name = "a", Does = "x", Model = "m-a" } },
            Hooks = new LlmClient.Hooks { BeforeLLM = _ => new LlmClient.LLMOverride(Model: "small-fast") },
        });
        var h = rt.Spawn(rt.Root, "a").Handle!;
        var r = await rt.RunTurnAsync(h, "hello");
        Assert.False(r.IsError, r.Text);
        Assert.Equal("small-fast", w.Bodies[0]["model"]);
        await rt.CloseAsync(rt.Root);
    }
}
