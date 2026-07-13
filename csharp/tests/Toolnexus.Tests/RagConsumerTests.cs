using System.Diagnostics;
using System.Net;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// ADR-0001 rag_go consumer needs (7 gaps) + the DisableTools/DisableSkills ergonomic layer.
/// Client-side gaps use a <see cref="StubServer"/> LLM that captures the request body; MCP-side
/// gaps use a hermetic streamable-HTTP MCP server produced by <see cref="Toolkit.ServeAsync"/>
/// (tools a,b,c). Mirrors golang/rag_consumer_test.go.
/// </summary>
public class RagConsumerTests
{
    // ---- capture LLM: records the last decoded request body; replies openai + anthropic "ok" ----

    private sealed class CaptureLlm : IDisposable
    {
        private readonly StubServer _srv;
        private readonly object _gate = new();
        private Dictionary<string, object?> _last = new();

        public CaptureLlm()
        {
            _srv = new StubServer(ctx =>
            {
                using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
                var raw = reader.ReadToEnd();
                lock (_gate) _last = Json.ParseObjectLoose(raw);
                StubServer.Respond(ctx, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]," +
                    "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]," +
                    "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2," +
                    "\"input_tokens\":1,\"output_tokens\":1}}");
            });
        }

        public string BaseUrl => _srv.BaseUrl;
        public Dictionary<string, object?> Body() { lock (_gate) return _last; }
        public void Dispose() => _srv.Dispose();
    }

    private static ITool Mk(string name) => NativeTool.Of(name, name, null, _ => name);

    // ---- hermetic streamable-HTTP MCP server exposing tools a,b,c ----

    private sealed class McpFixture : IAsyncDisposable
    {
        public required string Url { get; init; }
        public required ServeHandle Handle { get; init; }
        public required Toolkit Toolkit { get; init; }

        public async ValueTask DisposeAsync()
        {
            try { await Handle.StopAsync(); } catch { }
            try { await Toolkit.DisposeAsync(); } catch { }
        }
    }

    private static async Task<McpFixture> StartMcp()
    {
        var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            ExtraTools = new List<ITool> { Mk("a"), Mk("b"), Mk("c") },
        });
        var handle = await tk.ServeAsync("127.0.0.1:0", new Toolkit.ServeOptions { Mcp = new MCPServeConfig() });
        return new McpFixture { Url = handle.Url + "/mcp", Handle = handle, Toolkit = tk };
    }

    private static LlmClient.Options BaseOpts(string url, string style) => new()
    {
        BaseUrl = url, Style = style, Model = "m", ApiKey = "k",
    };

    private static HashSet<string> Names(IEnumerable<ITool> tools) => new(tools.Select(t => t.Name));

    // -----------------------------------------------------------------------
    // Gap 1 — RequestParams merge (caller wins) + BodyTransform last + forbidden keys stripped.
    // -----------------------------------------------------------------------

    [Fact]
    public async Task Gap1_RequestParamsMerge_CallerWins_BothStyles()
    {
        using var llm = new CaptureLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });

        var oa = BaseOpts(llm.BaseUrl, "openai");
        oa.RequestParams = new Dictionary<string, object?>
        {
            ["temperature"] = 0.42,
            ["chat_template_kwargs"] = new Dictionary<string, object?> { ["enable_thinking"] = false },
        };
        await LlmClient.Create(oa).RunAsync("hi", tk);
        Assert.Equal(0.42, Convert.ToDouble(llm.Body()["temperature"]));
        Assert.NotNull(llm.Body()["chat_template_kwargs"]);

        // anthropic: max_tokens in RequestParams WINS over the built-in default 4096.
        var an = BaseOpts(llm.BaseUrl, "anthropic");
        an.RequestParams = new Dictionary<string, object?> { ["max_tokens"] = 999L };
        await LlmClient.Create(an).RunAsync("hi", tk);
        Assert.Equal(999, Convert.ToInt64(llm.Body()["max_tokens"]));
    }

    [Fact]
    public async Task Gap1_BodyTransformLast_AndForbiddenKeysStripped()
    {
        using var llm = new CaptureLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });

        var opts = BaseOpts(llm.BaseUrl, "openai");
        opts.RequestParams = new Dictionary<string, object?>
        {
            ["temperature"] = 0.5,
            ["messages"] = new List<object?> { "nope" }, // forbidden
            ["tools"] = new List<object?> { 1 },         // forbidden
            ["stream"] = true,                           // forbidden
        };
        opts.BodyTransform = b => { b["injected"] = "yes"; b.Remove("temperature"); return b; };
        await LlmClient.Create(opts).RunAsync("hi", tk);

        var body = llm.Body();
        Assert.Equal("yes", body["injected"]);                 // transform output reached the wire
        Assert.False(body.ContainsKey("temperature"));          // transform ran before marshal
        Assert.False(body.ContainsKey("stream"));               // forbidden 'stream' never leaked into a non-stream call
        var msgs = body["messages"] as List<object?>;
        Assert.NotNull(msgs);
        Assert.NotEmpty(msgs!);
        Assert.IsAssignableFrom<IDictionary<string, object?>>(msgs![0]); // real message objects, not the forbidden ["nope"]
    }

    // -----------------------------------------------------------------------
    // Gap 2 — injected HttpClient is used for LLM calls.
    // -----------------------------------------------------------------------

    private sealed class RecordingHandler : DelegatingHandler
    {
        public int Hits;
        public RecordingHandler() : base(new HttpClientHandler()) { }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Hits);
            return base.SendAsync(request, cancellationToken);
        }
    }

    [Fact]
    public async Task Gap2_InjectedHttpClient_IsUsed()
    {
        using var llm = new CaptureLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var rec = new RecordingHandler();
        using var http = new HttpClient(rec) { Timeout = Timeout.InfiniteTimeSpan };
        var opts = BaseOpts(llm.BaseUrl, "openai");
        opts.HttpClient = http;
        await LlmClient.Create(opts).RunAsync("hi", tk);
        Assert.True(rec.Hits > 0, "injected HttpClient was not used");
    }

    // -----------------------------------------------------------------------
    // Gap 5 — empty toolkit omits tools/tool_choice; a non-empty toolkit keeps both.
    // -----------------------------------------------------------------------

    [Fact]
    public async Task Gap5_EmptyToolkit_OmitsToolsAndToolChoice()
    {
        using var llm = new CaptureLlm();

        await using var empty = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        await LlmClient.Create(BaseOpts(llm.BaseUrl, "openai")).RunAsync("hi", empty);
        Assert.False(llm.Body().ContainsKey("tools"));
        Assert.False(llm.Body().ContainsKey("tool_choice"));

        await using var one = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            ExtraTools = new List<ITool> { Mk("t") },
        });
        await LlmClient.Create(BaseOpts(llm.BaseUrl, "openai")).RunAsync("hi", one);
        Assert.True(llm.Body().ContainsKey("tools"));
        Assert.Equal("auto", llm.Body()["tool_choice"]);
    }

    // -----------------------------------------------------------------------
    // Gap 4 — ConversationStore accessor identity + default reflects the saved transcript.
    // -----------------------------------------------------------------------

    [Fact]
    public async Task Gap4_ConversationStoreAccessor()
    {
        var custom = new InMemoryConversationStore();
        var c1 = LlmClient.Create(new LlmClient.Options { BaseUrl = "http://x", Style = "openai", Model = "m", Store = custom });
        Assert.Same(custom, c1.ConversationStore());

        using var llm = new CaptureLlm();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var c2 = LlmClient.Create(BaseOpts(llm.BaseUrl, "openai"));
        Assert.NotNull(c2.ConversationStore());
        await c2.AskAsync("hi", tk, "conv1");
        var hist = await c2.ConversationStore().GetAsync("conv1");
        Assert.NotNull(hist);
        Assert.True(hist!.Count >= 2, $"default store did not hold the saved transcript, got {hist.Count} msgs");
    }

    // -----------------------------------------------------------------------
    // Gap 7 — per-server tool allowlist (original names); same filter semantics as builtins/skills.
    // -----------------------------------------------------------------------

    [Fact]
    public async Task Gap7_PerServerToolAllowlist()
    {
        await using var mcp = await StartMcp();

        var allow = await McpSource.LoadAsync(new Dictionary<string, object?>
        {
            ["srv"] = new Dictionary<string, object?>
            {
                ["url"] = mcp.Url,
                ["tools"] = new Dictionary<string, object?> { ["a"] = true, ["b"] = true },
            },
        });
        await using (allow)
        {
            var n = Names(allow.Tools);
            Assert.Equal(2, n.Count);
            Assert.Contains("srv_a", n);
            Assert.Contains("srv_b", n);
        }

        var drop = await McpSource.LoadAsync(new Dictionary<string, object?>
        {
            ["srv"] = new Dictionary<string, object?>
            {
                ["url"] = mcp.Url,
                ["tools"] = new Dictionary<string, object?> { ["c"] = false },
            },
        });
        await using (drop)
        {
            var n = Names(drop.Tools);
            Assert.Equal(2, n.Count);
            Assert.DoesNotContain("srv_c", n);
        }

        var all = await McpSource.LoadAsync(new Dictionary<string, object?>
        {
            ["srv"] = new Dictionary<string, object?> { ["url"] = mcp.Url },
        });
        await using (all)
        {
            Assert.Equal(3, Names(all.Tools).Count);
        }

        // unknown name is ignored (no throw), the real tool still loads.
        var unknown = await McpSource.LoadAsync(new Dictionary<string, object?>
        {
            ["srv"] = new Dictionary<string, object?>
            {
                ["url"] = mcp.Url,
                ["tools"] = new Dictionary<string, object?> { ["a"] = true, ["nope"] = true },
            },
        });
        await using (unknown)
        {
            var n = Names(unknown.Tools);
            Assert.Single(n);
            Assert.Contains("srv_a", n);
        }
    }

    // Pure filter semantics, independent of a live server.
    [Fact]
    public void Gap7_ApplyToolsFilter_Semantics()
    {
        var defs = new[] { "a", "b", "c" };
        string N(string s) => s;

        Assert.Equal(new[] { "a", "b", "c" }, McpSource.ApplyToolsFilter("srv", defs, N, null));
        Assert.Equal(new[] { "a", "b", "c" },
            McpSource.ApplyToolsFilter("srv", defs, N, new Dictionary<string, bool>()));
        Assert.Equal(new[] { "a", "b" }, // allowlist (≥1 true)
            McpSource.ApplyToolsFilter("srv", defs, N, new Dictionary<string, bool> { ["a"] = true, ["b"] = true }));
        Assert.Equal(new[] { "a", "b" }, // drop-list (only false)
            McpSource.ApplyToolsFilter("srv", defs, N, new Dictionary<string, bool> { ["c"] = false }));
        Assert.Equal(new[] { "a" }, // unknown ignored
            McpSource.ApplyToolsFilter("srv", defs, N, new Dictionary<string, bool> { ["a"] = true, ["nope"] = true }));
    }

    // -----------------------------------------------------------------------
    // Gap 6 — ListMcpTools: unfiltered inventory + per-server status incl. a failed server.
    // -----------------------------------------------------------------------

    [Fact]
    public async Task Gap6_ListMcpTools_UnfilteredWithStatus()
    {
        await using var mcp = await StartMcp();

        var inv = await McpSource.ListMcpToolsAsync(new Dictionary<string, object?>
        {
            // filter is IGNORED by the inventory (it exists to author/validate filters).
            ["good"] = new Dictionary<string, object?>
            {
                ["url"] = mcp.Url,
                ["tools"] = new Dictionary<string, object?> { ["a"] = true },
            },
            ["bad"] = new Dictionary<string, object?> { ["url"] = "http://127.0.0.1:1/mcp", ["timeout"] = 500 },
        });

        Assert.Equal(3, inv.Tools["good"].Count); // unfiltered: a,b,c
        Assert.Contains(inv.Tools["good"], t => t.Name == "a"); // ORIGINAL, unprefixed names
        Assert.Equal("connected", inv.Status["good"]);
        Assert.Equal("failed", inv.Status["bad"]);
    }

    // -----------------------------------------------------------------------
    // Gap 3 — hanging server is bounded by its timeout (→ failed); a cancelled token aborts the load.
    // -----------------------------------------------------------------------

    [Fact]
    public async Task Gap3_HangingBounded_AndCancelAborts()
    {
        using var gate = new CancellationTokenSource();
        // A server that accepts but never responds (blocks the handler up to 30s or until released).
        using var hang = new StubServer(_ => gate.Token.WaitHandle.WaitOne(TimeSpan.FromSeconds(30)));
        try
        {
            var sw = Stopwatch.StartNew();
            var src = await McpSource.LoadAsync(new Dictionary<string, object?>
            {
                ["hang"] = new Dictionary<string, object?> { ["url"] = hang.BaseUrl + "/mcp", ["timeout"] = 500 },
            });
            await using (src)
            {
                Assert.Equal("failed", src.Status["hang"]);      // per-server timeout isolates, no throw
                Assert.True(sw.Elapsed < TimeSpan.FromSeconds(5), $"not bounded by timeout: {sw.Elapsed}");
            }

            // Parent-token cancellation aborts the whole load.
            using var cts = new CancellationTokenSource();
            cts.Cancel();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
                await McpSource.LoadAsync(new Dictionary<string, object?>
                {
                    ["hang"] = new Dictionary<string, object?> { ["url"] = hang.BaseUrl + "/mcp", ["timeout"] = 60000 },
                }, cancellationToken: cts.Token));
        }
        finally
        {
            gate.Cancel(); // release the blocked handler
        }
    }

    // -----------------------------------------------------------------------
    // DisableTools / DisableSkills ergonomic layer.
    // -----------------------------------------------------------------------

    [Fact]
    public async Task DisableTools_DropsByFinalName()
    {
        await using var mcp = await StartMcp();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            McpConfig = new Dictionary<string, object?>
            {
                ["srv"] = new Dictionary<string, object?> { ["url"] = mcp.Url },
            },
            DisableTools = new[] { "srv_b" },
        });
        Assert.Null(tk.Get("srv_b"));      // dropped
        Assert.NotNull(tk.Get("srv_a"));   // others intact
        Assert.NotNull(tk.Get("srv_c"));
    }

    [Fact]
    public async Task DisableSkills_DropsByName()
    {
        await using var sk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            Skills = new List<SkillSource.SkillDef>
            {
                new("keep", "k", "k"),
                new("drop", "d", "d"),
            },
            DisableSkills = new[] { "drop" },
        });
        var prompt = sk.SkillsPrompt();
        Assert.Contains("keep", prompt);
        Assert.DoesNotContain("**drop**", prompt);
    }
}
