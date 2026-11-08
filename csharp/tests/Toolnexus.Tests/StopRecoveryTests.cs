using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>
/// SPEC §8 "Stop-reason recovery": replays conformance/stop_recovery/cases.json in both styles
/// (openai, anthropic) and both loops (run, stream) against a mock provider wire.
/// </summary>
public class StopRecoveryTests
{
    private static string CasesPath()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir != null; dir = dir.Parent)
        {
            var c = Path.Combine(dir.FullName, "conformance", "stop_recovery", "cases.json");
            if (File.Exists(c)) return c;
        }
        throw new InvalidOperationException("conformance/stop_recovery/cases.json not found");
    }

    private static readonly Lazy<JsonElement> Fixture = new(() => JsonDocument.Parse(File.ReadAllText(CasesPath())).RootElement.Clone());

    public static IEnumerable<object[]> Cases()
    {
        foreach (var c in Fixture.Value.GetProperty("cases").EnumerateArray())
            foreach (var style in new[] { "openai", "anthropic" })
                foreach (var stream in new[] { false, true })
                    yield return new object[] { c.GetProperty("name").GetString()!, style, stream };
    }

    [Fact]
    public void PinnedMessagesMatchFixture()
    {
        var m = Fixture.Value.GetProperty("messages");
        Assert.Equal(StopRecoveryMessages.Nudge, m.GetProperty("NUDGE").GetString());
        Assert.Equal(StopRecoveryMessages.Continue, m.GetProperty("CONTINUE").GetString());
        Assert.Equal(StopRecoveryMessages.Corrective, m.GetProperty("CORRECTIVE").GetString());
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task Replay(string name, string style, bool stream)
    {
        var c = Fixture.Value.GetProperty("cases").EnumerateArray().First(x => x.GetProperty("name").GetString() == name);
        var responses = c.GetProperty("responses").EnumerateArray().ToList();
        var calls = 0;
        var wire = new FnWire(_ =>
        {
            var i = calls++;
            var r = responses[Math.Min(i, responses.Count - 1)];
            return stream ? StreamWire(style, r, i) : RunWire(style, r, i);
        });

        var toolRuns = new List<string>();
        var echo = NativeTool.Of("echo", "echo", new Dictionary<string, object?> { ["type"] = "object" },
            args => { lock (toolRuns) toolRuns.Add(Json.Stringify(args)); return "ok"; });
        var tk = (await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false })).Register(echo);

        var client = HostEngineAsksTests.Client(wire, style, o =>
        {
            o.StopRecovery = c.GetProperty("stopRecovery").GetBoolean();
            if (c.TryGetProperty("maxTurns", out var mt)) o.MaxTurns = mt.GetInt32();
        });
        var result = stream
            ? await client.StreamAsync("go", tk, _ => { })
            : await client.RunAsync("go", tk);

        var exp = c.GetProperty("expect");
        Assert.Equal(exp.GetProperty("turns").GetInt32(), calls);
        Assert.Equal(exp.GetProperty("turns").GetInt32(), result.Turns);
        Assert.Equal(exp.GetProperty("text").GetString(), result.Text);

        var pinned = Fixture.Value.GetProperty("messages");
        var texts = new Dictionary<string, string>();
        foreach (var p in pinned.EnumerateObject()) texts[p.Value.GetString()!] = p.Name;
        var got = new List<string>();
        foreach (var body in wire.Bodies.Skip(1))
        {
            var msgs = (List<object?>)body["messages"]!;
            if (msgs[^1] is IDictionary<string, object?> last && last.Get("role") as string == "user"
                && last.Get("content") is string s && texts.TryGetValue(s, out var key))
            {
                Assert.Equal(2, last.Count); // exactly {role, content}
                got.Add(key);
            }
        }
        Assert.Equal(exp.GetProperty("recoveries").EnumerateArray().Select(x => x.GetString()!).ToList(), got);

        var expRuns = exp.GetProperty("toolRuns").EnumerateArray()
            .Select(x => Json.Stringify(Json.ParseObjectLoose(x.GetRawText()))).ToList();
        Assert.Equal(expRuns, toolRuns);
    }

    private static string Str(JsonElement r, string p) => r.TryGetProperty(p, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString()! : "";

    private static List<(string Name, string Args)> Calls(JsonElement r)
        => r.TryGetProperty("toolCalls", out var tc)
            ? tc.EnumerateArray().Select(t => (t.GetProperty("name").GetString()!, t.GetProperty("arguments").GetString()!)).ToList()
            : new();

    private static string Stop(string style, string stop) => (style, stop) switch
    {
        ("openai", "end") => "stop", ("openai", "truncated") => "length", ("openai", _) => "tool_calls",
        (_, "end") => "end_turn", (_, "truncated") => "max_tokens", _ => "tool_use",
    };

    private static object? AnthropicInput(string raw)
    {
        try
        {
            var d = JsonDocument.Parse(raw).RootElement;
            if (d.ValueKind == JsonValueKind.Object) return d.Clone();
        }
        catch { }
        return raw;
    }

    private static string RunWire(string style, JsonElement r, int turn)
    {
        var text = Str(r, "text");
        var thinking = Str(r, "thinking");
        var calls = Calls(r);
        var stop = Stop(style, r.GetProperty("stop").GetString()!);
        if (style == "openai")
        {
            var msg = new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = text };
            if (thinking.Length > 0) msg["reasoning_content"] = thinking;
            if (calls.Count > 0)
                msg["tool_calls"] = calls.Select((t, i) => new Dictionary<string, object?>
                {
                    ["id"] = $"c{turn}_{i}", ["type"] = "function",
                    ["function"] = new Dictionary<string, object?> { ["name"] = t.Name, ["arguments"] = t.Args },
                }).ToList();
            return JsonSerializer.Serialize(new { choices = new[] { new { message = msg, finish_reason = stop } } });
        }
        var content = new List<object?>();
        if (thinking.Length > 0) content.Add(new { type = "thinking", thinking, signature = "sig" });
        if (text.Length > 0 || calls.Count == 0) content.Add(new { type = "text", text });
        for (var i = 0; i < calls.Count; i++)
            content.Add(new { type = "tool_use", id = $"c{turn}_{i}", name = calls[i].Name, input = AnthropicInput(calls[i].Args) });
        return JsonSerializer.Serialize(new { content, stop_reason = stop });
    }

    private static string StreamWire(string style, JsonElement r, int turn)
    {
        var text = Str(r, "text");
        var thinking = Str(r, "thinking");
        var calls = Calls(r);
        var stop = Stop(style, r.GetProperty("stop").GetString()!);
        var ev = new List<object>();
        if (style == "openai")
        {
            if (thinking.Length > 0) ev.Add(new { choices = new[] { new { delta = new { reasoning_content = thinking } } } });
            if (text.Length > 0) ev.Add(new { choices = new[] { new { delta = new { content = text } } } });
            for (var i = 0; i < calls.Count; i++)
                ev.Add(new { choices = new[] { new { delta = new { tool_calls = new[] { new { index = i, id = $"c{turn}_{i}", type = "function", function = new { name = calls[i].Name, arguments = calls[i].Args } } } } } } });
            ev.Add(new { choices = new[] { new { delta = new { }, finish_reason = stop } } });
            ev.Add("[DONE]");
            return FnWire.Sse(ev.ToArray());
        }
        ev.Add(new { type = "message_start", message = new { usage = new { input_tokens = 1 } } });
        var idx = 0;
        if (thinking.Length > 0)
        {
            ev.Add(new { type = "content_block_start", index = idx, content_block = new { type = "thinking", thinking = "" } });
            ev.Add(new { type = "content_block_delta", index = idx, delta = new { type = "thinking_delta", thinking } });
            ev.Add(new { type = "content_block_delta", index = idx, delta = new { type = "signature_delta", signature = "sig" } });
            ev.Add(new { type = "content_block_stop", index = idx++ });
        }
        if (text.Length > 0)
        {
            ev.Add(new { type = "content_block_start", index = idx, content_block = new { type = "text", text = "" } });
            ev.Add(new { type = "content_block_delta", index = idx, delta = new { type = "text_delta", text } });
            ev.Add(new { type = "content_block_stop", index = idx++ });
        }
        for (var i = 0; i < calls.Count; i++)
        {
            ev.Add(new { type = "content_block_start", index = idx, content_block = new { type = "tool_use", id = $"c{turn}_{i}", name = calls[i].Name, input = new { } } });
            ev.Add(new { type = "content_block_delta", index = idx, delta = new { type = "input_json_delta", partial_json = calls[i].Args } });
            ev.Add(new { type = "content_block_stop", index = idx++ });
        }
        ev.Add(new { type = "message_delta", delta = new { stop_reason = stop }, usage = new { output_tokens = 1 } });
        ev.Add(new { type = "message_stop" });
        return FnWire.Sse(ev.ToArray());
    }
}
