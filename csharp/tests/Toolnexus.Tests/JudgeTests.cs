using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>§8B Simple judgments (add-judge-adapters), against examples/judge/adapters/.</summary>
public class JudgeTests
{
    private static JsonElement Load(string name)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(TestFixtures.Fixture(Path.Combine("judge", "adapters", name))));
        return doc.RootElement.Clone();
    }

    private static Ask FromFixture(JsonElement q)
    {
        var name = q.GetProperty("name").GetString()!;
        var ins = q.GetProperty("instructions").GetString()!;
        return q.GetProperty("kind").GetString() switch
        {
            "noul" => Judge.Noul(name, ins),
            "choice" => Judge.Choice(name, ins, q.GetProperty("options").EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetString()!)),
            "score" => Judge.Score(name, ins, q.GetProperty("levels").EnumerateArray().Select(e => e.GetString()!).ToArray()),
            var k => throw new InvalidOperationException(k),
        };
    }

    private static string Sorted(JsonElement e) => e.ValueKind switch
    {
        JsonValueKind.Object => "{" + string.Join(",", e.EnumerateObject().OrderBy(p => p.Name, StringComparer.Ordinal).Select(p => JsonSerializer.Serialize(p.Name) + ":" + Sorted(p.Value))) + "}",
        JsonValueKind.Array => "[" + string.Join(",", e.EnumerateArray().Select(Sorted)) + "]",
        _ => e.GetRawText(),
    };
    private static string Sorted(object? v) => Sorted(JsonSerializer.SerializeToElement(v));

    [Fact]
    public void StateCases_AllHold()
    {
        var cases = Load("state-cases.json").GetProperty("cases").EnumerateArray().ToList();
        Assert.True(cases.Count >= 5);
        foreach (var c in cases)
        {
            var asks = c.GetProperty("questions").EnumerateArray().Select(FromFixture).ToList();
            if (c.TryGetProperty("wantError", out var we))
            {
                var ex = Assert.Throws<ClassifierException>(() => Judge.Questions(asks));
                Assert.Contains(we.GetString()!, ex.Message);
                continue;
            }
            object state;
            if (c.TryGetProperty("context", out var cx))
                state = Judge.Context(cx.GetProperty("context").GetString()!, cx.GetProperty("message").GetString()!,
                    cx.GetProperty("extra").EnumerateObject().ToDictionary(p => p.Name, p => (object?)p.Value.GetString()));
            else if (c.TryGetProperty("roleState", out var rs))
            {
                var d = rs.GetProperty("data");
                object? data = d.ValueKind == JsonValueKind.Object
                    ? d.EnumerateObject().ToDictionary(p => p.Name, p => (object?)p.Value.GetString())
                    : d.GetString();
                state = Judge.State(rs.GetProperty("role").GetString()!, data);
            }
            else
            {
                var st = c.GetProperty("state");
                var role = st.GetProperty("role").GetString()!;
                var data = st.EnumerateObject().Where(p => p.Name != "role").ToDictionary(p => p.Name, p => (object?)p.Value.GetString());
                state = Judge.State(role, data);
            }
            Assert.Equal(Sorted(c.GetProperty("wantState")), Sorted(state));
            var wire = Judge.Questions(asks).ToDictionary(kv => kv.Key, kv => kv.Value.ToWire());
            Assert.Equal(Sorted(c.GetProperty("wantQuestions")), Sorted(wire));
        }
    }

    [Fact]
    public void State_WrapsNonObject()
    {
        Assert.Equal(Sorted(new Dictionary<string, object?> { ["role"] = "r", ["data"] = "plain text" }), Sorted(Judge.State("r", "plain text")));
    }

    [Fact]
    public void Builders_AreByteIdenticalToHandWritten()
    {
        var built = Judge.Questions(new[]
        {
            Judge.Noul("is_appropriate", "Is it ok?"),
            Judge.Choice("component", "Which?", new Dictionary<string, string> { ["a"] = "alpha", ["b"] = "beta" }),
            Judge.Score("fix", "How fixable?", "no", "yes"),
        });
        var hand = new Dictionary<string, Question>
        {
            ["fix"] = new ScoreQuestion { Instructions = "How fixable?", Criteria = new[] { "no", "yes" } },
            ["component"] = new ChoiceQuestion { Instructions = "Which?", Criteria = new Dictionary<string, string> { ["b"] = "beta", ["a"] = "alpha" } },
            ["is_appropriate"] = new NoulQuestion { Instructions = "Is it ok?" },
        };
        Assert.Equal(Classifier.CanonicalRequest("jev-latest", hand), Classifier.CanonicalRequest("jev-latest", built));
    }

    private static (Classifier C, List<Ask> Asks) GateFixture(JsonElement root, JsonElement c, object state)
    {
        var qs = root.GetProperty("questions");
        var asks = qs.EnumerateObject().Select(p =>
        {
            var ins = p.Value.GetProperty("instructions").GetString()!;
            var cr = p.Value.GetProperty("criteria");
            return p.Value.GetProperty("type").GetString() switch
            {
                "noul" => new Ask(p.Name, new NoulQuestion { Instructions = ins, Criteria = new NoulCriteria { True = cr.GetProperty("true").GetString()!, False = cr.GetProperty("false").GetString()! } }),
                "choice" => Judge.Choice(p.Name, ins, cr.EnumerateObject().ToDictionary(o => o.Name, o => o.Value.GetString()!)),
                _ => Judge.Score(p.Name, ins, cr.EnumerateArray().Select(e => e.GetString()!).ToArray()),
            };
        }).ToList();
        var response = "{\"model\":\"jev-latest\",\"answers\":" + c.GetProperty("answers").GetRawText() + "}";
        var cls = Classifier.FromRecorded(new[] { new RecordedDecision { State = state, Questions = Judge.Questions(asks), Response = response } });
        return (cls, asks);
    }

    private static List<Rule> Rules(JsonElement root) => root.GetProperty("rules").EnumerateArray().Select(r =>
    {
        var q = r.GetProperty("question").GetString()!;
        var a = r.GetProperty("action").GetString()!;
        var t = r.TryGetProperty("target", out var tt) ? tt.GetString()! : "";
        if (r.TryGetProperty("below", out var b)) return Rule.Below(q, b.GetDouble(), a, t);
        if (r.TryGetProperty("at_least", out var al)) return Rule.AtLeast(q, al.GetDouble(), a, t);
        return Rule.Is(q, r.GetProperty("is").GetString()!, a, t);
    }).ToList();

    [Fact]
    public async Task GateCases_AllHold()
    {
        var root = Load("gate-cases.json");
        var topRules = Rules(root);
        var n = 0;
        foreach (var c in root.GetProperty("cases").EnumerateArray())
        {
            var state = Judge.Context("triage bugs", "case " + c.GetProperty("name").GetString());
            var (cls, asks) = GateFixture(root, c, state);
            var rules = c.TryGetProperty("rules", out _) ? Rules(c) : topRules;
            Bands? bands = c.GetProperty("bands").ValueKind == JsonValueKind.Null ? null
                : new Bands(c.GetProperty("bands").GetProperty("low").GetDouble(), c.GetProperty("bands").GetProperty("high").GetDouble());
            var o = c.TryGetProperty("policy", out var pol)
                ? await Judge.GateAsync(cls, state, asks, new Policy { Rules = rules, Bands = bands,
                    Default = pol.GetProperty("default").GetString()!, SkipUncertain = pol.GetProperty("skipUncertain").GetBoolean() })
                : await Judge.GateAsync(cls, state, asks, rules, bands);
            var got = await Judge.AskAsync(cls, state, asks, bands);
            var wantAnswers = c.GetProperty("wantAnswers");
            Assert.Equal(wantAnswers.EnumerateObject().Count(), got.Count);
            foreach (var wa in wantAnswers.EnumerateObject())
            {
                var a = got[wa.Name];
                var at = $"{c.GetProperty("name").GetString()}/{wa.Name}";
                Assert.True(wa.Value.GetProperty("value").GetDouble() == a.Value(), at);
                if (wa.Value.TryGetProperty("band", out var wb)) Assert.True(wb.GetString() == a.Band, at);
                if (wa.Value.TryGetProperty("sure", out var ws)) Assert.True(ws.GetBoolean() == a.Sure, at);
                if (wa.Value.TryGetProperty("choice", out var wc)) Assert.True(wc.GetString() == a.Choice(), at);
            }
            var want = c.GetProperty("want");
            var label = c.GetProperty("name").GetString();
            Assert.True(want.GetProperty("action").GetString() == o.Action, $"{label}: action {o.Action}");
            Assert.Equal(want.GetProperty("target").GetString(), o.Target);
            Assert.Equal(want.GetProperty("escalated").GetBoolean(), o.Escalated);
            if (o.Escalated)
            {
                Assert.Equal("input", o.Request!.Kind);
                Assert.Equal(want.GetProperty("question").GetString(), o.Request.Data!["question"]);
                if (want.TryGetProperty("reason", out var wr)) Assert.Equal(wr.GetString(), o.Request.Data!["reason"]);
                Assert.Equal(want.GetProperty("requestId").GetString(), o.Request.Id);
            }
            n++;
        }
        Assert.Equal(root.GetProperty("cases").GetArrayLength(), n);
        Assert.True(n >= 18);
    }

    private static Decision D(string answers) => Decision.FromJson("{\"answers\":" + answers + "}");

    [Fact]
    public void Bands_ExclusiveAndOverridable()
    {
        Assert.Equal("uncertain", Judge.Wrap(new NoulAnswer { Noul = 0.30 }).Band);
        Assert.Equal("uncertain", Judge.Wrap(new NoulAnswer { Noul = 0.70 }).Band);
        Assert.Equal("yes", Judge.Wrap(new NoulAnswer { Noul = 0.55 }, new Bands(0.20, 0.50)).Band);
        Assert.False(Judge.Wrap(new ChoiceAnswer { Choice = "a", Confidence = 0.8, NearUniform = true }).Sure);
        var v = Judge.Wrap(new NoulAnswer { Noul = 0.96 });
        Assert.Equal(0.96, v.Value());
        Assert.Equal("a", Judge.Wrap(new ChoiceAnswer { Choice = "a", Confidence = 0.9 }).Choice());
    }

    [Fact]
    public async Task Ask_ReturnsAnswersByName()
    {
        var qs = new[] { Judge.Noul("ok", "Is `message` ok?") };
        var st = Judge.State("r", new Dictionary<string, object?> { ["message"] = "hi" });
        var c = Classifier.FromRecorded(new[] { new RecordedDecision { State = st, Questions = Judge.Questions(qs), Response = "{\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":0.9}}}" } });
        var a = await Judge.AskAsync(c, st, qs);
        Assert.Equal("yes", a["ok"].Band);
    }

    [Fact]
    public void Policy_DefaultAndSkipUncertain()
    {
        var d = D("{\"a\":{\"type\":\"noul\",\"noul\":0.5},\"b\":{\"type\":\"noul\",\"noul\":0.9}}");
        var none = Judge.Apply(D("{\"b\":{\"type\":\"noul\",\"noul\":0.9}}"), new Policy { Rules = new[] { Rule.Below("b", 0.3, "fail") } });
        Assert.True(none.Escalated);
        Assert.Equal("no rule fired", none.Request!.Data!["reason"]);
        var dflt = Judge.Apply(D("{\"b\":{\"type\":\"noul\",\"noul\":0.9}}"), new Policy { Rules = new[] { Rule.Below("b", 0.3, "fail") }, Default = "continue" });
        Assert.Equal("continue", dflt.Action);
        var rules = new[] { Rule.AtLeast("a", 0.5, "one"), Rule.AtLeast("b", 0.8, "two") };
        Assert.True(Judge.Apply(d, new Policy { Rules = rules }).Escalated);
        Assert.Equal("two", Judge.Apply(d, new Policy { Rules = rules, SkipUncertain = true }).Action);
        var missing = Judge.Apply(d, new Policy { Rules = new[] { Rule.Is("zz", "x", "go") } });
        Assert.Equal("missing answer \"zz\"", missing.Request!.Data!["reason"]);
        Assert.Equal("uncertain answer \"a\"", Judge.Apply(d, new Policy { Rules = rules }).Request!.Data!["reason"]);
    }

    [Fact]
    public void Policy_SkipUncertain_DoesNotSkipAMisfitRule()
    {
        // An is-rule on a present, confident noul is a config error, not uncertainty: it still escalates.
        var d = D("{\"a\":{\"type\":\"noul\",\"noul\":0.9},\"b\":{\"type\":\"noul\",\"noul\":0.9}}");
        var o = Judge.Apply(d, new Policy { Rules = new[] { Rule.Is("a", "x", "one"), Rule.AtLeast("b", 0.8, "two") }, SkipUncertain = true });
        Assert.True(o.Escalated);
        Assert.Equal("gate:0:a", o.Request!.Id);
    }

    [Fact]
    public async Task Tape_RecordsAndReplays_MissNamesKey()
    {
        var qs = Judge.Questions(new[] { Judge.Noul("ok", "Is `message` ok?") });
        var live = Classifier.FromRecorded(new[] { new RecordedDecision { State = "s", Questions = qs, Response = "{\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":0.2}}}" } });
        var tape = new Tape();
        await tape.RecordAsync("triage", live, "s", qs);
        var d = await tape.Replay("triage").EvaluateAsync("anything", qs);
        Assert.Equal(0.2, d.Noul("ok").Noul);
        var miss = tape.Replay("plan"); // obtaining a replayer for an unrecorded name never fails
        var ex = await Assert.ThrowsAsync<ClassifierException>(() => miss.EvaluateAsync("s", qs));
        Assert.Equal("tape: no recorded decision for call \"plan\"", ex.Message);
    }

    private static Classifier Batch(IReadOnlyDictionary<string, Question> qs, params (string State, double P)[] recs)
        => Classifier.FromRecorded(recs.Select(r => new RecordedDecision { State = r.State, Questions = qs, Response = $"{{\"answers\":{{\"ok\":{{\"type\":\"noul\",\"noul\":{r.P.ToString(System.Globalization.CultureInfo.InvariantCulture)}}}}}}}" }));

    [Fact]
    public async Task EvaluateBatch_OrderFailureEmpty()
    {
        var qs = Judge.Questions(new[] { Judge.Noul("ok", "Is `message` ok?") });
        var c = Batch(qs, ("a", 0.1), ("b", 0.5), ("c", 0.9));
        var ds = await c.EvaluateBatchAsync(new object?[] { "a", "b", "c" }, qs);
        Assert.Equal(new[] { 0.1, 0.5, 0.9 }, ds.Select(d => d.Noul("ok").Noul));

        var ex = await Assert.ThrowsAsync<ClassifierException>(() => c.EvaluateBatchAsync(new object?[] { "a", "missing", "c" }, qs));
        Assert.Contains("state 1", ex.Message);

        var calls = 0;
        var counting = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Custom,
            Evaluate = (_, _, _) => { Interlocked.Increment(ref calls); return Task.FromResult(new Decision()); },
        });
        await Assert.ThrowsAsync<ClassifierException>(() => counting.EvaluateBatchAsync(Array.Empty<object?>(), qs));
        Assert.Equal(0, calls);
    }

    [Fact]
    public async Task EvaluateBatch_SeveralFailuresNameLowestIndex()
    {
        // state 2 fails fast, state 0 fails late: the error still names state 0.
        var qs = Judge.Questions(new[] { Judge.Noul("ok", "?") });
        var c = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Custom,
            Evaluate = async (st, _, _) =>
            {
                if ((string?)st == "late") await Task.Delay(40);
                throw new ClassifierException("boom " + st);
            },
        });
        var ex = await Assert.ThrowsAsync<ClassifierException>(() => c.EvaluateBatchAsync(new object?[] { "late", "s1", "fast" }, qs));
        Assert.Contains("state 0", ex.Message);
    }

    [Fact]
    public void Policy_UncertainBeforeFit_AndSkipDoesNotSkipMissing()
    {
        var d = D("{\"a\":{\"type\":\"noul\",\"noul\":0.5},\"b\":{\"type\":\"noul\",\"noul\":0.9}}");
        var rules = new[] { Rule.Is("a", "x", "one"), Rule.AtLeast("b", 0.8, "two") };
        var e = Judge.Apply(d, new Policy { Rules = rules });
        Assert.Equal("uncertain answer \"a\"", e.Request!.Data!["reason"]);
        Assert.Equal("gate:0:a", e.Request.Id);
        Assert.Equal("two", Judge.Apply(d, new Policy { Rules = rules, SkipUncertain = true }).Action);
        var m = Judge.Apply(d, new Policy { Rules = new[] { Rule.Below("zz", 0.3, "fail"), Rule.AtLeast("b", 0.5, "go") }, SkipUncertain = true });
        Assert.Equal("missing answer \"zz\"", m.Request!.Data!["reason"]);
        Assert.Equal("zz", m.Request.Data!["question"]);
        Assert.Equal("gate:0:zz", m.Request.Id);
    }
}
