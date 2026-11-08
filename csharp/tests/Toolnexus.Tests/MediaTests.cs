using System.Net;
using System.Text;
using System.Text.RegularExpressions;

namespace Toolnexus.Tests;

/// SPEC §7G media — replays every conformance/media/*.json case (read from the repo, never copied)
/// hermetically: an in-process HTTP server for the API files, the POSIX fake CLI for cli.json, and
/// test-only custom clients for routing.json. Plus cancel, toolkit, RunResult.Files and model
/// visibility tests.
public class MediaTests
{
    // ================================================================ shared harness

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

    private static string RepoFile(string rel) => Path.GetFullPath(Path.Combine(Path.GetDirectoryName(ConformanceFile("verbs.json"))!, "..", "..", rel));

    private static Dictionary<string, object?> Load(string name) => Json.ToMap(File.ReadAllText(ConformanceFile(name)));

    private static List<Dictionary<string, object?>> Cases(string file, string key = "cases")
        => ((List<object?>)Load(file)[key]!).Cast<Dictionary<string, object?>>().ToList();

    private static IEnumerable<object[]> Names(string file) => Cases(file).Select(c => new object[] { (string)c["name"]! });

    public static IEnumerable<object[]> StyleCaseNames() => Names("cases.json");
    public static IEnumerable<object[]> ClientCaseNames() => Names("clients.json");
    public static IEnumerable<object[]> CliCaseNames() => Names("cli.json");
    public static IEnumerable<object[]> RoutingCaseNames() => Names("routing.json");

    private static Dictionary<string, object?> Case(string file, string name) => Cases(file).First(c => (string)c["name"]! == name);

    [Fact]
    public void CaseCounts_AreExact()
    {
        Assert.Equal(49, Cases("cases.json").Count);
        Assert.Equal(76, Cases("clients.json").Count);
        Assert.Equal(35, Cases("cli.json").Count);
        Assert.Equal(7, Cases("cli.json", "availabilityCases").Count);
        Assert.Equal(8, Cases("cli.json", "verbCases").Count);
        Assert.Equal(19, Cases("routing.json").Count);
        Assert.Single(Cases("routing.json", "schemaCases"));
    }

    [Fact]
    public void EmbeddedVerbTable_IsVerbatim()
        => Assert.Equal(File.ReadAllText(ConformanceFile("verbs.json")), MediaVerbs.VerbsJson);

    [Fact]
    public void EmbeddedA2EPreset_IsVerbatim()
        => Assert.Equal(File.ReadAllText(RepoFile("examples/media-styles/a2e.json")), A2EMediaClient.PresetJson);

    /// Replace every placeholder in every string of a JSON value.
    private static object? Subst(object? v, Dictionary<string, string> map) => v switch
    {
        string s => map.Aggregate(s, (acc, kv) => acc.Replace(kv.Key, kv.Value)),
        Dictionary<string, object?> d => d.ToDictionary(kv => kv.Key, kv => Subst(kv.Value, map)),
        List<object?> l => l.Select(x => Subst(x, map)).ToList(),
        _ => v,
    };

    private static void AssertJson(object? expected, object? actual, string what = "")
        => Assert.True(JsonEq(expected, actual), $"{what}\nexpected {Json.Stringify(expected)}\nactual   {Json.Stringify(actual)}");

    private static bool JsonEq(object? a, object? b) => (a, b) switch
    {
        (null, null) => true,
        (Dictionary<string, object?> x, Dictionary<string, object?> y) =>
            x.Count == y.Count && x.All(kv => y.TryGetValue(kv.Key, out var o) && JsonEq(kv.Value, o)),
        (List<object?> x, List<object?> y) => x.Count == y.Count && x.Zip(y).All(p => JsonEq(p.First, p.Second)),
        _ => Json.Stringify(a) == Json.Stringify(b),
    };

    private static object? Norm(object? v) => Json.ParseLoose(Json.Stringify(v));

    private sealed class EnvScope : IDisposable
    {
        private readonly Dictionary<string, string?> _old = new();

        public EnvScope(Dictionary<string, object?> c, Dictionary<string, string> map)
        {
            if (c.Get("env") is Dictionary<string, object?> env)
                foreach (var (k, v) in env) Set(k, (string?)Subst(v, map));
            if (c.Get("unsetEnv") is List<object?> unset)
                foreach (var k in unset) Set((string)k!, null);
        }

        private void Set(string k, string? v)
        {
            if (!_old.ContainsKey(k)) _old[k] = Environment.GetEnvironmentVariable(k);
            Environment.SetEnvironmentVariable(k, v);
        }

        public void Dispose()
        {
            foreach (var (k, v) in _old) Environment.SetEnvironmentVariable(k, v);
        }
    }

    private static string TempDir()
    {
        var d = Path.Combine(Path.GetTempPath(), "tn-media-" + Guid.NewGuid().ToString("N")[..12]);
        Directory.CreateDirectory(d);
        return d;
    }

    // ---------------------------------------------------------------- fake HTTP server

    private sealed record Recorded(string Method, string Path, byte[] Body, Dictionary<string, string> Headers)
    {
        public string Text => Encoding.UTF8.GetString(Body);
    }

    /// Scripted fake: serves script[] in order, the last entry repeats; records every request.
    private sealed class FakeServer : IDisposable
    {
        public readonly List<Recorded> Requests = new();
        private readonly StubServer _stub;
        private int _n;

        public FakeServer(List<object?> script)
        {
            _stub = new StubServer(ctx =>
            {
                using var ms = new MemoryStream();
                ctx.Request.InputStream.CopyTo(ms);
                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var k in ctx.Request.Headers.AllKeys) if (k != null) headers[k] = ctx.Request.Headers[k] ?? "";
                Dictionary<string, object?> entry;
                lock (Requests)
                {
                    Requests.Add(new Recorded(ctx.Request.HttpMethod, ctx.Request.RawUrl!, ms.ToArray(), headers));
                    entry = (Dictionary<string, object?>)Subst(script[Math.Min(_n++, script.Count - 1)], new() { ["{server}"] = Url })!;
                }
                byte[] bytes;
                if (entry.Get("bodyBase64") is string b64) bytes = Convert.FromBase64String(b64);
                else if (entry.Get("rawBody") is string raw) bytes = Encoding.UTF8.GetBytes(raw);
                else
                {
                    bytes = Encoding.UTF8.GetBytes(Json.Stringify(entry.Get("body")));
                    ctx.Response.ContentType = "application/json";
                }
                if (entry.Get("headers") is Dictionary<string, object?> rh)
                    foreach (var (hk, hv) in rh)
                    {
                        if (string.Equals(hk, "Content-Type", StringComparison.OrdinalIgnoreCase)) ctx.Response.ContentType = hv?.ToString();
                        else ctx.Response.Headers[hk] = hv?.ToString() ?? "";
                    }
                ctx.Response.StatusCode = Convert.ToInt32(entry["status"]);
                ctx.Response.ContentLength64 = bytes.Length;
                ctx.Response.OutputStream.Write(bytes);
                ctx.Response.Close();
            });
        }

