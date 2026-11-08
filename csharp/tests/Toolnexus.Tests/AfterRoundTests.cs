using System.Net;
using System.Text;
using Toolnexus.Agents;
using SubAgent = Toolnexus.Agents.Agent;

namespace Toolnexus.Tests;

/// <summary>SPEC §8 <c>AfterRound</c> (change add-round-boundary-hook, #192). Shared fixture:
/// examples/round-boundary/fixture.json (R1-R6) — run + stream × openai + anthropic, and the
/// §7D agent loop. Hermetic: a scripted handler replays replies ("@call" = an echo tool call).</summary>
public class AfterRoundTests
{
    private sealed class Wire : HttpMessageHandler
    {
        private readonly string[] _replies;
        public readonly List<Dictionary<string, object?>> Bodies = new();
        public Wire(params string[] replies) => _replies = replies;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var body = Json.ParseObjectLoose(await request.Content!.ReadAsStringAsync(ct));
            string reply;
            lock (Bodies) { Bodies.Add(body); reply = _replies[Math.Min(Bodies.Count - 1, _replies.Length - 1)]; }
            var call = reply == "@call";
            var anthropic = request.RequestUri!.AbsolutePath.EndsWith("/messages");
            var stream = body.Get("stream") is true;
            const string args = "{\\\"text\\\":\\\"hi\\\"}";
            string text, type;
            if (stream)
            {
                type = "text/event-stream";
                if (anthropic)
                    text = call
                        ? "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"echo\"}}\n\n" +
                          "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"" + args + "\"}}\n\n" +
                          "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
                        : "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                          "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + reply + "\"}}\n\n" +
                          "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n";
                else
                    text = call
                        ? "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"echo\",\"arguments\":\"" + args + "\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
                        : "data: {\"choices\":[{\"delta\":{\"content\":\"" + reply + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            }
            else
            {
                type = "application/json";
                if (anthropic)
                    text = call
                        ? "{\"content\":[{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"echo\",\"input\":{\"text\":\"hi\"}}],\"stop_reason\":\"tool_use\"}"
                        : "{\"content\":[{\"type\":\"text\",\"text\":\"" + reply + "\"}],\"stop_reason\":\"end_turn\"}";
                else
                    text = call
                        ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"" + args + "\"}}]},\"finish_reason\":\"tool_calls\"}]}"
                        : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + reply + "\"},\"finish_reason\":\"stop\"}]}";
            }
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(text, Encoding.UTF8, type) };
        }

        public List<object?> Sent(int i) => (List<object?>)Bodies[i]["messages"]!;
    }

    private sealed class RoundBoom : Exception { public RoundBoom() : base("round-boom") { } }

    private static LlmClient.Options Opts(Wire w, string style, LlmClient.Hooks? hooks, int maxTurns = 10) => new()
    {
        BaseUrl = "http://stub.invalid/v1", Style = style, Model = "m", ApiKey = "k",
        HttpClient = new HttpClient(w), Hooks = hooks, MaxTurns = maxTurns,
    };

    private static async Task<Toolkit> Kit()
    {
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(NativeTool.Of("echo", "echo", null, (IDictionary<string, object?> a) => (object)ToolResult.Ok(a.Get("text")?.ToString() ?? "")));
        return tk;
    }

    private static async Task<LlmClient.RunResult> Go(string mode, LlmClient.Options o, Toolkit tk) => mode switch
    {
        "run" => await LlmClient.Create(o).RunAsync("go", tk),
        "stream" => await LlmClient.Create(o).StreamAsync("go", tk, _ => { }),
        _ => (await new SubAgent("a", new AgentSpec { Does = "x" }).Loop(o, tk).RunAsync("go")).Result,
    };

    public static IEnumerable<object[]> Entries()
    {
        foreach (var style in new[] { "openai", "anthropic" })
            foreach (var mode in new[] { "run", "stream", "agent" })
                yield return new object[] { style, mode };
    }

    private static IDictionary<string, object?> Last(List<object?> msgs, int fromEnd) => (IDictionary<string, object?>)msgs[^fromEnd]!;

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task R1_InjectAfterToolResults(string style, string mode)
    {
        var w = new Wire("@call", "done");
        var calls = new List<string>();
        var h = new LlmClient.Hooks { AfterRound = ev => { calls.Add($"{ev.Turn}:{ev.Final}:{ev.Text}"); return ev.Final ? null : LlmClient.RoundInjection.Of("todo: 2 open"); } };
        await using var tk = await Kit();
        var r = await Go(mode, Opts(w, style, h), tk);
        Assert.Equal(2, w.Bodies.Count);
        Assert.Equal("done", r.Text);
        Assert.Equal("user", Last(w.Sent(1), 1)["role"]);
        Assert.Equal("todo: 2 open", Last(w.Sent(1), 1)["content"]);
        Assert.Equal(new[] { "0:False:", "1:True:done" }, calls);
    }

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task R2_ContinueWhenTheModelStops(string style, string mode)
    {
        var w = new Wire("first", "second");
        var calls = new List<string>();
        var h = new LlmClient.Hooks { AfterRound = ev => { calls.Add($"{ev.Turn}:{ev.Final}:{ev.Text}"); return ev.Final && ev.Turn == 0 ? LlmClient.RoundInjection.Of("keep going") : null; } };
        await using var tk = await Kit();
        var r = await Go(mode, Opts(w, style, h), tk);
        Assert.Equal(2, w.Bodies.Count);
        Assert.Equal("second", r.Text);
        Assert.Equal("assistant", Last(w.Sent(1), 2)["role"]);
        Assert.Equal("keep going", Last(w.Sent(1), 1)["content"]);
        Assert.Equal(new[] { "0:True:first", "1:True:second" }, calls);
    }

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task R3_EmptyInjectionEndsNormally(string style, string mode)
    {
        var w = new Wire("only");
        var n = 0;
        await using var tk = await Kit();
        var r = await Go(mode, Opts(w, style, new LlmClient.Hooks { AfterRound = _ => { n++; return LlmClient.RoundInjection.Of(""); } }), tk);
        Assert.Single(w.Bodies);
        Assert.Equal("only", r.Text);
        Assert.Equal(1, n);
    }

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task R4_NoContinuationPastMaxTurns(string style, string mode)
    {
        var w = new Wire("only");
        var n = 0;
        await using var tk = await Kit();
        var r = await Go(mode, Opts(w, style, new LlmClient.Hooks { AfterRound = _ => { n++; return LlmClient.RoundInjection.Of("again"); } }, 1), tk);
        Assert.Single(w.Bodies);
        Assert.Equal("only", r.Text);
        Assert.Equal(0, n);
    }

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task R5_FailingHookStopsTheRun(string style, string mode)
    {
        var w = new Wire("@call", "never");
        await using var tk = await Kit();
        await Assert.ThrowsAsync<RoundBoom>(() => Go(mode, Opts(w, style, new LlmClient.Hooks { AfterRound = _ => throw new RoundBoom() }), tk));
        Assert.Single(w.Bodies);
    }

    [Theory]
    [MemberData(nameof(Entries))]
    public async Task AbsentHook_IsByteIdentical(string style, string mode)
    {
        var a = new Wire("@call", "done");
        var b = new Wire("@call", "done");
        await using var tk = await Kit();
        await Go(mode, Opts(a, style, null), tk);
        await Go(mode, Opts(b, style, new LlmClient.Hooks { AfterRound = _ => null }), tk);
        Assert.Equal(Json.Stringify(a.Bodies), Json.Stringify(b.Bodies));
    }
}
