using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>
/// Conformance tests for SPEC.md §8B, run against the SHARED fixtures in <c>examples/judge/</c>.
/// The fixtures are the contract; nothing here re-derives what correct is believed to look like.
/// </summary>
public class ClassifierTests
{
    // ---------------------------------------------------------------- fixtures

    private sealed record JudgeFixture(
        string Name,
        string RequestModel,
        JsonElement? State,
        IReadOnlyDictionary<string, Question> Questions,
        string? Canonical,
        string? CanonicalSha256,
        int CanonicalBytes,
        string? ResponseJson,
        JsonElement? Expect,
        IReadOnlyList<JudgeFixture> Entries);

    private static JudgeFixture LoadFixture(string name)
    {
        var path = TestFixtures.Fixture(Path.Combine("judge", name + ".json"));
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        return Parse(doc.RootElement);
    }

    private static JudgeFixture Parse(JsonElement root)
    {
        var entries = new List<JudgeFixture>();
        if (root.TryGetProperty("entries", out var es) && es.ValueKind == JsonValueKind.Array)
            foreach (var e in es.EnumerateArray()) entries.Add(Parse(e));

        JsonElement? state = null;
        string model = "";
        IReadOnlyDictionary<string, Question> questions = new Dictionary<string, Question>();
        if (root.TryGetProperty("request", out var req))
        {
            if (req.TryGetProperty("model", out var m)) model = m.GetString() ?? "";
            if (req.TryGetProperty("state", out var s)) state = s.Clone();
            if (req.TryGetProperty("questions", out var qs)) questions = Questions(qs);
        }
        return new JudgeFixture(
            root.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "",
            model,
            state,
            questions,
            Str(root, "canonical"),
            Str(root, "canonicalSha256"),
            root.TryGetProperty("canonicalBytes", out var cb) ? cb.GetInt32() : 0,
            root.TryGetProperty("response", out var r) ? r.GetRawText() : null,
            root.TryGetProperty("expect", out var x) ? x.Clone() : null,
            entries);
    }

    private static string? Str(JsonElement o, string name)
        => o.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.String ? el.GetString() : null;

    /// <summary>
    /// Rebuilds the typed questions from a fixture's raw JSON. The absent-vs-empty distinction is
    /// preserved: a noul with no <c>criteria</c> key gets null, one with
    /// <c>{"true":"","false":""}</c> gets a <see cref="NoulCriteria"/> of empty strings, and the two
    /// produce different bytes.
    /// </summary>
    private static Dictionary<string, Question> Questions(JsonElement qs)
    {
        var out_ = new Dictionary<string, Question>();
        foreach (var p in qs.EnumerateObject())
        {
            var q = p.Value;
            var instructions = Str(q, "instructions") ?? "";
            var hasCriteria = q.TryGetProperty("criteria", out var criteria);
            out_[p.Name] = Str(q, "type") switch
            {
                "noul" => new NoulQuestion
                {
                    Instructions = instructions,
                    Criteria = hasCriteria
                        ? new NoulCriteria { True = Str(criteria, "true") ?? "", False = Str(criteria, "false") ?? "" }
                        : null,
                },
                "choice" => new ChoiceQuestion
                {
                    Instructions = instructions,
                    Criteria = criteria.EnumerateObject().ToDictionary(c => c.Name, c => c.Value.GetString() ?? ""),
                },
                "score" => new ScoreQuestion
                {
                    Instructions = instructions,
                    Criteria = criteria.EnumerateArray().Select(c => c.GetString() ?? "").ToList(),
                },
                var t => throw new InvalidOperationException($"question {p.Name}: unknown type {t}"),
            };
        }
        return out_;
    }

    /// <summary>A <c>static</c> classifier answering this fixture's own request.</summary>
    private static Classifier StaticFrom(JudgeFixture f, Action<ClassifierOptions>? tweak = null)
    {
        var opts = new ClassifierOptions
        {
            Style = ClassifierStyle.Static,
            Model = f.RequestModel,
            Decisions = new[]
            {
                new RecordedDecision { State = f.State, Questions = f.Questions, Response = f.ResponseJson! },
            },
        };
        tweak?.Invoke(opts);
        return new Classifier(opts);
    }

    private static string Sha256Hex(byte[] b) => Convert.ToHexString(SHA256.HashData(b)).ToLowerInvariant();

