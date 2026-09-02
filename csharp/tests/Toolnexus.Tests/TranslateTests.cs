using System.Net;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// Single-turn translation suite (SPEC.md §11, ADR-0011). Ports <c>golang/translate_test.go</c> —
/// Go's assertions are the cross-port oracle. Hermetic: a <see cref="StubServer"/> stands in for
/// the provider and records what it was actually sent.
/// </summary>
public class TranslateTests
{
    /// <summary>A provider stub that records the request body and replies with a canned response.</summary>
    private sealed class Upstream : IDisposable
    {
        private readonly StubServer _server;
        public Dictionary<string, object?> Sent { get; private set; } = new();

        public Upstream(string replyJson)
        {
            _server = new StubServer(ctx =>
            {
                using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
                Sent = Json.ParseObjectLoose(reader.ReadToEnd());
                StubServer.Respond(ctx, 200, replyJson);
            });
        }

        public string BaseUrl => _server.BaseUrl;
        public string SentJson() => Json.Stringify(Sent);
        public void Dispose() => _server.Dispose();
    }

    private static LlmClient Client(string baseUrl, string style) =>
        LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = baseUrl, Style = style, Model = "stub", ApiKey = "k",
        });

    /// <summary>The OpenAI <c>tools</c> array a client sends, verbatim.</summary>
    private static List<object?> OpenAITools() => new()
    {
        new Dictionary<string, object?>
        {
            ["type"] = "function",
            ["function"] = new Dictionary<string, object?>
            {
                ["name"] = "get_weather",
                ["description"] = "Get the weather",
                ["parameters"] = new Dictionary<string, object?>
                {
                    ["type"] = "object",
                    ["properties"] = new Dictionary<string, object?>
                    {
                        ["city"] = new Dictionary<string, object?> { ["type"] = "string" },
                    },
                    ["required"] = new List<object?> { "city" },
                },
            },
        },
    };

    /// <summary>Every content block across the sent messages, for structural assertions.</summary>
    private static List<IDictionary<string, object?>> BlocksOf(Dictionary<string, object?> sent)
    {
        var result = new List<IDictionary<string, object?>>();
        if (sent.Get("messages") is not IEnumerable<object?> msgs) return result;
        foreach (var m in msgs)
        {
            if (m is not IDictionary<string, object?> mm) continue;
            if (mm.Get("content") is not IEnumerable<object?> blocks) continue;
            result.AddRange(blocks.OfType<IDictionary<string, object?>>());
        }
        return result;
    }

    private static Translate.Request Req(List<object?> messages) => new() { Messages = messages };

    private static Dictionary<string, object?> UserMsg(string text) =>
        new() { ["role"] = "user", ["content"] = text };

    // ---- Anthropic upstream: the real translation ----

    [Fact]
    public async Task ToolUse_ComesBackAsAnOpenAiToolCall()
    {
        const string reply = """
        {"content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Chennai"}}],
         "stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":5}}
        """;
        using var up = new Upstream(reply);
        var res = await Client(up.BaseUrl, "anthropic").TranslateAsync(new Translate.Request
        {
            Messages = new List<object?> { UserMsg("weather in Chennai?") },
            Tools = OpenAITools(),
        });

        Assert.Equal("tool_calls", res.FinishReason);
        var tc = Assert.Single(res.ToolCalls);
        Assert.Equal("toolu_1", tc.Id);
        Assert.Equal("get_weather", tc.Name);
        // arguments must be a JSON STRING (the OpenAI wire shape), not an object
        Assert.Equal("Chennai", Json.ParseObjectLoose(tc.Arguments).Get("city"));
        Assert.True(res.Usage.TotalTokens > 0, "usage not reported");

        var sent = up.SentJson();
        Assert.Contains("input_schema", sent);
        Assert.Contains("get_weather", sent);
        Assert.DoesNotContain("\"parameters\"", sent); // OpenAI shape must not leak upstream
    }

    /// <summary>The case a text-flattening translator gets wrong.</summary>
    [Fact]
    public async Task MultiTurnToolExchange_Survives()
    {
        const string reply = """
        {"content":[{"type":"text","text":"It is 31C in Chennai."}],"stop_reason":"end_turn",
         "usage":{"input_tokens":20,"output_tokens":8}}
        """;
        using var up = new Upstream(reply);
        var assistant = new Dictionary<string, object?>
        {
            ["role"] = "assistant",
            ["tool_calls"] = new List<object?>
            {
                new Dictionary<string, object?>
                {
                    ["id"] = "call_abc", ["type"] = "function",
                    ["function"] = new Dictionary<string, object?>
                    {
                        ["name"] = "get_weather", ["arguments"] = "{\"city\":\"Chennai\"}",
                    },
                },
            },
        };

        var res = await Client(up.BaseUrl, "anthropic").TranslateAsync(new Translate.Request
        {
            Tools = OpenAITools(),
            Messages = new List<object?>
            {
                new Dictionary<string, object?> { ["role"] = "system", ["content"] = "Be terse." },
                UserMsg("weather in Chennai?"),
                assistant,
                new Dictionary<string, object?>
                {
                    ["role"] = "tool", ["tool_call_id"] = "call_abc", ["content"] = "31C, clear",
                },
            },
        });

        Assert.Equal("stop", res.FinishReason);
        Assert.Equal("It is 31C in Chennai.", res.Text);
        Assert.Equal("Be terse.", up.Sent.Get("system")); // system hoisted out of messages

        var sent = up.SentJson();
        foreach (var want in new[] { "tool_use", "tool_result", "call_abc", "31C, clear" })
            Assert.Contains(want, sent);

        // the tool_use's input is an OBJECT upstream, re-parsed from the JSON string
        var use = BlocksOf(up.Sent).FirstOrDefault(b => (b.Get("type") as string) == "tool_use");
        Assert.NotNull(use);
        Assert.Equal("Chennai", (use!.Get("input") as IDictionary<string, object?>)?.Get("city"));
    }

    [Fact]
    public async Task ThreeConsecutiveToolResults_MergeIntoOneUserTurn()
    {
        const string reply = """{"content":[{"type":"text","text":"done"}],"stop_reason":"end_turn"}""";
        using var up = new Upstream(reply);
        var assistant = new Dictionary<string, object?>
        {
            ["role"] = "assistant",
            ["tool_calls"] = new List<object?>
            {
                Call("a"), Call("b"), Call("c"),
            },
        };

        await Client(up.BaseUrl, "anthropic").TranslateAsync(Req(new List<object?>
        {
            UserMsg("do three things"),
            assistant,
            ToolResult("a", "ra"), ToolResult("b", "rb"), ToolResult("c", "rc"),
        }));

        // exactly ONE user turn carries all three tool_result blocks
        var resultTurns = 0;
        var resultsInTurn = 0;
        foreach (var m in (up.Sent.Get("messages") as IEnumerable<object?>)!.OfType<IDictionary<string, object?>>())
        {
            if (m.Get("content") is not IEnumerable<object?> blocks) continue;
            var n = blocks.OfType<IDictionary<string, object?>>().Count(b => (b.Get("type") as string) == "tool_result");
            if (n <= 0) continue;
            resultTurns++;
            resultsInTurn = n;
        }
        Assert.Equal(1, resultTurns);
        Assert.Equal(3, resultsInTurn);
        Assert.Equal(3, BlocksOf(up.Sent).Count(b => (b.Get("type") as string) == "tool_use"));

        static Dictionary<string, object?> Call(string id) => new()
        {
            ["id"] = id,
            ["function"] = new Dictionary<string, object?> { ["name"] = "f", ["arguments"] = "{}" },
        };
        static Dictionary<string, object?> ToolResult(string id, string content) => new()
        {
            ["role"] = "tool", ["tool_call_id"] = id, ["content"] = content,
        };
    }

    [Fact]
    public async Task ParallelToolCalls_AllReturnedInProviderOrder()
    {
        const string reply = """
        {"content":[{"type":"text","text":"calling three"},
                    {"type":"tool_use","id":"t1","name":"alpha","input":{"n":1}},
                    {"type":"tool_use","id":"t2","name":"beta","input":{"n":2}},
                    {"type":"tool_use","id":"t3","name":"gamma","input":{"n":3}}],
         "stop_reason":"tool_use"}
        """;
        using var up = new Upstream(reply);
        var res = await Client(up.BaseUrl, "anthropic").TranslateAsync(new Translate.Request
        {
            Messages = new List<object?> { UserMsg("go") },
            Tools = OpenAITools(),
        });
        Assert.Equal(new[] { "alpha", "beta", "gamma" }, res.ToolCalls.Select(t => t.Name));
        Assert.Equal("calling three", res.Text);
        Assert.Equal("tool_calls", res.FinishReason);
        Assert.Equal(3, res.ToolCallsJson().Count);
    }

    [Fact]
    public async Task ExecutesNothing_AndKeepsNoState()
    {
        const string reply = """
        {"content":[{"type":"tool_use","id":"t1","name":"danger","input":{}}],"stop_reason":"tool_use"}
        """;
        using var up = new Upstream(reply);
        var ran = 0;
        var danger = NativeTool.Of("danger", "must not run", null, _ =>
        {
            Interlocked.Increment(ref ran);
            return "RAN";
        });
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false, ExtraTools = new List<ITool> { danger },
        });
        var client = Client(up.BaseUrl, "anthropic");
        for (var i = 0; i < 3; i++)
        {
            var res = await client.TranslateAsync(new Translate.Request
            {
                Messages = new List<object?> { UserMsg("go") }, Toolkit = tk,
            });
            Assert.Equal("danger", Assert.Single(res.ToolCalls).Name);
        }
        Assert.Equal(0, ran); // translate must NEVER execute anything
        // no history accumulated between the three independent calls
        Assert.Single((up.Sent.Get("messages") as IEnumerable<object?>)!);
    }

    /// <summary>The generality case: a real toolkit works, not only OpenAI JSON.</summary>
    [Fact]
    public async Task Toolkit_IsDeclaredButNeverExecuted()
    {
        const string reply = """
        {"content":[{"type":"tool_use","id":"tu_9","name":"my_native_tool","input":{"x":1}}],
         "stop_reason":"tool_use"}
        """;
        using var up = new Upstream(reply);
        var ran = 0;
        var tool = NativeTool.Of("my_native_tool", "an ordinary executable tool", null, _ =>
        {
            Interlocked.Increment(ref ran);
            return "SHOULD NOT RUN";
        });
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false, ExtraTools = new List<ITool> { tool },
        });
        var res = await Client(up.BaseUrl, "anthropic").TranslateAsync(new Translate.Request
        {
            Messages = new List<object?> { UserMsg("use the tool") }, Toolkit = tk,
        });
        Assert.Equal(0, ran);
        var tc = Assert.Single(res.ToolCalls);
        Assert.Equal("my_native_tool", tc.Name);
        Assert.Equal("tu_9", tc.Id);
        Assert.Contains("input_schema", up.SentJson());
        Assert.Contains("my_native_tool", up.SentJson());
    }

    [Fact]
    public async Task ToolkitAndOpenAiTools_Compose()
    {
        const string reply = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""";
        using var up = new Upstream(reply);
        var tool = NativeTool.Of("server_side_tool", "gateway's own", null, _ => "x");
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false, ExtraTools = new List<ITool> { tool },
        });
        await Client(up.BaseUrl, "anthropic").TranslateAsync(new Translate.Request
        {
            Messages = new List<object?> { UserMsg("go") }, Toolkit = tk, Tools = OpenAITools(),
        });
        var sent = up.SentJson();
        Assert.Contains("server_side_tool", sent);
        Assert.Contains("get_weather", sent);
    }

    [Theory]
    [InlineData(null, null)]
    [InlineData("auto", null)]
    [InlineData("required", "\"type\":\"any\"")]
    [InlineData("none", "\"type\":\"none\"")]
    public async Task ToolChoice_Mapping(string? choice, string? want)
    {
        const string reply = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""";
        using var up = new Upstream(reply);
        await Client(up.BaseUrl, "anthropic").TranslateAsync(new Translate.Request
        {
            Messages = new List<object?> { UserMsg("go") }, Tools = OpenAITools(), ToolChoice = choice,
        });
        var present = up.Sent.Get("tool_choice");
        if (want == null)
        {
            Assert.Null(present);
            return;
        }
        Assert.NotNull(present);
        Assert.Contains(want, Json.Stringify(present).Replace(" ", ""));
    }

    [Fact]
    public async Task ToolChoice_SpecificFunction_MapsToNamedTool()
    {
        const string reply = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""";
        using var up = new Upstream(reply);
        await Client(up.BaseUrl, "anthropic").TranslateAsync(new Translate.Request
        {
            Messages = new List<object?> { UserMsg("go") },
            Tools = OpenAITools(),
            ToolChoice = new Dictionary<string, object?>
            {
                ["type"] = "function",
                ["function"] = new Dictionary<string, object?> { ["name"] = "get_weather" },
            },
        });
        Assert.Contains("\"name\":\"get_weather\"", Json.Stringify(up.Sent.Get("tool_choice")).Replace(" ", ""));
    }

    [Theory]
    [InlineData("end_turn", "stop")]
    [InlineData("max_tokens", "length")]
    [InlineData("refusal", "content_filter")]
    [InlineData("stop_sequence", "stop")]
    public async Task FinishReason_Mapping(string stop, string want)
    {
        var reply = $"{{\"content\":[{{\"type\":\"text\",\"text\":\"x\"}}],\"stop_reason\":\"{stop}\"}}";
        using var up = new Upstream(reply);
        var res = await Client(up.BaseUrl, "anthropic").TranslateAsync(Req(new List<object?> { UserMsg("go") }));
        Assert.Equal(want, res.FinishReason);
    }

    /// <summary>Some clients send <c>arguments</c> as an object rather than a JSON string.</summary>
    [Fact]
    public async Task Arguments_AcceptedAsAnObjectToo()
    {
        const string reply = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""";
        using var up = new Upstream(reply);
        var assistant = new Dictionary<string, object?>
        {
            ["role"] = "assistant",
            ["tool_calls"] = new List<object?>
            {
                new Dictionary<string, object?>
                {
                    ["id"] = "z",
                    ["function"] = new Dictionary<string, object?>
                    {
                        ["name"] = "f",
                        ["arguments"] = new Dictionary<string, object?> { ["city"] = "Madurai" },
                    },
                },
            },
        };
        await Client(up.BaseUrl, "anthropic").TranslateAsync(Req(new List<object?>
        {
            UserMsg("go"),
            assistant,
            new Dictionary<string, object?> { ["role"] = "tool", ["tool_call_id"] = "z", ["content"] = "done" },
        }));
        var use = BlocksOf(up.Sent).FirstOrDefault(b => (b.Get("type") as string) == "tool_use");
        Assert.NotNull(use);
        Assert.Equal("Madurai", (use!.Get("input") as IDictionary<string, object?>)?.Get("city"));
    }

    [Fact]
    public async Task TextContentParts_AreConcatenated()
    {
        // §11: text parts concatenate (unchanged). Non-text parts are NO LONGER flattened away —
        // see NonTextContentParts_SurviveTranslation below.
        const string reply = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""";
        using var up = new Upstream(reply);
        await Client(up.BaseUrl, "anthropic").TranslateAsync(Req(new List<object?>
        {
            new Dictionary<string, object?>
            {
                ["role"] = "user",
                ["content"] = new List<object?>
                {
                    new Dictionary<string, object?> { ["type"] = "text", ["text"] = "part one " },
                    new Dictionary<string, object?> { ["type"] = "text", ["text"] = "part two" },
                },
            },
        }));
        Assert.Contains("part one part two", up.SentJson());
    }

    [Fact]
    public async Task NonTextContentParts_SurviveTranslation()
    {
        // The previous behaviour passed a text-empty parts array through to the provider raw and
        // undocumented, in six ports. §11 now specifies one mapping: text parts concatenate, and
        // non-text parts become the provider's native block — nothing is dropped.
        const string reply = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""";
        using var up = new Upstream(reply);
        await Client(up.BaseUrl, "anthropic").TranslateAsync(Req(new List<object?>
        {
            new Dictionary<string, object?>
            {
                ["role"] = "user",
                ["content"] = new List<object?>
                {
                    new Dictionary<string, object?> { ["type"] = "text", ["text"] = "look" },
                    new Dictionary<string, object?>
                    {
                        ["type"] = "image", ["mimeType"] = "image/png", ["data"] = "QUJD",
                    },
                },
            },
        }));
        var sent = up.SentJson();
        Assert.Contains("\"type\":\"image\"", sent);
        Assert.Contains("\"media_type\":\"image/png\"", sent); // the Anthropic-native shape
        Assert.Contains("\"data\":\"QUJD\"", sent);
        Assert.Contains("\"text\":\"look\"", sent);            // and the text, in order
    }

    [Fact]
    public async Task LlmHooks_FireOnce_AndNoToolHookFires()
    {
        const string reply = """
        {"content":[{"type":"tool_use","id":"t1","name":"get_weather","input":{}}],"stop_reason":"tool_use"}
        """;
        using var up = new Upstream(reply);
        var before = 0;
        var after = 0;
        var toolHooks = 0;
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = up.BaseUrl, Style = "anthropic", Model = "stub", ApiKey = "k",
            Hooks = new LlmClient.Hooks
            {
                BeforeLLM = _ => { Interlocked.Increment(ref before); return null; },
                AfterLLM = _ => Interlocked.Increment(ref after),
                BeforeTool = _ => { Interlocked.Increment(ref toolHooks); return null; },
                AfterTool = _ => { Interlocked.Increment(ref toolHooks); return null; },
            },
        });
        await client.TranslateAsync(new Translate.Request
        {
            Messages = new List<object?> { UserMsg("go") }, Tools = OpenAITools(),
        });
        Assert.Equal(1, before);
        Assert.Equal(1, after);
        Assert.Equal(0, toolHooks); // no tool runs in translate
    }

    // ---- OpenAI upstream: near-passthrough ----

    [Fact]
    public async Task OpenAiUpstream_PassesToolsAndArgumentsThrough()
    {
        const string reply = """
        {"choices":[{"message":{"content":"","tool_calls":[
            {"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Madurai\"}"}}]},
          "finish_reason":"tool_calls"}],
         "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
        """;
        using var up = new Upstream(reply);
        var res = await Client(up.BaseUrl, "openai").TranslateAsync(new Translate.Request
        {
            Messages = new List<object?> { UserMsg("weather?") }, Tools = OpenAITools(),
        });
        Assert.Equal("tool_calls", res.FinishReason);
        Assert.Equal("{\"city\":\"Madurai\"}", Assert.Single(res.ToolCalls).Arguments);
        Assert.Equal(7, res.Usage.TotalTokens);
        Assert.Contains("\"parameters\"", up.SentJson()); // unchanged on an OpenAI upstream
    }
}
