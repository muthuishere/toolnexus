using System.Diagnostics;
using System.Net.Http;
using System.Text;

namespace Toolnexus.Tests;

/// <summary>
/// Starts (or reuses, via <c>TN_MOCK_LLM_URL</c>) the shared mock model server
/// <c>tools/mock-llm/mock_llm.py</c> once for the class. Null <see cref="BaseUrl"/> = python3 missing.
/// </summary>
public sealed class MockLlmServer : IDisposable
{
    private readonly Process? _proc;
    public string? BaseUrl { get; }
    public string? SkipReason { get; }

    public MockLlmServer()
    {
        var env = Environment.GetEnvironmentVariable("TN_MOCK_LLM_URL");
        if (!string.IsNullOrEmpty(env)) { BaseUrl = env.TrimEnd('/'); return; }
        var script = FindScript();
        if (script is null) { SkipReason = "tools/mock-llm/mock_llm.py not found"; return; }
        try
        {
            _proc = Process.Start(new ProcessStartInfo("python3")
            {
                ArgumentList = { script, "--port", "0", "--quiet" },
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            });
        }
        catch (System.ComponentModel.Win32Exception)
        {
            SkipReason = "python3 is not available; skipping the mock-LLM integration suite";
            return;
        }
        var line = _proc!.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(15)).GetAwaiter().GetResult();
        const string prefix = "toolnexus mock model at ";
        if (line is null || !line.StartsWith(prefix))
            throw new InvalidOperationException("mock did not announce its URL: " + line);
        BaseUrl = line[prefix.Length..].Trim().TrimEnd('/');
    }

    private static string? FindScript() => KitFile("mock_llm.py");

    /// <summary>A file of the mock kit: <c>TN_MOCK_KIT_DIR</c> if set, else <c>tools/mock-llm/</c> found upward.</summary>
    public static string? KitFile(string name)
    {
        var kit = Environment.GetEnvironmentVariable("TN_MOCK_KIT_DIR");
        if (!string.IsNullOrEmpty(kit)) return File.Exists(Path.Combine(kit, name)) ? Path.Combine(kit, name) : null;
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var p = Path.Combine(dir.FullName, "tools", "mock-llm", name);
            if (File.Exists(p)) return p;
            dir = dir.Parent;
        }
        return null;
    }

    public void Dispose()
    {
        if (_proc is { HasExited: false }) { try { _proc.Kill(true); _proc.WaitForExit(5000); } catch { } }
        _proc?.Dispose();
    }
}

/// <summary>
/// End-to-end against the real mock model server over real HTTP: client loop (OpenAI + Anthropic),
/// streaming, §1B attachments, §8 resilience, §8B classifier (custom + cloudflare) and §7G media.
/// Skips (passes with a message) when python3 is unavailable.
/// </summary>
public class MockLlmIntegrationTests : IClassFixture<MockLlmServer>
{
    private const string Reply = "This is a reply from the toolnexus mock model.";
    private readonly MockLlmServer _mock;
    private static readonly HttpClient Http = new();

    public MockLlmIntegrationTests(MockLlmServer mock) { _mock = mock; }

    private bool Skip()
    {
        if (_mock.BaseUrl != null) return false;
        Console.WriteLine("SKIP mock-LLM suite: " + _mock.SkipReason);
        return true;
    }

    private string Base => _mock.BaseUrl!;
    private string Origin => new Uri(Base).GetLeftPart(UriPartial.Authority);

    private LlmClient Client(string style) => LlmClient.Create(new LlmClient.Options
    {
        BaseUrl = Base, Style = style, Model = "mock", ApiKey = "mock-key", RetryBaseMs = 1,
    });

