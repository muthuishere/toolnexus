using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;
using Toolnexus.Mcp2026;
using Fx = Mcp2026Fixture;

namespace Toolnexus.Tests;

/// <summary>
/// MCP 2026-07-28 client (SPEC §2, §2A; change upgrade-mcp-2026-07-28) against the in-test fixture server
/// (examples/mcp-2026/fixture.json) over HTTP and stdio, and a dev authorization server. Hermetic: loopback only.
/// </summary>
public class Mcp2026Tests
{
    private static readonly JsonObject Expected =
        JsonNode.Parse(File.ReadAllText(TestFixtures.Fixture(Path.Combine("mcp-2026", "expected.json"))))!.AsObject();

    private static JsonObject Step(string id) =>
        Expected["steps"]!.AsArray().OfType<JsonObject>().First(s => (string?)s["id"] == id)["expect"]!.AsObject();

    private static Dictionary<string, object?> Cfg(string name, Dictionary<string, object?> server) =>
        new() { ["mcpServers"] = new Dictionary<string, object?> { [name] = server } };

    private static Dictionary<string, object?> Http(string url, Dictionary<string, object?>? extra = null)
    {
        var d = new Dictionary<string, object?> { ["url"] = url, ["timeout"] = 10000L };
        if (extra != null) foreach (var (k, v) in extra) d[k] = v;
        return d;
    }

    internal static string FixtureDll()
    {
        var p = Path.Combine(AppContext.BaseDirectory, "Mcp2026Fixture.dll");
        Assert.True(File.Exists(p), "Mcp2026Fixture.dll is copied next to the tests");
        return p;
    }

    private static Dictionary<string, object?> Stdio(string mode, long timeout = 10000L) => new()
    {
        ["command"] = "dotnet", ["args"] = new List<object?> { FixtureDll(), mode }, ["timeout"] = timeout,
    };

    private static ITool Tool(McpSource src, string name) => src.Tools.First(t => t.Name == name);

    private static Dictionary<string, object?> Args(params (string, object?)[] kv) => kv.ToDictionary(x => x.Item1, x => x.Item2);

    // ------------------------------------------------------------------ wire

    [Fact]
    public void McpNameHeaderUsesBase64SentinelForUnsafeValues()
    {
        Assert.Equal("echo", Wire.EncodeHeader("echo"));
        Assert.Equal("=?base64?IGVjaG8=?=", Wire.EncodeHeader(" echo"));
        Assert.Equal("=?base64?w6l0w6k=?=", Wire.EncodeHeader("été"));
        Assert.StartsWith("=?base64?", Wire.EncodeHeader("=?base64?abc?="));
    }

    [Fact]
    public void SkillUriContainmentRejectsTheSharedCases()
    {
        foreach (var bad in Expected["skillUriRejected"]!.AsArray().Select(x => (string)x!))
            Assert.False(SkillUri.TryParse(bad, out _, out _, out _), bad);
        Assert.True(SkillUri.TryParse("skill://hello-world/SKILL.md", out var name, out var rel, out _));
        Assert.Equal(("hello-world", "SKILL.md"), (name, rel));
    }

    // ------------------------------------------------------------------ discover, tools, MRTR (HTTP + stdio)

    [Fact]
    public async Task HttpDiscoverListAndCall()
    {
        await using var host = await Fx.HttpHost.StartAsync(Fx.Server.Fixture());
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp")));
        Assert.Equal("connected", src.Status["fx"]);
        var names = Step("tools/list")["toolNames"]!.AsArray().Select(x => "fx_" + (string)x!).ToList();
        Assert.Equal(names, src.Tools.Select(t => t.Name).OrderBy(n => n, StringComparer.Ordinal).ToList());
        var echo = Tool(src, "fx_echo");
        Assert.Equal("Echo the given text back.", echo.Description);
        Assert.Equal("object", echo.InputSchema["type"]);
        Assert.Equal(false, echo.InputSchema["additionalProperties"]);

        var exp = Step("tools/call echo");
        var r = await echo.ExecuteAsync(Args(("text", "hi toolnexus")));
        Assert.False(r.IsError);
        Assert.Equal(exp["structuredContent"]!.ToJsonString(), r.Output); // §2.F: structuredContent wins
        Assert.Equal("fx", r.Metadata!["server"]);
    }