    // ---------------------------------------------------------------- the bytes

    /// <summary>The byte-identity claim: every canonical fixture, bytes AND sha256. System.Text.Json's
    /// default encoder escapes <c>'</c> and <c>&lt;&gt;&amp;</c>, so this is the test the relaxed
    /// encoder exists for — and its default key order is culture-sensitive, so it is also the test
    /// <see cref="StringComparer.Ordinal"/> exists for.</summary>
    [Theory]
    [InlineData("base")]
    [InlineData("hardened")]
    [InlineData("numbers")]
    [InlineData("wide")]
    [InlineData("degenerate")]
    [InlineData("near-uniform")]
    public void CanonicalBytesMatchTheFixture(string name)
    {
        var f = LoadFixture(name);
        var got = Classifier.CanonicalRequest(f.RequestModel, f.Questions);
        Assert.Equal(f.Canonical, Encoding.UTF8.GetString(got));
        Assert.Equal(f.CanonicalBytes, got.Length);
        Assert.Equal(f.CanonicalSha256, Sha256Hex(got));
    }

    /// <summary>The three guard entries share one questions payload and one hash, differing only in
    /// state — which is exactly why the static corpus must key on state too.</summary>
    [Fact]
    public void DecisionsFixtureEntriesAreByteExact()
    {
        var f = LoadFixture("decisions");
        Assert.Equal(3, f.Entries.Count);
        foreach (var e in f.Entries)
        {
            var got = Classifier.CanonicalRequest(e.RequestModel, e.Questions);
            Assert.Equal(e.Canonical, Encoding.UTF8.GetString(got));
            Assert.Equal(e.CanonicalSha256, Sha256Hex(got));
        }
    }

