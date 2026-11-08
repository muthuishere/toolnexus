using System.Text;
using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>
/// §8B "Backends, images, cache and validation" (change add-classifier-backends), run against the
/// shared fixtures in <c>examples/judge/backends/</c>. Hermetic: a recording handler stands in for
/// every endpoint.
/// </summary>
public class ClassifierBackendsTests
{
    private static JsonElement Load(string name)
    {
        var path = TestFixtures.Fixture(Path.Combine("judge", "backends", name));
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        return doc.RootElement.Clone();
    }

    private static string? Str(JsonElement o, string name)
        => o.ValueKind == JsonValueKind.Object && o.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.String
            ? el.GetString() : null;

    /// <summary>A wire question map to typed questions (raw for unknown types).</summary>
    private static Dictionary<string, Question> Qs(JsonElement m)
    {
        var out_ = new Dictionary<string, Question>();
        foreach (var p in m.EnumerateObject())
        {
            var w = p.Value;
            var ins = Str(w, "instructions") ?? "";
            var has = w.TryGetProperty("criteria", out var c);
            out_[p.Name] = Str(w, "type") switch
            {
                "noul" => new NoulQuestion
                {
                    Instructions = ins,
                    Criteria = has ? new NoulCriteria { True = Str(c, "true") ?? "", False = Str(c, "false") ?? "" } : null,
                },
                "choice" => new ChoiceQuestion
                {
                    Instructions = ins,
                    Criteria = c.EnumerateObject().ToDictionary(x => x.Name, x => x.Value.GetString() ?? ""),
                },
                "score" => new ScoreQuestion { Instructions = ins, Criteria = c.EnumerateArray().Select(x => x.GetString() ?? "").ToList() },
                var t => new RawQuestion { Type = t ?? "", Instructions = ins, Criteria = has ? c : null },
            };
        }
        return out_;
    }

    /// <summary>Converts <c>{"$image":…}</c>/<c>{"$text":…}</c> markers into ContentParts.</summary>
    private static object? State(JsonElement v)
    {
        switch (v.ValueKind)
        {
            case JsonValueKind.Object:
                if (v.TryGetProperty("$image", out var im))
                    return new ContentPart { Type = "image", MimeType = Str(im, "mimeType"), Data = Str(im, "data") };
                if (v.TryGetProperty("$text", out var t))
                    return ContentPart.FromText(t.GetString()!);
                return v.EnumerateObject().ToDictionary(p => p.Name, p => State(p.Value));
            case JsonValueKind.Array:
                return v.EnumerateArray().Select(State).ToList();
            default:
                return v.Clone();
        }
    }

    private sealed class Fake : HttpMessageHandler
    {
        private readonly Func<int, string, (int, string)> _reply;
        private int _n;
        public readonly List<string> Urls = new();
        public readonly List<string> Bodies = new();
        public readonly List<HttpRequestMessage> Requests = new();

