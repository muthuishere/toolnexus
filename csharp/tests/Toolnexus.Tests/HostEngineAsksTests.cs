using System.Diagnostics;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>A mock LLM wire: the delegate sees the parsed body (and the request) and returns the response text.</summary>
internal sealed class FnWire : HttpMessageHandler
{
    private readonly Func<HttpRequestMessage, Dictionary<string, object?>, Task<string>> _fn;
    private readonly object _gate = new();
    public readonly List<Dictionary<string, object?>> Bodies = new();

    public FnWire(Func<HttpRequestMessage, Dictionary<string, object?>, Task<string>> fn) => _fn = fn;
    public FnWire(Func<Dictionary<string, object?>, string> fn) => _fn = (_, b) => Task.FromResult(fn(b));

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var raw = request.Content == null ? "{}" : await request.Content.ReadAsStringAsync(ct);
        var body = Json.ParseObjectLoose(raw);
        lock (_gate) Bodies.Add(body);
        var text = await _fn(request, body);
        var sse = text.StartsWith("data:");
        return new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(text, Encoding.UTF8, sse ? "text/event-stream" : "application/json"),
        };
    }

    public static string Sse(params object[] events)
        => string.Concat(events.Select(e => "data: " + (e is string s ? s : JsonSerializer.Serialize(e)) + "\n\n"));

    /// <summary>True when the last message is a tool result (openai `tool` or anthropic tool_result block).</summary>
    public static bool AfterToolResult(Dictionary<string, object?> body)
    {
        var msgs = body.Get("messages") as List<object?> ?? new();
        if (msgs.Count == 0 || msgs[^1] is not IDictionary<string, object?> last) return false;
        if (last.Get("role") as string == "tool") return true;
        return last.Get("content") is List<object?> blocks
               && blocks.OfType<IDictionary<string, object?>>().Any(b => b.Get("type") as string == "tool_result");
    }

    public static string OpenAiCall(string id, string name, object args)
        => JsonSerializer.Serialize(new
        {
            choices = new[] { new { message = new { role = "assistant", content = (string?)null, tool_calls = new[] {
                new { id, type = "function", function = new { name, arguments = JsonSerializer.Serialize(args) } } } } } },
        });

    public static string OpenAiText(string text)
        => JsonSerializer.Serialize(new { choices = new[] { new { message = new { role = "assistant", content = text } } } });
}

/// <summary>go-agent-cli asks (SPEC §4C): Register concurrency, OnServer, WaitForCtx, hints, gates, CallId.</summary>
public class HostEngineAsksTests
{
    internal static LlmClient Client(HttpMessageHandler wire, string style = "openai", Action<LlmClient.Options>? more = null)
    {
        var o = new LlmClient.Options
        {
            BaseUrl = "http://stub.invalid/v1", Style = style, Model = "m", ApiKey = "k",
            HttpClient = new HttpClient(wire), Retries = 0,
        };
        more?.Invoke(o);
        return LlmClient.Create(o);
    }

    /// <summary>Model asks for get_balance, then answers "done" after the tool result.</summary>
    private static FnWire BalanceWire() => new(b => FnWire.AfterToolResult(b)
        ? FnWire.OpenAiText("done")
        : FnWire.OpenAiCall("c1", "get_balance", new { }));

    /// <summary>get_balance suspends with an authorization request until authed (or answered).</summary>
    private static async Task<Toolkit> BalanceToolkit(Func<bool> authed, ToolHints? hints = null)
    {
        var tool = NativeTool.Of("get_balance", "balance", null, (_, ctx) =>
            authed() || ctx?.Answer != null
                ? "balance: 42"
                : ToolResult.Pending(new Request { Kind = "authorization", Prompt = "sign in to the bank" }));
        tool.Hints = hints;
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        return tk.Register(tool);
    }

    [Fact]
    public async Task ToolkitRegisterConcurrentWithReads()
    {
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        using var stop = new CancellationTokenSource();
        var readers = Enumerable.Range(0, 4).Select(r => Task.Run(async () =>
        {
            while (!stop.IsCancellationRequested)
            {
                _ = tk.Tools();
                _ = tk.Get("t0");
                _ = await tk.ExecuteAsync("t0", null);
                _ = tk.ToOpenAI();
            }
        })).ToArray();
        var writers = Enumerable.Range(0, 4).Select(w => Task.Run(() =>
        {
            for (var i = w; i < 200; i += 4)
                tk.Register(NativeTool.Of($"t{i}", "d", null, _ => "ok"));
        })).ToArray();
        await Task.WhenAll(writers);
        stop.Cancel();
        await Task.WhenAll(readers);
        Assert.Equal(200, tk.Tools().Count);
    }