    /// <summary>An absent criteria and an empty one are DIFFERENT values, and both survive.</summary>
    [Fact]
    public void AbsentCriteriaIsNotEmptyCriteria()
    {
        var absent = Classifier.CanonicalRequestString("m",
            new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "i" } });
        var empty = Classifier.CanonicalRequestString("m",
            new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "i", Criteria = new NoulCriteria() } });

        Assert.DoesNotContain("criteria", absent);
        Assert.Contains("\"criteria\":{\"false\":\"\",\"true\":\"\"}", empty);
        Assert.NotEqual(absent, empty);
    }

    // ---------------------------------------------------------------- the parse

    [Fact]
    public async Task BaseFixtureParses()
    {
        var f = LoadFixture("base");
        var d = await StaticFrom(f).EvaluateAsync(f.State, f.Questions);

        Assert.True(d.Calibrated);
        Assert.Equal("typesafe/jev-1.13-20260917", d.Model);

        var ch = d.Choice("department");
        Assert.Equal("shipping", ch.Choice);
        Assert.Equal(0.41, ch.Confidence);
        Assert.False(ch.NearUniform);
        // A zero probability stays an ENTRY — it is an offered option the model ruled out, not an
        // option that was never offered.
        Assert.True(ch.Probabilities.TryGetValue("technical", out var zero));
        Assert.Equal(0.0, zero);

        Assert.Equal(0.98, d.Noul("is_refund_request").Noul);

        var s = d.Score("urgency");
        Assert.Equal(1.21, s.Score);
        Assert.Equal(0.57, s.Confidence);
        Assert.Equal(new[] { "routine", "elevated", "urgent" }, s.Levels());

        // A wrong-type read throws with a clear message, never returns a default.
        var wrongType = Assert.Throws<ClassifierException>(() => d.Noul("department"));
        Assert.Contains("department", wrongType.Message);
        Assert.Contains("choice", wrongType.Message);
        Assert.Throws<ClassifierException>(() => d.Choice("nope"));
    }

    /// <summary>Numbers are compared NUMERICALLY, never as strings: 0 vs 0.0 and 1.6716e-5 vs
    /// 0.000016716 are the same value and different bytes.</summary>
    [Fact]
    public async Task NumbersFixtureParsesNumerically()
    {
        var f = LoadFixture("numbers");
        var d = await StaticFrom(f).EvaluateAsync(f.State, f.Questions);

        var s = d.Score("urgency");
        Assert.Equal(1.21, s.Score);
        Assert.Equal(0.04, s.Probabilities["0"]);
        Assert.Equal(0.0, d.Noul("is_expensive").Noul);
        Assert.Equal(1.6716e-05, d.Usage.Cost!.Value);
    }

    /// <summary>40 keys: above the width at which some runtimes stop iterating small maps in term
    /// order. The request bytes are covered by <see cref="CanonicalBytesMatchTheFixture"/>; the
    /// response side is checked here.</summary>
    [Fact]
    public async Task WideFixtureParses()
    {
        var f = LoadFixture("wide");
        var d = await StaticFrom(f).EvaluateAsync(f.State, f.Questions);

        var ch = d.Choice("skill");
        Assert.Equal("skill_07", ch.Choice);
        Assert.Equal(40, ch.Probabilities.Count);
        Assert.False(ch.NearUniform);
    }

    // ---------------------------------------------------------------- nearUniform

    [Fact]
    public async Task NearUniformMatchesTheFixture()
    {
        var f = LoadFixture("near-uniform");
        var expect = f.Expect!.Value;
        // The tolerance is pinned by the fixture, not chosen by the port.
        Assert.Equal(Classifier.NearUniformTolerance, expect.GetProperty("tolerance").GetDouble());

        var d = await StaticFrom(f).EvaluateAsync(f.State, f.Questions);
        foreach (var want in expect.GetProperty("answers").EnumerateObject())
        {
            var ch = d.Choice(want.Name);
            Assert.Equal(want.Value.GetProperty("nearUniform").GetBoolean(), ch.NearUniform);
            // The deviation the fixture records is the one the rule is decided on.
            var target = 1.0 / ch.Probabilities.Count;
            var max = ch.Probabilities.Values.Select(p => Math.Abs(p - target)).Max();
            Assert.True(Math.Abs(max - want.Value.GetProperty("maxDeviation").GetDouble()) < 1e-9,
                $"{want.Name}: maxDeviation = {max}");
        }
    }

    /// <summary>n == 1 is trivially uniform; an empty map has no distribution at all. The boundary
    /// itself is pinned by near-uniform.json at 0.0499 / 0.0501, 1e-4 either side — a deviation of
    /// EXACTLY 0.05 is not representable in binary floating point, which is why the fixtures stay
    /// clear of it and no port needs an epsilon.</summary>
    [Theory]
    [InlineData(0.5499, 0.4501, true)]
    [InlineData(0.5501, 0.4499, false)]
    public void NearUniformBoundaryIsInclusiveAndAbsolute(double a, double b, bool want)
        => Assert.Equal(want, Classifier.NearUniform(new Dictionary<string, double> { ["a"] = a, ["b"] = b }));

    [Fact]
    public void NearUniformDegenerateSizes()
    {
        Assert.True(Classifier.NearUniform(new Dictionary<string, double> { ["only"] = 1 }));
        Assert.False(Classifier.NearUniform(new Dictionary<string, double>()));
    }

    // ---------------------------------------------------------------- degenerate

    /// <summary>The WARNING is the assertion — and the bytes are unchanged by it.</summary>
    [Fact]
    public async Task DegenerateCriteriaWarnOncePerKeyAndChangeNothing()
    {
        var f = LoadFixture("degenerate");
        var expect = f.Expect!.Value;
        var warned = new List<string>();

        var c = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Custom,
            Model = f.RequestModel,
            Evaluate = (_, _, _) => Task.FromResult(new Decision { Model = f.RequestModel }),
            OnMetric = ev =>
            {
                if (ev.Event != Classifier.MetricWarning) return;
                warned.Add(ev.Question!);
                Assert.Null(ev.Error); // a warning is advisory, never a failure
                Assert.Contains(ev.Question!, ev.Warning!); // the warning NAMES the key
            },
        });

        // Twice: detection is ONCE PER QUESTION KEY, so a per-turn judge does not flood the sink.
        await c.EvaluateAsync(f.State, f.Questions);
        await c.EvaluateAsync(f.State, f.Questions);

        var want = expect.GetProperty("warnings").EnumerateArray().Select(e => e.GetString()!).ToList();
        warned.Sort(StringComparer.Ordinal);
        Assert.Equal(want, warned);
        foreach (var ok in expect.GetProperty("noWarning").EnumerateArray())
            Assert.DoesNotContain(ok.GetString(), warned);

        // Detection, never repair: the bytes are the bytes the same questions produce with detection
        // switched off.
        Assert.Equal(f.CanonicalSha256, Sha256Hex(Classifier.CanonicalRequest(f.RequestModel, f.Questions)));
    }

    [Theory]
    [InlineData(new[] { "a", "", "b", "" }, true)]                      // all empty
    [InlineData(new[] { "a", "a", "b", "b" }, true)]                    // all equal their own key
    [InlineData(new[] { "a", "same", "b", "same" }, true)]              // all identical
    [InlineData(new[] { "a", "one thing", "b", "another" }, false)]     // described
    [InlineData(new[] { "a", "", "b", "described" }, false)]            // one empty is not all empty
    [InlineData(new[] { "only", "only" }, false)]                       // n == 1 is never reported
    public void DegeneratePredicate(string[] flat, bool degenerate)
    {
        var map = new Dictionary<string, string>();
        for (var i = 0; i < flat.Length; i += 2) map[flat[i]] = flat[i + 1];
        Assert.Equal(degenerate, Classifier.DegenerateReason(map) != null);
    }

    // ---------------------------------------------------------------- the static backend

    /// <summary>The three guard bands: one questions payload, one hash, three states. A corpus keyed
    /// on the canonical request alone would return the same band three times.</summary>
    [Fact]
    public async Task StaticBackendDistinguishesTheGuardBands()
    {
        var f = LoadFixture("decisions");
        var c = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Static,
            Model = f.Entries[0].RequestModel,
            Decisions = f.Entries
                .Select(e => new RecordedDecision { State = e.State, Questions = e.Questions, Response = e.ResponseJson! })
                .ToList(),
        });

        var wantScores = new[] { 0.02, 2.25, 2.97 };
        for (var i = 0; i < f.Entries.Count; i++)
        {
            var d = await c.EvaluateAsync(f.Entries[i].State, f.Entries[i].Questions);
            Assert.Equal(wantScores[i], d.Score("risk").Score);
        }

        // An unrecorded state is an error, never a guess at a neighbouring band.
        var e = await Assert.ThrowsAsync<ClassifierException>(() =>
            c.EvaluateAsync(JsonDocument.Parse("{\"command\":\"unseen\"}").RootElement.Clone(), f.Entries[0].Questions));
        Assert.Contains("no recorded decision", e.Message);
    }

    // ---------------------------------------------------------------- retry rule

    /// <summary>A handler that answers the given status once, then the fixture's response.
    /// What keeps these tests off the clock is <c>RetryBaseMs</c> on the options, not a faked
    /// <c>Retry-After</c> header — the wait is configured, not routed around.</summary>
    private sealed class FailThenSucceedHandler : HttpMessageHandler
    {
        private readonly int _status;
        private readonly string _body;
        public int Calls;

        public FailThenSucceedHandler(int status, string body) { _status = status; _body = body; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var n = Interlocked.Increment(ref Calls);
            var res = n == 1
                ? new HttpResponseMessage((System.Net.HttpStatusCode)_status) { Content = new StringContent("overloaded") }
                : new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent(_body) };
            return Task.FromResult(res);
        }
    }

    private sealed class AlwaysHandler : HttpMessageHandler
    {
        private readonly int _status;
        public int Calls;

        public AlwaysHandler(int status) { _status = status; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Calls);
            return Task.FromResult(new HttpResponseMessage((System.Net.HttpStatusCode)_status)
            {
                Content = new StringContent("unprocessable"),
            });
        }
    }

    private static readonly int[] Cloudflare = { 520, 521, 522, 523, 524, 525, 526, 527 };

    private Classifier Judge(HttpMessageHandler handler, IReadOnlyCollection<int>? extra = null,
        Func<LlmClient.ErrorInfo, LlmClient.Tier>? onError = null, string? model = null) =>
        new(new ClassifierOptions
        {
            BaseUrl = "https://gateway.example/v1",
            Model = model,
            ApiKeyEnv = "TEST_JUDGE_UNSET",
            HttpHandler = handler,
            Retries = 2,
            RetryBaseMs = 1, // the rule under test is the status set, not the wait
            RetryableStatuses = extra,
            OnError = onError,
        });

    /// <summary>The default set is an ENUMERATION: 529 (TypeSafe's "retry with backoff") is in it,
    /// an unlisted 5xx (520) and a permanently-broken one (501) are terminal. A host widens it
    /// declaratively with <c>RetryableStatuses</c>, which ADDS and never replaces — 429 keeps
    /// retrying (and keeps <c>Retry-After</c> with it) while 501 stays terminal.</summary>
    [Theory]
    [InlineData(529, null, true)]
    [InlineData(520, null, false)]
    [InlineData(501, null, false)]
    [InlineData(520, "cf", true)]
    [InlineData(429, "cf", true)]
    [InlineData(501, "cf", false)]
    [InlineData(422, null, false)]
    public async Task RetryableStatusMatrix(int status, string? extra, bool retried)
    {
        var set = extra is null ? null : Cloudflare;
        if (retried)
        {
            var f = LoadFixture("base");
            var handler = new FailThenSucceedHandler(status, f.ResponseJson!);
            await Judge(handler, set, model: f.RequestModel).EvaluateAsync(f.State, f.Questions);
            Assert.Equal(2, handler.Calls);
        }
        else
        {
            var handler = new AlwaysHandler(status);
            var c = Judge(handler, set);
            var e = await Assert.ThrowsAsync<ClassifierException>(() =>
                c.EvaluateAsync("s", new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "?" } }));
            Assert.Contains(status.ToString(), e.Message);
            Assert.Equal(1, handler.Calls);
        }
    }

    /// <summary><c>RetryBaseMs</c> is the classifier's half of the §8
    /// <c>ClientOptions.RetryBaseMs</c> it mirrors: the same default (500) and the same
    /// <c>base * 2^attempt</c> shape. The default is asserted as a LOWER bound on a real wait,
    /// because an unset option that silently became 1 ms would pass every other test faster.
    /// No <c>Retry-After</c> is sent — that header wins over backoff and would hide the rule.</summary>
    [Fact]
    public async Task RetryBaseMsScalesTheBackoffAndDefaultsTo500()
    {
        async Task<long> WaitedAsync(int? retryBaseMs)
        {
            var f = LoadFixture("base");
            var handler = new FailThenSucceedHandler(503, f.ResponseJson!);
            var c = new Classifier(new ClassifierOptions
            {
                BaseUrl = "https://gateway.example/v1",
                Model = f.RequestModel,
                ApiKeyEnv = "TEST_JUDGE_UNSET",
                HttpHandler = handler,
                Retries = 2,
                RetryBaseMs = retryBaseMs,
            });
            var sw = System.Diagnostics.Stopwatch.StartNew();
            await c.EvaluateAsync(f.State, f.Questions);
            sw.Stop();
            Assert.Equal(2, handler.Calls);
            return sw.ElapsedMilliseconds;
        }

        Assert.True(await WaitedAsync(null) >= 450, "an unset base must still back off ~500ms");
        Assert.True(await WaitedAsync(1) < 200, "RetryBaseMs = 1 must not wait ~500ms");
    }

    /// <summary><c>RetryableStatuses</c> sets the DEFAULT classification only — <c>OnError</c> runs
    /// per attempt and has the final say, so "fail" overrides a status the host itself listed.</summary>
    [Fact]
    public async Task OnErrorFailOverridesAListedStatus()
    {
        var handler = new AlwaysHandler(520);
        var c = Judge(handler, Cloudflare, onError: info =>
        {
            Assert.True(info.Retryable);
            return LlmClient.Tier.Fail;
        });

        await Assert.ThrowsAsync<ClassifierException>(() =>
            c.EvaluateAsync("s", new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "?" } }));
        Assert.Equal(1, handler.Calls);
    }

    // ---------------------------------------------------------------- absent cost

    private sealed class FixedBodyHandler : HttpMessageHandler
    {
        private readonly string _body;
        public FixedBodyHandler(string body) { _body = body; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent(_body) });
    }

    /// <summary>TypeSafe's own API returns model/answers/usage and no <c>cost</c> key at all.
    /// Reporting 0 there would read as "this call was free" when the truth is "this backend does
    /// not say".</summary>
    [Fact]
    public async Task AnAbsentCostIsNullNotZero()
    {
        var handler = new FixedBodyHandler(
            """{"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":0.98}},"usage":{"input_tokens":331,"output_tokens":48}}""");
        var c = new Classifier(new ClassifierOptions
        {
            BaseUrl = "https://api.typesafe.ai/v1",
            Model = "jev-latest",
            ApiKeyEnv = "TEST_JUDGE_UNSET",
            HttpHandler = handler,
        });

        var d = await c.EvaluateAsync("s", new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "?" } });
        Assert.Equal(331, d.Usage.InputTokens);
        Assert.Null(d.Usage.Cost);
    }

    // ---------------------------------------------------------------- limits

    /// <summary>A handler that fails loudly if anything reaches the wire.</summary>
    private sealed class NoRequestHandler : HttpMessageHandler
    {
        public int Calls;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Calls);
            throw new InvalidOperationException("no request should have been sent");
        }
    }

    /// <summary>Limits are enforced CLIENT-SIDE, before the request: no HTTP call is made, and the
    /// error names the offending question key and the limit.</summary>
    [Fact]
    public async Task LimitsAreRejectedPreFlightWithNoHttpCall()
    {
        var handler = new NoRequestHandler();
        var tooMany = new Dictionary<string, string>();
        for (var i = 0; i <= Classifier.MaxChoiceOptions; i++) tooMany[$"opt_{i:D3}"] = $"description {i}";

        var cases = new (Question Q, string Want)[]
        {
            (new ChoiceQuestion { Instructions = "?", Criteria = tooMany }, "1..255 options"),
            (new ChoiceQuestion { Instructions = "?" }, "1..255 options"),
            (new ScoreQuestion { Instructions = "?", Criteria = new[] { "only" } }, "2..10 ordered levels"),
            (new ScoreQuestion { Instructions = "?", Criteria = Enumerable.Repeat("x", 11).ToList() }, "2..10 ordered levels"),
        };

        foreach (var (q, want) in cases)
        {
            var c = new Classifier(new ClassifierOptions { HttpHandler = handler });
            var e = await Assert.ThrowsAsync<ClassifierException>(() =>
                c.EvaluateAsync("s", new Dictionary<string, Question> { ["the_key"] = q }));
            Assert.Contains("the_key", e.Message);
            Assert.Contains(want, e.Message);
        }
        Assert.Equal(0, handler.Calls);
    }

    // ---------------------------------------------------------------- secrets

    /// <summary>No credential value and no expanded header value reaches any log, metric, error
    /// message or returned value — INCLUDING when the backend reflects them back in its own error
    /// body, which a real gateway happily does.</summary>
    [Fact]
    public async Task NoCredentialOrExpandedHeaderLeaks()
    {
        const string key = "sk-live-NEVER-IN-AN-ERROR";
        const string tenant = "tenant-NEVER-IN-AN-ERROR";
        Environment.SetEnvironmentVariable("TEST_JUDGE_KEY", key);
        Environment.SetEnvironmentVariable("TEST_JUDGE_TENANT", tenant);
        try
        {
            string? sawAuth = null, sawTenant = null;
            using var srv = new StubServer(ctx =>
            {
                sawAuth = ctx.Request.Headers["Authorization"];
                sawTenant = ctx.Request.Headers["X-Tenant"];
                StubServer.Respond(ctx, 401, $"{{\"error\":\"bad credential {sawAuth} for {sawTenant}\"}}");
            });

            var events = new List<MetricEvent>();
            var c = new Classifier(new ClassifierOptions
            {
                BaseUrl = srv.BaseUrl,
                ApiKeyEnv = "TEST_JUDGE_KEY",
                Headers = new Dictionary<string, string> { ["X-Tenant"] = "${TEST_JUDGE_TENANT}" },
                Retries = 1,
                OnMetric = events.Add,
            });

            var e = await Assert.ThrowsAsync<ClassifierException>(() =>
                c.EvaluateAsync("s", new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "?" } }));

            // The credential and the header DID reach the wire (they are use-only, not unused) …
            Assert.Equal("Bearer " + key, sawAuth);
            Assert.Equal(tenant, sawTenant);

            // … and nowhere else.
            var haystacks = new List<string> { e.Message, e.ToString() };
            haystacks.AddRange(events.Select(ev => $"{ev.Error}{ev.Model}{ev.Question}{ev.Status}"));
            foreach (var h in haystacks)
                foreach (var secret in new[] { key, tenant, "NEVER-IN-AN-ERROR" })
                    Assert.DoesNotContain(secret, h);

            // An auth failure names the status and the endpoint, and nothing else.
            Assert.Contains("401", e.Message);
            Assert.Contains(srv.BaseUrl, e.Message);
        }
        finally
        {
            Environment.SetEnvironmentVariable("TEST_JUDGE_KEY", null);
            Environment.SetEnvironmentVariable("TEST_JUDGE_TENANT", null);
        }
    }

    /// <summary>A backend's own limit error is surfaced with its reported cause intact, so a caller
    /// can tell a limit from a transport fault.</summary>
    [Fact]
    public async Task BackendCauseSurvivesIntact()
    {
        using var srv = new StubServer(ctx =>
            StubServer.Respond(ctx, 400, "{\"error\":\"Too many choices. Must have at most 255 choices.\"}"));

        var c = new Classifier(new ClassifierOptions { BaseUrl = srv.BaseUrl, ApiKeyEnv = "TEST_JUDGE_UNSET" });
        var e = await Assert.ThrowsAsync<ClassifierException>(() =>
            c.EvaluateAsync("s", new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "?" } }));
        Assert.Contains("Too many choices", e.Message);
    }

    // ---------------------------------------------------------------- metrics

    [Fact]
    public async Task EvaluateEmitsAnOkMetric()
    {
        var f = LoadFixture("base");
        var events = new List<MetricEvent>();
        var c = StaticFrom(f, o => o.OnMetric = events.Add);
        await c.EvaluateAsync(f.State, f.Questions);

        var ev = Assert.Single(events);
        Assert.Equal(Classifier.MetricEvaluate, ev.Event);
        Assert.Equal("ok", ev.Status);
        Assert.Equal("typesafe/jev-1.13-20260917", ev.Model);
        Assert.Equal(398, ev.PromptTokens);
        Assert.Equal(72, ev.CompletionTokens);
    }

    /// <summary>Neither classifier event is folded into the Prometheus registry, so the rendered
    /// metrics text stays byte-identical.</summary>
    [Fact]
    public void ClassifierEventsAreNotInThePrometheusText()
    {
        var client = LlmClient.Create(new LlmClient.Options { BaseUrl = "http://127.0.0.1:1", Model = "m", ApiKey = "k" });
        var before = client.Metrics();
        Assert.DoesNotContain("classifier", before);
    }

    // ---------------------------------------------------------------- construction

    [Fact]
    public void ConstructionRejectsIncompleteStylesAndAppliesDefaults()
    {
        Assert.Throws<ClassifierException>(() => new Classifier(new ClassifierOptions { Style = ClassifierStyle.Llm }));
        Assert.Throws<ClassifierException>(() => new Classifier(new ClassifierOptions { Style = ClassifierStyle.Custom }));
        Assert.Throws<ClassifierException>(() => new Classifier(new ClassifierOptions { Style = (ClassifierStyle)99 }));

        var c = new Classifier();
        Assert.Equal(ClassifierStyle.SystemOne, c.Style);
        Assert.Equal(Classifier.DefaultBaseUrl, c.BaseUrl);
        Assert.Equal(Classifier.DefaultModel, c.Model);
        Assert.Equal(Classifier.DefaultApiKeyEnv, c.ApiKeyEnv);
        Assert.Equal(Classifier.DefaultTimeout, c.Timeout);
    }

    // ---------------------------------------------------------------- non-breaking

    /// <summary>Proves the non-breaking claim rather than asserting it: the request body the client
    /// loop sends is byte-identical whether or not a <see cref="Classifier"/> exists in the same
    /// process (SPEC §8B).</summary>
    [Fact]
    public async Task ConstructingAClassifierChangesNoClientRequest()
    {
        async Task<string> CaptureAsync()
        {
            string body = "";
            using var srv = new StubServer(ctx =>
            {
                using (var r = new StreamReader(ctx.Request.InputStream)) body = r.ReadToEnd();
                StubServer.Respond(ctx, 200, "{\"choices\":[{\"message\":{\"content\":\"done\"}}],\"usage\":{}}");
            });
            var client = LlmClient.Create(new LlmClient.Options { BaseUrl = srv.BaseUrl, Model = "m", ApiKey = "k" });
            await client.RunAsync("hello", await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false }));
            return body;
        }

        var without = await CaptureAsync();

        // Construct one, and exercise it, before capturing again.
        var c = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Custom,
            Evaluate = (_, _, _) => Task.FromResult(new Decision { Model = "m" }),
        });
        await c.EvaluateAsync("s", new Dictionary<string, Question> { ["q"] = new NoulQuestion { Instructions = "?" } });

        var with = await CaptureAsync();
        Assert.Equal(without, with);
    }
}