        public string Url => _stub.BaseUrl;
        public void Dispose() => _stub.Dispose();
    }

    /// Parsed multipart/form-data parts, in order.
    private static List<object?> ParseMultipart(Recorded r)
    {
        var ct = r.Headers["Content-Type"];
        var boundary = Regex.Match(ct, "boundary=\"?([^\";]+)\"?").Groups[1].Value;
        var latin = Encoding.Latin1.GetString(r.Body);
        var parts = new List<object?>();
        foreach (var chunk in latin.Split("--" + boundary).Skip(1))
        {
            if (chunk.StartsWith("--")) break;
            var body = chunk.TrimStart('\r', '\n');
            var split = body.IndexOf("\r\n\r\n", StringComparison.Ordinal);
            var head = body[..split];
            var content = body[(split + 4)..];
            if (content.EndsWith("\r\n")) content = content[..^2];
            var name = Regex.Match(head, "name=\"?([^\";\r\n]+)\"?").Groups[1].Value;
            var fn = Regex.Match(head, "filename=\"?([^\";\r\n]+)\"?");
            var bytes = Encoding.Latin1.GetBytes(content);
            var p = new Dictionary<string, object?> { ["name"] = name };
            if (fn.Success)
            {
                p["filename"] = fn.Groups[1].Value;
                p["contentType"] = Regex.Match(head, "Content-Type: ([^\r\n]+)", RegexOptions.IgnoreCase).Groups[1].Value;
                p["bytesBase64"] = Convert.ToBase64String(bytes);
            }
            else p["value"] = Encoding.UTF8.GetString(bytes);
            parts.Add(p);
        }
        return parts;
    }

    private static void AssertRequests(Dictionary<string, object?> c, FakeServer server, bool strictBody)
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
                    AssertJson(e["body"], Json.ParseLoose(r.Text), $"request {i} body");
                    Assert.StartsWith("application/json", r.Headers["Content-Type"]);
                }
                else if (e.Get("multipart") is List<object?> mp)
                {
                    var got = ParseMultipart(r);
                    Assert.Equal(mp.Count, got.Count);
                    for (var j = 0; j < mp.Count; j++)
                        foreach (var (k, v) in (Dictionary<string, object?>)mp[j]!)
                            Assert.Equal(v?.ToString(), ((Dictionary<string, object?>)got[j]!).Get(k)?.ToString());
                }
                else if (strictBody || r.Method == "GET")
                {
                    Assert.Empty(r.Body);
                    Assert.False(r.Headers.ContainsKey("Content-Type"), $"request {i} has a Content-Type");
                }
                if (e.Get("headers") is Dictionary<string, object?> hs)
                    foreach (var (k, v) in hs) Assert.Equal((string)v!, r.Headers.TryGetValue(k, out var got) ? got : null);
                if (e.Get("absentHeaders") is List<object?> absent)
                    foreach (var k in absent) Assert.False(r.Headers.ContainsKey((string)k!), $"request {i} sent {k}");
            }
        }
        if (c.Get("expectHeaders") is Dictionary<string, object?> eh)
        {
            Assert.NotEmpty(server.Requests);
            foreach (var r in server.Requests)
                foreach (var (k, v) in eh)
                    Assert.Equal((string)v!, r.Headers.TryGetValue(k, out var got) ? got : "");
        }
        if (c.Get("expectHeaderPatterns") is Dictionary<string, object?> hp) // §7G.12 D23
        {
            Assert.NotEmpty(server.Requests);
            foreach (var r in server.Requests)
                foreach (var (k, v) in hp)
                    Assert.Matches((string)v!, r.Headers.TryGetValue(k, out var got) ? got : "");
        }
    }

    /// §7G.12 D20/D21: runFilePath and noUsage flags, checked on the function-face result.
    private static void AssertRunFilesAndUsage(Dictionary<string, object?> c, MediaResult r)
    {
        if (c.Get("noUsage") is true) Assert.Null(r.Usage);
        if (c.Get("runFilePath") is not bool want) return;
        var tr = ToolResult.OkWithParts("x", r.Files.Select(f => f.ToPart()).ToList(), new Dictionary<string, object?> { ["source"] = "media" });
        var files = LlmClient.RunFilesOf(new[] { new LlmClient.ToolCall("generate_x", new Dictionary<string, object?>(), tr.Output, false, tr.Metadata, tr.Parts, "c1") });
        Assert.Equal(r.Files.Count, files.Count);
        for (var i = 0; i < files.Count; i++)
        {
            if (want)
            {
                Assert.NotNull(files[i].Path);
                Assert.True(Path.IsPathRooted(files[i].Path));
                Assert.Equal(r.Files[i].Path, files[i].Path);
            }
            else Assert.Null(files[i].Path);
            if (r.Files[i].Path is { } fp) Assert.DoesNotContain(fp, Json.Stringify(Norm(PartsJson(tr.Parts!))));
        }
    }

    // ---------------------------------------------------------------- result / error / tool asserts

    private static void AssertResult(Dictionary<string, object?> expected, MediaResult r, string? dir, string client, string verb)
    {
        var files = (List<object?>)expected["files"]!;
        Assert.Equal(files.Count, r.Files.Count);
        for (var i = 0; i < files.Count; i++)
        {
            var e = (Dictionary<string, object?>)files[i]!;
            var f = r.Files[i];
            Assert.Equal((string)e["type"]!, f.Type);
            Assert.Equal((string)e["mimeType"]!, f.MimeType);
            if (e.Get("saved") is Dictionary<string, object?> saved)
            {
                Assert.NotNull(f.Path);
                Assert.True(Path.IsPathRooted(f.Path));
                Assert.Equal(dir, Path.GetDirectoryName(f.Path));
                Assert.Matches($"^{Regex.Escape(client)}-{Regex.Escape(verb)}-[0-9]+-[0-9]+{Regex.Escape((string)saved["ext"]!)}$", Path.GetFileName(f.Path!));
                Assert.Equal("file://" + f.Path, f.Url);
                Assert.Null(f.Data);
                Assert.Equal((string)saved["bytesBase64"]!, Convert.ToBase64String(File.ReadAllBytes(f.Path!)));
            }
            else
            {
                Assert.Equal(e.Get("url") as string, f.Url);
                Assert.Equal(e.Get("data") as string, f.Data);
                Assert.Equal(e.Get("path") as string, f.Path);
            }
        }
        Assert.Equal((string)expected["status"]!, r.Status);
        if (expected.ContainsKey("usage")) AssertJson(expected["usage"], Norm(r.Usage?.ToMap()), "usage");
        else Assert.Null(r.Usage);
        Assert.Equal(expected.Get("text") as string, r.Text);
        Assert.Equal(expected.Get("jobId") as string, r.JobId);
        // §7G.10b style/model: asserted only when the case names them
        if (expected.ContainsKey("style")) Assert.Equal(expected["style"] as string, r.Style);
        if (expected.ContainsKey("model")) Assert.Equal(expected["model"] as string, r.Model);
    }

    private static void AssertError(Dictionary<string, object?> c, Exception ex)
    {
        var me = Assert.IsAssignableFrom<MediaException>(ex);
        if (c.Get("error") is string err) Assert.Equal(err, me.Message);
        if (c.Get("errorPrefix") is string pre) Assert.StartsWith(pre, me.Message);
        if (c.Get("status") is string st)
        {
            Assert.Equal(st, me.Status);
            Assert.Equal(st == "unavailable", me is MediaUnavailableException);
        }
    }

    private static List<object?> PartsJson(IEnumerable<ContentPart> parts) => parts.Select(p =>
    {
        var d = new Dictionary<string, object?> { ["type"] = p.Type, ["mimeType"] = p.MimeType };
        if (p.Url != null) d["url"] = p.Url;
        if (p.Data != null) d["data"] = p.Data;
        return (object?)d;
    }).ToList();

    private static void AssertTool(Dictionary<string, object?> exp, ToolResult r, string? dir)
    {
        Assert.Equal((bool)exp["isError"]!, r.IsError);
        if (exp.Get("output") is string o) Assert.Equal(o, r.Output);
        if (exp.Get("outputPrefix") is string op) Assert.StartsWith(op, r.Output);
        if (exp.Get("outputPattern") is string pat)
            Assert.Matches(pat.Replace("{dir}", Regex.Escape(dir ?? "")), r.Output);
        if (exp.ContainsKey("parts")) AssertJson(exp["parts"], Norm(PartsJson(r.Parts!)), "tool parts");
        else if (exp.Get("noParts") is true || r.IsError) Assert.Null(r.Parts);
        if (exp.ContainsKey("metadata")) AssertJson(exp["metadata"], Norm(r.Metadata), "tool metadata");
        if (exp.Get("noUsage") is true) Assert.False(r.Metadata?.ContainsKey("usage") ?? false);
        if (exp.ContainsKey("modelParts"))
            AssertJson(exp["modelParts"], Norm(PartsJson(Media.ModelParts(r) ?? Array.Empty<ContentPart>())), "modelParts");
    }

    private static string ToolName(string verb) => MediaVerbs.All.First(v => v.Verb == verb).Tool;

    private static MediaClientConfig Config(Dictionary<string, object?>? cfg, List<string> progress)
    {
        var c = new MediaClientConfig { Loop = new MediaLoop { OnProgress = p => { lock (progress) progress.Add(p.Status); } } };
        if (cfg == null) return c;
        c.Model = cfg.Get("model") as string;
        c.Key = cfg.Get("key") as string;
        c.Url = cfg.Get("url") as string;
        if (cfg.Get("models") is Dictionary<string, object?> ms) c.Models = ms.ToDictionary(kv => kv.Key, kv => (string)kv.Value!);
        if (cfg.Get("command") is List<object?> cmd) c.Command = cmd.Select(x => (string)x!).ToList();
        if (cfg.Get("loop") is Dictionary<string, object?> lp)
        {
            c.Loop.IntervalMs = lp.Get("intervalMs") is long iv ? iv : null;
            c.Loop.TimeoutMs = lp.Get("timeoutMs") is long tv ? tv : null;
        }
        if (cfg.Get("gather") is Dictionary<string, object?> g)
            c.Gather = new MediaGather
            {
                Name = g.Get("name") as string, Mode = g.Get("mode") as string, Dir = g.Get("dir") as string,
                MaxBytes = g.Get("maxBytes") as long?, Keep = g.Get("keep") as bool?,
            };
        return c;
    }

    // ================================================================ cases.json — style engine

    private static StyleMediaClient BuildStyle(Dictionary<string, object?> c, Dictionary<string, string> map, List<string> progress)
        => new(MediaStyle.FromDictionary((Dictionary<string, object?>)Subst(c["style"], map)!),
            Config((Dictionary<string, object?>?)Subst(c.Get("config"), map), progress));

    [Theory]
    [MemberData(nameof(StyleCaseNames))]
    public async Task Style_FunctionFace(string name)
    {
        var c = Case("cases.json", name);
        using var server = new FakeServer((List<object?>)c["script"]!);
        var map = new Dictionary<string, string> { ["{server}"] = server.Url };
        using var env = new EnvScope(c, map);
        var progress = new List<string>();
        if (c.Get("styleError") is true)
        {
            var se = Assert.Throws<MediaException>(() => BuildStyle(c, map, progress));
            Assert.Equal((string)c["error"]!, se.Message);
            Assert.Empty(server.Requests);
            return;
        }
        var media = Media.Create(BuildStyle(c, map, progress));
        var args = (Dictionary<string, object?>)Subst(c["args"], map)!;
        if (c.ContainsKey("error"))
            AssertError(c, await Record.ExceptionAsync(() => media.GenerateAsync((string)c["verb"]!, args)));
        else
            AssertResult((Dictionary<string, object?>)Subst(c["result"], map)!, await media.GenerateAsync((string)c["verb"]!, args), null, "style", (string)c["verb"]!);
        AssertRequests(c, server, strictBody: true);
        if (c.Get("expectProgress") is List<object?> ep) Assert.Equal(ep.Select(x => (string)x!).ToList(), progress);
    }

    [Theory]
    [MemberData(nameof(StyleCaseNames))]
    public async Task Style_ToolFace(string name)
    {
        var c = Case("cases.json", name);
        if (c.Get("tool") is not Dictionary<string, object?> exp) return; // styleError / unknown_verb: no tool face
        using var server = new FakeServer((List<object?>)c["script"]!);
        var map = new Dictionary<string, string> { ["{server}"] = server.Url };
        using var env = new EnvScope(c, map);
        var media = Media.Create(BuildStyle(c, map, new List<string>()));
        var tool = media.Tools().Single(t => t.Name == (string)exp["name"]!);
        Assert.Equal("media", tool.Source);
        var r = await tool.ExecuteAsync((Dictionary<string, object?>)Norm(Subst(c["args"], map))!);
        AssertTool((Dictionary<string, object?>)Subst(exp, map)!, r, null);
        AssertRequests(c, server, strictBody: true);
    }

    [Fact]
    public void Schemas_MatchExactlyIncludingOrder()
    {
        var doc = Load("schemas.json");
        var media = Media.Create(new StyleMediaClient(MediaStyle.FromDictionary((Dictionary<string, object?>)doc["style"]!)));
        AssertTools((List<object?>)doc["tools"]!, media.Tools());
    }

    private static void AssertTools(List<object?> exp, IReadOnlyList<ITool> tools)
    {
        Assert.Equal(exp.Count, tools.Count);
        for (var i = 0; i < exp.Count; i++)
        {
            var e = (Dictionary<string, object?>)exp[i]!;
            Assert.Equal((string)e["name"]!, tools[i].Name);
            Assert.Equal((string)e["description"]!, tools[i].Description);
            Assert.Equal((string)e["source"]!, tools[i].Source);
            var actual = Norm(tools[i].InputSchema);
            AssertJson(e["inputSchema"], actual, tools[i].Name);
            var ep = ((Dictionary<string, object?>)((Dictionary<string, object?>)e["inputSchema"]!)["properties"]!).Keys;
            var ap = ((Dictionary<string, object?>)((Dictionary<string, object?>)actual!)["properties"]!).Keys;
            Assert.Equal(ep.ToList(), ap.ToList());
        }
    }

    // ================================================================ clients.json — built-in API clients

    private static IMediaClient BuiltIn(string name, MediaClientConfig cfg) => name switch
    {
        "a2e" => new A2EMediaClient(cfg),
        "elevenlabs" => new ElevenLabsMediaClient(cfg),
        "openai" => new OpenAIMediaClient(cfg),
        "openrouter" => new OpenRouterMediaClient(cfg),
        "fal" => new FalMediaClient(cfg),
        "grok" => new GrokMediaClient(cfg),
        "codex" => new CodexMediaClient(cfg),
        _ => throw new ArgumentException(name),
    };

    private static async Task RunClientCase(Dictionary<string, object?> c, bool toolFace)
    {
        using var server = new FakeServer((List<object?>)c["script"]!);
        var dir = TempDir();
        var inputs = TempDir();
        try
        {
            var map = new Dictionary<string, string> { ["{server}"] = server.Url, ["{dir}"] = dir, ["{inputs}"] = inputs };
            if (c.Get("inputFiles") is Dictionary<string, object?> inf)
                foreach (var (n, b64) in inf) File.WriteAllBytes(Path.Combine(inputs, n), Convert.FromBase64String((string)b64!));
            using var env = new EnvScope(c, map);
            var progress = new List<string>();
            var client = (string)c["client"]!;
            var verb = (string)c["verb"]!;
            var media = Media.Create(BuiltIn(client, Config((Dictionary<string, object?>?)Subst(c.Get("config"), map), progress)));
            var args = (Dictionary<string, object?>)Subst(c["args"], map)!;
            var cs = (Dictionary<string, object?>)Subst(c, map)!;
            var sw = System.Diagnostics.Stopwatch.StartNew();
            if (!toolFace)
            {
                if (c.ContainsKey("error") || c.ContainsKey("errorPrefix"))
                    AssertError(cs, await Record.ExceptionAsync(() => media.GenerateAsync(verb, args, CtxFor(c))));
                else
                {
                    var res = await media.GenerateAsync(verb, args);
                    AssertResult((Dictionary<string, object?>)cs["result"]!, res, dir, client, verb);
                    AssertRunFilesAndUsage(c, res);
                }
                if (c.Get("expectProgress") is List<object?> ep) Assert.Equal(ep.Select(x => (string)x!).ToList(), progress);
            }
            else
            {
                if (cs.Get("tool") is not Dictionary<string, object?> exp || c.ContainsKey("ctxTimeoutMs")) return;
                var tool = media.Tools().Single(t => t.Name == (exp.Get("name") as string ?? ToolName(verb)));
                var tr = await tool.ExecuteAsync((Dictionary<string, object?>)Norm(args)!);
                AssertTool(exp, tr, dir);
                if (c.Get("noUsage") is true) Assert.False(tr.Metadata?.ContainsKey("usage") ?? false);
            }
            if (c.Get("maxElapsedMs") is long max) Assert.True(sw.ElapsedMilliseconds < max, $"took {sw.ElapsedMilliseconds}ms");
            AssertRequests(cs, server, strictBody: false);
        }
        finally
        {
            Directory.Delete(dir, true);
            Directory.Delete(inputs, true);
        }
    }

    [Theory]
    [MemberData(nameof(ClientCaseNames))]
    public Task Client_FunctionFace(string name) => RunClientCase(Case("clients.json", name), toolFace: false);

    [Theory]
    [MemberData(nameof(ClientCaseNames))]
    public Task Client_ToolFace(string name) => RunClientCase(Case("clients.json", name), toolFace: true);

    // ================================================================ cli.json — CLI clients over the fake CLI

    /// <summary>ctxTimeoutMs (D12): the function face runs with ctx.deadline = now + ctxTimeoutMs.</summary>
    private static MediaContext? CtxFor(Dictionary<string, object?> c)
        => c.Get("ctxTimeoutMs") is long ms ? new MediaContext { Deadline = DateTimeOffset.UtcNow.AddMilliseconds(ms) } : null;

    private static async Task RunCliCase(Dictionary<string, object?> c, bool toolFace)
    {
        var root = TempDir();
        var outDir = Path.Combine(root, "out");
        var dir = Path.Combine(root, "dir");
        Directory.CreateDirectory(outDir);
        Directory.CreateDirectory(dir);
        try
        {
            var fixture = Path.Combine(root, "fixture.jsonl");
            File.WriteAllText(fixture, string.Join("\n", ((List<object?>)c["fixture"]!).Select(x => (string)x!)) + "\n");
            var map = new Dictionary<string, string> { ["{out}"] = outDir, ["{dir}"] = dir };
            var cs = (Dictionary<string, object?>)Subst(c, map)!;
            using var env = new EnvScope(c, map);
            var progress = new List<string>();
            var cfg = Config((Dictionary<string, object?>?)cs.Get("config"), progress);
            cfg.Command = cs.Get("command") is List<object?> cmd
                ? cmd.Select(x => (string)x!).ToList()
                : new List<string> { "/bin/sh", ConformanceFile("fake-cli/fake-cli.sh"), fixture, outDir };
            var style = (string)c["style"]!;
            var verb = (string)c["verb"]!;
            var loaded = MediaStyle.Load(style, cfg);
            var client = loaded.Name;
            var media = Media.Create(loaded);
            var args = (Dictionary<string, object?>)cs["args"]!;
            var sw = System.Diagnostics.Stopwatch.StartNew();
            if (!toolFace)
            {
                if (c.ContainsKey("error") || c.ContainsKey("errorPrefix"))
                    AssertError(cs, await Record.ExceptionAsync(() => media.GenerateAsync(verb, args, CtxFor(c))));
                else
                {
                    var res = await media.GenerateAsync(verb, args);
                    AssertResult((Dictionary<string, object?>)cs["result"]!, res, dir, client, verb);
                    AssertRunFilesAndUsage(c, res);
                    if (c.Get("oneShot") is true)
                        AssertResult((Dictionary<string, object?>)cs["result"]!, await Media.GenerateMediaAsync(style, verb, args, cfg), dir, client, verb);
                }
                if (c.Get("expectProgress") is List<object?> ep) Assert.Equal(ep.Select(x => (string)x!).ToList(), progress);
            }
            else
            {
                if (cs.Get("tool") is not Dictionary<string, object?> exp || c.ContainsKey("ctxTimeoutMs")) return;
                var tool = media.Tools().Single(t => t.Name == (exp.Get("name") as string ?? ToolName(verb)));
                var tr = await tool.ExecuteAsync((Dictionary<string, object?>)Norm(args)!);
                AssertTool(exp, tr, dir);
                if (c.Get("noUsage") is true) Assert.False(tr.Metadata?.ContainsKey("usage") ?? false);
            }
            if (c.Get("maxElapsedMs") is long max) Assert.True(sw.ElapsedMilliseconds < max, $"took {sw.ElapsedMilliseconds}ms");
            var argvFile = Path.Combine(outDir, "argv.txt");
            if (c.Get("expectNoSpawn") is true) Assert.False(File.Exists(argvFile));
            if (cs.Get("expectArgv") is List<object?> argv)
            {
                var got = File.ReadAllText(argvFile).Split('\n')[..^1];
                var cwd = File.ReadAllText(Path.Combine(outDir, "cwd.txt")).TrimEnd('\n');
                Assert.Equal(argv.Count, got.Length);
                for (var i = 0; i < argv.Count; i++)
                    if ((string)argv[i]! == "{cwd}") Assert.Equal(cwd, got[i]);
                    else Assert.Equal((string)argv[i]!, got[i]);
            }
            if (cs.Get("expectEnv") is Dictionary<string, object?> ee && ee.Get("GROK_CONFIG") is string gc)
                Assert.Equal(gc, File.ReadAllText(Path.Combine(outDir, "env-GROK_CONFIG.txt")));
            if (cs.Get("expectExists") is List<object?> ex) foreach (var p in ex) Assert.True(File.Exists((string)p!), $"{p} missing");
            if (cs.Get("expectMissing") is List<object?> mi) foreach (var p in mi) Assert.False(File.Exists((string)p!), $"{p} exists");
        }
        finally
        {
            Directory.Delete(root, true);
        }
    }

    [Theory]
    [MemberData(nameof(CliCaseNames))]
    public Task Cli_FunctionFace(string name) => RunCliCase(Case("cli.json", name), toolFace: false);

    [Theory]
    [MemberData(nameof(CliCaseNames))]
    public Task Cli_ToolFace(string name) => RunCliCase(Case("cli.json", name), toolFace: true);

    public static IEnumerable<object[]> CliVerbCaseNames() => Cases("cli.json", "verbCases").Select(c => new object[] { (string)c["name"]! });

    public static IEnumerable<object[]> AvailabilityCaseNames() => Cases("cli.json", "availabilityCases").Select(c => new object[] { (string)c["name"]! });

    /// <summary>cli.json availabilityCases (§7G.10b): {dir} = a fresh realpath'd temp dir.</summary>
    [Theory]
    [MemberData(nameof(AvailabilityCaseNames))]
    public void Cli_AvailabilityCase(string name)
    {
        var c = Cases("cli.json", "availabilityCases").First(x => (string)x["name"]! == name);
        var dir = MediaUtil.RealPath(TempDir());
        try
        {
            var map = new Dictionary<string, string> { ["{dir}"] = dir };
            var cs = (Dictionary<string, object?>)Subst(c, map)!;
            using var env = new EnvScope(c, map);
            if (cs.Get("createFiles") is List<object?> cf) foreach (var p in cf) File.WriteAllBytes((string)p!, Array.Empty<byte>());
            var style = cs["style"] is string preset
                ? MediaStyle.Load(preset, Config((Dictionary<string, object?>?)cs.Get("config"), new List<string>()))
                : MediaStyle.FromDictionary((Dictionary<string, object?>)cs["style"]!);
            var sw = System.Diagnostics.Stopwatch.StartNew();
            var got = style.Available();
            Assert.True(sw.ElapsedMilliseconds < 1000, $"took {sw.ElapsedMilliseconds}ms");
            var exp = (Dictionary<string, object?>)cs["expect"]!;
            Assert.Equal(((bool)exp["installed"]!, (bool)exp["loggedIn"]!, (string)exp["reason"]!), (got.Installed, got.LoggedIn, got.Reason));
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    /// <summary>cli.json verbCases: createMedia over loaded styles (string = preset, object = HTTP style).</summary>
    [Theory]
    [MemberData(nameof(CliVerbCaseNames))]
    public async Task Cli_VerbCase(string name)
    {
        var c = Cases("cli.json", "verbCases").First(x => (string)x["name"]! == name);
        var noRun = new MediaClientConfig { Command = new[] { "/nonexistent/toolnexus-must-not-run" } };
        var media = Media.Create(((List<object?>)c["styles"]!).Select(s => (IMediaClient)(s is string preset
            ? MediaStyle.Load(preset, noRun)
            : MediaStyle.FromDictionary((Dictionary<string, object?>)s!))));
        Assert.Equal(((List<object?>)c["expectVerbs"]!).Select(x => (string)x!).ToList(), media.Verbs());
        Assert.Equal(((List<object?>)c["expectTools"]!).Select(x => (string)x!).ToList(), media.Tools().Select(t => t.Name).ToList());
        var e = await Assert.ThrowsAsync<MediaException>(() => media.GenerateAsync((string)c["unsupported"]!, new Dictionary<string, object?>()));
        Assert.Equal((string)c["error"]!, e.Message);
    }

    // ================================================================ routing.json — createMedia

    /// Test-only custom client built from routing.json: records every call.
    private sealed class ScriptedClient : IMediaClient
    {
        private readonly Dictionary<string, object?> _verbs;
        private readonly List<Dictionary<string, object?>> _calls;

        public ScriptedClient(Dictionary<string, object?> spec, List<Dictionary<string, object?>> calls)
        {
            Name = (string)spec["name"]!;
            _verbs = (Dictionary<string, object?>)spec["verbs"]!;
            _calls = calls;
        }

        public string Name { get; }
        public bool Supports(string verb) => _verbs.ContainsKey(verb);

        public IReadOnlyList<MediaArg>? Args(string verb)
            => (_verbs.Get(verb) as Dictionary<string, object?>)?.Get("args") is List<object?> a
                ? a.Cast<Dictionary<string, object?>>().Select(x => new MediaArg((string)x["name"]!, x.Get("required") is true)).ToList()
                : null;

        private Task<MediaResult> Go(string verb, IDictionary<string, object?> args)
        {
            _calls.Add(new Dictionary<string, object?> { ["client"] = Name, ["verb"] = verb, ["args"] = Norm(args) });
            var outcome = (Dictionary<string, object?>)((Dictionary<string, object?>)_verbs[verb]!)["outcome"]!;
            if (outcome.Get("error") is string err)
                throw outcome.Get("status") as string == "unavailable" ? new MediaUnavailableException(err) : new MediaException(err, (string)outcome["status"]!);
            var r = (Dictionary<string, object?>)outcome["result"]!;
            var files = ((List<object?>)r["files"]!).Cast<Dictionary<string, object?>>()
                .Select(f => new MediaFile((string)f["type"]!, (string)f["mimeType"]!, f.Get("url") as string, f.Get("path") as string, f.Get("data") as string)).ToList();
            var u = r.Get("usage") as Dictionary<string, object?>;
            return Task.FromResult(new MediaResult(files, (string)r["status"]!, r.Get("text") as string, r.Get("jobId") as string)
            {
                Usage = u == null ? null : new MediaUsage
                {
                    InputTokens = u.Get("inputTokens") as long?, OutputTokens = u.Get("outputTokens") as long?,
                    CostUsd = u.Get("costUsd") switch { double d => d, long l => l, _ => null },
                    Raw = u.Get("raw") as Dictionary<string, object?>,
                },
            });
        }

        public Task<MediaResult> ImageAsync(IDictionary<string, object?> a, MediaContext c) => Go("image", a);
        public Task<MediaResult> VideoAsync(IDictionary<string, object?> a, MediaContext c) => Go("video", a);
        public Task<MediaResult> SpeechAsync(IDictionary<string, object?> a, MediaContext c) => Go("speech", a);
        public Task<MediaResult> MusicAsync(IDictionary<string, object?> a, MediaContext c) => Go("music", a);
        public Task<MediaResult> TranscribeAsync(IDictionary<string, object?> a, MediaContext c) => Go("transcribe", a);
        public Task<MediaResult> CloneVoiceAsync(IDictionary<string, object?> a, MediaContext c) => Go("cloneVoice", a);
        public Task<MediaResult> TalkAsync(IDictionary<string, object?> a, MediaContext c) => Go("talk", a);
    }

    private static Media Routed(Dictionary<string, object?> c, List<Dictionary<string, object?>> calls)
        => Media.Create(((List<object?>)c["clients"]!).Cast<Dictionary<string, object?>>().Select(s => (IMediaClient)new ScriptedClient(s, calls)));

    [Theory]
    [MemberData(nameof(RoutingCaseNames))]
    public async Task Routing_Case(string name)
    {
        var c = Case("routing.json", name);
        var calls = new List<Dictionary<string, object?>>();
        var media = Routed(c, calls);
        var verb = (string)c["verb"]!;
        var args = (Dictionary<string, object?>)c["args"]!;
        if (c.ContainsKey("error")) AssertError(c, await Record.ExceptionAsync(() => media.GenerateAsync(verb, args)));
        else AssertResult((Dictionary<string, object?>)c["result"]!, await media.GenerateAsync(verb, args), null, "", verb);
        if (c.Get("expectCalls") is List<object?> ec) AssertJson(ec, Norm(calls), "calls");

        if (c.Get("tool") is Dictionary<string, object?> exp)
        {
            var fresh = Routed(c, new List<Dictionary<string, object?>>());
            var tool = fresh.Tools().Single(t => t.Name == (exp.Get("name") as string ?? ToolName(verb)));
            AssertTool(exp, await tool.ExecuteAsync((Dictionary<string, object?>)Norm(args)!), null);
        }
    }

    [Fact]
    public void Routing_SchemaCases()
    {
        foreach (var c in Cases("routing.json", "schemaCases"))
            AssertTools((List<object?>)c["tools"]!, Routed(c, new List<Dictionary<string, object?>>()).Tools());
    }

    [Fact]
    public void Routing_EmptyClients()
    {
        var exp = (string)((Dictionary<string, object?>)Load("routing.json")["emptyClients"]!)["error"]!;
        Assert.Equal(exp, Assert.Throws<MediaException>(() => Media.Create(Array.Empty<IMediaClient>())).Message);
    }

    // ================================================================ beyond the conformance files

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
        var media = Media.Create(new StyleMediaClient(MediaStyle.Parse(AsyncStyle.Replace("{server}", server.Url)),
            new MediaClientConfig { Loop = new MediaLoop { OnProgress = _ => cts.Cancel() } }));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => media.ImageAsync(new ImageInput("x"), cts.Token));
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
    public async Task Cli_Cancel_KillsTheProcess()
    {
        var root = TempDir();
        try
        {
            var fixture = Path.Combine(root, "f.jsonl");
            File.WriteAllText(fixture, "#hang\n");
            var media = Media.Create(new GrokMediaClient(new MediaClientConfig
            {
                Command = new[] { "/bin/sh", ConformanceFile("fake-cli/fake-cli.sh"), fixture, Path.Combine(root, "out") },
            }));
            using var cts = new CancellationTokenSource(300);
            var sw = System.Diagnostics.Stopwatch.StartNew();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => media.ImageAsync(new ImageInput("x"), cts.Token));
            Assert.True(sw.ElapsedMilliseconds < 5000);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task ContextProgress_WinsOverLoopOnProgress()
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"id":"j"}},{"status":200,"body":{"st":"ok","u":"https://x/a.png"}}]""")!;
        using var server = new FakeServer(script);
        var loop = new List<string>();
        var ctx = new List<MediaProgress>();
        var media = Media.Create(new StyleMediaClient(MediaStyle.Parse(AsyncStyle.Replace("{server}", server.Url)),
            new MediaClientConfig { Loop = new MediaLoop { OnProgress = p => loop.Add(p.Status) } }));
        await media.GenerateAsync("image", new Dictionary<string, object?> { ["prompt"] = "x" }, new MediaContext { Progress = ctx.Add });
        Assert.Empty(loop);
        var p = Assert.Single(ctx);
        Assert.Equal(("image", "style", "j", "ok"), (p.Verb, p.Client, p.JobId, p.Status));
    }

    [Fact]
    public async Task TypedVerbs_ProviderOptionsOverwriteBodyLast()
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"u":"https://x/a.mp3"}}]""")!;
        using var server = new FakeServer(script);
        var media = Media.Create(new StyleMediaClient(MediaStyle.Parse($$"""
            { "baseUrl": "{{server.Url}}", "verbs": { "speech": { "mode": "sync", "kind": "audio",
              "start": { "path": "/t" }, "defaults": { "model": "a" }, "args": { "text": "t", "voice": "v" }, "resultPath": "u" } } }
            """)));
        var r = await media.SpeechAsync(new SpeechInput("hi")
        {
            ProviderOptions = new Dictionary<string, object?> { ["model"] = "b", ["t"] = "over" },
        });
        Assert.Equal("audio/mpeg", r.Files[0].MimeType);
        AssertJson(Json.ParseLoose("""{"model":"b","t":"over"}"""), Json.ParseLoose(server.Requests[0].Text));
    }

    private static Media UrlStyle(string baseUrl, string verb, string kind, string argKey)
        => Media.Create(new StyleMediaClient(MediaStyle.Parse($$"""
            { "baseUrl": "{{baseUrl}}", "verbs": { "{{verb}}": { "mode": "sync", "kind": "{{kind}}",
              "start": { "path": "/i" }, "args": { "{{argKey}}": "p" }, "resultPath": "u" } } }
            """)));

    [Fact]
    public async Task Toolkit_MediaOption_RegistersTools_AndRunResultCarriesFiles()
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"u":["https://x/a.png","https://x/b.webp"]}}]""")!;
        using var server = new FakeServer(script);
        var media = UrlStyle(server.Url, "image", "image", "prompt");
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
        var media = UrlStyle("http://unused", "image", "image", "prompt");
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

    /// §7G.6 model visibility on the wire: an image part reaches the model, a video part does not —
    /// but both land in RunResult.Files.
    [Theory]
    [InlineData("openai")]
    [InlineData("anthropic")]
    public async Task ModelVisibility_OnlyImagePartsReachTheModel(string style)
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"u":"https://x/v.mp4"}}]""")!;
        using var mediaServer = new FakeServer(script);
        var media = UrlStyle(mediaServer.Url, "video", "video", "prompt");
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, Media = media });
        var bodies = new List<string>();
        var n = 0;
        using var llm = new StubServer(ctx =>
        {
            using (var rd = new StreamReader(ctx.Request.InputStream)) lock (bodies) bodies.Add(rd.ReadToEnd());
            var first = Interlocked.Increment(ref n) == 1;
            var body = style == "anthropic"
                ? first
                    ? """{"content":[{"type":"tool_use","id":"t1","name":"generate_video","input":{"prompt":"waves"}}],"stop_reason":"tool_use"}"""
                    : """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
                : first
                    ? """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"t1","type":"function","function":{"name":"generate_video","arguments":"{\"prompt\":\"waves\"}"}}]},"finish_reason":"tool_calls"}]}"""
                    : """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";
            StubServer.Respond(ctx, 200, body);
        });
        var client = LlmClient.Create(new LlmClient.Options { BaseUrl = llm.BaseUrl, ApiKey = "YOUR_KEY_HERE", Model = "m", Retries = 0, Style = style });
        var r = await client.RunAsync("film", tk);
        Assert.Equal("ok", r.Text);
        Assert.Equal("https://x/v.mp4", Assert.Single(r.Files).Url);
        Assert.Equal(2, bodies.Count);
        Assert.Contains("video ready: https://x/v.mp4", bodies[1]);
        Assert.DoesNotContain("x/v.mp4\"", bodies[1].Replace("video ready: https://x/v.mp4\"", ""));
        Assert.DoesNotContain("Output of tool", bodies[1]);
    }

    /// §7G.12 D9: a media tool result with no image part (modelParts []) sends the plain output
    /// string as the tool-result content in every message style — no empty array, no placeholder.
    /// (C# has no Gemini client style, so openai and anthropic are every style.)
    [Theory]
    [InlineData("openai", "video", "video", "prompt", "generate_video", "video ready: https://x/v.mp4")]
    [InlineData("anthropic", "video", "video", "prompt", "generate_video", "video ready: https://x/v.mp4")]
    [InlineData("openai", "speech", "audio", "text", "generate_speech", "audio ready: https://x/v.mp4")]
    [InlineData("anthropic", "speech", "audio", "text", "generate_speech", "audio ready: https://x/v.mp4")]
    public async Task D9_NoImageParts_ToolResultContentIsPlainOutput(string style, string verb, string kind, string arg, string tool, string output)
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"u":"https://x/v.mp4"}}]""")!;
        using var mediaServer = new FakeServer(script);
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false, Media = UrlStyle(mediaServer.Url, verb, kind, arg) });
        var bodies = new List<string>();
        var n = 0;
        using var llm = new StubServer(ctx =>
        {
            using (var rd = new StreamReader(ctx.Request.InputStream)) lock (bodies) bodies.Add(rd.ReadToEnd());
            var first = Interlocked.Increment(ref n) == 1;
            var input = "{\\\"" + arg + "\\\":\\\"waves\\\"}";
            var body = style == "anthropic"
                ? first
                    ? $$$"""{"content":[{"type":"tool_use","id":"t1","name":"{{{tool}}}","input":{"{{{arg}}}":"waves"}}],"stop_reason":"tool_use"}"""
                    : """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
                : first
                    ? $$$"""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"t1","type":"function","function":{"name":"{{{tool}}}","arguments":"{{{input}}}"}}]},"finish_reason":"tool_calls"}]}"""
                    : """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";
            StubServer.Respond(ctx, 200, body);
        });
        var client = LlmClient.Create(new LlmClient.Options { BaseUrl = llm.BaseUrl, ApiKey = "YOUR_KEY_HERE", Model = "m", Retries = 0, Style = style });
        var r = await client.RunAsync("go", tk);
        Assert.Equal("ok", r.Text);
        Assert.Equal(output, r.ToolCalls[0].Output);
        Assert.DoesNotContain(r.ToolCalls[0].Parts!, p => p.Type == "image"); // modelParts == []
        Assert.Equal(2, bodies.Count);
        var messages = (List<object?>)((Dictionary<string, object?>)Json.ParseLoose(bodies[1])!)["messages"]!;
        object? content;
        if (style == "anthropic")
        {
            var block = messages.Cast<Dictionary<string, object?>>()
                .SelectMany(m => m["content"] as List<object?> ?? new List<object?>()).Cast<Dictionary<string, object?>>()
                .Single(b => b.Get("type") as string == "tool_result");
            content = block["content"];
        }
        else
        {
            var msg = messages.Cast<Dictionary<string, object?>>().Single(m => m.Get("role") as string == "tool");
            content = msg["content"];
            Assert.DoesNotContain(messages.Cast<Dictionary<string, object?>>(), m => m.Get("role") as string == "user" && m.Get("content") is List<object?>);
        }
        Assert.Equal(output, Assert.IsType<string>(content));
    }

    [Fact]
    public async Task Streaming_RunResultCarriesFiles()
    {
        var script = (List<object?>)Json.ParseLoose("""[{"status":200,"body":{"u":"https://x/s.wav"}}]""")!;
        using var mediaServer = new FakeServer(script);
        var media = UrlStyle(mediaServer.Url, "speech", "audio", "text");
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

    /// Media tools are concurrency-safe (SPEC §7G.6, §8 Tool scheduling).
    [Fact]
    public void MediaTools_AreConcurrencySafe_AllSevenVerbs()
    {
        var verbs = new[] { ("image", "image", "prompt"), ("video", "video", "prompt"), ("speech", "audio", "text"), ("music", "audio", "prompt"),
            ("transcribe", "text", "audio"), ("cloneVoice", "text", "name"), ("talk", "video", "image") };
        var style = "{\"baseUrl\":\"http://unused\",\"verbs\":{" + string.Join(",", verbs.Select(v =>
            $"\"{v.Item1}\":{{\"mode\":\"sync\",\"kind\":\"{v.Item2}\",\"start\":{{\"path\":\"/x\"}},\"args\":{{\"{v.Item3}\":\"p\"}},\"resultPath\":\"u\"}}")) + "}}";
        var tools = Media.Create(new StyleMediaClient(MediaStyle.Parse(style))).Tools();
        Assert.Equal(new[] { "generate_image", "generate_video", "generate_speech", "generate_music", "transcribe_audio", "clone_voice", "talking_video" },
            tools.Select(t => t.Name));
        foreach (var t in tools) Assert.True(ToolScheduler.IsConcurrencySafe(t, new Dictionary<string, object?>()), t.Name);
    }

    [Fact]
    public void BuiltInClients_SupportTheirVerbTables()
    {
        Assert.Equal(new[] { "image", "video", "speech", "talk" }, MediaVerbs.All.Select(v => v.Verb).Where(new A2EMediaClient().Supports));
        Assert.Equal(new[] { "speech", "music", "transcribe", "cloneVoice" }, MediaVerbs.All.Select(v => v.Verb).Where(new ElevenLabsMediaClient().Supports));
        Assert.Equal(new[] { "image", "video", "speech", "transcribe" }, MediaVerbs.All.Select(v => v.Verb).Where(new OpenAIMediaClient().Supports));
        Assert.Equal(new[] { "image" }, MediaVerbs.All.Select(v => v.Verb).Where(new OpenRouterMediaClient().Supports));
        Assert.Equal(new[] { "image", "video" }, MediaVerbs.All.Select(v => v.Verb).Where(new FalMediaClient().Supports));
        Assert.Equal(new[] { "image", "video" }, MediaVerbs.All.Select(v => v.Verb).Where(new GrokMediaClient().Supports));
        Assert.Equal(new[] { "image" }, MediaVerbs.All.Select(v => v.Verb).Where(new CodexMediaClient().Supports));
    }

    // ---------------------------------------------------------------- edge rules

    private static StyleMediaClient SyncStyle(string baseUrl, object? headers = null, long? timeoutMs = null)
    {
        var verb = new Dictionary<string, object?>
        {
            ["mode"] = "sync", ["kind"] = "image", ["start"] = new Dictionary<string, object?> { ["path"] = "/gen" },
            ["args"] = new Dictionary<string, object?> { ["prompt"] = "prompt" }, ["resultPath"] = "url",
        };
        if (timeoutMs != null) verb["poll"] = new Dictionary<string, object?> { ["timeoutMs"] = timeoutMs };
        var style = new Dictionary<string, object?> { ["baseUrl"] = baseUrl, ["verbs"] = new Dictionary<string, object?> { ["image"] = verb } };
        if (headers != null) style["headers"] = headers;
        return new StyleMediaClient(MediaStyle.FromDictionary(style));
    }

    [Fact]
    public async Task CrossOriginRedirect_DropsStyleHeaders()
    {
        var seenB = new List<Dictionary<string, string>>();
        using var b = new StubServer(ctx =>
        {
            var h = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var k in ctx.Request.Headers.AllKeys) if (k != null) h[k] = ctx.Request.Headers[k] ?? "";
            lock (seenB) seenB.Add(h);
            StubServer.Respond(ctx, 200, "{\"url\":\"https://x/a.png\"}");
        });
        var seenA = new List<string?>();
        using var a = new StubServer(ctx =>
        {
            lock (seenA) seenA.Add(ctx.Request.Headers["Authorization"]);
            ctx.Response.StatusCode = 307;
            ctx.Response.Headers["Location"] = b.BaseUrl + "/elsewhere";
            ctx.Response.Close();
        });
        var media = Media.Create(SyncStyle(a.BaseUrl, new Dictionary<string, object?>
            { ["Authorization"] = "Bearer YOUR_KEY_HERE", ["X-Style"] = "s" }));
        var r = await media.GenerateAsync("image", new Dictionary<string, object?> { ["prompt"] = "cat" });
        Assert.Equal("https://x/a.png", r.Files[0].Url);
        Assert.Equal("Bearer YOUR_KEY_HERE", Assert.Single(seenA));
        var hb = Assert.Single(seenB);
        Assert.False(hb.ContainsKey("Authorization"));
        Assert.False(hb.ContainsKey("X-Style"));
    }

    [Fact]
    public async Task HungServer_BoundedByRemainingDeadline()
    {
        using var release = new ManualResetEventSlim();
        using var hung = new StubServer(ctx => { release.Wait(TimeSpan.FromSeconds(10)); try { StubServer.Respond(ctx, 200, "{}"); } catch { } });
        var media = Media.Create(SyncStyle(hung.BaseUrl, timeoutMs: 1000));
        var sw = System.Diagnostics.Stopwatch.StartNew();
        var ex = await Assert.ThrowsAsync<MediaException>(() => media.GenerateAsync("image", new Dictionary<string, object?> { ["prompt"] = "cat" }));
        sw.Stop();
        release.Set();
        Assert.Equal("image: timed out after 1000ms", ex.Message);
        Assert.Equal("timeout", ex.Status);
        Assert.True(sw.ElapsedMilliseconds < 5000, $"took {sw.ElapsedMilliseconds}ms");
    }

    [Fact]
    public void EdgeHelpers()
    {
        Assert.Equal("a%2Fb%20c%C3%A9-._~", MediaUtil.PercentEncode("a/b cé-._~"));
        Assert.Equal(new string('x', 1000) + "…", MediaUtil.CapBody(new string('x', 1001)));
        Assert.Equal(new string('x', 1000), MediaUtil.CapBody(new string('x', 1000)));
        Assert.Equal("%21%27%28%29%2A", MediaUtil.PercentEncode("!'()*"));
        Assert.Equal("100000000000000000000", MediaUtil.ScalarText(1e20, integralOnly: true));
        Assert.Equal("9223372036854775808", MediaUtil.ScalarText(9223372036854775808d, integralOnly: true));
        Assert.Equal("-100000000000000000000", MediaUtil.ScalarText(-1e20, integralOnly: true));
        Assert.Equal("42", MediaUtil.ScalarText(42d, integralOnly: true));
        var root = new Dictionary<string, object?> { ["a"] = new List<object?> { 1L } };
        MediaUtil.WritePath(root, "a.b", 2L);
        Assert.Equal("{\"a\":{\"b\":2}}", Json.Stringify(root));
        var ex = Assert.Throws<MediaException>(() => MediaStyle.Parse("{\"verbs\":{\"image\":{\"args\":{\"prompt\":\"\"}}}}"));
        Assert.Equal("style: empty path for argument \"prompt\"", ex.Message);
    }
}
