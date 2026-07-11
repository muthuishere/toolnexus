using System.Collections.Concurrent;
using System.Net;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// Observability + block-style streaming (SPEC §8): the <c>OnMetric</c> sink, the built-in
/// Prometheus <c>Metrics()</c> text (byte-identical to the JS reference), <c>StreamAsync</c> with an
/// <c>id</c> (stateful across consumptions), and <c>AskAsync</c> with an <c>onText</c> callback.
/// Hermetic — a tiny <see cref="HttpListener"/> stub stands in for the LLM; no network, no live LLM.
/// </summary>
public class ClientObservabilityTests
{
    // ---- Metrics() byte-parity with the JS reference (registry fed known events) ----

    [Fact]
    public void Metrics_BeforeActivity_RendersHelpAndTypeOnly()
    {
        var reg = new MetricsRegistry();

        const string expectedRaw = """
        # HELP toolnexus_llm_requests_total Total LLM requests.
        # TYPE toolnexus_llm_requests_total counter
        # HELP toolnexus_llm_tokens_total Total tokens, by type.
        # TYPE toolnexus_llm_tokens_total counter
        # HELP toolnexus_llm_request_duration_seconds LLM request duration in seconds.
        # TYPE toolnexus_llm_request_duration_seconds histogram
        # HELP toolnexus_tool_calls_total Total tool calls.
        # TYPE toolnexus_tool_calls_total counter
        # HELP toolnexus_tool_duration_seconds Tool execution duration in seconds.
        # TYPE toolnexus_tool_duration_seconds histogram
        # HELP toolnexus_run_errors_total Total run errors.
        # TYPE toolnexus_run_errors_total counter
        """;

        Assert.Equal(Norm(expectedRaw), reg.Render());
    }

    [Fact]
    public void Metrics_AfterEvents_IsByteIdenticalToJs()
    {
        // Same fixed events (with fixed ms so durations are deterministic: 100ms=0.1s, 250ms=0.25s)
        // fed to the JS MetricsRegistry to produce the reference text below.
        var reg = new MetricsRegistry();
        reg.Record(MetricEvent.Llm("gpt", "ok", 100, 5, 7));
        reg.Record(MetricEvent.ToolCall("echo", "mcp", false, 250));
        reg.Record(MetricEvent.Run("gpt", 1, 1, 12, 100));

        const string expectedRaw = """
        # HELP toolnexus_llm_requests_total Total LLM requests.
        # TYPE toolnexus_llm_requests_total counter
        toolnexus_llm_requests_total{model="gpt",status="ok"} 1
        # HELP toolnexus_llm_tokens_total Total tokens, by type.
        # TYPE toolnexus_llm_tokens_total counter
        toolnexus_llm_tokens_total{type="completion"} 7
        toolnexus_llm_tokens_total{type="prompt"} 5
        # HELP toolnexus_llm_request_duration_seconds LLM request duration in seconds.
        # TYPE toolnexus_llm_request_duration_seconds histogram
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="0.05"} 0
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="0.1"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="0.25"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="0.5"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="1"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="2.5"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="5"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="10"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="30"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="60"} 1
        toolnexus_llm_request_duration_seconds_bucket{model="gpt",le="+Inf"} 1
        toolnexus_llm_request_duration_seconds_sum{model="gpt"} 0.1
        toolnexus_llm_request_duration_seconds_count{model="gpt"} 1
        # HELP toolnexus_tool_calls_total Total tool calls.
        # TYPE toolnexus_tool_calls_total counter
        toolnexus_tool_calls_total{tool="echo",source="mcp",is_error="false",pending="false"} 1
        # HELP toolnexus_tool_duration_seconds Tool execution duration in seconds.
        # TYPE toolnexus_tool_duration_seconds histogram
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="0.05"} 0
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="0.1"} 0
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="0.25"} 1
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="0.5"} 1
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="1"} 1
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="2.5"} 1
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="5"} 1
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="10"} 1
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="30"} 1
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="60"} 1
        toolnexus_tool_duration_seconds_bucket{tool="echo",le="+Inf"} 1
        toolnexus_tool_duration_seconds_sum{tool="echo"} 0.25
        toolnexus_tool_duration_seconds_count{tool="echo"} 1
        # HELP toolnexus_run_errors_total Total run errors.
        # TYPE toolnexus_run_errors_total counter
        """;

        Assert.Equal(Norm(expectedRaw), reg.Render());
    }

    // ---- StreamAsync with an id is stateful across two consumptions ----

    [Fact]
    public async Task StreamAsync_WithId_RemembersAcrossConsumptions()
    {
        using var llm = StreamingCountingLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var client = ClientFor(llm);

        var first = await client.StreamAsync("first", tk, _ => { }, "s1");
        Assert.Equal("1", first.Text); // first turn: just the user message

        var second = await client.StreamAsync("second", tk, _ => { }, "s1");
        Assert.Equal("3", second.Text); // same id remembers: user + assistant + user = 3

        var other = await client.StreamAsync("other", tk, _ => { }, "s2");
        Assert.Equal("1", other.Text); // a different id is independent
    }

