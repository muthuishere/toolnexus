using System.Net;
using System.Text;
using System.Text.Json;
using Toolnexus;

namespace Toolnexus.Tests;

/// <summary>
/// Reactive compaction on context overflow (SPEC §7F.1, issue #194). Replays the whole shared
/// fixture <c>conformance/reactive_compaction/cases.json</c>: every detection case against
/// <see cref="LlmClient.IsContextOverflow"/>, and every loop case through run AND stream in both
/// styles, against an in-test fake HTTP handler (no network).
/// </summary>
public class ReactiveCompactionTests
{
    private static JsonElement Cases()
    {
        var path = Path.Combine(Path.GetDirectoryName(TestFixtures.Fixture("mcp.json"))!, "..", "conformance", "reactive_compaction", "cases.json");
        return JsonDocument.Parse(File.ReadAllText(path)).RootElement.Clone();
    }

    [Fact]
    public void Detection_MatchesFixture()
    {
        var root = Cases();
        var n = 0;
        foreach (var c in root.GetProperty("detection").EnumerateArray())
        {
            var got = LlmClient.IsContextOverflow(c.GetProperty("status").GetInt32(), c.GetProperty("body").GetString()!);
            Assert.True(c.GetProperty("overflow").GetBoolean() == got, c.GetProperty("name").GetString());
            n++;
        }
        Assert.Equal(10, n);
    }

    /// <summary>Scripted provider: one response per HTTP call, in order. Records the number of
    /// messages each request carried so the rebuilt (compacted) body can be checked.</summary>
    private sealed class ScriptHandler : HttpMessageHandler
    {
        private readonly string[] _script;
        private readonly string _style;
        private readonly JsonElement _root;
        public readonly List<int> MessageCounts = new();
        public int Calls => MessageCounts.Count;