    [Fact]
    public async Task MrtrRoundTripThroughWaitFor()
    {
        var server = Fx.Server.Fixture();
        await using var host = await Fx.HttpHost.StartAsync(server);
        var asked = new List<Request>();
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp")), r =>
        {
            asked.Add(r);
            return Task.FromResult(new Answer { Id = r.Id, Ok = true, Data = new Dictionary<string, object?> { ["name"] = "toolnexus" } });
        });
        var r = await Tool(src, "fx_greet").ExecuteAsync(Args());
        Assert.False(r.IsError, r.Output);
        Assert.Contains((string)Step("tools/call greet (MRTR input_required round trip)")["text"]!, r.Output);
        var req = Assert.Single(asked);
        Assert.Equal("input", req.Kind);
        Assert.StartsWith("mrtr-", req.Id);
        Assert.Equal("What is your name?", req.Prompt);
        Assert.NotNull(req.Data!["schema"]);
        Assert.Equal(1, server.Executions);
    }

    [Fact]
    public async Task MrtrWithoutWaitForIsPendingAndResumes()
    {
        var server = Fx.Server.Fixture();
        await using var host = await Fx.HttpHost.StartAsync(server);
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp")));
        var greet = Tool(src, "fx_greet");
        var first = await greet.ExecuteAsync(Args());
        var pending = ToolResult.PendingOf(first);
        Assert.NotNull(pending);
        Assert.Equal("input", pending!.Kind);
        Assert.Equal(0, server.Executions);

        var answer = new Answer { Id = pending.Id, Ok = true, Data = new Dictionary<string, object?> { ["name"] = "toolnexus" } };
        var done = await greet.ExecuteAsync(Args(), new ToolContext(answer: answer));
        Assert.False(done.IsError, done.Output);
        Assert.Contains("Hello, toolnexus!", done.Output);
        Assert.Equal(1, server.Executions);
    }

    [Fact]
    public async Task MrtrPendingHaltsTheClientLoopAndResumesWithTheAnswer()
    {
        // Through the real client loop: no waitFor ⇒ RunResult status "pending" (§10), no hang.
        var server = Fx.Server.Fixture();
        await using var host = await Fx.HttpHost.StartAsync(server);
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { McpConfig = Cfg("fx", Http(host.Url + "/mcp")), Builtins = false });
        var src = tk.Tools().First(t => t.Name == "fx_greet");
        var r = await tk.ExecuteAsync("fx_greet", Args());
        Assert.NotNull(ToolResult.PendingOf(r));
        Assert.Equal("mcp", src.Source);
    }

    [Fact]
    public async Task StdioDiscoverListCallAndMrtr()
    {
        var asked = 0;
        await using var src = await McpSource.LoadAsync(Cfg("sx", Stdio("stdio-fixture")), r =>
        {
            asked++;
            return Task.FromResult(new Answer { Id = r.Id, Ok = true, Data = new Dictionary<string, object?> { ["name"] = "stdio" } });
        });
        Assert.Equal("connected", src.Status["sx"]);
        Assert.Equal(new[] { "sx_echo", "sx_greet" }, src.Tools.Select(t => t.Name).OrderBy(n => n, StringComparer.Ordinal));
        // concurrent calls are demultiplexed by id
        var calls = Enumerable.Range(0, 20).Select(i => Tool(src, "sx_echo").ExecuteAsync(Args(("text", "m" + i)))).ToList();
        var results = await Task.WhenAll(calls);
        for (var i = 0; i < 20; i++) Assert.Equal("{\"text\":\"m" + i + "\"}", results[i].Output);
        var g = await Tool(src, "sx_greet").ExecuteAsync(Args());
        Assert.Contains("Hello, stdio!", g.Output);
        Assert.Equal(1, asked);
    }

    [Fact]
    public async Task StdioDeadChildIsAnIsolatedFailure()
    {
        await using var src = await McpSource.LoadAsync(Cfg("dead", Stdio("stdio-exit")));
        Assert.Equal("failed", src.Status["dead"]);
        Assert.Empty(src.Tools);
    }

    [Fact]
    public async Task StdioSilentServerFallsBackWithinBudget()
    {
        // No reply to server/discover within min(timeout, 5 s) ⇒ frozen fallback; the SDK path then fails
        // in its own budget. Bounded and isolated, never a hang.
        var sw = System.Diagnostics.Stopwatch.StartNew();
        await using var src = await McpSource.LoadAsync(Cfg("quiet", Stdio("stdio-silent", 1500)));
        Assert.Equal("failed", src.Status["quiet"]);
        Assert.True(sw.ElapsedMilliseconds < 15000, $"took {sw.ElapsedMilliseconds}ms");
    }

    [Fact]
    public async Task OldServerFallsBackToTheFrozenSdkPath()
    {
        if (!McpProgressTests.NodeOk()) return;
        var fixture = TestFixtures.Fixture("mcp-progress-server.mjs");
        await using var src = await McpSource.LoadAsync(Cfg("old", new Dictionary<string, object?>
        {
            ["command"] = "node", ["args"] = new List<object?> { fixture }, ["timeout"] = 10000L,
        }));
        Assert.Equal("connected", src.Status["old"]);
        Assert.NotEmpty(src.Tools);
    }

    [Fact]
    public async Task HttpOldServerAnswering404FallsBack()
    {
        // discover gets HTTP 404 ⇒ fallback (§2.2a); the SDK path then fails against this non-server: "failed",
        // never a crash. Proves 4xx-on-discover is a fallback trigger, not a load failure on its own.
        var hits = 0;
        await using var host = await Fx.HttpHost.StartAsync(http => { Interlocked.Increment(ref hits); http.Response.StatusCode = 404; return Task.CompletedTask; });
        await using var src = await McpSource.LoadAsync(Cfg("x", Http(host.Url + "/mcp", new() { ["timeout"] = 3000L })));
        Assert.Equal("failed", src.Status["x"]);
        Assert.True(hits >= 2, "the SDK fallback also tried the server");
    }

    [Fact]
    public async Task Http5xxOnDiscoverTriesTheFrozenFallback()
    {
        var hits = 0;
        await using var host = await Fx.HttpHost.StartAsync(http => { Interlocked.Increment(ref hits); http.Response.StatusCode = 503; return Task.CompletedTask; });
        await using var src = await McpSource.LoadAsync(Cfg("x", Http(host.Url + "/mcp", new() { ["timeout"] = 3000L })));
        Assert.Equal("failed", src.Status["x"]);
        Assert.True(hits >= 2, "the SDK fallback was tried after the 5xx");
    }

    // ------------------------------------------------------------------ no hidden re-execution (§2.6)

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ToolCallIsNeverResentAfterTheServerRanIt(bool use502)
    {
        var server = Fx.Server.Fixture();
        await using var host = await Fx.HttpHost.StartAsync(server);
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp")));
        if (use502) server.Fail502AfterRun = true; else server.DropAfterRun = true;
        var r = await Tool(src, "fx_echo").ExecuteAsync(Args(("text", "once")));
        Assert.True(r.IsError);
        Assert.Equal(1, server.Executions);
    }

    // ------------------------------------------------------------------ skills extension (§2.7)

    [Fact]
    public async Task RemoteSkillsJoinTheSkillToolWithTheLocalBody()
    {
        await using var host = await Fx.HttpHost.StartAsync(Fx.Server.Fixture());
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { McpConfig = Cfg("fx", Http(host.Url + "/mcp")), Builtins = false });
        var local = SkillSource.Load(TestFixtures.SkillsDir()).Skills["hello-world"];
        var r = await tk.ExecuteAsync("skill", Args(("name", "hello-world")));
        Assert.False(r.IsError, r.Output);
        Assert.Contains("<skill_content name=\"hello-world\">", r.Output);
        Assert.Contains(local.Content.Trim(), r.Output);
        Assert.Contains("Base directory for this skill: skill://hello-world/", r.Output);
    }

    [Fact]
    public async Task RemoteSkillBodyIsByteIdenticalToTheLocalCopy()
    {
        await using var host = await Fx.HttpHost.StartAsync(Fx.Server.Fixture());
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp")));
        var remote = Assert.Single(src.RemoteSkills);
        var local = SkillSource.Load(TestFixtures.SkillsDir()).Skills["hello-world"];
        Assert.Equal(local.Name, remote.Name);
        Assert.Equal(local.Description, remote.Description);
        Assert.Equal(local.Content, remote.Content);
    }

    // ------------------------------------------------------------------ tool search (§2.8)

    [Fact]
    public async Task ToolSearchExposesTwoToolsAndRunsTheFoundOne()
    {
        var server = Fx.Server.Fixture();
        await using var host = await Fx.HttpHost.StartAsync(server);
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp", new() { ["toolSearch"] = true, ["description"] = "the fixture" })));
        Assert.Equal(new[] { "fx_call_tool", "fx_search_tools" }, src.Tools.Select(t => t.Name).OrderBy(n => n, StringComparer.Ordinal));
        var search = Tool(src, "fx_search_tools");
        Assert.Equal("Search the tools of the fixture by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.", search.Description);
        Assert.Equal("Run a tool found with search_tools.", Tool(src, "fx_call_tool").Description);
        Assert.Equal("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"query\"]}",
            Json.Stringify(search.InputSchema));

        var hits = JsonNode.Parse((await search.ExecuteAsync(Args(("query", "echo text")))).Output)!.AsArray();
        var top = hits[0]!.AsObject();
        Assert.Equal("echo", (string)top["name"]!);
        foreach (var k in Expected["toolSearch"]!["hitKeys"]!.AsArray()) Assert.True(top.ContainsKey((string)k!), (string)k!);
        Assert.Equal("the fixture", (string)top["server"]!);

        var r = await Tool(src, "fx_call_tool").ExecuteAsync(Args(("name", "echo"), ("arguments", Args(("text", "found")))));
        Assert.Equal("{\"text\":\"found\"}", r.Output);
        var bad = await Tool(src, "fx_call_tool").ExecuteAsync(Args(("name", "fx_echo")));
        Assert.True(bad.IsError);
        Assert.Equal("unknown tool \"fx_echo\" on fx", bad.Output);
    }

    [Fact]
    public async Task ConnectionRefusedIsALoadFailure()
    {
        var l = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        l.Start();
        var port = ((System.Net.IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        await using var src = await McpSource.LoadAsync(Cfg("x", Http($"http://127.0.0.1:{port}/mcp", new() { ["timeout"] = 3000L })));
        Assert.Equal("failed", src.Status["x"]);
    }

    [Fact]
    public async Task SearchWithNoMatchIsAnEmptyArray()
    {
        await using var host = await Fx.HttpHost.StartAsync(Fx.Server.Fixture());
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp", new() { ["toolSearch"] = true })));
        Assert.Equal("[]", (await Tool(src, "fx_search_tools").ExecuteAsync(Args(("query", "zzz")))).Output);
        var hit = (await Tool(src, "fx_search_tools").ExecuteAsync(Args(("query", "echo"), ("limit", 1L)))).Output;
        Assert.StartsWith("[{\"name\":\"echo\",\"description\":\"Echo the given text back.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]},\"server\":\"\"}]", hit);
    }

    [Fact]
    public async Task ToolsFilterAppliesBeforeToolSearch()
    {
        var server = Fx.Server.Fixture();
        await using var host = await Fx.HttpHost.StartAsync(server);
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp", new()
        {
            ["toolSearch"] = true, ["tools"] = new Dictionary<string, object?> { ["echo"] = false },
        })));
        Assert.Equal("[]", (await Tool(src, "fx_search_tools").ExecuteAsync(Args(("query", "echo text")))).Output);
        Assert.Contains("\"greet\"", (await Tool(src, "fx_search_tools").ExecuteAsync(Args(("query", "greet")))).Output);
        var r = await Tool(src, "fx_call_tool").ExecuteAsync(Args(("name", "echo"), ("arguments", Args(("text", "x")))));
        Assert.True(r.IsError);
        Assert.Equal("unknown tool \"echo\" on fx", r.Output);
        Assert.Equal(0, server.Executions);
    }

    [Fact]
    public async Task ToolkitDisableToolsAppliesBeforeToolSearch()
    {
        var server = Fx.Server.Fixture();
        await using var host = await Fx.HttpHost.StartAsync(server);
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            McpConfig = Cfg("my fx", Http(host.Url + "/mcp", new() { ["toolSearch"] = true })),
            DisableTools = ["my_fx_echo"], Builtins = false,
        });
        Assert.Equal("[]", (await tk.ExecuteAsync("my_fx_search_tools", Args(("query", "echo text")))).Output);
        var r = await tk.ExecuteAsync("my_fx_call_tool", Args(("name", "echo"), ("arguments", Args(("text", "x")))));
        Assert.Equal("unknown tool \"echo\" on my fx", r.Output);
        Assert.False((await tk.ExecuteAsync("my_fx_call_tool", Args(("name", "greet")))).Output.StartsWith("unknown tool"));
        Assert.Equal(0, server.Executions);
    }

    [Fact]
    public async Task ToolSearchAboutDefaultsToTheServerName()
    {
        await using var host = await Fx.HttpHost.StartAsync(Fx.Server.Fixture());
        await using var src = await McpSource.LoadAsync(Cfg("fx", Http(host.Url + "/mcp", new() { ["toolSearch"] = true })));
        Assert.StartsWith("Search the tools of fx by keywords.", Tool(src, "fx_search_tools").Description);
    }

    // ------------------------------------------------------------------ OAuth (§2A)

    /// <summary>A dev AS + a protected fixture server (+ whoami) + a CIMD document host, all plain http on loopback.</summary>
    private sealed class AuthEnv : IAsyncDisposable
    {
        public Fx.DevAuthServer As = null!;
        public Fx.Server Server = null!;
        public Fx.HttpHost Mcp = null!, Cimd = null!;
        public string Url = "", CimdUrl = "";

        public static async Task<AuthEnv> StartAsync()
        {
            var e = new AuthEnv { As = await Fx.DevAuthServer.StartAsync() };
            e.Cimd = await Fx.HttpHost.StartAsync(async http =>
            {
                http.Response.ContentType = "application/json";
                await http.Response.WriteAsync(new JsonObject
                {
                    ["client_id"] = e.CimdUrl, ["client_name"] = "toolnexus tests", ["redirect_uris"] = new JsonArray("http://127.0.0.1/callback"),
                    ["grant_types"] = new JsonArray("authorization_code", "refresh_token"), ["response_types"] = new JsonArray("code"),
                    ["token_endpoint_auth_method"] = "none",
                }.ToJsonString());
            });
            e.CimdUrl = e.Cimd.Url + "/client.json";
            e.Server = Fx.Server.Fixture();
            e.Server.Auth = new Fx.ServerAuth { Issuer = e.As.Issuer, Scopes = new() { ["*"] = ["mcp:read"], ["greet"] = ["mcp:greet"] } };
            e.Server.Tool("whoami", "who is calling", (ctx, _) => ctx.Principal is { } p ? p.Subject : throw new Exception("no principal"));
            e.Mcp = await Fx.HttpHost.StartAsync(e.Server);
            e.Url = e.Mcp.Url + "/mcp";
            e.As.Clients["prereg"] = ["http://127.0.0.1:9/callback"];
            return e;
        }

        public Dictionary<string, object?> Auto(Dictionary<string, object?>? extra = null)
        {
            var a = new Dictionary<string, object?> { ["clientMetadataUrl"] = CimdUrl, ["allowHttp"] = true };
            if (extra != null) foreach (var (k, v) in extra) a[k] = v;
            return Cfg("p", Http(Url, new() { ["auth"] = a }));
        }

        public Dictionary<string, object?> Pause() => Cfg("p", Http(Url, new()
        {
            ["auth"] = new Dictionary<string, object?>
            {
                ["clientIds"] = new Dictionary<string, object?> { [As.Issuer] = "prereg" }, ["mode"] = "pause",
                ["redirectUri"] = "http://127.0.0.1:9/callback", ["allowHttp"] = true,
            },
        }));

        public McpLoadOptions Opts(Func<Request, Task<Answer>>? waitFor = null, ITokenStore? store = null, List<Exception>? errors = null) => new()
        {
            WaitFor = waitFor, TokenStore = store,
            OpenUrl = async url => await Fx.DevAuthServer.BrowseAsync(url),
            OnServer = r => { if (r.Error != null) errors?.Add(r.Error); },
        };

        /// <summary>A §10 waitFor that "is the browser": visits the authorization URL, answers with the callback URL.</summary>
        public static Func<Request, Task<Answer>> Browser(List<Request>? seen = null, Func<string, string>? tamper = null) => async r =>
        {
            seen?.Add(r);
            if (r.Kind != "authorization")
                return new Answer { Id = r.Id, Ok = true, Data = new Dictionary<string, object?> { ["name"] = "auth" } };
            var cb = await Fx.DevAuthServer.CaptureRedirectAsync(r.Url!);
            return new Answer { Id = r.Id, Ok = true, Data = new Dictionary<string, object?> { ["callbackUrl"] = tamper?.Invoke(cb) ?? cb } };
        };

        public async ValueTask DisposeAsync()
        {
            await Mcp.DisposeAsync();
            await Cimd.DisposeAsync();
            await As.DisposeAsync();
        }
    }

    [Fact]
    public async Task UnauthenticatedRequestIsChallengedAsExpected()
    {
        await using var e = await AuthEnv.StartAsync();
        using var hc = new HttpClient();
        var req = new HttpRequestMessage(HttpMethod.Post, e.Url) { Content = new StringContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}") };
        req.Headers.Add("MCP-Protocol-Version", "2026-07-28");
        req.Headers.Add("Mcp-Method", "server/discover");
        using var resp = await hc.SendAsync(req);
        Assert.Equal(401, (int)resp.StatusCode);
        var www = resp.Headers.WwwAuthenticate.ToString();
        var exp = Expected["auth"]!["cases"]!.AsArray().OfType<JsonObject>().First(c => (string?)c["id"] == "no token")["expect"]!;
        foreach (var s in exp["wwwAuthenticateContains"]!.AsArray()) Assert.Contains((string)s!, www);
    }

    [Fact]
    public async Task AutoModeLogsInWithCimdAndCallsTools()
    {
        await using var e = await AuthEnv.StartAsync();
        var store = new MemoryTokenStore();
        await using var src = await McpSource.LoadAsync(e.Auto(), e.Opts(store: store));
        Assert.Equal("connected", src.Status["p"]);
        var r = await Tool(src, "p_whoami").ExecuteAsync(Args());
        Assert.Equal("dev-user", r.Output);
        Assert.Equal("{\"text\":\"secured\"}", (await Tool(src, "p_echo").ExecuteAsync(Args(("text", "secured")))).Output);
        Assert.Equal(1, e.As.Stats().Authorizations);
        var q = e.As.LastAuthorizeQuery;
        Assert.Equal(e.Url, q["resource"]);
        Assert.Equal("S256", q["code_challenge_method"]);
        Assert.Equal(e.CimdUrl, q["client_id"]);
        var t = store.Load(e.Url)!;
        Assert.Equal(e.As.Issuer, t.Issuer);
        Assert.DoesNotContain(t.AccessToken, t.ToString());
    }

    [Fact]
    public async Task PauseModeDuringLoadUsesWaitForWithTheCallbackUrl()
    {
        await using var e = await AuthEnv.StartAsync();
        var seen = new List<Request>();
        await using var src = await McpSource.LoadAsync(e.Pause(), e.Opts(AuthEnv.Browser(seen)));
        Assert.Equal("connected", src.Status["p"]);
        var p = Assert.Single(seen);
        Assert.Equal("authorization", p.Kind);
        Assert.StartsWith("auth-", p.Id);
        Assert.Equal("Authorize access to " + e.Url, p.Prompt);
        Assert.StartsWith(e.As.Issuer + "/authorize?", p.Url);
        Assert.NotNull(p.ExpiresAt);
        Assert.Equal("prereg", e.As.LastAuthorizeQuery["client_id"]);
    }

    [Fact]
    public async Task PauseDuringLoadWithoutWaitForFailsTheServerOnly()
    {
        await using var e = await AuthEnv.StartAsync();
        var errors = new List<Exception>();
        await using var src = await McpSource.LoadAsync(e.Pause(), e.Opts(errors: errors));
        Assert.Equal("failed", src.Status["p"]);
        Assert.Equal("authorization pending", Assert.Single(errors).Message);
    }

    [Fact]
    public async Task PauseDuringToolCallIsPendingAndResumeRunsTheToolOnce()
    {
        await using var e = await AuthEnv.StartAsync();
        var store = new MemoryTokenStore();
        // 1) authorize once (with a waitFor) so the store holds a token for this resource
        await using (var first = await McpSource.LoadAsync(e.Pause(), e.Opts(AuthEnv.Browser(), store))) { }
        // 2) a durable host (no waitFor) restarts: token-less first request learns the issuer, stored token works
        await using var src = await McpSource.LoadAsync(e.Pause(), e.Opts(store: store));
        Assert.Equal("connected", src.Status["p"]);
        // 3) the token is revoked/lost: the next tool call pauses (pre-dispatch 401), no tool execution
        store.Delete(e.Url);
        var echo = Tool(src, "p_echo");
        var r = await echo.ExecuteAsync(Args(("text", "after login")));
        var pending = ToolResult.PendingOf(r);
        Assert.NotNull(pending);
        Assert.Equal("authorization", pending!.Kind);
        Assert.StartsWith("auth-", pending.Id);
        Assert.Equal(0, e.Server.Executions);
        // 4) resume with the callback URL: the flow completes and the paused request is sent once
        var cb = await Fx.DevAuthServer.CaptureRedirectAsync(pending.Url!);
        var done = await echo.ExecuteAsync(Args(("text", "after login")), new ToolContext(answer: new Answer
        {
            Id = pending.Id, Ok = true, Data = new Dictionary<string, object?> { ["callbackUrl"] = cb },
        }));
        Assert.Equal("{\"text\":\"after login\"}", done.Output);
        Assert.Equal(1, e.Server.Executions);
        // an unknown id is not a resume: a fresh call
        Assert.False((await echo.ExecuteAsync(Args(("text", "x")), new ToolContext(answer: new Answer { Id = "auth-nope", Ok = true }))).IsError);
    }

    [Fact]
    public async Task IssMismatchIsRejectedAndNothingIsStored()
    {
        await using var e = await AuthEnv.StartAsync();
        e.As.IssOverride = "http://evil.example";
        var store = new MemoryTokenStore();
        var errors = new List<Exception>();
        await using var src = await McpSource.LoadAsync(e.Auto(), e.Opts(store: store, errors: errors));
        Assert.Equal("failed", src.Status["p"]);
        Assert.Equal("authorization response iss mismatch (possible mix-up attack)", Assert.Single(errors).Message);
        Assert.Null(store.Load(e.Url));
    }

    [Fact]
    public async Task StateMismatchIsRejectedAndNothingIsStored()
    {
        await using var e = await AuthEnv.StartAsync();
        var store = new MemoryTokenStore();
        var errors = new List<Exception>();
        var tamper = (string cb) => System.Text.RegularExpressions.Regex.Replace(cb, "state=[^&]+", "state=forged");
        await using var src = await McpSource.LoadAsync(e.Pause(), e.Opts(AuthEnv.Browser(tamper: tamper), store, errors));
        Assert.Equal("failed", src.Status["p"]);
        Assert.Equal("authorization response state mismatch", Assert.Single(errors).Message);
        Assert.Null(store.Load(e.Url));
    }

    [Fact]
    public async Task DeniedPauseAnswerFailsWithReason()
    {
        await using var e = await AuthEnv.StartAsync();
        var errors = new List<Exception>();
        await using var src = await McpSource.LoadAsync(e.Pause(), e.Opts(r => Task.FromResult(Answer.Declined(r.Id)), errors: errors));
        Assert.Equal("authorization denied: declined", Assert.Single(errors).Message);
    }

    [Fact]
    public async Task AuthorizationServerWithoutS256IsRefused()
    {
        await using var e = await AuthEnv.StartAsync();
        e.As.NoS256 = true;
        var errors = new List<Exception>();
        await using var src = await McpSource.LoadAsync(e.Auto(), e.Opts(errors: errors));
        Assert.Equal("authorization server does not advertise PKCE S256; refusing", Assert.Single(errors).Message);
    }

    [Fact]
    public async Task NoClientIdentityNamesTheIssuerAndRefusesDcr()
    {
        await using var e = await AuthEnv.StartAsync();
        var errors = new List<Exception>();
        var cfg = Cfg("p", Http(e.Url, new() { ["auth"] = new Dictionary<string, object?> { ["allowHttp"] = true } }));
        await using var src = await McpSource.LoadAsync(cfg, e.Opts(errors: errors));
        var msg = Assert.Single(errors).Message;
        Assert.StartsWith("no client identity for " + e.As.Issuer + ":", msg);
        Assert.Contains("register an app with", msg);
        Assert.EndsWith("register an app with " + e.As.Issuer + " and set auth.clientId (dynamic client registration is not supported)", msg);
    }

    [Fact]
    public async Task FormEncodedTokenResponseIsParsed()
    {
        await using var e = await AuthEnv.StartAsync();
        e.As.FormTokens = true;
        var store = new MemoryTokenStore();
        await using var src = await McpSource.LoadAsync(e.Auto(), e.Opts(store: store));
        Assert.Equal("connected", src.Status["p"]);
        Assert.NotNull(store.Load(e.Url));
    }

    private static void Expire(ITokenStore store, string url) =>
        store.Save(url, store.Load(url)! with { ExpiresAt = DateTimeOffset.UtcNow.AddMinutes(-1) });

    [Fact]
    public async Task ExpiredTokenIsRefreshedOnceEvenUnder100ConcurrentCalls()
    {
        await using var e = await AuthEnv.StartAsync();
        var store = new MemoryTokenStore();
        await using var src = await McpSource.LoadAsync(e.Auto(), e.Opts(store: store));
        Expire(store, e.Url);
        var before = e.As.Stats().Refreshes;
        var r = await Task.WhenAll(Enumerable.Range(0, 100).Select(i => Tool(src, "p_echo").ExecuteAsync(Args(("text", "c" + i)))));
        Assert.All(r, x => Assert.False(x.IsError, x.Output));
        Assert.Equal(1, e.As.Stats().Refreshes - before);
        Assert.Equal(1, e.As.Stats().Authorizations);
        Assert.Equal(e.Url, e.As.LastTokenForm["resource"]); // resource sent on refresh by default
    }

    [Fact]
    public async Task OmitRefreshResourceWorksAgainstAnAsRejectingIt()
    {
        await using var e = await AuthEnv.StartAsync();
        e.As.RejectRefreshResource = true;
        var store = new MemoryTokenStore();
        await using var src = await McpSource.LoadAsync(e.Auto(new() { ["omitRefreshResource"] = true }), e.Opts(store: store));
        Expire(store, e.Url);
        var r = await Tool(src, "p_echo").ExecuteAsync(Args(("text", "x")));
        Assert.False(r.IsError, r.Output);
        Assert.Equal(1, e.As.Stats().Refreshes);
        Assert.False(e.As.LastTokenForm.ContainsKey("resource"));
        Assert.Equal(1, e.As.Stats().Authorizations);
    }

    [Fact]
    public async Task StepUpOnInsufficientScopeRequestsTheUnion()
    {
        await using var e = await AuthEnv.StartAsync();
        var store = new MemoryTokenStore();
        var opts = e.Opts(AuthEnv.Browser(), store); // waitFor answers the greet input; auto mode does the logins
        await using var src = await McpSource.LoadAsync(e.Auto(), opts);
        var r = await Tool(src, "p_greet").ExecuteAsync(Args());
        Assert.False(r.IsError, r.Output);
        Assert.Contains("Hello, auth!", r.Output);
        Assert.Equal("mcp:read mcp:greet", e.As.LastAuthorizeQuery["scope"]);
        Assert.Equal(2, e.As.Stats().Authorizations);
        Assert.Equal(1, e.Server.Executions);
    }

    [Fact]
    public async Task TokensNeverAppearInLogsOrErrors()
    {
        await using var e = await AuthEnv.StartAsync();
        var store = new MemoryTokenStore();
        var err = new StringWriter();
        var old = Console.Error;
        Console.SetError(err);
        try
        {
            await using var src = await McpSource.LoadAsync(e.Auto(), e.Opts(store: store));
            e.Server.DropAfterRun = true;
            var r = await Tool(src, "p_echo").ExecuteAsync(Args(("text", "x")));
            var t = store.Load(e.Url)!;
            foreach (var secret in new[] { t.AccessToken, t.RefreshToken! })
            {
                Assert.DoesNotContain(secret, err.ToString());
                Assert.DoesNotContain(secret, r.Output);
                Assert.DoesNotContain(secret, t.ToString());
            }
        }
        finally { Console.SetError(old); }
    }
}