    [Fact]
    public async Task LoadMcpOnServerReportsFastServerFirst()
    {
        if (!McpProgressTests.NodeOk()) return;
        var fixture = TestFixtures.Fixture("mcp-progress-server.mjs");
        // "slow" starts only after OnServer has reported "fast" (it waits for this gate file),
        // so the order is guaranteed by causality, not by a sleep racing node startup.
        var gate = Path.Combine(Path.GetTempPath(), "tn-fast-gate-" + Guid.NewGuid().ToString("N"));
        var cfg = new Dictionary<string, object?>
        {
            ["mcp"] = new Dictionary<string, object?>
            {
                ["fast"] = new Dictionary<string, object?> { ["command"] = new List<object?> { "node", fixture }, ["timeout"] = 5000L },
                ["slow"] = new Dictionary<string, object?> { ["command"] = new List<object?> { "sh", "-c", "while [ ! -f '" + gate + "' ]; do sleep 0.02; done; exec node '" + fixture + "'" }, ["timeout"] = 10000L },
                ["bad"] = new Dictionary<string, object?> { ["command"] = new List<object?> { "/nonexistent/toolnexus-no-such-binary" }, ["timeout"] = 2000L },
                ["off"] = new Dictionary<string, object?> { ["command"] = new List<object?> { "node", fixture }, ["enabled"] = false },
            },
        };
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var sw = Stopwatch.StartNew();
        var order = new List<string>();
        var at = new Dictionary<string, long>();
        var results = new Dictionary<string, McpServerResult>();
        await using var src = await McpSource.LoadAsync(cfg, new McpLoadOptions
        {
            OnServer = r =>
            {
                order.Add(r.Name);
                at[r.Name] = sw.ElapsedMilliseconds;
                results[r.Name] = r;
                tk.Register(r.Tools.ToArray());
                if (r.Name == "fast") File.WriteAllText(gate, "");
            },
        });
        try { File.Delete(gate); } catch { }
        Assert.Equal(4, order.Count);
        Assert.Equal("connected", results["fast"].Status);
        Assert.NotEmpty(results["fast"].Tools);
        Assert.Equal("connected", results["slow"].Status);
        Assert.Equal("failed", results["bad"].Status);
        Assert.NotNull(results["bad"].Error);
        Assert.Equal("disabled", results["off"].Status);
        Assert.True(order.IndexOf("fast") < order.IndexOf("slow"), string.Join(",", order));
        Assert.True(at["fast"] <= at["slow"], $"fast={at["fast"]} slow={at["slow"]}");
        Assert.NotNull(tk.Get("fast_slow"));
        Assert.Equal("disabled", src.Status["off"]);
    }

