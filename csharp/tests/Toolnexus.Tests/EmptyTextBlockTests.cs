using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>
/// Issue #235 / SPEC §8.x: an empty anthropic text block beside tool_use must never be stored in
/// the assistant message, or the next request is rejected with a 400.
/// </summary>
public class EmptyTextBlockTests
{
    private static string Turn1(bool stream) => stream
        ? FnWire.Sse(
            new { type = "message_start", message = new { usage = new { input_tokens = 1 } } },
            new { type = "content_block_start", index = 0, content_block = new { type = "text", text = "" } },
            new { type = "content_block_stop", index = 0 },
            new { type = "content_block_start", index = 1, content_block = new { type = "tool_use", id = "toolu_0", name = "echo", input = new { } } },
            new { type = "content_block_delta", index = 1, delta = new { type = "input_json_delta", partial_json = "{}" } },
            new { type = "content_block_stop", index = 1 },
            new { type = "message_delta", delta = new { stop_reason = "tool_use" }, usage = new { output_tokens = 1 } },
            new { type = "message_stop" })
        : JsonSerializer.Serialize(new
        {
            content = new object[] { new { type = "text", text = "" }, new { type = "tool_use", id = "toolu_0", name = "echo", input = new { } } },
            stop_reason = "tool_use",
        });

    private static string Turn2(bool stream) => stream
        ? FnWire.Sse(
            new { type = "message_start", message = new { usage = new { input_tokens = 1 } } },
            new { type = "content_block_start", index = 0, content_block = new { type = "text", text = "" } },
            new { type = "content_block_delta", index = 0, delta = new { type = "text_delta", text = "done" } },
            new { type = "content_block_stop", index = 0 },
            new { type = "message_delta", delta = new { stop_reason = "end_turn" }, usage = new { output_tokens = 1 } },
            new { type = "message_stop" })
        : JsonSerializer.Serialize(new { content = new object[] { new { type = "text", text = "done" } }, stop_reason = "end_turn" });

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AnthropicEmptyTextBlockIsNotStored(bool stream)
    {
        var calls = 0;
        var wire = new FnWire(_ => calls++ == 0 ? Turn1(stream) : Turn2(stream));
        var echo = NativeTool.Of("echo", "echo", new Dictionary<string, object?> { ["type"] = "object" }, _ => "ok");
        var tk = (await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false })).Register(echo);
        var client = HostEngineAsksTests.Client(wire, "anthropic");
        var result = stream ? await client.StreamAsync("go", tk, _ => { }) : await client.RunAsync("go", tk);

        Assert.Equal("done", result.Text);
        Assert.Equal(2, wire.Bodies.Count);
        foreach (var m in ((List<object?>)wire.Bodies[1]["messages"]!).OfType<IDictionary<string, object?>>())
        {
            if (m.Get("content") is not List<object?> blocks) continue;
            foreach (var b in blocks.OfType<IDictionary<string, object?>>())
                Assert.False(b.Get("type") as string == "text" && b.Get("text") as string == "", "empty text block sent: " + Json.Stringify(m));
        }
    }
}
