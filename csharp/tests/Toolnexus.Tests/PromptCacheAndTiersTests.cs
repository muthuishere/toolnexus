using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>
/// Prompt-cache replay (change add-prompt-cache, phase 1), mirroring golang/prompt_cache_test.go. A
/// Karya-shaped session — fixed tools, a ~6.6k-token skills catalog in the system prompt, several
/// turns, a schema loaded mid-session as a tool result — runs against a mock anthropic server that
/// SIMULATES the provider's prefix cache: a request reads the longest prefix that ends at a
/// breakpoint an earlier request wrote. Passes if cache reads start on request 2 and never drop.
/// </summary>
public class PromptCacheTests
{
    private static object? Strip(object? v) => v switch
    {
        IDictionary<string, object?> d => d.Where(kv => kv.Key != "cache_control")
            .OrderBy(kv => kv.Key, StringComparer.Ordinal)
            .ToDictionary(kv => kv.Key, kv => Strip(kv.Value)),
        List<object?> l => l.Select(Strip).ToList(),
        _ => v,
    };

    private static bool HasCacheControl(object? v) => Json.Stringify(v).Contains("\"cache_control\"");

    private sealed class CacheSim
    {
        private readonly object _gate = new();
        private readonly HashSet<string> _written = new();
        public readonly List<long> Reads = new();
        public readonly List<Dictionary<string, object?>> Bodies = new();
        private int _turn;

        public string Handle(Dictionary<string, object?> body)
        {
            var segs = new List<string>();
            var marks = new List<bool>();
            void Add(object? v)
            {
                segs.Add(Json.Stringify(Strip(v)));
                marks.Add(HasCacheControl(v));
            }
            Add(body.Get("tools"));
            Add(body.Get("system"));
            foreach (var m in (List<object?>)body["messages"]!) Add(m);
            long read = 0, write = 0;
            lock (_gate)
            {
                for (var i = segs.Count; i > 0; i--)
                {
                    if (_written.Contains(string.Join("\0", segs.Take(i))))
                    {
                        read = string.Concat(segs.Take(i)).Length / 4;
                        break;
                    }
                }
                for (var i = 0; i < segs.Count; i++)
                {
                    if (!marks[i]) continue;
                    var key = string.Join("\0", segs.Take(i + 1));
                    if (_written.Add(key)) write = string.Concat(segs.Take(i + 1)).Length / 4 - read;
                }
                Reads.Add(read);
                Bodies.Add(body);
            }
            var isToolResult = FnWire.AfterToolResult(body);
            object[] content;
            if (isToolResult)
            {
                content = new object[] { new { type = "text", text = "ok" } };
            }
            else
            {
                int n;
                lock (_gate) n = ++_turn;
                content = new object[] { new { type = "tool_use", id = $"t{n}", name = n == 2 ? "tool_search" : "read_notes", input = new { } } };
            }
            return JsonSerializer.Serialize(new
            {
                content,
                stop_reason = isToolResult ? "end_turn" : "tool_use",
                usage = new { input_tokens = 10, output_tokens = 5, cache_read_input_tokens = read, cache_creation_input_tokens = write },
            });
        }
    }

