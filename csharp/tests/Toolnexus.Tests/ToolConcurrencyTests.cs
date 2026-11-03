using System.Net;
using System.Text;
using System.Text.Json;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>add-tool-concurrency (SPEC §8 "Tool scheduling"), C# port of golang/tool_concurrency_test.go.
/// A scripted handler answers turn 1 with one call per name, turn 2 with "done", in all four wire
/// modes (openai/anthropic × run/stream). Plus the §7D background-run recipe.</summary>
public class ToolConcurrencyTests
{
    private sealed class ScriptedHandler(List<string> names) : HttpMessageHandler
    {
        private int _turn;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var raw = request.Content == null ? "{}" : await request.Content.ReadAsStringAsync(ct);
            var body = JsonDocument.Parse(raw).RootElement;
            var stream = body.TryGetProperty("stream", out var s) && s.ValueKind == JsonValueKind.True;
            var first = Interlocked.Increment(ref _turn) == 1;
            var anth = request.RequestUri!.AbsolutePath.Contains("messages");
            string text;
            string type;
            if (!stream)
            {
                type = "application/json";
                if (anth)
                {
                    text = first
                        ? JsonSerializer.Serialize(new { content = names.Select((n, i) => new { type = "tool_use", id = $"c{i + 1}", name = n, input = new { } }), stop_reason = "tool_use" })
                        : JsonSerializer.Serialize(new { content = new[] { new { type = "text", text = "done" } }, stop_reason = "end_turn" });
                }
                else
                {
                    text = first
                        ? JsonSerializer.Serialize(new { choices = new[] { new { message = new { role = "assistant", content = (string?)null, tool_calls = names.Select((n, i) => new { id = $"c{i + 1}", type = "function", function = new { name = n, arguments = "{}" } }) } } } })
                        : JsonSerializer.Serialize(new { choices = new[] { new { message = new { role = "assistant", content = "done" } } } });
                }
            }
            else
            {
                type = "text/event-stream";
                var ev = new List<object>();
                if (!anth)
                {
                    if (first)
                    {
                        for (var i = 0; i < names.Count; i++)
                            ev.Add(new { choices = new[] { new { delta = new { tool_calls = new[] { new { index = i, id = $"c{i + 1}", type = "function", function = new { name = names[i], arguments = "{}" } } } } } } });
                        ev.Add(new { choices = new[] { new { delta = new { }, finish_reason = "tool_calls" } } });
                    }
                    else
                    {
                        ev.Add(new { choices = new[] { new { delta = new { content = "done" } } } });
                        ev.Add(new { choices = new[] { new { delta = new { }, finish_reason = "stop" } } });
                    }
                }
                else
                {
                    if (first)
                    {
                        for (var i = 0; i < names.Count; i++)
                        {
                            ev.Add(new { type = "content_block_start", index = i, content_block = new { type = "tool_use", id = $"c{i + 1}", name = names[i] } });
                            ev.Add(new { type = "content_block_delta", index = i, delta = new { type = "input_json_delta", partial_json = "{}" } });
                            ev.Add(new { type = "content_block_stop", index = i });
                        }
                        ev.Add(new { type = "message_delta", delta = new { stop_reason = "tool_use" } });
                    }
                    else
                    {
                        ev.Add(new { type = "content_block_start", index = 0, content_block = new { type = "text" } });
                        ev.Add(new { type = "content_block_delta", index = 0, delta = new { type = "text_delta", text = "done" } });
                        ev.Add(new { type = "message_delta", delta = new { stop_reason = "end_turn" } });
                    }
                    ev.Add(new { type = "message_stop" });
                }
                var sb = new StringBuilder();
                foreach (var e in ev) sb.Append("data: ").Append(JsonSerializer.Serialize(e)).Append("\n\n");
                if (!anth) sb.Append("data: [DONE]\n\n");
                text = sb.ToString();
            }
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(text, Encoding.UTF8, type) };
        }
    }

    private sealed class Tracker
    {
        private readonly object _lock = new();
        private int _inflight;
        public int Peak;
        public readonly List<string> Log = new();

        public ITool Tool(string name, bool readOnly)
        {
            var t = NativeTool.OfAsync(name, name, new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() },
                async (_, _) =>
                {
                    lock (_lock) { _inflight++; Peak = Math.Max(Peak, _inflight); Log.Add($"start {name}"); }
                    await Task.Delay(60);
                    lock (_lock) { _inflight--; Log.Add($"end {name}"); }
                    return (object?)$"out {name}";
                });
            if (readOnly) t.Hints = new ToolHints(ReadOnly: true);
            return t;
        }
    }

    private static async Task<List<string>> Run(string style, bool stream, List<string> names, List<ITool> tools, int? cap = null)
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = tools });
        var o = new LlmClient.Options
        {
            BaseUrl = style == "openai" ? "http://mock.local/v1" : "http://mock.local",
            Style = style, Model = "m", ApiKey = "k",
            HttpClient = new HttpClient(new ScriptedHandler(names)),
        };
        if (cap is { } c) o.MaxToolConcurrency = c;
        var client = LlmClient.Create(o);
        var r = stream ? await client.StreamAsync("go", tk, _ => { }) : await client.RunAsync("go", tk);
        return r.ToolCalls.Select(tc => tc.Name).ToList();
    }

    private static List<string> Reads(int n) => Enumerable.Range(1, n).Select(i => $"r{i}").ToList();
    private static List<ITool> Tools(Tracker tr, List<string> names) => names.Select(n => tr.Tool(n, n.StartsWith('r'))).ToList();

    public static TheoryData<string, bool> Modes => new() { { "openai", false }, { "anthropic", false }, { "openai", true }, { "anthropic", true } };

    [Theory, MemberData(nameof(Modes))]
    public async Task UnsafeCallsSerialize(string style, bool stream)
    {
        var tr = new Tracker();
        var names = new List<string> { "w1", "w2" };
        var got = await Run(style, stream, names, Tools(tr, names));
        Assert.Equal(1, tr.Peak);
        Assert.Equal(new[] { "start w1", "end w1", "start w2", "end w2" }, tr.Log);
        Assert.Equal(names, got);
    }

    [Theory, MemberData(nameof(Modes))]
    public async Task ReadOnlyCallsOverlap(string style, bool stream)
    {
        var tr = new Tracker();
        var names = Reads(6);
        var got = await Run(style, stream, names, Tools(tr, names));
        Assert.Equal(6, tr.Peak);
        Assert.Equal(names, got);
    }

    [Theory, MemberData(nameof(Modes))]
    public async Task CapIsHonoured(string style, bool stream)
    {
        var tr = new Tracker();
        var names = Reads(6);
        await Run(style, stream, names, Tools(tr, names), 2);
        Assert.Equal(2, tr.Peak);
    }

    [Theory, MemberData(nameof(Modes))]
    public async Task MixedRunsKeepOrder(string style, bool stream)
    {
        var tr = new Tracker();
        var names = new List<string> { "r1", "r2", "w", "r3", "r4" };
        var got = await Run(style, stream, names, Tools(tr, names));
        foreach (var r in new[] { "r1", "r2" }) Assert.True(tr.Log.IndexOf($"end {r}") < tr.Log.IndexOf("start w"), string.Join(",", tr.Log));
        foreach (var r in new[] { "r3", "r4" }) Assert.True(tr.Log.IndexOf($"start {r}") > tr.Log.IndexOf("end w"), string.Join(",", tr.Log));
        Assert.Equal(2, tr.Peak);
        Assert.Equal(names, got);
    }

    [Fact]
    public void EnvOverrideAndFallback()
    {
        Assert.Equal(3, ToolScheduler.ResolveMaxToolConcurrency(null, _ => "3"));
        Assert.Equal(4, ToolScheduler.ResolveMaxToolConcurrency(4, _ => "3"));
        foreach (var bad in new[] { "0", "-2", "abc", "", null })
            Assert.Equal(10, ToolScheduler.ResolveMaxToolConcurrency(null, _ => bad));
        Assert.Equal(10, ToolScheduler.ResolveMaxToolConcurrency(0, _ => null));
    }

    [Fact]
    public async Task SafetyRule()
    {
        await using (var tk = await Toolkit.CreateAsync(new Toolkit.Options()))
        {
            var want = new Dictionary<string, bool>
            {
                ["read"] = true, ["grep"] = true, ["glob"] = true, ["webfetch"] = true,
                ["bash"] = false, ["write"] = false, ["edit"] = false, ["apply_patch"] = false,
            };
            foreach (var (name, safe) in want)
            {
                var t = tk.Get(name);
                Assert.NotNull(t);
                Assert.Equal(safe, ToolScheduler.IsConcurrencySafe(t, new Dictionary<string, object?>()));
            }
        }
        var plain = NativeTool.Of("x", "", null, _ => "");
        Assert.False(ToolScheduler.IsConcurrencySafe(plain, new Dictionary<string, object?>()));
        var own = NativeTool.Of("y", "", null, _ => "");
        own.Hints = new ToolHints(ReadOnly: true);
        own.ConcurrencySafe = a => a.TryGetValue("dry", out var d) && d is true;
        Assert.False(ToolScheduler.IsConcurrencySafe(own, new Dictionary<string, object?>()));
        Assert.True(ToolScheduler.IsConcurrencySafe(own, new Dictionary<string, object?> { ["dry"] = true }));
    }

    /// <summary>SPEC §7D "Background runs": spawn + wake return at once with the id; a background
    /// wait delivers the completion to the host's callback. No new API; this pins the recipe.</summary>
    [Fact]
    public async Task BackgroundRunRecipe()
    {
        var rt = new AgentRuntime(new RuntimeOptions { Handler = new SubagentMockHandler(), Registry = AgentRuntimeTests.Registry(), ApiKey = "test" });
        var h = rt.Spawn(rt.Root, "coordinator").Handle!;
        Assert.False(string.IsNullOrEmpty(h.Id));
        var done = new TaskCompletionSource<(string Id, string Status, string Text)>();
        _ = rt.WaitAsync(h).ContinueWith(t => done.SetResult((h.Id, t.Result.Status, t.Result.Text))); // onDone(id, r)
        rt.Wake(h, "answer using two lookups");
        var got = await done.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(h.Id, got.Id);
        Assert.Equal("done", got.Status);
        Assert.Contains("found:", got.Text);
    }
}