    [Fact]
    public async Task WaitForCtxCancelsWithTurn()
    {
        using var cts = new CancellationTokenSource();
        var seen = new TaskCompletionSource<bool>();
        var c = Client(BalanceWire(), more: o =>
        {
            o.WaitFor = _ => throw new InvalidOperationException("WaitFor must not run when WaitForCtx is set");
            o.WaitForCtx = async (req, ct) =>
            {
                _ = Task.Delay(50).ContinueWith(_ => cts.Cancel());
                try
                {
                    await Task.Delay(5000, ct);
                    seen.TrySetResult(false);
                    return new Answer { Id = req.Id, Ok = true };
                }
                catch (OperationCanceledException)
                {
                    seen.TrySetResult(true);
                    throw;
                }
            };
        });
        var btk = await BalanceToolkit(() => false);
        var sw = Stopwatch.StartNew();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => c.RunAsync("what is my balance?", btk, null, cts.Token));
        Assert.True(await seen.Task, "WaitForCtx must see the turn's token cancelled");
        Assert.True(sw.ElapsedMilliseconds < 2000, $"run took {sw.ElapsedMilliseconds}ms after cancel");
    }

    [Fact]
    public async Task WaitForCtxResolves()
    {
        var c = Client(BalanceWire(), more: o => o.WaitForCtx = (req, _) => Task.FromResult(new Answer { Id = req.Id, Ok = true }));
        var res = await c.RunAsync("what is my balance?", await BalanceToolkit(() => false));
        Assert.Equal("done", res.Status);
        Assert.Equal("balance: 42", res.ToolCalls.Single().Output);
    }

    [Fact]
    public void BuiltinHints()
    {
        var want = new Dictionary<string, bool>
        {
            ["read"] = true, ["grep"] = true, ["glob"] = true, ["webfetch"] = true, ["question"] = true, ["todowrite"] = true,
            ["bash"] = false, ["write"] = false, ["edit"] = false, ["apply_patch"] = false,
        };
        var tools = BuiltinTools.Create();
        Assert.Equal(10, tools.Count);
        foreach (var t in tools)
        {
            Assert.True(want.TryGetValue(t.Name, out var ro), t.Name);
            Assert.NotNull(t.Hints);
            Assert.Equal(ro, t.Hints!.ReadOnly);
            Assert.NotEqual(ro, t.Hints.Destructive);
        }
        Assert.True(tools.Single(t => t.Name == "bash").Hints!.OpenWorld);
        Assert.True(tools.Single(t => t.Name == "webfetch").Hints!.OpenWorld);
    }

    [Fact]
    public void McpHintsMapping()
    {
        Assert.Null(McpSource.McpHints(null, null, null));
        Assert.Equal(new ToolHints(true, false, true), McpSource.McpHints(true, null, null));
        Assert.Equal(new ToolHints(false, false, false), McpSource.McpHints(null, false, false));
        Assert.Equal(new ToolHints(false, true, true), McpSource.McpHints(false, null, null));
    }

    [Fact]
    public async Task PermissionGateDeniesMutatingTool()
    {
        ToolHints? seen = null;
        var c = Client(BalanceWire(), more: o => o.Hooks = new LlmClient.Hooks
        {
            BeforeTool = ev =>
            {
                seen = ev.Hints;
                return ev.Hints is not { ReadOnly: true }
                    ? LlmClient.ToolOverride.WithResult(ToolResult.Error("denied: plan mode allows read-only tools only"))
                    : null;
            },
        });
        var res = await c.RunAsync("balance?", await BalanceToolkit(() => true, new ToolHints(Destructive: true)));
        Assert.Equal("done", res.Status);
        Assert.True(seen is { Destructive: true });
        var call = Assert.Single(res.ToolCalls);
        Assert.True(call.IsError);
        Assert.Equal("denied: plan mode allows read-only tools only", call.Output);
    }

    /// <summary>Always answers with two parallel calls: c1 → fast, c2 → slow.</summary>
    private static FnWire TwoCallWire() => new((req, body) =>
    {
        var anthropic = req.RequestUri!.AbsolutePath.EndsWith("/messages");
        var stream = body.Get("stream") is true;
        if (anthropic)
            return Task.FromResult(stream
                ? FnWire.Sse(
                    new { type = "message_start", message = new { usage = new { input_tokens = 1 } } },
                    new { type = "content_block_start", index = 0, content_block = new { type = "tool_use", id = "c1", name = "fast" } },
                    new { type = "content_block_delta", index = 0, delta = new { type = "input_json_delta", partial_json = "{}" } },
                    new { type = "content_block_start", index = 1, content_block = new { type = "tool_use", id = "c2", name = "slow" } },
                    new { type = "content_block_delta", index = 1, delta = new { type = "input_json_delta", partial_json = "{}" } },
                    new { type = "message_delta", delta = new { stop_reason = "tool_use" }, usage = new { output_tokens = 1 } })
                : JsonSerializer.Serialize(new
                {
                    content = new object[]
                    {
                        new { type = "tool_use", id = "c1", name = "fast", input = new { } },
                        new { type = "tool_use", id = "c2", name = "slow", input = new { } },
                    },
                    stop_reason = "tool_use",
                }));
        return Task.FromResult(stream
            ? FnWire.Sse(
                new { choices = new[] { new { delta = new { tool_calls = new[] { new { index = 0, id = "c1", function = new { name = "fast", arguments = "{}" } } } } } } },
                new { choices = new[] { new { delta = new { tool_calls = new[] { new { index = 1, id = "c2", function = new { name = "slow", arguments = "{}" } } } } } } },
                "[DONE]")
            : JsonSerializer.Serialize(new
            {
                choices = new[] { new { message = new { role = "assistant", content = (string?)null, tool_calls = new object[] {
                    new { id = "c1", type = "function", function = new { name = "fast", arguments = "{}" } },
                    new { id = "c2", type = "function", function = new { name = "slow", arguments = "{}" } } } } } },
            }));
    });

    [Theory]
    [InlineData("openai", false)]
    [InlineData("openai", true)]
    [InlineData("anthropic", false)]
    [InlineData("anthropic", true)]
    public async Task ToolContextCallIdOnParallelCalls(string style, bool stream)
    {
        var seen = new System.Collections.Concurrent.ConcurrentDictionary<string, string?>();
        ITool Mk(string name) => NativeTool.OfAsync(name, name, null, async (_, ctx) =>
        {
            if (name == "slow") await Task.Delay(30);
            seen[name] = ctx?.CallId;
            return "ok";
        });
        var tk = (await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false })).Register(Mk("fast"), Mk("slow"));
        var c = Client(TwoCallWire(), style, o => o.MaxTurns = 1);
        if (stream) await c.StreamAsync("go", tk, _ => { });
        else await c.RunAsync("go", tk);
        Assert.Equal("c1", seen["fast"]);
        Assert.Equal("c2", seen["slow"]);
    }
}