    private static async Task<(Toolkit, List<(double, double)>)> AddToolkit()
    {
        var calls = new List<(double, double)>();
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var schema = new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["a"] = new Dictionary<string, object?> { ["type"] = "integer" },
                ["b"] = new Dictionary<string, object?> { ["type"] = "integer" },
            },
            ["required"] = new List<object?> { "a", "b" },
        };
        tk.Register(NativeTool.Of("add", "add two integers", schema, args =>
        {
            var a = Convert.ToDouble(args["a"]?.ToString(), System.Globalization.CultureInfo.InvariantCulture);
            var b = Convert.ToDouble(args["b"]?.ToString(), System.Globalization.CultureInfo.InvariantCulture);
            lock (calls) calls.Add((a, b));
            return ((long)(a + b)).ToString();
        }));
        return (tk, calls);
    }

    private async Task<string> Get(string path) => await Http.GetStringAsync(Base + path);
    private async Task Post(string path, string json)
        => (await Http.PostAsync(Base + path, new StringContent(json, Encoding.UTF8, "application/json"))).EnsureSuccessStatusCode();

    [Theory]
    [InlineData("openai")]
    [InlineData("anthropic")]
    public async Task ClientRun_ExecutesTheMockToolCall(string style)
    {
        if (Skip()) return;
        var (tk, calls) = await AddToolkit();
        var r = await Client(style).RunAsync("add one and one", tk);
        Assert.Equal(new[] { (1.0, 1.0) }, calls);
        Assert.Equal("Tool result: 2", r.Text);
    }

    [Theory]
    [InlineData("openai")]
    [InlineData("anthropic")]
    public async Task Stream_ConcatenatedDeltasEqualTheReply(string style)
    {
        if (Skip()) return;
        var sb = new StringBuilder();
        var r = await Client(style).StreamAsync("hello", null, e =>
        {
            if (e.Type == LlmClient.StreamKind.Text) sb.Append(e.Delta);
        });
        Assert.Equal(Reply, sb.ToString());
        Assert.Equal(Reply, r.Text);
    }

    [Fact]
    public async Task Attachment_ImageBytesArriveIntact()
    {
        if (Skip()) return;
        var png = TestFixtures.Fixture("media/fixture.png");
        var sha = File.ReadAllText(TestFixtures.Fixture("media/fixture.png.sha256")).Trim()[..12];
        var r = await Client("openai").RunAsync(
            new List<ContentPart> { ContentPart.FromText("what is this?"), ContentPart.FromFile(png) }, null);
        Assert.Contains("image/png 82 bytes sha256=" + sha, r.Text);
    }

    [Fact]
    public async Task Resilience_RetriesOne429ThenSucceeds()
    {
        if (Skip()) return;
        await Post("/_mock/reset", "{}");
        await Post("/_mock/faults", """{"path":"/chat/completions","status":429,"times":1,"retryAfter":"0"}""");
        var r = await Client("openai").RunAsync("hello", null);
        Assert.Equal(Reply, r.Text);
        var counts = Json.ToMap(await Get("/_mock/requests"));
        var flat = Json.Stringify(counts);
        Assert.True(FindCount(counts, "/chat/completions") == 2, "expected 2 chat calls, got " + flat);
    }

    private static long? FindCount(object? node, string route)
    {
        if (node is Dictionary<string, object?> m)
        {
            foreach (var (k, v) in m)
            {
                if (k.EndsWith(route) && v is not Dictionary<string, object?> && v is not List<object?>)
                    return Convert.ToInt64(v);
                if (FindCount(v, route) is { } n) return n;
            }
        }
        return null;
    }

    private static readonly Dictionary<string, string> Options = new()
    {
        ["billing"] = "a payment or invoice problem",
        ["bug"] = "something is broken",
        ["other"] = "anything else",
    };

    private static async Task AssertClassifier(Classifier cl)
    {
        var asks = new[]
        {
            Judge.Noul("urgent", "Is this urgent?"),
            Judge.Choice("team", "Which team owns this?", Options),
            Judge.Score("severity", "How severe is it?", "low", "medium", "high"),
        };
        var d = await cl.State("my invoice was charged twice").EvaluateAsync(asks);
        Assert.Equal(0.95, d.Noul("urgent").Noul, 6);
        var c = d.Choice("team");
        Assert.Contains(c.Choice, Options.Keys);
        Assert.Equal(Options.Keys.OrderBy(k => k, StringComparer.Ordinal), c.Probabilities.Keys.OrderBy(k => k, StringComparer.Ordinal));
        Assert.Equal(1.0, c.Probabilities.Values.Sum(), 6);
        Assert.Equal(0.7, c.Probabilities[c.Choice], 6);
        var s = d.Score("severity");
        Assert.InRange(s.Score, 0.0, 2.0);
        Assert.Equal(1.0, s.Probabilities.Values.Sum(), 6);

        var again = await cl.State("my invoice was charged twice").EvaluateAsync(asks);
        Assert.Equal(c.Choice, again.Choice("team").Choice);
        Assert.Equal(s.Score, again.Score("severity").Score, 9);
    }

    [Fact]
    public async Task Classifier_CustomBackend_SystemOneWire()
    {
        if (Skip()) return;
        await AssertClassifier(new Classifier(new ClassifierOptions
        {
            Backend = ClassifierBackend.Custom, BaseUrl = Base, Supports = new ClassifierSupports(false),
            Model = "jev-mock", ApiKeyEnv = "TN_MOCK_UNSET_KEY", OnError = _ => LlmClient.Tier.Fail,
        }));
    }

    [Fact]
    public async Task Classifier_CloudflareBackend()
    {
        if (Skip()) return;
        await AssertClassifier(new Classifier(new ClassifierOptions
        {
            Backend = ClassifierBackend.Cloudflare, BaseUrl = Origin + "/v1/accounts/acc/ai/run",
            ApiKeyEnv = "TN_MOCK_UNSET_KEY", OnError = _ => LlmClient.Tier.Fail,
        }));
    }

    [Fact]
    public async Task Media_EveryVerbAndAnAsyncFailure()
    {
        if (Skip()) return;
        var tmp = Path.Combine(Path.GetTempPath(), "tn-mock-media-" + Guid.NewGuid().ToString("N") + ".json");
        await File.WriteAllTextAsync(tmp, await Get("/_mock/media-style.json"));
        try
        {
            var media = Media.Create(MediaStyle.Load(tmp));

            var img = await media.ImageAsync(new ImageInput("a red fox"));
            Assert.Equal("completed", img.Status);
            var f = Assert.Single(img.Files);
            Assert.EndsWith("/files/image.png", f.Url);
            Assert.Equal("image/png", f.MimeType);

            var vid = await media.VideoAsync(new VideoInput("a fox running"));
            Assert.EndsWith("/video.mp4", Assert.Single(vid.Files).Url);
            Assert.Equal("video/mp4", vid.Files[0].MimeType);

            var talk = await media.TalkingAsync(new TalkingInput(Base + "/files/image.png", Base + "/files/speech.wav"));
            Assert.Matches("/(video|talking)\\.mp4$", Assert.Single(talk.Files).Url);
            Assert.Equal("video/mp4", talk.Files[0].MimeType);

            var sp = await media.SpeechAsync(new SpeechInput("hello there"));
            Assert.EndsWith("/speech.wav", Assert.Single(sp.Files).Url);
            Assert.Equal("audio/wav", sp.Files[0].MimeType);

            var audio = Base + "/files/speech.wav";
            var tr = await media.TranscribeAsync(new TranscribeInput(audio));
            Assert.Equal("Mock transcript of " + audio + ".", tr.Text);

            var cv = await media.CloneVoiceAsync(new CloneVoiceInput("narrator", new[] { audio }));
            var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes("narrator"))).ToLowerInvariant();
            Assert.Equal("voice-" + hash[..8], cv.Text);

            var e = await Assert.ThrowsAsync<MediaException>(() => media.ImageAsync(new ImageInput("a fox [fail]")));
            Assert.Contains("mock: job asked to fail", e.Message);
        }
        finally { File.Delete(tmp); }
    }
}