    // ---- AskAsync with onText forwards each delta and still returns the RunResult ----

    [Fact]
    public async Task AskAsync_WithOnText_ForwardsDeltasAndReturnsResult()
    {
        using var llm = StreamingChunksLlm("Hel", "lo");
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var client = ClientFor(llm);

        var deltas = new List<string>();
        var result = await client.AskAsync("hi", tk, id: null, onText: deltas.Add);

        Assert.Equal(new[] { "Hel", "lo" }, deltas); // each text delta forwarded, in order
        Assert.Equal("Hello", result.Text);          // RunResult still returned, fully assembled
    }

    // ---- OnMetric fires llm + tool + run events over a real run loop ----

    [Fact]
    public async Task OnMetric_Fires_Llm_Tool_And_Run()
    {
        using var llm = ToolThenFinalLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var events = new ConcurrentQueue<MetricEvent>();
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl,
            Style = "openai",
            Model = "gpt",
            ApiKey = "k",
            OnMetric = events.Enqueue,
        });
        tk.Register(NativeTool.Of("echo", "", null, _ => "hi"));

        var result = await client.RunAsync("hi", tk);
        Assert.Equal("done", result.Text);
        Assert.Equal(1, result.ToolCallCount);

        var all = events.ToList();
        Assert.Equal(2, all.Count(e => e.Event == "llm"));   // one per LLM round trip
        Assert.All(all.Where(e => e.Event == "llm"), e => Assert.Equal("ok", e.Status));

        var tool = Assert.Single(all, e => e.Event == "tool");
        Assert.Equal("echo", tool.Tool);
        Assert.Equal("native", tool.Source); // source comes from the tool
        Assert.False(tool.IsError);

        var run = Assert.Single(all, e => e.Event == "run");
        Assert.Equal("gpt", run.Model);
        Assert.Equal(2, run.Turns);
        Assert.Equal(1, run.ToolCalls);
        Assert.Null(run.Error);

        // The built-in registry saw the same events.
        var text = client.Metrics();
        Assert.Contains("toolnexus_llm_requests_total{model=\"gpt\",status=\"ok\"} 2", text);
        Assert.Contains("toolnexus_tool_calls_total{tool=\"echo\",source=\"native\",is_error=\"false\",pending=\"false\"} 1", text);
    }

    // ---- helpers ----

    private static string Norm(string raw) => raw.Replace("\r\n", "\n") + "\n";

    private static LlmClient ClientFor(StubServer llm)
        => LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl,
            Style = "openai",
            Model = "gpt",
            ApiKey = "k",
        });

    private static string ReadBody(HttpListenerContext ctx)
    {
        using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
        return reader.ReadToEnd();
    }

    /// <summary>Streaming OpenAI stub whose reply text is the number of messages it received.</summary>
    private static StubServer StreamingCountingLlm()
        => new(ctx =>
        {
            var req = Json.ParseObjectLoose(ReadBody(ctx));
            var count = ((req["messages"] as IEnumerable<object?>) ?? Enumerable.Empty<object?>()).Count();
            StubServer.Respond(ctx, 200, Sse(
                "{\"choices\":[{\"delta\":{\"content\":\"" + count + "\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));
        });

    /// <summary>Streaming OpenAI stub that emits the given content chunks as separate text deltas.</summary>
    private static StubServer StreamingChunksLlm(params string[] chunks)
        => new(ctx =>
        {
            var frames = chunks
                .Select(c => "{\"choices\":[{\"delta\":{\"content\":\"" + c + "\"}}]}")
                .Append("{\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}")
                .ToArray();
            StubServer.Respond(ctx, 200, Sse(frames));
        });

    /// <summary>Non-streaming OpenAI stub: first call returns one tool call, second returns final text.</summary>
    private static StubServer ToolThenFinalLlm()
    {
        var hits = 0;
        return new StubServer(ctx =>
        {
            var n = Interlocked.Increment(ref hits);
            if (n == 1)
            {
                StubServer.Respond(ctx, 200,
                    "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[" +
                    "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]," +
                    "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}");
                return;
            }
            StubServer.Respond(ctx, 200,
                "{\"choices\":[{\"message\":{\"content\":\"done\"}}]," +
                "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}");
        });
    }

    /// <summary>Assemble SSE frames into a <c>data:</c>-prefixed stream ending in <c>[DONE]</c>.</summary>
    private static string Sse(params string[] frames)
    {
        var sb = new StringBuilder();
        foreach (var f in frames) sb.Append("data: ").Append(f).Append("\n\n");
        sb.Append("data: [DONE]\n\n");
        return sb.ToString();
    }
}
