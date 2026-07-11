using System.Collections.Concurrent;
using System.Net;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// §10 Suspension observability + determinism. Mirrors the JS reference tests:
///   G2 — a suspension is <c>pending</c>, never a tool error; <c>AfterTool</c> sees only the resolved
///        result (the suspension itself skips the afterTool failure path).
///   Path B — a <c>BeforeTool</c> guard can raise a suspension with no tool code running; on approval the
///        real tool runs, and the guard-raised call is counted <c>pending</c>, not error.
///   G3 — two concurrent suspensions with no <c>WaitFor</c> surface the FIRST in call order, and the
///        second's placeholder result never pollutes the transcript.
/// Hermetic — a localhost <see cref="StubServer"/> stands in for the LLM; no network, no key.
/// </summary>
public class SuspensionMetricsTests
{
    // ---- G2: a suspension is pending, not a tool error; afterTool sees only the resolved result ----

    [Fact]
    public async Task Suspension_IsPending_NotError_AfterToolSeesResolvedOnly()
    {
        using var llm = QuestionThenDoneLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = true }); // `question` exists

        var events = new ConcurrentQueue<MetricEvent>();
        var afterToolSaw = new List<ToolResult>();
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl, Style = "openai", Model = "x", ApiKey = "k",
            WaitFor = r => Task.FromResult(new Answer
            {
                Id = r.Id, Ok = true, Data = new Dictionary<string, object?> { ["answers"] = new List<object?> { "a" } },
            }),
            Hooks = new LlmClient.Hooks { AfterTool = ev => { afterToolSaw.Add(ev.Result); return null; } },
            OnMetric = events.Enqueue,
        });

        var res = await client.RunAsync("ask", tk);

        Assert.Equal("done", res.Status); // WaitFor resolved the suspension and the run finished

        var questionEvents = events.Where(e => e.Event == "tool" && e.Tool == "question").ToList();
        var suspend = questionEvents.SingleOrDefault(e => e.Pending);
        Assert.NotNull(suspend);           // the suspension emitted a tool event tagged pending
        Assert.False(suspend!.IsError);    // a suspension is not a tool error

        Assert.Single(afterToolSaw);                              // afterTool ran exactly once — not on the suspension
        Assert.Null(ToolResult.PendingOf(afterToolSaw[0]));       // it saw the resolved result, never the suspension
    }

    // ---- Path B: a beforeTool guard-raised pending is honored, counted pending not error ----

    [Fact]
    public async Task GuardRaisedPending_RunsRealToolOnApproval_CountedPendingNotError()
    {
        using var llm = AddThenDoneLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(NativeTool.Of("add", "add", null,
            (IDictionary<string, object?> a, ToolContext? _) =>
                (object)$"{AsLong(a.Get("a")) + AsLong(a.Get("b"))}"));

        var events = new ConcurrentQueue<MetricEvent>();
        var asked = false;
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl, Style = "openai", Model = "x", ApiKey = "k",
            // guard raises a suspension with NO tool code running (path B)
            Hooks = new LlmClient.Hooks
            {
                BeforeTool = ev => ev.Name == "add" && !asked
                    ? LlmClient.ToolOverride.WithResult(ToolResult.Pending(new Request { Kind = "approval", Prompt = "approve add?" }))
                    : null,
            },
            WaitFor = r => { asked = true; return Task.FromResult(new Answer { Id = r.Id, Ok = true }); },
            OnMetric = events.Enqueue,
        });

        var res = await client.RunAsync("add them", tk);

        Assert.Equal("done", res.Status); // on approval the real tool ran and the run finished

        var addEvents = events.Where(e => e.Event == "tool" && e.Tool == "add").ToList();
        var suspend = addEvents.SingleOrDefault(e => e.Pending);
        Assert.NotNull(suspend);        // the guard-raised pending emitted a tool event tagged pending
        Assert.False(suspend!.IsError); // a guard-raised suspension is not a tool error

        Assert.Contains(res.ToolCalls, t => t.Name == "add" && t.Output == "3"); // the real tool ran after approval
    }

    // ---- G3: two concurrent suspensions with no waitFor surface the first, not the last ----

    [Fact]
    public async Task TwoConcurrentSuspensions_NoWaitFor_SurfaceFirst_NotLast()
    {
        using var llm = TwoQuestionsLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = true }); // no WaitFor
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = llm.BaseUrl, Style = "openai", Model = "x", ApiKey = "k",
        });

        var res = await client.RunAsync("ask two", tk);

        Assert.Equal("pending", res.Status);
        Assert.NotNull(res.Pending);
        Assert.Equal("First?", res.Pending!.Prompt); // the FIRST suspension in call order is surfaced, deterministically

        // the second concurrent suspension does not pollute the transcript
        var c2 = res.Messages.OfType<IDictionary<string, object?>>().Any(m => (m.Get("tool_call_id") as string) == "c2");
        Assert.False(c2);
    }

    // ---- helpers ----

    private static long AsLong(object? v) => v switch
    {
        null => 0,
        long l => l,
        int i => i,
        double d => (long)d,
        _ => long.TryParse(v.ToString(), out var p) ? p : 0,
    };

    private static string ReadBody(HttpListenerContext ctx)
    {
        using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
        return reader.ReadToEnd();
    }

    private static bool SawToolResult(string body)
        => ((Json.ParseObjectLoose(body).Get("messages") as IEnumerable<object?>) ?? Enumerable.Empty<object?>())
            .OfType<IDictionary<string, object?>>()
            .Any(m => (m.Get("role") as string) == "tool");

    private const string Usage = "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}";

    /// <summary>One <c>question</c> tool_call as a JSON string (arguments is a JSON-escaped string value).</summary>
    private static string QuestionCall(string id, string text)
        => "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"question\",\"arguments\":\"" +
           "{\\\"questions\\\":[{\\\"question\\\":\\\"" + text + "\\\"}]}" + "\"}}";

    /// <summary>Turn 1 (no tool result yet) calls `question`; turn 2 returns a final answer.</summary>
    private static StubServer QuestionThenDoneLlm()
        => new(ctx =>
        {
            var json = SawToolResult(ReadBody(ctx))
                ? "{\"choices\":[{\"message\":{\"content\":\"done\"}}]," + Usage + "}"
                : "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[" + QuestionCall("c1", "Pick?") + "]}}]," + Usage + "}";
            StubServer.Respond(ctx, 200, json);
        });

    /// <summary>Turn 1 calls `add`; turn 2 (once it has a tool result) returns "3".</summary>
    private static StubServer AddThenDoneLlm()
        => new(ctx =>
        {
            var json = SawToolResult(ReadBody(ctx))
                ? "{\"choices\":[{\"message\":{\"content\":\"3\"}}]," + Usage + "}"
                : "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[" +
                  "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"add\",\"arguments\":\"{\\\"a\\\":1,\\\"b\\\":2}\"}}]}}]," + Usage + "}";
            StubServer.Respond(ctx, 200, json);
        });

    /// <summary>A single turn that emits TWO `question` tool_calls (First?, Second?).</summary>
    private static StubServer TwoQuestionsLlm()
        => new(ctx =>
        {
            ReadBody(ctx); // drain
            var json = "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[" +
                       QuestionCall("c1", "First?") + "," + QuestionCall("c2", "Second?") +
                       "]}}]," + Usage + "}";
            StubServer.Respond(ctx, 200, json);
        });
}
