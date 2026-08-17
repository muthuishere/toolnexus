namespace Toolnexus.Tests;

/// InProcess.CreateClient — a model in this process, with no wire configuration.
/// openspec/changes/add-in-process-client. Mirrored in all seven ports.
public class InProcessTests
{
    private static ITool AddTool() => NativeTool.Of("add", "Add two numbers.",
        new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["a"] = new Dictionary<string, object?> { ["type"] = "number" },
                ["b"] = new Dictionary<string, object?> { ["type"] = "number" },
            },
            ["required"] = new[] { "a", "b" },
        },
        args => Convert.ToString(Convert.ToDouble(args!["a"]) + Convert.ToDouble(args["b"]),
            System.Globalization.CultureInfo.InvariantCulture)!);

    [Fact]
    public async Task NoWireConfigurationIsRequired()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        // No BaseUrl. No ApiKey. No Style. That is the whole point.
        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "my-local",
            Generate = _ => InProcess.Response.FromContent("hello from in-process"),
        });

        var r = await client.RunAsync("hi", tk);
        Assert.Equal("hello from in-process", r.Text);
        Assert.Equal("done", r.Status);
    }

    [Fact]
    public async Task GenerateSeesTheAssembledRequest()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(AddTool());
        InProcess.Request? seen = null;

        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "my-local",
            SystemPrompt = "You are terse.",
            Generate = req => { seen = req; return InProcess.Response.FromContent("ok"); },
        });
        await client.RunAsync("What is 2 + 3?", tk);

        Assert.Equal("my-local", seen!.Model);
        Assert.NotEmpty(seen.Tools);
        var messages = System.Text.Json.JsonSerializer.Serialize(seen.Messages);
        Assert.Contains("terse", messages);
        // Not "2 + 3": System.Text.Json escapes '+' as \u002B by default, so match on
        // text the encoder leaves alone.
        Assert.Contains("What is", messages);
        Assert.Contains("\"role\":\"user\"", messages);
    }

    [Fact]
    public async Task ToolCallsLoopBackWithTheResult()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(AddTool());
        var n = 0;

        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "m",
            Generate = _ => ++n == 1
                ? InProcess.Response.FromToolCalls(new InProcess.ToolCall
                { Name = "add", Arguments = new Dictionary<string, object?> { ["a"] = 2, ["b"] = 3 } })
                : InProcess.Response.FromContent("the answer is 5"),
        });

        var r = await client.RunAsync("What is 2 + 3?", tk);
        Assert.Single(r.ToolCalls);
        Assert.Equal("add", r.ToolCalls[0].Name);
        Assert.Equal("5", r.ToolCalls[0].Output);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ArgumentsStructuredOrPreEncoded(bool preEncoded)
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(AddTool());
        object args = preEncoded
            ? "{\"a\":2,\"b\":3}"
            : new Dictionary<string, object?> { ["a"] = 2, ["b"] = 3 };
        var n = 0;

        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "m",
            Generate = _ => ++n == 1
                ? InProcess.Response.FromToolCalls(new InProcess.ToolCall { Name = "add", Arguments = args })
                : InProcess.Response.FromContent("done"),
        });

        var r = await client.RunAsync("go", tk);
        Assert.Equal("5", r.ToolCalls[0].Output);
    }

    [Fact]
    public async Task UsageIsOptionalAndDerived()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });

        var bare = InProcess.CreateClient(new InProcess.Options
        { Model = "m", Generate = _ => InProcess.Response.FromContent("x") });
        Assert.Equal(0, (await bare.RunAsync("hi", tk)).Usage.TotalTokens);

        var counted = InProcess.CreateClient(new InProcess.Options
        {
            Model = "m",
            Generate = _ => new InProcess.Response
            { Content = "x", Usage = new InProcess.Usage { PromptTokens = 11, CompletionTokens = 4 } },
        });
        var r = await counted.RunAsync("hi", tk);
        Assert.Equal(11, r.Usage.PromptTokens);
        Assert.Equal(15, r.Usage.TotalTokens);
    }

    [Fact]
    public async Task StreamingIsRefusedLoudly()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var client = InProcess.CreateClient(new InProcess.Options
        { Model = "m", Generate = _ => InProcess.Response.FromContent("x") });

        var deltas = 0;
        var boom = await Assert.ThrowsAnyAsync<Exception>(() =>
            client.StreamAsync("hi", tk, ev =>
            {
                if (ev.Type == LlmClient.StreamKind.Text) deltas++;
            }));
        Assert.Contains("does not support streaming", boom.ToString());
        Assert.Equal(0, deltas);
    }

    [Fact]
    public void GenerateIsRequired() =>
        Assert.Throws<ArgumentException>(() =>
            InProcess.CreateClient(new InProcess.Options { Model = "m" }));
}