        public Fake(Func<int, string, (int, string)> reply) { _reply = reply; }
        public Fake(string body) : this((_, _) => (200, body)) { }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var body = await request.Content!.ReadAsStringAsync(ct);
            int n;
            lock (Bodies)
            {
                Urls.Add(request.RequestUri!.ToString());
                Bodies.Add(body);
                Requests.Add(request);
                n = ++_n;
            }
            var (st, text) = _reply(n, body);
            return new HttpResponseMessage((System.Net.HttpStatusCode)st) { Content = new StringContent(text) };
        }
    }

    private const string NoulReply =
        """{"model":"jev-1.14.0","answers":{"q":{"type":"noul","noul":0.91}},"usage":{"input_tokens":3,"output_tokens":1}}""";

    private static ClassifierOptions Opts(Fake f, string baseUrl = "https://fake.test/v1") => new()
    {
        BaseUrl = baseUrl,
        HttpHandler = f,
        ApiKeyEnv = "TN_TEST_UNSET_KEY",
        OnError = _ => LlmClient.Tier.Fail,
    };

    // ---------------------------------------------------------------- presets

    [Fact]
    public void BackendPresetsMatchTheFixture()
    {
        var fx = Load("presets.json");
        var backends = fx.GetProperty("backends");
        var n = 0;
        foreach (var b in backends.EnumerateObject())
        {
            n++;
            var preset = ClassifierBackends.Table[ClassifierBackends.Parse(b.Name)];
            var w = b.Value;
            Assert.Equal(Str(w, "baseUrl"), preset.BaseUrl);
            Assert.Equal(Str(w, "model"), preset.Model);
            Assert.Equal(Str(w, "apiKeyEnv"), preset.ApiKeyEnv);
            Assert.Equal(Str(w, "wire"), preset.Wire);
            Assert.Equal(w.GetProperty("imagesHeader").GetBoolean(), preset.ImagesHeader);
            Assert.Equal(b.Name, ClassifierBackends.Name(ClassifierBackends.Parse(b.Name)));
            if (w.GetProperty("supportsImages").ValueKind == JsonValueKind.Object)
                foreach (var m in w.GetProperty("supportsImages").EnumerateObject())
                    Assert.Equal(m.Value.GetBoolean(), preset.Supports!(m.Name).Images);
            else
                Assert.Null(preset.Supports);
        }
        Assert.Equal(n, ClassifierBackends.Table.Count);

        var unknown = Assert.Throws<ClassifierException>(() => new ClassifierOptions().WithBackend("nope"));
        Assert.Equal(Str(fx, "unknownBackendError"), unknown.Message);
        var undefinedEnum = Assert.Throws<ClassifierException>(() => new Classifier(new ClassifierOptions { Backend = (ClassifierBackend)99 }));
        Assert.StartsWith("classifier: unknown backend \"99\" (want \"typesafe\"", undefinedEnum.Message);

        var custom = Assert.Throws<ClassifierException>(() => new Classifier(new ClassifierOptions
        { Backend = ClassifierBackend.Custom, BaseUrl = "http://x" }));
        Assert.Equal(Str(fx, "customError"), custom.Message);
        Assert.Throws<ClassifierException>(() => new Classifier(new ClassifierOptions
        { Backend = ClassifierBackend.Custom, Supports = new ClassifierSupports() }));
        _ = new Classifier(new ClassifierOptions { Backend = ClassifierBackend.Custom, BaseUrl = "http://x", Supports = new ClassifierSupports() });
        // Styles that never touch the wire are not held to the custom-endpoint declarations.
        _ = new Classifier(new ClassifierOptions { Backend = ClassifierBackend.Custom, Style = ClassifierStyle.Static });
    }

    // ---------------------------------------------------------------- images

    [Fact]
    public async Task ImagesFixture()
    {
        var fx = Load("images.json");
        var qs = Qs(fx.GetProperty("questions"));
        foreach (var c in fx.GetProperty("cases").EnumerateArray())
        {
            var name = Str(c, "name")!;
            var backend = Str(c, "backend")!;
            var expect = c.GetProperty("expect");
            var f = new Fake(backend == "cloudflare" ? "{\"success\":true,\"result\":" + NoulReply + "}" : NoulReply);
            var preset = ClassifierBackends.Table[ClassifierBackends.Parse(backend)];
            var baseUrl = "https://fake.test/v1";
            if (backend == "cloudflare")
            {
                baseUrl = "https://fake.test/accounts/${TN_TEST_ACCOUNT}/ai/run";
                Environment.SetEnvironmentVariable("TN_TEST_ACCOUNT", "acct123");
                if (Str(expect, "url") is { } u) Assert.Equal(u, preset.BaseUrl);
            }
            var opts = Opts(f, baseUrl);
            opts.Backend = ClassifierBackends.Parse(backend);
            opts.Model = Str(c, "model");
            if (c.TryGetProperty("supports", out var sup) && sup.ValueKind == JsonValueKind.Object)
                opts.Supports = new ClassifierSupports(sup.GetProperty("images").GetBoolean());
            var cl = new Classifier(opts);
            Exception? err = null;
            try { await cl.EvaluateAsync(State(c.GetProperty("state")), qs); }
            catch (Exception e) { err = e; }

            if (expect.TryGetProperty("unsupported", out var un) && un.GetBoolean())
            {
                var u = Assert.IsType<ClassifierUnsupportedException>(err);
                Assert.Equal(Str(expect, "error"), u.Message);
                Assert.Equal("images", u.Feature);
                Assert.Empty(f.Bodies);
            }
            else if (Str(expect, "errorPrefix") is { } prefix)
            {
                Assert.True(err != null && err.Message.StartsWith(prefix, StringComparison.Ordinal), $"{name}: {err?.Message}");
                Assert.IsNotType<ClassifierUnsupportedException>(err);
                Assert.Empty(f.Bodies);
            }
            else
            {
                Assert.True(err == null, $"{name}: {err?.Message}");
                Assert.Equal(Str(expect, "body"), f.Bodies[0]);
                var hasHeader = f.Requests[0].Headers.TryGetValues(ClassifierBackends.ImagesHeader, out var hv) && hv.Contains("true");
                Assert.True(expect.GetProperty("imagesHeader").GetBoolean() == hasHeader, $"{name}: header {hasHeader}");
                if (backend == "cloudflare") Assert.Equal("https://fake.test/accounts/acct123/ai/run", f.Urls[0]);
                else Assert.Equal("https://fake.test/v1/systemone", f.Urls[0]);
            }
        }
    }

    [Fact]
    public async Task HostHeadersWinOverTheImagesHeaderAndOtherPartsAreRefused()
    {
        var png = "iVBORw0KGgoAAAANSUhEUgAAASwAAAEsCAIAAAAAAAAA";
        var f = new Fake(NoulReply);
        var opts = Opts(f);
        opts.Headers = new Dictionary<string, string> { [ClassifierBackends.ImagesHeader] = "false" };
        var cl = new Classifier(opts);
        var qs = new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "x" } };
        await cl.EvaluateAsync(new Dictionary<string, object?> { ["p"] = new ContentPart { Type = "image", MimeType = "image/png", Data = png } }, qs);
        Assert.Equal(new[] { "false" }, f.Requests[0].Headers.GetValues(ClassifierBackends.ImagesHeader));

        var audio = new ContentPart { Type = "audio", MimeType = "audio/wav", Data = "AAAA" };
        var e = await Assert.ThrowsAsync<ClassifierException>(() => cl.EvaluateAsync(new List<object?> { audio }, qs));
        Assert.Equal("classifier: state: audio parts are not supported", e.Message);
        var url = new ContentPart { Type = "image", MimeType = "image/png", Url = "https://x/y.png" };
        await Assert.ThrowsAsync<ClassifierException>(() => cl.EvaluateAsync(url, qs));
        Assert.Single(f.Bodies);

        // Non-systemone styles get the state verbatim: no lifting, no checks.
        object? seen = null;
        var custom = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Custom,
            Evaluate = (s, _, _) => { seen = s; return Task.FromResult(Decision.FromJson("""{"answers":{"q":{"type":"noul","noul":0.5}}}""")); },
        });
        await custom.EvaluateAsync(audio, qs);
        Assert.Same(audio, seen);
    }

    [Fact]
    public void JpegAndPngHeadersAreRead()
    {
        var jpeg = Convert.FromBase64String("/9j/4AAQSkZJRgABAQAAAQABAAD/xAAEAAD/wAALCAEsAZABAREA/9k=");
        Assert.Equal((400L, 300L), ClassifierImages.Size("jpeg", jpeg));
        var png = Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAASwAAAEsCAIAAAAAAAAA");
        Assert.Equal((300L, 300L), ClassifierImages.Size("png", png));
        Assert.Null(ClassifierImages.Size("png", jpeg));
        Assert.Null(ClassifierImages.Size("jpeg", png));
    }

    // ---------------------------------------------------------------- cloudflare

    [Fact]
    public async Task CloudflareRepliesFixture()
    {
        var fx = Load("cloudflare-replies.json");
        var qs = Qs(fx.GetProperty("questions"));
        foreach (var c in fx.GetProperty("cases").EnumerateArray())
        {
            var f = new Fake(c.GetProperty("reply").GetRawText());
            var opts = Opts(f, "https://fake.test/run");
            opts.Backend = ClassifierBackend.Cloudflare;
            var cl = new Classifier(opts);
            var expect = c.GetProperty("expect");
            if (Str(expect, "error") is { } want)
            {
                var e = await Assert.ThrowsAsync<ClassifierException>(() => cl.EvaluateAsync("x", qs));
                Assert.Equal(want, e.Message);
                continue;
            }
            var d = await cl.EvaluateAsync("x", qs);
            Assert.Equal(Str(expect, "model"), d.Model);
            Assert.Equal(expect.GetProperty("noul").GetDouble(), d.Noul("q").Noul);
            Assert.Equal("https://fake.test/run", f.Urls[0]);
        }
    }

    // ---------------------------------------------------------------- openai decisions (#157)

    [Fact]
    public async Task OpenAIDecisionsFixture()
    {
        var fx = Load("openai-decisions.json");
        var qs = Qs(fx.GetProperty("questions"));
        var okReply = fx.GetProperty("replyCases")[0].GetProperty("reply").GetRawText();
        ClassifierOptions OpenAI(Fake f, string? model = null)
        {
            var o = Opts(f);
            o.Backend = ClassifierBackend.OpenAI;
            if (model != null) o.Model = model;
            return o;
        }

        foreach (var c in fx.GetProperty("requestCases").EnumerateArray())
        {
            var f = new Fake(okReply);
            await new Classifier(OpenAI(f, Str(c, "model"))).EvaluateAsync(State(c.GetProperty("state")), qs);
            Assert.Equal(Str(c.GetProperty("expect"), "body"), f.Bodies[0]);
            Assert.Equal("https://fake.test/v1/decisions", f.Urls[0]);
            Assert.False(f.Requests[0].Headers.Contains(ClassifierBackends.ImagesHeader), Str(c, "name"));
        }

        foreach (var c in fx.GetProperty("replyCases").EnumerateArray())
        {
            var reply = c.GetProperty("reply").GetRawText();
            var expect = c.GetProperty("expect");
            if (Str(expect, "normalized") is { } norm)
                Assert.Equal(norm, Classifier.DecisionsReply(reply, qs));
            var cl = new Classifier(OpenAI(new Fake(reply)));
            if (Str(expect, "error") is { } want)
            {
                var e = await Assert.ThrowsAsync<ClassifierException>(() => cl.EvaluateAsync("x", qs));
                Assert.Equal(want, e.Message);
                continue;
            }
            var d = await cl.EvaluateAsync("x", qs);
            Assert.Equal(Str(expect, "model"), d.Model);
            Assert.Equal(expect.GetProperty("calibrated").GetBoolean(), d.Calibrated);
            foreach (var p in expect.GetProperty("noul").EnumerateObject()) Assert.Equal(p.Value.GetDouble(), d.Noul(p.Name).Noul);
            foreach (var p in expect.GetProperty("choice").EnumerateObject()) Assert.Equal(p.Value.GetString(), d.Choice(p.Name).Choice);
            foreach (var p in expect.GetProperty("score").EnumerateObject()) Assert.Equal(p.Value.GetDouble(), d.Score(p.Name).Score);
        }

        foreach (var c in fx.GetProperty("errorCases").EnumerateArray())
        {
            var f = new Fake(okReply);
            var cl = new Classifier(OpenAI(f));
            var expect = c.GetProperty("expect");
            var e = await Assert.ThrowsAnyAsync<ClassifierException>(() => cl.EvaluateAsync("x", Qs(c.GetProperty("questions"))));
            Assert.Equal(Str(expect, "error"), e.Message);
            Assert.Equal(expect.TryGetProperty("unsupported", out var u) && u.GetBoolean(), e is ClassifierUnsupportedException);
            Assert.Empty(f.Bodies);
        }

        foreach (var c in fx.GetProperty("captureCases").EnumerateArray())
        {
            var env = c.GetProperty("env").EnumerateObject().ToList();
            var saved = env.ToDictionary(p => p.Name, p => Environment.GetEnvironmentVariable(p.Name));
            try
            {
                foreach (var p in env) Environment.SetEnvironmentVariable(p.Name, p.Value.GetString());
                var f = new Fake(okReply);
                var o = OpenAI(f);
                o.ApiKeyEnv = Str(c, "apiKeyEnv");
                await new Classifier(o).EvaluateAsync("x", qs);
                var got = f.Requests[0].Headers.Authorization?.ToString();
                Assert.True(Str(c, "expectAuthorization") == got, $"{Str(c, "name")}: Authorization = {got ?? "<absent>"}");
            }
            finally
            {
                foreach (var (k, v) in saved) Environment.SetEnvironmentVariable(k, v);
            }
        }
    }

    [Fact]
    public async Task CloudflareErrorKeepsTheAccountTemplate()
    {
        Environment.SetEnvironmentVariable("TN_TEST_ACCOUNT2", "secretacct");
        var f = new Fake((_, _) => (500, "boom"));
        var opts = Opts(f, "https://fake.test/accounts/${TN_TEST_ACCOUNT2}/ai/run");
        opts.Backend = ClassifierBackend.Cloudflare;
        var cl = new Classifier(opts);
        var e = await Assert.ThrowsAsync<ClassifierException>(() =>
            cl.EvaluateAsync("x", new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "y" } }));
        Assert.DoesNotContain("secretacct", e.Message);
        Assert.Contains("${TN_TEST_ACCOUNT2}", e.Message);
        Assert.Equal("https://fake.test/accounts/secretacct/ai/run", f.Urls[0]);
    }

    // ---------------------------------------------------------------- key routing (#155)

    [Fact]
    public async Task KeyRoutingFixture()
    {
        var fx = Load("key-routing.json");
        var qs = Qs(fx.GetProperty("questions"));
        foreach (var c in fx.GetProperty("captureCases").EnumerateArray())
        {
            var name = Str(c, "name");
            var env = c.TryGetProperty("env", out var e) ? e.EnumerateObject().ToList() : new();
            var saved = env.ToDictionary(p => p.Name, p => Environment.GetEnvironmentVariable(p.Name));
            try
            {
                foreach (var p in env) Environment.SetEnvironmentVariable(p.Name, p.Value.GetString());
                var backend = Str(c, "backend")!;
                var auths = new List<string?>();
                using var srv = new StubServer(ctx =>
                {
                    lock (auths) auths.Add(ctx.Request.Headers["Authorization"]);
                    new StreamReader(ctx.Request.InputStream).ReadToEnd();
                    StubServer.Respond(ctx, 200, backend == "cloudflare"
                        ? "{\"success\":true,\"result\":" + NoulReply + "}" : NoulReply);
                });
                var opts = new ClassifierOptions
                {
                    BaseUrl = srv.BaseUrl + "/v1",
                    ApiKeyEnv = Str(c, "apiKeyEnv"),
                    OnError = _ => LlmClient.Tier.Fail,
                };
                opts.WithBackend(backend);
                if (backend == "custom") opts.Supports = new ClassifierSupports(false);
                if (c.TryGetProperty("headers", out var hs) && hs.ValueKind == JsonValueKind.Object)
                    opts.Headers = hs.EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetString()!);
                await new Classifier(opts).EvaluateAsync("x", qs);
                Assert.NotEmpty(auths);
                var want = Str(c, "expectAuthorization");
                foreach (var got in auths) Assert.True(want == got, $"{name}: Authorization = {got ?? "<absent>"}, want {want ?? "<absent>"}");
            }
            finally
            {
                foreach (var (k, v) in saved) Environment.SetEnvironmentVariable(k, v);
            }
        }
        foreach (var c in fx.GetProperty("hostCases").EnumerateArray())
            Assert.Equal(Str(c, "expectKeyEnv"),
                ClassifierBackends.KeyEnv(ClassifierBackends.Parse(Str(c, "backend")!), Str(c, "baseUrl")));
    }

    // ---------------------------------------------------------------- cache

    [Fact]
    public void CacheKeysFixture()
    {
        foreach (var c in Load("cache-keys.json").GetProperty("cases").EnumerateArray())
        {
            IReadOnlyDictionary<string, object?>? assets = null;
            if (c.TryGetProperty("assets", out var a) && a.ValueKind == JsonValueKind.Object)
                assets = a.EnumerateObject().ToDictionary(p => p.Name, p => (object?)p.Value.Clone());
            Assert.Equal(Str(c, "modelKey"), Classifier.ModelCacheKey(Str(c, "endpoint")!, Str(c, "model")!));
            Assert.Equal(Str(c, "decisionKey"), Classifier.DecisionCacheKey(Str(c, "endpoint")!, Str(c, "served")!,
                c.GetProperty("state").Clone(), assets, Qs(c.GetProperty("questions"))));
        }
    }

    [Fact]
    public async Task StateBuilderCachesAndReportsIt()
    {
        var f = new Fake(NoulReply);
        var events = new List<MetricEvent>();
        var opts = Opts(f);
        opts.Cache = new MemoryCache(TimeSpan.FromHours(1), 100);
        opts.OnMetric = e => { lock (events) events.Add(e); };
        var cl = new Classifier(opts);
        var q = Judge.Noul("q", "Is the object red?");
        var d1 = await cl.State("a red mug").EvaluateAsync(q);
        Assert.False(d1.Cached);
        var d2 = await cl.State("a red mug").EvaluateAsync(q);
        Assert.True(d2.Cached);
        Assert.Single(f.Bodies);
        Assert.True(events[1].Cached);
        Assert.Equal(1, events[1].Questions);
        Assert.False(string.IsNullOrEmpty(events[1].QuestionsHash));
        await cl.State("a blue mug").EvaluateAsync(q);
        Assert.Equal(2, f.Bodies.Count);
        await Assert.ThrowsAsync<ClassifierException>(() => cl.State("x").EvaluateAsync(q, q));
        Assert.Equal(2, f.Bodies.Count);
    }

    [Fact]
    public async Task InvalidRepliesAreNeverCached()
    {
        var f = new Fake("""{"model":"m","answers":{"q":{"type":"noul","noul":1.5}}}""");
        var opts = Opts(f);
        opts.Cache = new MemoryCache();
        var events = new List<MetricEvent>();
        opts.OnMetric = events.Add;
        var cl = new Classifier(opts);
        var qs = new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "x" } };
        await cl.EvaluateAsync("s", qs); // plain evaluate: returned as before
        await cl.EvaluateAsync("s", qs);
        Assert.Equal(2, f.Bodies.Count);
        var e = await Assert.ThrowsAsync<ClassifierException>(() => cl.State("s").EvaluateAsync(Judge.Noul("q", "x")));
        Assert.StartsWith("classifier: invalid decision: answer \"q\"", e.Message);
        Assert.Equal("error", events[^1].Status);
        Assert.Equal(e.Message, events[^1].Error);
    }

    [Fact]
    public async Task CacheFollowsTheServedModel()
    {
        var served = "jev-1.13.0";
        var f = new Fake((_, _) => (200, "{\"model\":\"" + served + "\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.5}}}"));
        var cache = new MemoryCache();
        var opts = Opts(f);
        opts.Cache = cache;
        var cl = new Classifier(opts);
        await cl.State("s").EvaluateAsync(Judge.Noul("q", "x"));
        Assert.Equal("jev-1.13.0", await cache.GetAsync(Classifier.ModelCacheKey("https://fake.test/v1", Classifier.DefaultModel)));
        served = "jev-1.14.0";
        await cl.State("other").EvaluateAsync(Judge.Noul("q", "x"));
        var d = await cl.State("s").EvaluateAsync(Judge.Noul("q", "x"));
        Assert.False(d.Cached);
        Assert.Equal(3, f.Bodies.Count);
        Assert.Equal("jev-1.14.0", d.Model);
    }

    [Fact]
    public async Task MemoryCacheTtlAndMax()
    {
        var now = DateTimeOffset.UnixEpoch;
        var c = new MemoryCache(TimeSpan.FromSeconds(10), 2) { Now = () => now };
        await c.SetAsync("a", "1");
        await c.SetAsync("b", "2");
        await c.SetAsync("c", "3");
        Assert.Null(await c.GetAsync("a")); // oldest out first
        Assert.Equal("2", await c.GetAsync("b"));
        now = now.AddSeconds(11);
        Assert.Null(await c.GetAsync("c"));
    }

    // ---------------------------------------------------------------- batch

    [Fact]
    public async Task StatesBatchIsPerItem()
    {
        var f = new Fake((_, body) => body.Contains("\"state\":\"bad\"") ? (400, "{\"error\":\"nope\"}") : (200, NoulReply));
        var cl = new Classifier(Opts(f));
        var rs = await cl.States("a", "bad", "c").EvaluateAsync(Judge.Noul("q", "Is the object red?"));
        Assert.Equal(3, rs.Count);
        Assert.Null(rs[0].Error);
        Assert.Null(rs[2].Error);
        Assert.NotNull(rs[1].Error);
        Assert.Null(rs[1].Decision);
        Assert.Equal("jev-1.14.0", rs[0].Decision!.Model);
        Assert.Empty(await cl.States().EvaluateAsync(Judge.Noul("q", "x")));
        await Assert.ThrowsAsync<ClassifierException>(() => cl.States("a").EvaluateAsync(Judge.Noul("q", "x"), Judge.Noul("q", "y")));
        Assert.Equal(3, f.Bodies.Count);
    }

    // ---------------------------------------------------------------- V1 / T1 / C1

    [Fact]
    public void ValidateCasesFixture()
    {
        foreach (var c in Load("validate-cases.json").GetProperty("cases").EnumerateArray())
        {
            var d = Decision.FromJson(c.GetProperty("response").GetRawText());
            var err = Classifier.ValidateDecision(Qs(c.GetProperty("questions")), d);
            var name = Str(c, "name");
            Assert.True(c.GetProperty("valid").GetBoolean() == (err == null), $"{name}: {err?.Message}");
            if (err != null)
            {
                Assert.StartsWith("classifier: invalid decision: ", err.Message);
                Assert.Contains("\"" + Str(c, "key") + "\"", err.Message);
            }
        }
    }

    [Fact]
    public void ThresholdsFixture()
    {
        var fx = Load("thresholds-cases.json");
        var def = fx.GetProperty("default");
        Assert.Equal(new Thresholds(def.GetProperty("yes").GetDouble(), def.GetProperty("no").GetDouble(),
            def.GetProperty("minConfidence").GetDouble()), Thresholds.Default);
        foreach (var c in fx.GetProperty("cases").EnumerateArray())
        {
            var a = Decision.FromJson("{\"answers\":{\"a\":" + c.GetProperty("answer").GetRawText() + "}}").Answers["a"];
            var th = Thresholds.Default;
            if (c.TryGetProperty("thresholds", out var t))
                th = new Thresholds(t.GetProperty("yes").GetDouble(), t.GetProperty("no").GetDouble(), t.GetProperty("minConfidence").GetDouble());
            Assert.Equal(Str(c, "band"), th.Band(a));
            var bands = Toolnexus.Judge.Wrap(a);
            var bandsBand = a is NoulAnswer ? bands.Band : bands.Sure ? "yes" : "uncertain";
            Assert.Equal(Str(c, "bandsBand"), bandsBand);
        }
    }

    [Fact]
    public void ChunkQuestionsSplitsInAsciiOrder()
    {
        var qs = new Dictionary<string, Question>
        {
            ["c"] = new NoulQuestion { Instructions = "" }, ["a"] = new NoulQuestion { Instructions = "" }, ["b"] = new NoulQuestion { Instructions = "" },
        };
        var ch = Classifier.ChunkQuestions(qs, 2);
        Assert.Equal(2, ch.Count);
        Assert.Equal(new[] { "a", "b" }, ch[0].Keys.OrderBy(k => k, StringComparer.Ordinal));
        Assert.Equal(new[] { "c" }, ch[1].Keys);
        Assert.Throws<ClassifierException>(() => Classifier.ChunkQuestions(qs, 0));
    }

    // ---------------------------------------------------------------- raw / L1

    [Fact]
    public async Task RawQuestionAndAnswer()
    {
        var f = new Fake("""{"model":"jev-1.14.0","answers":{"spot":{"type":"box","box":[1,2,3,4]}}}""");
        var cl = new Classifier(Opts(f));
        var d = await cl.State("s").EvaluateAsync(Judge.Raw("spot", "box", "Where is the mug?"));
        Assert.Equal("""{"model":"jev-latest","questions":{"spot":{"instructions":"Where is the mug?","type":"box"}},"state":"s"}""", f.Bodies[0]);
        var ra = Assert.IsType<RawAnswer>(d.Answers["spot"]);
        Assert.Equal("box", ra.Type);
        Assert.Equal(JsonValueKind.Array, ra.Value.GetProperty("box").ValueKind);
        _ = Toolnexus.Judge.Questions(new[] { Judge.Raw("x", "", "y") });
        await Assert.ThrowsAsync<ClassifierException>(() =>
            cl.EvaluateAsync("s", new Dictionary<string, Question> { ["x"] = new RawQuestion { Type = "", Instructions = "y" } }));
    }

    [Fact]
    public async Task MetricHashMatchesTheBaseFixture()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(TestFixtures.Fixture(Path.Combine("judge", "base.json"))));
        var req = doc.RootElement.GetProperty("request");
        MetricEvent? ev = null;
        var cl = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Custom,
            Model = Str(req, "model"),
            Evaluate = (_, _, _) => Task.FromResult(new Decision { Model = "m" }),
            OnMetric = e => ev = e,
        });
        await cl.EvaluateAsync(req.GetProperty("state").Clone(), Qs(req.GetProperty("questions")));
        Assert.Equal(Str(doc.RootElement, "canonicalSha256"), ev!.QuestionsHash);
        Assert.Equal(3, ev.Questions);
        Assert.False(ev.Cached);
    }
}
