using System.Diagnostics;
using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;
using Toolnexus.Acp;

namespace Toolnexus.Tests;

/// <summary>
/// conformance/acp-client/cases.json (SPEC §8C, ADR 0041) replayed against FakeAcpServer:
/// stream.acpDrip, passthrough (+ set_model fallback + bare), agentExit, unknownRequest and
/// asMcpServer. stream.inProcess is replayed in <see cref="InProcessTests"/>.
/// </summary>
public class AcpConformanceTests
{
    internal static string CasesPath()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir != null; dir = dir.Parent)
        {
            var c = Path.Combine(dir.FullName, "conformance", "acp-client", "cases.json");
            if (File.Exists(c)) return c;
        }
        throw new InvalidOperationException("conformance/acp-client/cases.json not found");
    }

    private static JsonElement Case(params string[] path)
    {
        var el = JsonDocument.Parse(File.ReadAllText(CasesPath())).RootElement;
        foreach (var p in path) el = el.GetProperty(p);
        return el;
    }

    private static string FakeDll() => Path.Combine(AppContext.BaseDirectory, "FakeAcpServer.dll");

    private static Dictionary<string, string> Env(JsonElement fakeEnv)
    {
        var env = new Dictionary<string, string>();
        foreach (var p in fakeEnv.EnumerateObject()) env[p.Name] = p.Value.GetString()!;
        return env;
    }

    private static Task<AcpClient> Start(Dictionary<string, string>? env = null,
        IReadOnlyList<object?>? servers = null, IDictionary<string, object?>? sessionMeta = null,
        IDictionary<string, object?>? capsMeta = null, string? model = null, TimeSpan? promptTimeout = null) =>
        AcpClient.StartAsync(new AcpStartOptions
        {
            Command = "dotnet",
            Arguments = new[] { FakeDll() },
            Cwd = Directory.GetCurrentDirectory(),
            Env = env,
            McpServers = servers,
            SessionMeta = sessionMeta,
            ClientCapsMeta = capsMeta,
            Model = model,
            PromptTimeout = promptTimeout,
        });

    private static List<JsonObject> Records(string rec) =>
        File.ReadAllLines(rec).Where(l => l.Length > 0).Select(l => JsonNode.Parse(l)!.AsObject()).ToList();

    private static JsonNode? Params(List<JsonObject> recs, string method) =>
        recs.FirstOrDefault(r => r["method"]!.GetValue<string>() == method)?["params"];

    private static bool DeepEq(JsonElement want, JsonNode? got) =>
        JsonNode.DeepEquals(JsonNode.Parse(want.GetRawText()), got);

    // ------------------------------------------------------------------ stream.acpDrip

    [Fact]
    public async Task StreamAcpDrip()
    {
        var cs = Case("stream", "acpDrip");
        using var acp = await Start(Env(cs.GetProperty("fakeEnv")));
        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "acp",
            GenerateStream = (req, emit, ct) => acp.GenerateStreamAsync(req, emit, ct),
        });

        var sw = Stopwatch.StartNew();
        var texts = new List<string>();
        var thoughts = new List<LlmClient.StreamEvent>();
        long firstText = -1, done = -1;
        LlmClient.RunResult? doneResult = null;
        await client.StreamAsync("drip?", null, ev =>
        {
            lock (texts)
            {
                switch (ev.Type)
                {
                    case LlmClient.StreamKind.Text:
                        if (firstText < 0) firstText = sw.ElapsedMilliseconds;
                        texts.Add(ev.Delta!);
                        break;
                    case LlmClient.StreamKind.Thought: thoughts.Add(ev); break;
                    case LlmClient.StreamKind.Done:
                        done = sw.ElapsedMilliseconds;
                        doneResult = ev.Result;
                        break;
                }
            }
        });
        Assert.Equal(cs.GetProperty("chunks").EnumerateArray().Select(x => x.GetString()!), texts);
        var th = Assert.Single(thoughts);
        var want = cs.GetProperty("expectThought");
        Assert.Equal(want.GetProperty("delta").GetString(), th.Delta);
        Assert.Equal(want.GetProperty("via").GetString(), th.Via);
        Assert.Equal(cs.GetProperty("expectDoneText").GetString(), doneResult!.Text);
        Assert.True(firstText >= 0 && done - firstText >= cs.GetProperty("minLeadMs").GetInt64(),
            $"stream not incremental: first delta {done - firstText}ms before done");

        var res = await client.RunAsync("drip?", null);
        Assert.Equal(cs.GetProperty("expectDoneText").GetString(), res.Text);
    }

    // ------------------------------------------------------------------ stream.acpToolUpdate (#188)

    [Fact]
    public async Task StreamAcpToolUpdate()
    {
        var cs = Case("stream", "acpToolUpdate");
        using var acp = await Start(Env(cs.GetProperty("fakeEnv")));
        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "acp",
            GenerateStream = (req, emit, ct) => acp.GenerateStreamAsync(req, emit, ct),
        });

        var events = new JsonArray();
        LlmClient.RunResult? doneResult = null;
        await client.StreamAsync("go", null, ev =>
        {
            lock (events)
            {
                switch (ev.Type)
                {
                    case LlmClient.StreamKind.ToolCall:
                        events.Add(new JsonObject { ["type"] = "tool_call", ["id"] = ev.Id, ["name"] = ev.Name, ["via"] = ev.Via });
                        break;
                    case LlmClient.StreamKind.ToolCallArgs:
                        events.Add(new JsonObject
                        {
                            ["type"] = "tool_call_args", ["id"] = ev.Id, ["name"] = ev.Name,
                            ["args"] = JsonSerializer.SerializeToNode(ev.Args), ["via"] = ev.Via,
                        });
                        break;
                    case LlmClient.StreamKind.ToolResult:
                        events.Add(new JsonObject
                        {
                            ["type"] = "tool_result", ["id"] = ev.Id, ["name"] = ev.Name,
                            ["output"] = ev.Output, ["isError"] = ev.IsError, ["via"] = ev.Via,
                        });
                        break;
                    case LlmClient.StreamKind.Done: doneResult = ev.Result; break;
                }
            }
        });
        Assert.True(DeepEq(cs.GetProperty("expectEvents"), events), events.ToJsonString());
        Assert.Equal(cs.GetProperty("expectDoneText").GetString(), doneResult!.Text);
    }

    // ------------------------------------------------------------------ passthrough

    [Fact]
    public async Task PassthroughSendsOptionsVerbatim()
    {
        var cs = Case("passthrough");
        var o = cs.GetProperty("options");
        var rec = Path.GetTempFileName();
        try
        {
            var servers = o.GetProperty("mcpServers").EnumerateArray().Select(e => (object?)e.Clone()).ToList();
            var sessionMeta = (IDictionary<string, object?>)Json.FromElement(o.GetProperty("sessionMeta"))!;
            var capsMeta = (IDictionary<string, object?>)Json.FromElement(o.GetProperty("clientCapsMeta"))!;
            using (await Start(new() { ["TN_FAKE_REC"] = rec }, servers, sessionMeta, capsMeta,
                       o.GetProperty("model").GetString())) { }
            var recs = Records(rec);

            Assert.True(DeepEq(cs.GetProperty("expectInitializeClientCapabilities"),
                Params(recs, "initialize")!["clientCapabilities"]));
            var sn = Params(recs, "session/new")!;
            Assert.True(DeepEq(o.GetProperty("mcpServers"), sn["mcpServers"]), sn.ToJsonString());
            Assert.True(DeepEq(o.GetProperty("sessionMeta"), sn["_meta"]), sn.ToJsonString());
            var sco = Params(recs, "session/set_config_option")!.AsObject();
            foreach (var p in cs.GetProperty("expectSetConfigOption").EnumerateObject())
                Assert.True(DeepEq(p.Value, sco[p.Name]), sco.ToJsonString());
            Assert.Equal("sess-1", sco["sessionId"]!.GetValue<string>());
            Assert.Null(Params(recs, "session/set_model"));
        }
        finally { File.Delete(rec); }
    }

    [Fact]
    public async Task PassthroughFallsBackToSetModel()
    {
        var cs = Case("passthrough");
        var fb = cs.GetProperty("fallbackSetModel");
        var rec = Path.GetTempFileName();
        try
        {
            var env = Env(fb.GetProperty("fakeEnv"));
            env["TN_FAKE_REC"] = rec;
            using (await Start(env, model: cs.GetProperty("options").GetProperty("model").GetString())) { }
            var sm = Params(Records(rec), "session/set_model")!.AsObject();
            foreach (var p in fb.GetProperty("expectSetModel").EnumerateObject())
                Assert.True(DeepEq(p.Value, sm[p.Name]), sm.ToJsonString());
            Assert.Equal("sess-1", sm["sessionId"]!.GetValue<string>());
        }
        finally { File.Delete(rec); }
    }

    [Fact]
    public async Task PassthroughBare()
    {
        var bare = Case("passthrough", "bareExpect");
        var rec = Path.GetTempFileName();
        try
        {
            using (await Start(new() { ["TN_FAKE_REC"] = rec })) { }
            var recs = Records(rec);
            var sn = Params(recs, "session/new")!.AsObject();
            Assert.True(DeepEq(bare.GetProperty("mcpServers"), sn["mcpServers"]));
            Assert.Equal(bare.GetProperty("sessionNewHasMeta").GetBoolean(), sn.ContainsKey("_meta"));
            var caps = Params(recs, "initialize")!["clientCapabilities"]!.AsObject();
            Assert.Equal(bare.GetProperty("clientCapabilitiesHasMeta").GetBoolean(), caps.ContainsKey("_meta"));
            Assert.Null(Params(recs, "session/set_config_option"));
        }
        finally { File.Delete(rec); }
    }

    // ------------------------------------------------------------------ agentExit / unknownRequest

    [Fact]
    public async Task AgentExitFailsPromptly()
    {
        var cs = Case("agentExit");
        using var acp = await Start(Env(cs.GetProperty("fakeEnv")), promptTimeout: TimeSpan.FromSeconds(30));
        var sw = Stopwatch.StartNew();
        var ex = await Assert.ThrowsAnyAsync<Exception>(() => acp.PromptAsync("q"));
        Assert.Contains(cs.GetProperty("expectError").GetString()!, ex.Message);
        Assert.True(sw.Elapsed < TimeSpan.FromSeconds(10), $"took {sw.Elapsed}");
    }

    [Fact]
    public async Task UnknownRequestAnsweredMethodNotFound()
    {
        var cs = Case("unknownRequest");
        Assert.Equal(-32601, cs.GetProperty("expectCode").GetInt32()); // the fake checks this code
        using var acp = await Start(Env(cs.GetProperty("fakeEnv")));
        Assert.Equal(cs.GetProperty("expectText").GetString(), await acp.PromptAsync("q"));
    }

    // ------------------------------------------------------------------ asMcpServer

    private static async Task<Toolkit> EchoToolkit() => await Toolkit.CreateAsync(new Toolkit.Options
    {
        Builtins = false,
        SkillsDir = new List<string> { TestFixtures.Fixture("skills") },
        ExtraTools = new List<ITool>
        {
            NativeTool.Of("echo", "echo back text", new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?> { ["text"] = new Dictionary<string, object?> { ["type"] = "string" } },
            }, args => args.TryGetValue("text", out var v) ? v?.ToString() ?? "" : ""),
        },
    });

    [Fact]
    public async Task AsMcpServer()
    {
        var cs = Case("asMcpServer");
        await using var tk = await EchoToolkit();
        var srv = tk.AsMcpServer();
        using var other = tk.AsMcpServer();
        Assert.Matches(cs.GetProperty("urlPattern").GetString()!, srv.Url);
        Assert.Matches(cs.GetProperty("tokenPattern").GetString()!, srv.Token);
        if (cs.GetProperty("twoServersDistinctTokens").GetBoolean())
            Assert.NotEqual(srv.Token, other.Token);

        using var http = new HttpClient();
        HttpRequestMessage Req(HttpMethod m, string url, string? token)
        {
            var r = new HttpRequestMessage(m, url);
            if (m == HttpMethod.Post)
                r.Content = new StringContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
                    System.Text.Encoding.UTF8, "application/json");
            r.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream");
            if (token != null) r.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
            return r;
        }

        using (var r = await http.SendAsync(Req(HttpMethod.Post, srv.Url, null)))
        {
            Assert.Equal(cs.GetProperty("noTokenStatus").GetInt32(), (int)r.StatusCode);
            foreach (var h in cs.GetProperty("noTokenHeader").EnumerateObject())
                Assert.Equal(h.Value.GetString(), string.Join(",", r.Headers.GetValues(h.Name)));
        }
        using (var r = await http.SendAsync(Req(HttpMethod.Post, srv.Url, other.Token)))
            Assert.Equal(cs.GetProperty("wrongTokenStatus").GetInt32(), (int)r.StatusCode);
        using (var r = await http.SendAsync(Req(HttpMethod.Get, srv.Url, srv.Token)))
            Assert.Equal(cs.GetProperty("getWithTokenStatus").GetInt32(), (int)r.StatusCode);
        using (var r = await http.SendAsync(Req(HttpMethod.Post, srv.Url.Replace("/mcp", "/other"), srv.Token)))
            Assert.Equal(cs.GetProperty("otherPathStatus").GetInt32(), (int)r.StatusCode);

        // The port's own MCP client.
        var client = await McpClient.CreateAsync(new HttpClientTransport(new HttpClientTransportOptions
        {
            Endpoint = new Uri(srv.Url),
            TransportMode = HttpTransportMode.StreamableHttp,
            AdditionalHeaders = new Dictionary<string, string> { ["Authorization"] = "Bearer " + srv.Token },
        }));
        try
        {
            var names = (await client.ListToolsAsync()).Select(t => t.Name).ToList();
            foreach (var n in cs.GetProperty("toolsListContains").EnumerateArray())
                Assert.Contains(n.GetString()!, names);
            var call = cs.GetProperty("callEcho");
            var args = (IDictionary<string, object?>)Json.FromElement(call.GetProperty("arguments"))!;
            var res = await client.CallToolAsync(call.GetProperty("name").GetString()!,
                args.ToDictionary(kv => kv.Key, kv => kv.Value));
            Assert.Equal(call.GetProperty("expectText").GetString(), res.Content.OfType<TextContentBlock>().First().Text);
        }
        finally { await client.DisposeAsync(); }

        srv.Close();
        srv.Close(); // idempotent
        if (cs.GetProperty("afterCloseRefused").GetBoolean())
            await Assert.ThrowsAnyAsync<HttpRequestException>(() => http.SendAsync(Req(HttpMethod.Post, srv.Url, srv.Token)));
    }
}