    private static async Task<(CacheSim Sim, long Read)> RunSession(PromptCacheOptions? cache)
    {
        var sim = new CacheSim();
        var catalog = string.Concat(Enumerable.Repeat("skill: does a thing in a stable way. ", 700)); // ~6.6k tokens
        var c = HostEngineAsksTests.Client(new FnWire(sim.Handle), "anthropic", o =>
        {
            o.SystemPrompt = "You are Karya.\n\n" + catalog;
            o.PromptCache = cache;
            o.Store = new InMemoryConversationStore();
        });
        var notes = NativeTool.Of("read_notes", "Read the notes.", null, _ => "notes");
        var search = NativeTool.Of("tool_search", "Load a deferred tool's schema.", null,
            _ => "{\"name\":\"deploy\",\"description\":\"Deploy.\",\"inputSchema\":{\"type\":\"object\"}}");
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { ExtraTools = new List<ITool> { notes, search } });
        long read = 0;
        foreach (var p in new[] { "first", "second", "third", "fourth" })
        {
            var res = await c.AskAsync(p, tk, "session-1");
            read += res.Usage.CacheReadTokens;
        }
        return (sim, read);
    }

    [Fact]
    public async Task ReplayNoRegression()
    {
        var (sim, read) = await RunSession(new PromptCacheOptions { Key = "session-1" });
        Assert.True(read > 0, "usage never reported cache-read tokens");
        Assert.Equal(8, sim.Reads.Count);
        Assert.Equal(0, sim.Reads[0]);
        var floor = sim.Reads[1];
        Assert.True(floor >= 6000, $"2nd request read {floor} tokens, want the ~6.6k stable prefix");
        for (var i = 1; i < sim.Reads.Count; i++)
        {
            Assert.True(sim.Reads[i] >= floor, $"cache read regressed at request {i + 1}: {string.Join(",", sim.Reads)}");
            Assert.True(sim.Reads[i] >= sim.Reads[i - 1], $"cache read shrank at request {i + 1}: {string.Join(",", sim.Reads)}");
        }
        var t0 = Json.Stringify(sim.Bodies[0].Get("tools"));
        var s0 = Json.Stringify(sim.Bodies[0].Get("system"));
        foreach (var b in sim.Bodies)
        {
            Assert.Equal(t0, Json.Stringify(b.Get("tools")));
            Assert.Equal(s0, Json.Stringify(b.Get("system")));
        }
    }

    [Fact]
    public async Task OffIsByteIdentical()
    {
        var (sim, _) = await RunSession(null);
        foreach (var b in sim.Bodies)
        {
            Assert.False(HasCacheControl(b));
            Assert.IsType<string>(b["system"]);
        }
    }

    [Fact]
    public void OpenAiKeyAndUsage()
    {
        var body = new Dictionary<string, object?> { ["messages"] = new List<object?>() };
        LlmClient.ApplyPromptCache(body, "openai", new PromptCacheOptions { Key = "s1" });
        Assert.Equal("s1", body["prompt_cache_key"]);
        var u = new LlmClient.Usage();
        LlmClient.AddUsage(u, new Dictionary<string, object?>
        {
            ["prompt_tokens"] = 100.0, ["completion_tokens"] = 5.0,
            ["prompt_tokens_details"] = new Dictionary<string, object?> { ["cached_tokens"] = 80.0 },
        }, "openai");
        Assert.Equal(80, u.CacheReadTokens);
        Assert.Equal(100, u.PromptTokens);
        var a = new LlmClient.Usage();
        LlmClient.AddUsage(a, new Dictionary<string, object?>
        {
            ["input_tokens"] = 10L, ["output_tokens"] = 2L, ["cache_read_input_tokens"] = 7L, ["cache_creation_input_tokens"] = 3L,
        }, "anthropic");
        Assert.Equal((10L, 7L, 3L), (a.PromptTokens, a.CacheReadTokens, a.CacheWriteTokens));
    }

    [Fact]
    public void DoesNotMutateTranscript()
    {
        var msg = new Dictionary<string, object?> { ["role"] = "user", ["content"] = "hi" };
        var tool = new Dictionary<string, object?> { ["name"] = "x" };
        var body = new Dictionary<string, object?>
        {
            ["tools"] = new List<Dictionary<string, object?>> { tool }, ["system"] = "s", ["messages"] = new List<object?> { msg },
        };
        LlmClient.ApplyPromptCache(body, "anthropic", new PromptCacheOptions { LongTtl = true });
        Assert.IsType<string>(msg["content"]);
        Assert.False(tool.ContainsKey("cache_control"));
        var s = Json.Stringify(body);
        Assert.Equal(3, s.Split("\"ttl\":\"1h\"").Length - 1);
    }
}