/// <summary>SPEC §4C security: a key is only ever sent to the host it belongs to; plus ListModels.</summary>
[Collection("env")]
public class ResolveKeyTests : IDisposable
{
    private static readonly string[] Vars = { "OPENROUTER_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "TN_TEST_KEY" };
    private readonly Dictionary<string, string?> _saved = Vars.ToDictionary(v => v, Environment.GetEnvironmentVariable);

    public ResolveKeyTests()
    {
        Environment.SetEnvironmentVariable("OPENROUTER_API_KEY", "fake-or");
        Environment.SetEnvironmentVariable("OPENAI_API_KEY", "fake-oa");
        Environment.SetEnvironmentVariable("ANTHROPIC_API_KEY", "fake-an");
        Environment.SetEnvironmentVariable("TN_TEST_KEY", "fake-custom");
    }

    public void Dispose()
    {
        foreach (var (k, v) in _saved) Environment.SetEnvironmentVariable(k, v);
    }

    private static string Resolve(string baseUrl, string style = "openai", string? key = null, string? env = null)
        => LlmClient.ResolveKey(new LlmClient.Options { BaseUrl = baseUrl, Style = style, ApiKey = key, ApiKeyEnv = env });

    [Fact]
    public void ResolveKeyByHost()
    {
        Assert.Equal("fake-oa", Resolve("https://api.openai.com/v1"));
        Assert.Equal("fake-an", Resolve("https://api.anthropic.com", "anthropic"));
        Assert.Equal("fake-or", Resolve("https://openrouter.ai/api/v1", "anthropic"));
        Assert.Equal("fake-oa", Resolve("https://API.OpenAI.com/v1"));
        Assert.Equal("fake-explicit", Resolve("https://example.com/v1", key: "fake-explicit"));
        Assert.Equal("fake-explicit", Resolve("https://api.openai.com/v1", key: "fake-explicit"));
        Assert.Equal("fake-custom", Resolve("https://example.com/v1", env: "TN_TEST_KEY"));

        // never a cross-provider fallback
        foreach (var b in new[]
                 {
                     "https://example.com/v1", "http://api.openai.com/v1", "https://api.openai.com.evil.test/",
                     "https://evil-openrouter.ai/v1", "http://localhost:11434/v1",
                 })
        {
            var e = Assert.Throws<InvalidOperationException>(() => Resolve(b));
            Assert.DoesNotContain("fake-", e.Message);
        }

        // the right env var unset ⇒ an error naming it, not another provider's key
        Environment.SetEnvironmentVariable("OPENAI_API_KEY", null);
        var err = Assert.Throws<InvalidOperationException>(() => Resolve("https://api.openai.com/v1"));
        Assert.Contains("OPENAI_API_KEY", err.Message);
    }

    [Fact]
    public async Task ListModels()
    {
        string? auth = null, path = null;
        var wire = new FnWire((req, _) =>
        {
            path = req.RequestUri!.AbsolutePath + "?" + req.RequestUri.Query.TrimStart('?');
            if (req.Headers.Contains("x-api-key"))
            {
                auth = "x-api-key";
                return Task.FromResult(req.RequestUri.Query.Contains("after_id")
                    ? "{\"data\":[{\"id\":\"claude-a\",\"display_name\":\"A\"}],\"has_more\":false}"
                    : "{\"data\":[{\"id\":\"claude-b\",\"display_name\":\"B\"}],\"has_more\":true,\"last_id\":\"claude-b\"}");
            }
            auth = req.Headers.Authorization?.ToString();
            return Task.FromResult("{\"data\":[{\"id\":\"z/model\",\"name\":\"Z\",\"context_length\":200000},{\"id\":\"a/model\"}]}");
        });
        var ms = await LlmClient.ListModelsAsync(new LlmClient.Options
            { BaseUrl = "http://stub.invalid/api/v1", ApiKey = "fake", HttpClient = new HttpClient(wire) });
        Assert.Equal(new[] { "a/model", "z/model" }, ms.Select(m => m.Id));
        Assert.Equal(200000, ms[1].ContextLength);
        Assert.Equal("Bearer fake", auth);
        Assert.Equal("/api/v1/models?", path);

        ms = await LlmClient.ListModelsAsync(new LlmClient.Options
            { BaseUrl = "http://stub.invalid", Style = "anthropic", ApiKey = "fake", HttpClient = new HttpClient(wire) });
        Assert.Equal(new[] { "claude-a", "claude-b" }, ms.Select(m => m.Id));
        Assert.Equal("B", ms[1].Name);
        Assert.Equal("x-api-key", auth);

        await Assert.ThrowsAsync<InvalidOperationException>(() => LlmClient.ListModelsAsync(new LlmClient.Options
            { BaseUrl = "http://stub.invalid", HttpClient = new HttpClient(wire) }));
    }
}

[CollectionDefinition("env", DisableParallelization = true)]
public class EnvCollection { }

/// <summary>ask #7: background shells, websearch, notebook_edit, MCP resource tools, grep context, truncation metadata.</summary>
public class HostBuiltinExtrasTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "tn-extras-" + Guid.NewGuid().ToString("N"));

    public HostBuiltinExtrasTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch { }
    }

    private static ITool Extra(Dictionary<string, object?> cfg, string name)
        => BuiltinTools.Create(cfg).Single(t => t.Name == name);

    [Fact]
    public void OptInBuiltinsAbsentByDefault()
    {
        var tools = BuiltinTools.Create(new Dictionary<string, object?>());
        Assert.DoesNotContain(tools, t => t.Name is "bash_output" or "kill_shell" or "websearch" or "notebook_edit");
        var props = (IDictionary<string, object?>)tools.Single(t => t.Name == "bash").InputSchema["properties"]!;
        Assert.False(props.ContainsKey("run_in_background"));
    }

    /// <summary>A shell signalled from outside (not via kill_shell) reports exit code -1, as Go does.</summary>
    [Fact]
    public async Task BackgroundShellSignalledFromOutsideIsMinusOne()
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return;
        var bg = new BackgroundShells();
        try
        {
            var cfg = new Dictionary<string, object?> { ["background"] = bg, ["killGraceMs"] = 100L };
            var bash = Extra(cfg, "bash");
            var output = Extra(cfg, "bash_output");
            var r = await bash.ExecuteAsync(new Dictionary<string, object?> { ["command"] = "kill -TERM $$; sleep 5", ["run_in_background"] = true });
            var id = r.Metadata?.Get("backgroundId") as string;
            var deadline = DateTime.UtcNow.AddSeconds(5);
            while (true)
            {
                var o = await output.ExecuteAsync(new Dictionary<string, object?> { ["id"] = id });
                if (o.Metadata!["status"] as string == "exited")
                {
                    Assert.Equal(-1L, o.Metadata["exitCode"]);
                    Assert.Contains("[status: exited with code -1]", o.Output);
                    break;
                }
                Assert.True(DateTime.UtcNow < deadline, "never exited");
                await Task.Delay(50);
            }
        }
        finally { await bg.CloseAllAsync(); }
    }

    [Fact]
    public async Task BackgroundShellLifecycle()
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return;
        var bg = new BackgroundShells();
        try
        {
            var cfg = new Dictionary<string, object?> { ["background"] = bg, ["killGraceMs"] = 100L };
            var bash = Extra(cfg, "bash");
            var output = Extra(cfg, "bash_output");
            var kill = Extra(cfg, "kill_shell");

            var r = await bash.ExecuteAsync(new Dictionary<string, object?> { ["command"] = "echo one; sleep 0.2; echo two", ["run_in_background"] = true });
            var id = r.Metadata?.Get("backgroundId") as string;
            Assert.False(r.IsError, r.Output);
            Assert.False(string.IsNullOrEmpty(id));
            var deadline = DateTime.UtcNow.AddSeconds(5);
            var all = new StringBuilder();
            while (true)
            {
                var o = await output.ExecuteAsync(new Dictionary<string, object?> { ["id"] = id });
                all.Append(o.Output.Split("\n[status:")[0]);
                if (o.Metadata!["status"] as string == "exited")
                {
                    Assert.Equal(0L, o.Metadata["exitCode"]);
                    break;
                }
                Assert.True(DateTime.UtcNow < deadline, "never exited");
                await Task.Delay(50);
            }
            var text = all.ToString();
            Assert.Equal(1, text.Split("one").Length - 1);
            Assert.Equal(1, text.Split("two").Length - 1);

            // a stubborn command with a child, killed as a tree
            r = await bash.ExecuteAsync(new Dictionary<string, object?> { ["command"] = "trap '' TERM; sleep 30 & wait", ["run_in_background"] = true });
            var id2 = (string)r.Metadata!["backgroundId"]!;
            await Task.Delay(100);
            var sw = Stopwatch.StartNew();
            var k = await kill.ExecuteAsync(new Dictionary<string, object?> { ["id"] = id2 });
            Assert.False(k.IsError, k.Output);
            Assert.Equal(true, k.Metadata!["killed"]);
            Assert.True(sw.ElapsedMilliseconds < 2000, $"kill took {sw.ElapsedMilliseconds}ms");
            var after = await output.ExecuteAsync(new Dictionary<string, object?> { ["id"] = id2 });
            Assert.Equal("killed", after.Metadata!["status"]);
            var k2 = await kill.ExecuteAsync(new Dictionary<string, object?> { ["id"] = id2 });
            Assert.Equal(false, k2.Metadata!["killed"]);
            Assert.True((await output.ExecuteAsync(new Dictionary<string, object?> { ["id"] = "nope" })).IsError);
            Assert.Equal(2, bg.List().Count);
        }
        finally
        {
            await bg.CloseAllAsync();
        }
    }

    [Fact]
    public async Task WebSearchProvider()
    {
        string? gotQ = null;
        var gotN = 0;
        WebSearchFn search = (q, n, _) =>
        {
            gotQ = q;
            gotN = n;
            return Task.FromResult<IReadOnlyList<WebSearchResult>>(new[]
            {
                new WebSearchResult("A", "https://a.example", "sa"), new WebSearchResult("B", "https://b.example"),
            });
        };
        var ws = Extra(new Dictionary<string, object?> { ["webSearch"] = search }, "websearch");
        Assert.True(ws.Hints is { ReadOnly: true, OpenWorld: true });
        var r = await ws.ExecuteAsync(new Dictionary<string, object?> { ["query"] = "go", ["limit"] = 1L });
        Assert.Equal("go", gotQ);
        Assert.Equal(1, gotN);
        Assert.Equal("1. A\n   https://a.example\n   sa", r.Output);
    }

    [Fact]
    public async Task NotebookEdit()
    {
        var nb = "{\"cells\":[{\"cell_type\":\"code\",\"id\":\"a\",\"metadata\":{},\"source\":[\"x = 1\\n\"],\"outputs\":[{\"output_type\":\"stream\",\"text\":[\"1\"]}],\"execution_count\":3},{\"cell_type\":\"markdown\",\"id\":\"b\",\"metadata\":{},\"source\":[\"# T\"]}],\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}";
        var p = Path.Combine(_dir, "n.ipynb");
        File.WriteAllText(p, nb);
        var ne = Extra(new Dictionary<string, object?> { ["notebookEdit"] = true, ["baseDir"] = _dir }, "notebook_edit");
        List<object?> Load() => (List<object?>)Json.ToMap(File.ReadAllText(p))["cells"]!;

        var r = await ne.ExecuteAsync(new Dictionary<string, object?> { ["path"] = "n.ipynb", ["cell_id"] = "a", ["new_source"] = "x = 2\ny = 3" });
        Assert.False(r.IsError, r.Output);
        var c0 = (IDictionary<string, object?>)Load()[0]!;
        Assert.Empty((List<object?>)c0["outputs"]!);
        Assert.Null(c0["execution_count"]);
        Assert.Equal(2, ((List<object?>)c0["source"]!).Count);

        r = await ne.ExecuteAsync(new Dictionary<string, object?> { ["path"] = "n.ipynb", ["cell_index"] = 0L, ["edit_mode"] = "insert", ["cell_type"] = "markdown", ["new_source"] = "hi" });
        Assert.False(r.IsError, r.Output);
        var cells = Load();
        Assert.Equal(3, cells.Count);
        Assert.Equal("markdown", ((IDictionary<string, object?>)cells[1]!)["cell_type"]);

        r = await ne.ExecuteAsync(new Dictionary<string, object?> { ["path"] = "n.ipynb", ["cell_id"] = "b", ["edit_mode"] = "delete" });
        Assert.False(r.IsError, r.Output);
        Assert.Equal(2, Load().Count);
        Assert.NotNull(r.Metadata!.Get("files"));
        Assert.True((await ne.ExecuteAsync(new Dictionary<string, object?> { ["path"] = "n.ipynb", ["cell_id"] = "zz" })).IsError);
    }

    [Fact]
    public async Task GrepContextAndTruncation()
    {
        File.WriteAllText(Path.Combine(_dir, "f.txt"), "a\nb\nHIT1\nc\nd\ne\nf\nHIT2\ng\n");
        var cfg = new Dictionary<string, object?> { ["baseDir"] = _dir };
        var grep = Extra(cfg, "grep");
        var r = await grep.ExecuteAsync(new Dictionary<string, object?> { ["pattern"] = "HIT", ["context"] = 1L });
        Assert.Equal("f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g", r.Output);
        r = await grep.ExecuteAsync(new Dictionary<string, object?> { ["pattern"] = "HIT", ["before"] = 0L, ["after"] = 4L });
        Assert.Equal("f.txt:3:HIT1\nf.txt-4-c\nf.txt-5-d\nf.txt-6-e\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g", r.Output);
        r = await grep.ExecuteAsync(new Dictionary<string, object?> { ["pattern"] = "HIT", ["limit"] = 1L });
        Assert.Equal("f.txt:3:HIT1", r.Output);
        Assert.Equal(true, r.Metadata!["truncated"]);
        Assert.Equal(2L, r.Metadata["total"]);
        // no limit hit ⇒ no truncation keys, text unchanged
        r = await grep.ExecuteAsync(new Dictionary<string, object?> { ["pattern"] = "HIT" });
        Assert.Equal("f.txt:3:HIT1\nf.txt:8:HIT2", r.Output);
        Assert.False(r.Metadata!.ContainsKey("truncated"));

        var read = Extra(cfg, "read");
        r = await read.ExecuteAsync(new Dictionary<string, object?> { ["path"] = "f.txt", ["limit"] = 2L });
        Assert.Equal("a\nb", r.Output);
        Assert.Equal(true, r.Metadata!["truncated"]);
        Assert.Equal(9L, r.Metadata["totalLines"]);
        Assert.Equal(3L, r.Metadata["nextOffset"]);
        r = await read.ExecuteAsync(new Dictionary<string, object?> { ["path"] = "f.txt", ["offset"] = 8L });
        Assert.Equal("HIT2\ng\n", r.Output);
        Assert.Null(r.Metadata);
    }

    [Fact]
    public async Task McpResourceTools()
    {
        if (!McpProgressTests.NodeOk()) return;
        var fixture = TestFixtures.Fixture("mcp-progress-server.mjs");
        // "slow" starts only after OnServer has reported "fast" (it waits for this gate file),
        // so the order is guaranteed by causality, not by a sleep racing node startup.
        var gate = Path.Combine(Path.GetTempPath(), "tn-fast-gate-" + Guid.NewGuid().ToString("N"));
        var cfg = "{\"mcp\":{\"progress\":{\"command\":\"node\",\"args\":[" + Json.Stringify(fixture) + "],\"timeout\":5000}}}";
        await using var src = await McpSource.LoadAsync(cfg);
        var tools = src.ResourceTools();
        var (list, read) = (tools[0], tools[1]);
        var r = await list.ExecuteAsync(new Dictionary<string, object?>());
        Assert.False(r.IsError, r.Output);
        Assert.Equal("progress fixture://hello — hello (text/plain): A fixed text resource.", r.Output);
        r = await read.ExecuteAsync(new Dictionary<string, object?> { ["server"] = "progress", ["uri"] = "fixture://hello" });
        Assert.False(r.IsError, r.Output);
        Assert.Equal("hello resource", r.Output);
        Assert.True((await read.ExecuteAsync(new Dictionary<string, object?> { ["server"] = "progress", ["uri"] = "fixture://nope" })).IsError);
        Assert.True((await read.ExecuteAsync(new Dictionary<string, object?> { ["server"] = "nope", ["uri"] = "x" })).IsError);
        Assert.True(list.Hints!.ReadOnly && read.Hints!.ReadOnly);
    }
}