        public ScriptHandler(string[] script, string style, JsonElement root)
        {
            _script = script; _style = style; _root = root;
        }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var reqBody = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct)).RootElement;
            var stream = reqBody.TryGetProperty("stream", out var s) && s.ValueKind == JsonValueKind.True;
            var i = MessageCounts.Count;
            MessageCounts.Add(reqBody.GetProperty("messages").GetArrayLength());
            var step = i < _script.Length ? _script[i] : "text";
            if (step is "overflow" or "bad400")
            {
                var e = step == "overflow" ? _root.GetProperty("overflowBodies").GetProperty(_style) : _root.GetProperty("bad400");
                return new HttpResponseMessage((HttpStatusCode)e.GetProperty("status").GetInt32())
                {
                    Content = new StringContent(e.GetProperty("body").GetString()!, Encoding.UTF8, "application/json"),
                };
            }
            var tool = step == "tool";
            var id = $"call_{i}";
            string body;
            if (!stream)
            {
                body = _style == "openai"
                    ? tool
                        ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]}}]}"
                        : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}"
                    : tool
                        ? "{\"content\":[{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"ping\",\"input\":{}}],\"stop_reason\":\"tool_use\"}"
                        : "{\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"stop_reason\":\"end_turn\"}";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
            }
            var sb = new StringBuilder();
            if (_style == "openai")
            {
                sb.Append(tool
                    ? "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id + "\",\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]}}]}\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n\n");
                sb.Append("data: [DONE]\n\n");
            }
            else
            {
                sb.Append("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n");
                if (tool)
                {
                    sb.Append("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"ping\",\"input\":{}}}\n\n");
                    sb.Append("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\n");
                }
                else
                {
                    sb.Append("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n");
                    sb.Append("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}\n\n");
                }
                sb.Append("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n");
                sb.Append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + (tool ? "tool_use" : "end_turn") + "\"},\"usage\":{\"output_tokens\":1}}\n\n");
                sb.Append("data: {\"type\":\"message_stop\"}\n\n");
            }
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(sb.ToString(), Encoding.UTF8, "text/event-stream") };
        }
    }

    private static List<object?> History()
    {
        var h = new List<object?>();
        for (var i = 0; i < 8; i++)
            h.Add(new Dictionary<string, object?> { ["role"] = i % 2 == 0 ? "user" : "assistant", ["content"] = $"h{i}" });
        return h;
    }

    public static IEnumerable<object[]> LoopModes()
    {
        foreach (var style in new[] { "openai", "anthropic" })
            foreach (var stream in new[] { false, true })
                yield return new object[] { style, stream };
    }

    [Theory]
    [MemberData(nameof(LoopModes))]
    public async Task Loops_MatchFixture(string style, bool stream)
    {
        var root = Cases();
        var ran = 0;
        foreach (var c in root.GetProperty("loops").EnumerateArray())
        {
            var name = $"{c.GetProperty("name").GetString()} [{style}, {(stream ? "stream" : "run")}]";
            var script = c.GetProperty("script").EnumerateArray().Select(x => x.GetString()!).ToArray();
            var noop = c.TryGetProperty("hookNoop", out var hn) && hn.GetBoolean();
            var expect = c.GetProperty("expect");
            var handler = new ScriptHandler(script, style, root);
            var hookCalls = 0;
            var opts = new LlmClient.Options
            {
                BaseUrl = "http://scripted.invalid", Style = style, Model = "m", ApiKey = "not-a-real-key",
                HttpHandler = handler, Retries = 0, MaxTurns = 50,
            };
            if (c.GetProperty("option").GetBoolean())
            {
                opts.CompactOnOverflow = ev =>
                {
                    hookCalls++;
                    return noop ? null : new LlmClient.LLMOverride(ev.Messages.Skip(1).ToList());
                };
            }
            var ping = NativeTool.Of("ping", "Answers ok.", null, (IDictionary<string, object?> _, ToolContext? _) => (object)"ok");
            await using var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false).WithExtraTools(ping));
            var client = LlmClient.Create(opts);

            Task<LlmClient.RunResult> Go()
            {
                if (!stream) return client.RunAsync("go", tk, History());
                client.ConversationStore().SaveAsync("conv", History()).GetAwaiter().GetResult();
                return client.StreamAsync("go", tk, _ => { }, "conv");
            }

            if (expect.TryGetProperty("text", out var text))
            {
                var res = await Go();
                Assert.True(text.GetString() == res.Text, name);
                Assert.True(expect.GetProperty("compactions").GetInt32() == hookCalls, name + " compactions");
                // A compaction does not consume a turn: turns = successful calls.
                Assert.True(script.Count(s => s != "overflow") == res.Turns, name + " turns");
            }
            else
            {
                var ex = await Assert.ThrowsAnyAsync<Exception>(Go);
                var kind = expect.GetProperty("error").GetString();
                if (kind == "LlmHttpError")
                {
                    var pe = Assert.IsType<LlmClient.ProviderException>(ex);
                    Assert.True(expect.GetProperty("status").GetInt32() == pe.Status, name);
                }
                else
                {
                    var co = Assert.IsType<LlmClient.ContextOverflowException>(ex);
                    Assert.True(expect.GetProperty("compactions").GetInt32() == co.Compactions, name);
                    Assert.True(expect.GetProperty("message").GetString() == co.Message, name);
                    Assert.Equal(400, co.Status);
                    Assert.NotEmpty(co.Body);
                }
            }
            Assert.True(c.GetProperty("calls").GetInt32() == handler.Calls, $"{name} calls: {handler.Calls}");
            // The retry after a compaction carries the compacted transcript (one fewer message).
            for (var i = 0; i + 1 < handler.Calls; i++)
                if (script[i] == "overflow" && hookCalls > 0)
                    Assert.True(handler.MessageCounts[i + 1] == handler.MessageCounts[i] - 1, $"{name} rebuilt body at call {i + 1}");
            ran++;
        }
        Assert.Equal(7, ran);
    }
}
