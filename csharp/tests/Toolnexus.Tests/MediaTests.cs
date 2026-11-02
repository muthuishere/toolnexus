using System.Net;
using System.Text;

namespace Toolnexus.Tests;

/// SPEC §7G media generation — replays conformance/media/{cases,schemas,verbs}.json (read from the
/// repo, never copied) against an in-process HTTP server, plus cancel and RunResult.Files tests.
public class MediaTests
{
    private static string ConformanceFile(string name)
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var p = Path.Combine(dir.FullName, "conformance", "media", name);
            if (File.Exists(p)) return p;
            dir = dir.Parent;
        }
        throw new FileNotFoundException("conformance/media/" + name);
    }

    private static Dictionary<string, object?> Load(string name) => Json.ToMap(File.ReadAllText(ConformanceFile(name)));

    public static IEnumerable<object[]> CaseNames()
        => ((List<object?>)Load("cases.json")["cases"]!).Cast<Dictionary<string, object?>>()
            .Select(c => new object[] { (string)c["name"]! });

    private static Dictionary<string, object?> Case(string name)
        => ((List<object?>)Load("cases.json")["cases"]!).Cast<Dictionary<string, object?>>()
            .First(c => (string)c["name"]! == name);

    private sealed record Recorded(string Method, string Path, string Body, Dictionary<string, string> Headers);

    /// Scripted fake: entry 0 answers the start, 1.. answer polls, the last entry repeats.
    private sealed class FakeServer : IDisposable
    {
        public readonly List<Recorded> Requests = new();
        private readonly StubServer _stub;
        private int _n;

        public FakeServer(List<object?> script)
        {
            _stub = new StubServer(ctx =>
            {
                string body;
                using (var r = new StreamReader(ctx.Request.InputStream, Encoding.UTF8)) body = r.ReadToEnd();
                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var k in ctx.Request.Headers.AllKeys) if (k != null) headers[k] = ctx.Request.Headers[k] ?? "";
                Dictionary<string, object?> entry;
                lock (Requests)
                {
                    Requests.Add(new Recorded(ctx.Request.HttpMethod, ctx.Request.Url!.AbsolutePath, body, headers));
                    entry = (Dictionary<string, object?>)script[Math.Min(_n++, script.Count - 1)]!;
                }
                var text = entry.Get("rawBody") as string ?? Json.Stringify(entry.Get("body"));
                StubServer.Respond(ctx, Convert.ToInt32(entry["status"]), text);
            });
        }

        public string Url => _stub.BaseUrl;
        public void Dispose() => _stub.Dispose();
    }

    private static Media Build(Dictionary<string, object?> c, FakeServer server, List<string>? progress = null)
    {
        var style = (Dictionary<string, object?>)Json.ParseLoose(Json.Stringify(c["style"]))!;
        style["baseUrl"] = ((string)style["baseUrl"]!).Replace("{server}", server.Url);
        return Media.Create(MediaStyle.FromDictionary(style),
            new MediaOptions { OnProgress = p => progress?.Add(p.Status) });
    }

    private static void SetEnv(Dictionary<string, object?> c, bool on)
    {
        if (c.Get("env") is Dictionary<string, object?> env)
            foreach (var (k, v) in env) Environment.SetEnvironmentVariable(k, on ? v?.ToString() : null);
    }

    private static void AssertJson(object? expected, object? actual)
        => Assert.True(JsonEq(expected, actual), $"expected {Json.Stringify(expected)}\nactual   {Json.Stringify(actual)}");

    private static bool JsonEq(object? a, object? b) => (a, b) switch
    {
        (null, null) => true,
        (Dictionary<string, object?> x, Dictionary<string, object?> y) =>
            x.Count == y.Count && x.All(kv => y.TryGetValue(kv.Key, out var o) && JsonEq(kv.Value, o)),
        (List<object?> x, List<object?> y) => x.Count == y.Count && x.Zip(y).All(p => JsonEq(p.First, p.Second)),
        _ => Json.Stringify(a) == Json.Stringify(b),
    };

    private static object? Norm(object? v) => Json.ParseLoose(Json.Stringify(v));

    private static void AssertRequests(Dictionary<string, object?> c, FakeServer server)
    {
        if (c.Get("expectRequests") is List<object?> exp)
        {
            Assert.Equal(exp.Count, server.Requests.Count);
            for (var i = 0; i < exp.Count; i++)
            {
                var e = (Dictionary<string, object?>)exp[i]!;
                var r = server.Requests[i];
                Assert.Equal((string)e["method"]!, r.Method);
                Assert.Equal((string)e["path"]!, r.Path);
                if (e.ContainsKey("body"))
                {
                    AssertJson(e["body"], Json.ParseLoose(r.Body));
                    Assert.StartsWith("application/json", r.Headers["Content-Type"]);
                }
                else
                {
                    Assert.Equal("", r.Body);
                    Assert.False(r.Headers.ContainsKey("Content-Type"));
                }
            }
        }
        if (c.Get("expectHeaders") is Dictionary<string, object?> hs)
        {
            Assert.NotEmpty(server.Requests);
            foreach (var r in server.Requests)
                foreach (var (k, v) in hs)
                    Assert.Equal((string)v!, r.Headers.TryGetValue(k, out var got) ? got : "");
        }
    }

    [Theory]
    [MemberData(nameof(CaseNames))]
    public async Task ConformanceCase_FunctionFace(string name)
    {
        var c = Case(name);
        SetEnv(c, true);
        try
        {
            using var server = new FakeServer((List<object?>)c["script"]!);
            var progress = new List<string>();
            var media = Build(c, server, progress);
            var args = (Dictionary<string, object?>)c["args"]!;
            if (c.ContainsKey("error"))
            {
                var ex = await Assert.ThrowsAsync<MediaException>(() => media.GenerateAsync((string)c["verb"]!, args));
                Assert.Equal((string)c["error"]!, ex.Message);
            }
            else
            {
                var r = await media.GenerateAsync((string)c["verb"]!, args);
                var actual = new Dictionary<string, object?>
                {
                    ["files"] = r.Files.Select(f => (object?)new Dictionary<string, object?>
                        { ["type"] = f.Type, ["mimeType"] = f.MimeType, ["url"] = f.Url }).ToList(),
                    ["status"] = r.Status,
                };
                if (r.Text != null) actual["text"] = r.Text;
                if (r.JobId != null) actual["jobId"] = r.JobId;
                AssertJson(c["result"], actual);
            }
            AssertRequests(c, server);
            if (c.Get("expectProgress") is List<object?> ep)
                Assert.Equal(ep.Select(x => (string)x!).ToList(), progress);
        }
        finally { SetEnv(c, false); }
    }

    [Theory]
    [MemberData(nameof(CaseNames))]
    public async Task ConformanceCase_ToolFace(string name)
    {
        var c = Case(name);
        if (c.Get("tool") is not Dictionary<string, object?> exp) return; // unknown_verb has no tool face
        SetEnv(c, true);
        try
        {
            using var server = new FakeServer((List<object?>)c["script"]!);
            var media = Build(c, server);
            var tool = media.Tools().Single(t => t.Name == (string)exp["name"]!);
            Assert.Equal("media", tool.Source);
            var r = await tool.ExecuteAsync((Dictionary<string, object?>)Norm(c["args"])!);
            Assert.Equal((bool)exp["isError"]!, r.IsError);
            Assert.Equal((string)exp["output"]!, r.Output);
            if (exp.ContainsKey("parts")) AssertJson(exp["parts"], Norm(r.Parts!.Select(x => new Dictionary<string, object?> { ["type"] = x.Type, ["mimeType"] = x.MimeType, ["url"] = x.Url }).ToList()));
            else Assert.Null(r.Parts);
            if (exp.ContainsKey("metadata")) AssertJson(exp["metadata"], Norm(r.Metadata));
            AssertRequests(c, server);
        }
        finally { SetEnv(c, false); }
    }

    [Fact]
    public void Schemas_MatchExactlyIncludingOrder()
    {
        var doc = Load("schemas.json");
        var media = Media.Create(MediaStyle.FromDictionary((Dictionary<string, object?>)doc["style"]!));
        var tools = media.Tools();
        var exp = (List<object?>)doc["tools"]!;
        Assert.Equal(exp.Count, tools.Count);
        for (var i = 0; i < exp.Count; i++)
        {
            var e = (Dictionary<string, object?>)exp[i]!;
            Assert.Equal((string)e["name"]!, tools[i].Name);
            Assert.Equal((string)e["description"]!, tools[i].Description);
            Assert.Equal((string)e["source"]!, tools[i].Source);
            var actual = Norm(tools[i].InputSchema);
            AssertJson(e["inputSchema"], actual);
            // property order is canonical order
            var ep = ((Dictionary<string, object?>)((Dictionary<string, object?>)e["inputSchema"]!)["properties"]!).Keys;
            var ap = ((Dictionary<string, object?>)((Dictionary<string, object?>)actual!)["properties"]!).Keys;
            Assert.Equal(ep.ToList(), ap.ToList());
        }
    }

    [Fact]
    public void EmbeddedVerbTable_IsVerbatim()
        => AssertJson(Load("verbs.json")["verbs"], Json.ParseLoose(MediaVerbs.VerbsJson));

    private const string AsyncStyle = """
        { "baseUrl": "{server}", "verbs": { "image": { "mode": "async", "kind": "image",
          "start": { "path": "/s" }, "args": { "prompt": "p" }, "idPath": "id",
          "poll": { "path": "/p/{id}", "statusPath": "st", "done": ["ok"], "failed": ["bad"], "intervalMs": 5, "timeoutMs": 60000 },
          "resultPath": "u" } } }
        """;

    [Fact]
    public async Task Cancel_StopsPolling_FunctionFaceThrowsToolFaceReturnsError()
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"id":"j"}},{"status":200,"body":{"st":"running"}}]""")!;
        using var server = new FakeServer(script);
        using var cts = new CancellationTokenSource();
        var media = Media.Create(MediaStyle.Parse(AsyncStyle.Replace("{server}", server.Url)),
            new MediaOptions { OnProgress = _ => cts.Cancel() });
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => media.ImageAsync(new ImageInput("x"), cts.Token));
        var polls = server.Requests.Count;
        await Task.Delay(50);
        Assert.Equal(polls, server.Requests.Count);

        using var cts2 = new CancellationTokenSource();
        cts2.Cancel();
        var r = await media.Tools()[0].ExecuteAsync(new Dictionary<string, object?> { ["prompt"] = "x" },
            new ToolContext(cancellationToken: cts2.Token));
        Assert.True(r.IsError);
        Assert.NotEmpty(r.Output);
    }

    [Fact]
    public async Task TypedVerbs_ProviderOptionsOverwriteBodyLast()
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"u":"https://x/a.mp3"}}]""")!;
        using var server = new FakeServer(script);
        var media = Media.Create(MediaStyle.Parse($$"""
            { "baseUrl": "{{server.Url}}", "verbs": { "speech": { "mode": "sync", "kind": "audio",
              "start": { "path": "/t" }, "defaults": { "model": "a" }, "args": { "text": "t", "voice": "v" }, "resultPath": "u" } } }
            """));
        var r = await media.SpeechAsync(new SpeechInput("hi")
        {
            ProviderOptions = new Dictionary<string, object?> { ["model"] = "b", ["t"] = "over" },
        });
        Assert.Equal("audio/mpeg", r.Files[0].MimeType);
        AssertJson(Json.ParseLoose("""{"model":"b","t":"over"}"""), Json.ParseLoose(server.Requests[0].Body));
    }

    [Fact]
    public async Task Toolkit_MediaOption_RegistersTools_AndRunResultCarriesFiles()
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"u":["https://x/a.png","https://x/b.webp"]}}]""")!;
        using var server = new FakeServer(script);
        var media = Media.Create(MediaStyle.Parse($$"""
            { "baseUrl": "{{server.Url}}", "verbs": { "image": { "mode": "sync", "kind": "image",
              "start": { "path": "/i" }, "args": { "prompt": "p" }, "resultPath": "u" } } }
            """));
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, Media = media });
        Assert.Equal("media", tk.Get("generate_image")!.Source);

        var turn = 0;
        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "fake",
            Generate = _ => turn++ == 0
                ? InProcess.Response.FromToolCalls(new InProcess.ToolCall
                    { Id = "call_7", Name = "generate_image", Arguments = new Dictionary<string, object?> { ["prompt"] = "cat" } })
                : InProcess.Response.FromContent("done"),
        });
        var r = await client.RunAsync("draw", tk);
        Assert.Equal("done", r.Text);
        Assert.Equal(2, r.Files.Count);
        Assert.Equal(new LlmClient.RunFile("image", "image/png", "https://x/a.png", null, "generate_image", "call_7"), r.Files[0]);
        Assert.Equal("image/webp", r.Files[1].MimeType);
        Assert.Equal(2, r.ToolCalls[0].Parts!.Count);
        Assert.Equal("call_7", r.ToolCalls[0].CallId);
    }

    [Fact]
    public async Task Toolkit_MediaToolBeatsSameNamedExtraTool()
    {
        var media = Media.Create(MediaStyle.Parse("""
            { "baseUrl": "http://unused", "verbs": { "image": { "mode": "sync", "kind": "image",
              "start": { "path": "/i" }, "args": { "prompt": "p" }, "resultPath": "u" } } }
            """));
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            Builtins = false, Media = media,
            ExtraTools = new List<ITool> { NativeTool.Of("generate_image", "Host.", NativeTool.EmptySchema(), _ => "host") },
        });
        Assert.Equal("media", tk.Get("generate_image")!.Source);
        Assert.Single(tk.Tools(), t => t.Name == "generate_image");
    }

    [Fact]
    public async Task RunWithoutMedia_FilesEmpty_PartsNull()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        tk.Register(NativeTool.Of("echo", "Echo.", NativeTool.EmptySchema(), _ => "hi"));
        var turn = 0;
        var client = InProcess.CreateClient(new InProcess.Options
        {
            Model = "fake",
            Generate = _ => turn++ == 0
                ? InProcess.Response.FromToolCalls(new InProcess.ToolCall { Id = "c1", Name = "echo", Arguments = "{}" })
                : InProcess.Response.FromContent("ok"),
        });
        var r = await client.RunAsync("x", tk);
        Assert.NotNull(r.Files);
        Assert.Empty(r.Files);
        Assert.Null(r.ToolCalls[0].Parts);
    }

    [Fact]
    public async Task Streaming_RunResultCarriesFiles()
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"u":"https://x/s.wav"}}]""")!;
        using var mediaServer = new FakeServer(script);
        var media = Media.Create(MediaStyle.Parse($$"""
            { "baseUrl": "{{mediaServer.Url}}", "verbs": { "speech": { "mode": "sync", "kind": "audio",
              "start": { "path": "/t" }, "args": { "text": "t" }, "resultPath": "u" } } }
            """));
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, Media = media });
        var n = 0;
        using var llm = new StubServer(ctx =>
        {
            using (var rd = new StreamReader(ctx.Request.InputStream)) rd.ReadToEnd();
            var sb = new StringBuilder();
            if (Interlocked.Increment(ref n) == 1)
                sb.Append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"s1\",\"function\":{\"name\":\"generate_speech\",\"arguments\":\"{\\\"text\\\":\\\"hi\\\"}\"}}]}}]}\n\n");
            else
                sb.Append("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n");
            sb.Append("data: [DONE]\n\n");
            StubServer.Respond(ctx, 200, sb.ToString());
        });
        var client = LlmClient.Create(new LlmClient.Options { BaseUrl = llm.BaseUrl, ApiKey = "YOUR_KEY_HERE", Model = "m", Retries = 0 });
        var r = await client.StreamAsync("speak", tk, _ => { });
        Assert.Equal("ok", r.Text);
        var f = Assert.Single(r.Files);
        Assert.Equal(new LlmClient.RunFile("audio", "audio/wav", "https://x/s.wav", null, "generate_speech", "s1"), f);
    }
}