internal static class McpProgressTests
{
    public static bool NodeOk()
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("node", "--version")
                { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false });
            p!.WaitForExit(5000);
            return p.ExitCode == 0;
        }
        catch { return false; }
    }
}

/// <summary>ask #6: Compactor OnCompact, the sole user prompt is kept, no-op when not smaller, Folded verbatim.</summary>
public class CompactorHostTests
{
    private static List<object?> Transcript(int userTurns)
    {
        var pad = string.Concat(Enumerable.Repeat("pad ", 40));
        var data = string.Concat(Enumerable.Repeat("data ", 40));
        var m = new List<object?> { new Dictionary<string, object?> { ["role"] = "system", ["content"] = "You are Ava. SOUL." } };
        for (var i = 0; i < userTurns; i++)
        {
            var id = "c" + i;
            m.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = $"question {i} {pad}" });
            m.Add(new Dictionary<string, object?>
            {
                ["role"] = "assistant", ["content"] = null,
                ["tool_calls"] = new List<object?> { new Dictionary<string, object?>
                {
                    ["id"] = id, ["type"] = "function",
                    ["function"] = new Dictionary<string, object?> { ["name"] = "lookup", ["arguments"] = "{}" },
                } },
            });
            m.Add(new Dictionary<string, object?> { ["role"] = "tool", ["tool_call_id"] = id, ["content"] = $"result {i} {data}" });
            m.Add(new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = $"answer {i}" });
        }
        return m;
    }

    private static string Summarize(IReadOnlyList<object?> older) => $"summarized {older.Count} messages";

    private static LlmClient.BeforeLLMEvent Ev(List<object?> msgs) => new(msgs, new List<Dictionary<string, object?>>(), "m", 0);

    [Fact]
    public void OnCompactReportsWhatChanged()
    {
        var got = new List<Compaction.CompactInfo>();
        var hook = Compaction.Compactor(new Compaction.Options { MaxTokens = 50, KeepTail = 20, Summarize = Summarize, OnCompact = got.Add });
        var msgs = Transcript(10);
        var o = hook(Ev(msgs));
        Assert.NotNull(o);
        var i = Assert.Single(got);
        Assert.Equal(msgs.Count, i.MessagesBefore);
        Assert.Equal(o!.Messages!.Count, i.MessagesAfter);
        Assert.True(i.Summarized > 0);
        Assert.Equal(Compaction.EstimateTokens(msgs), i.TokensBefore);
        Assert.Equal(Compaction.EstimateTokens(o.Messages), i.TokensAfter);
        Assert.True(i.TokensAfter < i.TokensBefore);
        Assert.NotEmpty(i.Summary);

        var big = Compaction.Compactor(new Compaction.Options
            { MaxTokens = 1 << 30, Summarize = Summarize, OnCompact = _ => throw new Exception("OnCompact on a no-op") });
        Assert.Null(big(Ev(msgs)));
    }

    [Fact]
    public void KeepsSoleUserPrompt()
    {
        var data = string.Concat(Enumerable.Repeat("data ", 40));
        var msgs = new List<object?>
        {
            new Dictionary<string, object?> { ["role"] = "system", ["content"] = "SOUL" },
            new Dictionary<string, object?> { ["role"] = "user", ["content"] = "do the task" },
        };
        for (var i = 0; i < 8; i++)
        {
            var id = "c" + i;
            msgs.Add(new Dictionary<string, object?>
            {
                ["role"] = "assistant", ["content"] = null,
                ["tool_calls"] = new List<object?> { new Dictionary<string, object?>
                {
                    ["id"] = id, ["type"] = "function",
                    ["function"] = new Dictionary<string, object?> { ["name"] = "lookup", ["arguments"] = "{}" },
                } },
            });
            msgs.Add(new Dictionary<string, object?> { ["role"] = "tool", ["tool_call_id"] = id, ["content"] = data });
        }
        Compaction.CompactInfo? info = null;
        var hook = Compaction.Compactor(new Compaction.Options { MaxTokens = 100, KeepTail = 20, Summarize = Summarize, OnCompact = x => info = x });
        var o = hook(Ev(msgs));
        Assert.NotNull(o);
        Assert.Equal(3, o!.Messages!.Count);
        Assert.Equal("do the task", ((IDictionary<string, object?>)o.Messages[1]!)["content"]);
        Assert.Equal(msgs.Count - 2, info!.Folded.Count);
    }

    [Fact]
    public void NoOpWhenNotSmaller()
    {
        var msgs = Transcript(2);
        var hook = Compaction.Compactor(new Compaction.Options
        {
            MaxTokens = 10, KeepTail = 5, Summarize = _ => string.Concat(Enumerable.Repeat("long summary ", 500)),
            OnCompact = _ => throw new Exception("OnCompact on a no-op"),
        });
        Assert.Null(hook(Ev(msgs)));
    }

    [Fact]
    public void FoldedIsVerbatim()
    {
        var msgs = Transcript(10);
        Compaction.CompactInfo? info = null;
        var hook = Compaction.Compactor(new Compaction.Options { MaxTokens = 50, KeepTail = 20, Summarize = Summarize, OnCompact = x => info = x });
        Assert.NotNull(hook(Ev(msgs)));
        Assert.Equal(info!.Summarized, info.Folded.Count);
        Assert.NotEmpty(info.Folded);
        for (var i = 0; i < info.Folded.Count; i++) Assert.Same(msgs[1 + i], info.Folded[i]);
    }
}