/// <summary>
/// Round 2 of the mock kit: a mixed toolkit (MCP stdio + http + legacy, HTTP tool, A2A agent,
/// native tool, skills), a client run over it, the in-process one-shot CLI client
/// (<c>fake_cli.py</c>) and the ACP client (<c>fake_acp_agent.py</c>: approve, reject, cancel).
/// </summary>
public class MockKitIntegrationTests : IClassFixture<MockLlmServer>
{
    private readonly MockLlmServer _mock;
    public MockKitIntegrationTests(MockLlmServer mock) { _mock = mock; }

    private bool Skip()
    {
        if (_mock.BaseUrl != null && MockLlmServer.KitFile("mock_mcp.py") != null) return false;
        Console.WriteLine("SKIP mock-kit suite: " + (_mock.SkipReason ?? "tools/mock-llm kit not found"));
        return true;
    }

    private string Base => _mock.BaseUrl!;
    private string Origin => new Uri(Base).GetLeftPart(UriPartial.Authority);
    private static string Kit(string name) => MockLlmServer.KitFile(name)!;

    private static readonly List<(double, double)> NoCalls = new();

    private static NativeTool Add(List<(double, double)> calls) => NativeTool.Of("add", "Add two numbers.",
        new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["a"] = new Dictionary<string, object?> { ["type"] = "number" },
                ["b"] = new Dictionary<string, object?> { ["type"] = "number" },
            },
            ["required"] = new List<object?> { "a", "b" },
        },
        args =>
        {
            var inv = System.Globalization.CultureInfo.InvariantCulture;
            double a = Convert.ToDouble(args["a"]?.ToString(), inv), b = Convert.ToDouble(args["b"]?.ToString(), inv);
            lock (calls) calls.Add((a, b));
            return ((long)(a + b)).ToString(inv);
        });

    /// <summary>Starts <c>mock_mcp.py --http</c> (optionally legacy) and returns it with its URL.</summary>
    private static (Process, string) StartHttpMcp(bool legacy = false)
    {
        var psi = new ProcessStartInfo("python3")
        {
            ArgumentList = { Kit("mock_mcp.py"), "--http", "--port", "0" },
            RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false,
        };
        if (legacy) psi.ArgumentList.Add("--legacy");
        var p = Process.Start(psi)!;
        var line = p.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(15)).GetAwaiter().GetResult();
        const string prefix = "toolnexus mock mcp at ";
        if (line is null || !line.StartsWith(prefix)) { p.Kill(true); throw new InvalidOperationException("mock mcp: " + line); }
        return (p, line[prefix.Length..].Trim());
    }

    private static void Stop(Process p) { try { if (!p.HasExited) { p.Kill(true); p.WaitForExit(5000); } } catch { } p.Dispose(); }

    private async Task<Toolkit> MixedToolkit(string httpMcpUrl, List<(double, double)> calls)
    {
        var http = HttpTool.Of(new HttpTool.Options
        {
            Name = "http_add", Description = "Add two numbers over HTTP.", Method = "POST", Url = Base + "/tools/add",
            InputSchema = new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["a"] = new Dictionary<string, object?> { ["type"] = "integer" },
                    ["b"] = new Dictionary<string, object?> { ["type"] = "integer" },
                },
                ["required"] = new List<object?> { "a", "b" },
            },
        });
        return await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false,
            SkillsDir = new List<string> { TestFixtures.SkillsDir() },
            ExtraTools = new List<ITool> { Add(calls), http },
            McpConfig = new Dictionary<string, object?>
            {
                ["mcpServers"] = new Dictionary<string, object?>
                {
                    ["mstdio"] = new Dictionary<string, object?>
                    { ["type"] = "local", ["command"] = new List<object?> { "python3", Kit("mock_mcp.py") } },
                    ["mlegacy"] = new Dictionary<string, object?>
                    { ["type"] = "local", ["command"] = new List<object?> { "python3", Kit("mock_mcp.py"), "--legacy" } },
                    ["mhttp"] = new Dictionary<string, object?> { ["type"] = "remote", ["url"] = httpMcpUrl },
                },
                ["agents"] = new Dictionary<string, object?>
                {
                    ["mock"] = new Dictionary<string, object?>
                    { ["card"] = Origin + "/.well-known/agent-card.json", ["pollEvery"] = 20 },
                },
            },
        });
    }

    [Fact]
    public async Task MixedToolkit_EverySourceExecutes()
    {
        if (Skip()) return;
        var (mcp, url) = StartHttpMcp();
        try
        {
            var calls = new List<(double, double)>();
            await using var tk = await MixedToolkit(url, calls);
            var names = tk.Tools().Select(t => t.Name).ToHashSet();
            foreach (var n in new[] { "mstdio_add", "mstdio_echo", "mlegacy_add", "mhttp_add", "mhttp_echo",
                         "mockagent_summarize", "http_add", "add", "skill" })
                Assert.Contains(n, names);

            var two3 = new Dictionary<string, object?> { ["a"] = 2, ["b"] = 3 };
            foreach (var n in new[] { "mstdio_add", "mlegacy_add", "mhttp_add" })
            {
                var r = await tk.ExecuteAsync(n, two3);
                Assert.False(r.IsError, n + ": " + r.Output);
                Assert.Equal("5", r.Output.Trim());
            }
            var echo = await tk.ExecuteAsync("mhttp_echo", new Dictionary<string, object?> { ["text"] = "hi" });
            // SPEC §0.4: structuredContent wins, as JSON.stringify.
            Assert.Equal("""{"text":"hi"}""", echo.Output.Trim());

            var a2a = await tk.ExecuteAsync("mockagent_summarize", new Dictionary<string, object?> { ["task"] = "x" });
            Assert.Equal("A2A mock done: x", a2a.Output.Trim());

            var h = await tk.ExecuteAsync("http_add", two3);
            Assert.False(h.IsError, h.Output);
            Assert.Equal(5L, Convert.ToInt64(Json.ToMap(h.Output)["sum"]));

            Assert.Equal("5", (await tk.ExecuteAsync("add", two3)).Output);
            Assert.Equal(new[] { (2.0, 3.0) }, calls);

            var sk = await tk.ExecuteAsync("skill", new Dictionary<string, object?> { ["name"] = "hello-world" });
            Assert.False(sk.IsError, sk.Output);
            Assert.Contains("# Hello World Skill", sk.Output);
        }
        finally { Stop(mcp); }
    }

    [Fact]
    public async Task ClientRun_OverTheMixedToolkit()
    {
        if (Skip()) return;
        var (mcp, url) = StartHttpMcp();
        try
        {
            await using var tk = await MixedToolkit(url, new List<(double, double)>());
            var client = LlmClient.Create(new LlmClient.Options { BaseUrl = Base, Style = "openai", Model = "mock", ApiKey = "mock-key" });
            var r = await client.RunAsync("use a tool", tk);
            var call = Assert.Single(r.ToolCalls);
            Assert.Equal(tk.ToOpenAI().Select(t => (string)((Dictionary<string, object?>)t["function"]!)["name"]!).First(), call.Name);
            Assert.False(string.IsNullOrWhiteSpace(call.Output));
            Assert.Equal("Tool result: " + call.Output.Trim(), r.Text);
        }
        finally { Stop(mcp); }
    }

    [Fact]
    public async Task InProcess_OneShotCli_DrivesFakeCli()
    {
        if (Skip()) return;
        var calls = new List<(double, double)>();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = new List<ITool> { Add(calls) } });
        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "fake-cli",
            Generate = req =>
            {
                var psi = new ProcessStartInfo("python3")
                {
                    ArgumentList = { Kit("fake_cli.py") },
                    RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
                };
                using var p = Process.Start(psi)!;
                var stdout = p.StandardOutput.ReadToEndAsync();
                p.StandardInput.Write(Render(req));
                p.StandardInput.Close();
                Assert.True(p.WaitForExit(30_000));
                return Parse(stdout.Result);
            },
        });
        var r = await client.RunAsync("Use the add tool to add 19 and 23, then tell me the sum.", tk);
        Assert.Equal("The sum is 42.", r.Text);
        Assert.Equal(new[] { (19.0, 23.0) }, calls);
    }

    // The one-shot CLI contract from site/recipes/in-process-client/csharp/one-shot-cli/OneShot.cs.
    private static string Render(InProcess.Request req)
    {
        var lines = new List<string> { "You are the model inside a tool-calling loop. Tools you may call:" };
        foreach (var t in req.Tools.Select(o => System.Text.Json.JsonSerializer.SerializeToNode(o)!["function"]!))
            lines.Add($"- {t["name"]}: {t["description"]} args={t["parameters"]?.ToJsonString()}");
        lines.AddRange(new[] { "", "Conversation:" });
        var called = new Dictionary<string, string>();
        foreach (var m in req.Messages.Select(o => System.Text.Json.JsonSerializer.SerializeToNode(o)!))
        {
            var role = (string)m["role"]!;
            if (role == "tool")
            {
                var id = (string)m["tool_call_id"]!;
                lines.Add($"[tool {called.GetValueOrDefault(id, id)}] {m["content"]}");
            }
            else if (m["tool_calls"] is System.Text.Json.Nodes.JsonArray tcs)
            {
                foreach (var c in tcs) called[(string)c!["id"]!] = (string)c["function"]!["name"]!;
                lines.Add("[assistant called] " + tcs.ToJsonString());
            }
            else lines.Add($"[{role}] {m["content"]}");
        }
        lines.AddRange(new[] { "", """Reply with ONLY one JSON object: {"tool_calls":[{"name":"...","arguments":{...}}]} to call tools,""",
            """or {"content":"..."} with the final answer. No prose, no code fences.""" });
        return string.Join("\n", lines);
    }

    private static InProcess.Response Parse(string output)
    {
        int start = output.IndexOf('{'), end = output.LastIndexOf('}');
        var r = System.Text.Json.Nodes.JsonNode.Parse(output[start..(end + 1)])!;
        if (r["tool_calls"] is System.Text.Json.Nodes.JsonArray { Count: > 0 } calls)
            return new InProcess.Response
            {
                ToolCalls = calls.Select(c => new InProcess.ToolCall
                { Name = (string)c!["name"]!, Arguments = c["arguments"]?.ToJsonString() ?? "{}" }).ToList(),
            };
        return InProcess.Response.FromContent((string)r["content"]!);
    }

    private async Task<(string Text, List<(double, double)> Calls, List<string> Names)> AcpRun(bool approve)
    {
        var calls = new List<(double, double)>();
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, ExtraTools = new List<ITool> { Add(calls) } });
        await using var server = tk.AsMcpServer();
        using var agent = await Toolnexus.Acp.AcpClient.StartAsync(new Toolnexus.Acp.AcpStartOptions
        {
            Command = "python3", Arguments = new[] { Kit("fake_acp_agent.py") },
            McpServers = new List<object?>
            {
                new Dictionary<string, object?>
                {
                    ["type"] = "http", ["name"] = "toolnexus", ["url"] = server.Url,
                    ["headers"] = new[] { new Dictionary<string, object?> { ["name"] = "Authorization", ["value"] = "Bearer " + server.Token } },
                },
            },
            OnPermission = approve ? null : _ => null,
            PromptTimeout = TimeSpan.FromSeconds(30),
        });
        var client = InProcess.CreateClient(new InProcess.Options { Model = "fake-acp", GenerateStream = agent.GenerateStreamAsync });
        await using var empty = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var sb = new StringBuilder();
        var names = new List<string>();
        var r = await client.StreamAsync("Use the add tool to add 17 and 25.", empty, ev =>
        {
            if (ev.Type == LlmClient.StreamKind.Text) sb.Append(ev.Delta);
            else if (ev.Type == LlmClient.StreamKind.ToolCall && ev.Via == "acp") lock (names) names.Add(ev.Name ?? "");
        });
        Assert.Equal(sb.ToString(), r.Text);
        return (r.Text, calls, names);
    }

    [Fact]
    public async Task Acp_PermissionApproved_RunsTheServedTool()
    {
        if (Skip()) return;
        var (text, calls, names) = await AcpRun(approve: true);
        Assert.Equal("The sum is 42.", text);
        Assert.Equal(new[] { (17.0, 25.0) }, calls);
        Assert.Contains("add", names);
    }

    [Fact]
    public async Task Acp_PermissionRejected()
    {
        if (Skip()) return;
        var (text, calls, _) = await AcpRun(approve: false);
        Assert.Equal("Permission denied.", text);
        Assert.Empty(calls);
    }

    [Fact]
    public async Task Acp_CancelEndsTheTurnAndSessionStaysWarm()
    {
        if (Skip()) return;
        var log = Path.Combine(Path.GetTempPath(), "tn-fake-acp-" + Guid.NewGuid().ToString("N") + ".log");
        try
        {
            using var agent = await Toolnexus.Acp.AcpClient.StartAsync(new Toolnexus.Acp.AcpStartOptions
            {
                Command = "python3", Arguments = new[] { Kit("fake_acp_agent.py") },
                Env = new Dictionary<string, string> { ["FAKE_ACP_LOG"] = log },
                PromptTimeout = TimeSpan.FromSeconds(30), CallTimeout = TimeSpan.FromSeconds(10),
            });
            var seen = new StringBuilder();
            using var cts = new CancellationTokenSource();
            var turn = agent.PromptStreamAsync("please [wait]", ev =>
            {
                if (ev.Type == LlmClient.StreamKind.Text) { lock (seen) seen.Append(ev.Delta); cts.Cancel(); }
            }, cts.Token);
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => turn);
            Assert.Equal("Working...", seen.ToString());
            Assert.Contains(File.ReadAllLines(log), l => l.Contains("\"cancel\""));
            Assert.Equal("Mock ACP reply to: hi", await agent.PromptAsync("hi"));
        }
        finally { File.Delete(log); }
    }
}