/// <summary>Tool tiers (change add-prompt-cache, phase 2), mirroring golang/tool_tiers_test.go.</summary>
public class ToolTierTests
{
    private static async Task<(Toolkit Tk, List<string> Ran)> TierToolkit()
    {
        var ran = new List<string>();
        NativeTool Mk(string name, string desc, ToolHints? hints)
        {
            var t = NativeTool.Of(name, desc, new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?> { ["x"] = new Dictionary<string, object?> { ["type"] = "string" } },
            }, a =>
            {
                lock (ran) ran.Add($"{name}({a.Get("x")})");
                return name + " ok";
            });
            t.Hints = hints;
            return t;
        }
        var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            ExtraTools = new List<ITool>
            {
                Mk("zeta_read", "Read zeta.\nsecond line hidden", new ToolHints(ReadOnly: true)),
                Mk("alpha_read", "Read alpha.", new ToolHints(ReadOnly: true)),
                Mk("deploy", "Deploy the service to production.", new ToolHints(Destructive: true)),
                Mk("lint", "Lint the code.", null),
            },
        });
        return (tk, ran);
    }

    /// <summary>Same bytes as golang TestToolSearchJSONIsHTMLEscaped: sorted keys, 2-space indent, &lt;&gt;&amp; escaped.</summary>
    [Fact]
    public async Task ToolSearchJsonEscapesLikeGo()
    {
        var amp = NativeTool.Of("amp", "Use <a & b> here", new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?> { ["x"] = new Dictionary<string, object?> { ["type"] = "string" } },
        }, _ => "");
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = new List<ITool> { amp } });
        var c = LlmClient.Create(new LlmClient.Options { BaseUrl = "http://x", Model = "m", ApiKey = "k", ToolTiers = new ToolTierOptions() });
        var r = c.ToolSearch(tk, new Dictionary<string, object?> { ["query"] = "select:amp" });
        const string want = "Loaded tools (call by name, or with call_tool):\n[\n  {\n    \"description\": \"Use \\u003ca \\u0026 b\\u003e here\",\n    \"inputSchema\": {\n      \"properties\": {\n        \"x\": {\n          \"type\": \"string\"\n        }\n      },\n      \"type\": \"object\"\n    },\n    \"name\": \"amp\"\n  }\n]";
        Assert.Equal(want, r.Output);
    }

    [Fact]
    public void FirstLineCutsOnCharacterBoundary()
    {
        Assert.Equal(new string('a', 119) + "…", LlmClient.FirstLine(new string('a', 119) + "é tail"));
        Assert.Equal(new string('a', 117) + "€…", LlmClient.FirstLine(new string('a', 117) + "€z"));
    }

    [Fact]
    public async Task PlanAndDescription()
    {
        var (tk, _) = await TierToolkit();
        var c = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://x", Model = "m", ApiKey = "k",
            ToolTiers = new ToolTierOptions { Core = new[] { "zeta_read", "alpha_read" }, Deferred = new[] { "deploy" } },
        });
        var got = c.RequestTools(tk);
        Assert.Equal("alpha_read,zeta_read,tool_search,call_tool", string.Join(",", got.Select(t => t.Name)));
        var desc = got[2].Description;
        Assert.Contains("- lint — Lint the code.", desc);
        Assert.Contains("(name only): deploy", desc);
        Assert.DoesNotContain("Deploy the service", desc);
        Assert.DoesNotContain("second line hidden", desc);
        var plan = c.ToolTierPlan(tk)!;
        Assert.Equal(ToolTier.Deferred, plan["deploy"]);
        Assert.Equal(ToolTier.Listed, plan["lint"]);
        Assert.Equal(ToolTier.Core, plan["alpha_read"]);

        // registered after the plan froze: not in the array or the description, still found by search
        tk.Register(NativeTool.Of("late", "A late tool.", null, _ => "late ok"));
        var again = c.RequestTools(tk);
        Assert.Equal(4, again.Count);
        Assert.Equal(desc, again[2].Description);
        Assert.Contains("\"name\": \"late\"", c.ToolSearch(tk, new Dictionary<string, object?> { ["query"] = "late" }).Output);

        // ResetToolTiers rebuilds: the late tool is now listed
        c.ResetToolTiers(tk);
        Assert.Contains("- late — A late tool.", c.RequestTools(tk)[2].Description);
    }

    [Fact]
    public async Task BudgetDemotesLeastUsed()
    {
        var (tk, _) = await TierToolkit();
        var uses = new Dictionary<string, int> { ["alpha_read"] = 9, ["zeta_read"] = 1, ["lint"] = 5, ["deploy"] = 0 };
        var b = LlmClient.BuildPlan(new ToolTierOptions { Core = new[] { "alpha_read", "zeta_read" } }, tk.Tools());
        var fix = LlmClient.DefaultCountTokens(LlmClient.ToolDef(b.Search)) + LlmClient.DefaultCountTokens(LlmClient.ToolDef(b.Call));
        var one = LlmClient.DefaultCountTokens(LlmClient.ToolDef(tk.Get("alpha_read")!));
        var p = LlmClient.BuildPlan(new ToolTierOptions
        {
            Core = new[] { "alpha_read", "zeta_read" }, BudgetTokens = fix + one + 15, UsageCount = n => uses[n],
        }, tk.Tools());
        Assert.Equal(ToolTier.Core, p.Tier["alpha_read"]);
        Assert.Equal(ToolTier.Listed, p.Tier["zeta_read"]);

        p = LlmClient.BuildPlan(new ToolTierOptions { Core = new[] { "alpha_read" }, BudgetTokens = 1, UsageCount = n => uses[n] }, tk.Tools());
        Assert.All(p.Tier.Values, t => Assert.Equal(ToolTier.Deferred, t));
    }

    /// <summary>tool_search loads deploy, the model calls it via call_tool, the permission hook sees the
    /// real name and hints, and the tools array is the same bytes on every request.</summary>
    [Fact]
    public async Task LoopCallToolUnwraps()
    {
        var (tk, ran) = await TierToolkit();
        var step = 0;
        var arrays = new List<string>();
        var wire = new FnWire(body =>
        {
            int n;
            lock (arrays)
            {
                arrays.Add(Json.Stringify(body.Get("tools")));
                n = ++step;
            }
            object content = n switch
            {
                1 => new object[] { new { type = "tool_use", id = "s1", name = "tool_search", input = new { query = "select:deploy" } } },
                2 => new object[] { new { type = "tool_use", id = "c1", name = "call_tool", input = new { name = "deploy", arguments = new { x = "prod" } } } },
                3 => new object[] { new { type = "tool_use", id = "d1", name = "lint", input = new { x = "direct" } } },
                _ => new object[] { new { type = "text", text = "done" } },
            };
            return JsonSerializer.Serialize(new { content, stop_reason = n <= 3 ? "tool_use" : "end_turn", usage = new { input_tokens = 1, output_tokens = 1 } });
        });
        var gated = new List<string>();
        var c = HostEngineAsksTests.Client(wire, "anthropic", o =>
        {
            o.ToolTiers = new ToolTierOptions { Core = new[] { "alpha_read" }, Deferred = new[] { "deploy" } };
            o.Hooks = new LlmClient.Hooks
            {
                BeforeTool = ev =>
                {
                    lock (gated) gated.Add(ev.Name + ":" + (ev.Hints == null ? "?" : ev.Hints.Destructive.ToString().ToLowerInvariant()));
                    return null;
                },
            };
        });
        var res = await c.RunAsync("ship it", tk);
        Assert.Equal("done", res.Status);
        Assert.Contains("\"name\": \"deploy\"", res.ToolCalls[0].Output);
        Assert.Equal("deploy(prod),lint(direct)", string.Join(",", ran));
        Assert.Equal("deploy:true,lint:?", string.Join(",", gated));
        Assert.All(arrays, a => Assert.Equal(arrays[0], a));
    }

    [Fact]
    public async Task StreamNamesRealTool()
    {
        var (tk, ran) = await TierToolkit();
        var step = 0;
        var wire = new FnWire(_ =>
        {
            if (Interlocked.Increment(ref step) == 1)
            {
                var args = JsonSerializer.Serialize(new { name = "deploy", arguments = new { x = "prod" } });
                return FnWire.Sse(
                    new { choices = new[] { new { delta = new { tool_calls = new[] { new { index = 0, id = "c1", type = "function", function = new { name = "call_tool", arguments = args } } } } } } },
                    new { choices = new[] { new { delta = new { }, finish_reason = "tool_calls" } } },
                    "[DONE]");
            }
            return FnWire.Sse(new { choices = new[] { new { delta = new { content = "done" }, finish_reason = "stop" } } }, "[DONE]");
        });
        var c = HostEngineAsksTests.Client(wire, "openai", o => o.ToolTiers = new ToolTierOptions { Core = new[] { "alpha_read" } });
        var got = new List<string>();
        await c.StreamAsync("go", tk, ev =>
        {
            if (ev.Type is LlmClient.StreamKind.ToolCall or LlmClient.StreamKind.ToolResult)
                got.Add($"{ev.Type}:{ev.Name}:{ev.Via}:{ev.Args?.Get("x")}");
        });
        Assert.Equal("ToolCall:deploy:call_tool:prod,ToolResult:deploy:call_tool:", string.Join(",", got));
        Assert.Equal("deploy(prod)", string.Join(",", ran));
    }
}