/// <summary>ask #7 follow-up: AsTool cancels with the calling token; AsToolWith runs the child under the host's gate and ask bridge.</summary>
public class AsToolHostTests
{
    private sealed class BlockingLlm : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            await Task.Delay(Timeout.Infinite, ct);
            throw new OperationCanceledException(ct);
        }
    }

    [Fact]
    public async Task AsToolCancelledWithCallingToken()
    {
        var child = new Toolnexus.Agents.Agent("child", new AgentSpec { Does = "d", Soul = "s" });
        var tool = child.AsTool(new RuntimeOptions { Handler = new BlockingLlm(), ApiKey = "k" });
        using var cts = new CancellationTokenSource(100);
        var sw = Stopwatch.StartNew();
        var r = await tool.ExecuteAsync(new Dictionary<string, object?> { ["prompt"] = "go" }, new ToolContext(cancellationToken: cts.Token));
        Assert.True(sw.ElapsedMilliseconds < 3000, $"child not cancelled: took {sw.ElapsedMilliseconds}ms");
        Assert.True(r.IsError);
        Assert.StartsWith("cancelled", r.Output);
    }

    [Fact]
    public async Task AsToolWithGateAndAskBridge()
    {
        var ran = false;
        var danger = NativeTool.Of("danger", "mutates", null, _ => { ran = true; return "did it"; });
        danger.Hints = new ToolHints(Destructive: true);
        var child = new Toolnexus.Agents.Agent("child", new AgentSpec { Does = "d", Soul = "s", Uses = new List<ITool> { danger } });
        var gated = new List<string>();
        var asked = 0;
        var n = 0;
        var wire = new FnWire(b => Interlocked.Increment(ref n) == 1
            ? FnWire.OpenAiCall("d1", "danger", new { })
            : FnWire.OpenAiText("child done"));
        var tool = child.AsToolWith(_ => new RuntimeOptions
        {
            Handler = wire, ApiKey = "k",
            Hooks = new LlmClient.Hooks
            {
                BeforeTool = ev =>
                {
                    gated.Add(ev.Name);
                    return ev.Hints is not { ReadOnly: true }
                        ? LlmClient.ToolOverride.WithResult(ToolResult.Pending(new Request { Kind = "approval", Prompt = $"allow {ev.Name}?" }))
                        : null;
                },
            },
            WaitFor = req =>
            {
                asked++;
                return Task.FromResult(new Answer { Id = req.Id, Ok = true });
            },
        });
        var r = await tool.ExecuteAsync(new Dictionary<string, object?> { ["prompt"] = "go" }, new ToolContext());
        Assert.False(r.IsError, r.Output);
        Assert.Equal("child done", r.Output);
        Assert.Equal(new[] { "danger" }, gated);
        Assert.Equal(1, asked);
        Assert.True(ran);
    }
}
