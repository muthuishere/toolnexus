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
        Assert.Equal(3, cases.Count);
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
        var rules = Rules(root);
        var n = 0;
        foreach (var c in root.GetProperty("cases").EnumerateArray())
        {
            var state = Judge.Context("triage bugs", "case " + c.GetProperty("name").GetString());
            var (cls, asks) = GateFixture(root, c, state);
            Bands? bands = c.GetProperty("bands").ValueKind == JsonValueKind.Null ? null
                : new Bands(c.GetProperty("bands").GetProperty("low").GetDouble(), c.GetProperty("bands").GetProperty("high").GetDouble());
            var o = await Judge.GateAsync(cls, state, asks, rules, bands);
            var want = c.GetProperty("want");
            var label = c.GetProperty("name").GetString();
            Assert.True(want.GetProperty("action").GetString() == o.Action, $"{label}: action {o.Action}");
            Assert.Equal(want.GetProperty("target").GetString(), o.Target);
            Assert.Equal(want.GetProperty("escalated").GetBoolean(), o.Escalated);
            if (o.Escalated) { Assert.Equal("input", o.Request!.Kind); Assert.True(o.Request.Data!.ContainsKey("reason")); }
            n++;
        }
        Assert.Equal(13, n);
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
        Assert.Equal("missing answer", missing.Request!.Data!["reason"]);
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
        var ex = await Assert.ThrowsAsync<ClassifierException>(() => tape.Replay("plan").EvaluateAsync("s", qs));
        Assert.Contains("plan", ex.Message);
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

        await Assert.ThrowsAsync<ClassifierException>(() => c.EvaluateBatchAsync(Array.Empty<object?>(), qs));
    }
}
