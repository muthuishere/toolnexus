using System.Text.Json;
using System.Text.Json.Nodes;
using Toolnexus;
using static JudgeSpike.Judge;

namespace JudgeSpike;

public class JudgeTests
{
    static JsonElement Load(string f) =>
        JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "shared", f))).RootElement;

    static Ask ToAsk(string name, JsonElement q)
    {
        var instr = q.GetProperty("instructions").GetString()!;
        var kind = q.TryGetProperty("kind", out var k) ? k.GetString() : q.GetProperty("type").GetString();
        return kind switch
        {
            "noul" => Noul(name, instr),
            "choice" => Choice(name, instr, (q.TryGetProperty("options", out var o) ? o : q.GetProperty("criteria"))
                .EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetString()!)),
            _ => Score(name, instr, (q.TryGetProperty("levels", out var l) ? l : q.GetProperty("criteria"))
                .EnumerateArray().Select(x => x.GetString()!).ToArray()),
        };
    }

    static JsonObject Wire(Question q) => q switch
    {
        NoulQuestion n => new() { ["type"] = "noul", ["instructions"] = n.Instructions },
        ChoiceQuestion c => new() { ["type"] = "choice", ["instructions"] = c.Instructions,
            ["criteria"] = JsonSerializer.SerializeToNode(c.Criteria) },
        ScoreQuestion s => new() { ["type"] = "score", ["instructions"] = s.Instructions,
            ["criteria"] = JsonSerializer.SerializeToNode(s.Criteria) },
        _ => throw new InvalidOperationException(),
    };

    public static TheoryData<string> StateCases() => new(Load("state-cases.json").GetProperty("cases").EnumerateArray().Select(c => c.GetProperty("name").GetString()!));
    public static TheoryData<string> GateCases() => new(Load("gate-cases.json").GetProperty("cases").EnumerateArray().Select(c => c.GetProperty("name").GetString()!));

    [Theory, MemberData(nameof(StateCases))]
    public void Builders(string name)
    {
        var c = Load("state-cases.json").GetProperty("cases").EnumerateArray().First(x => x.GetProperty("name").GetString() == name);
        object state = c.TryGetProperty("context", out var cx)
            ? State(cx.GetProperty("context").GetString()!, cx.GetProperty("message").GetString()!,
                cx.GetProperty("extra").EnumerateObject().ToDictionary(p => p.Name, p => (object?)p.Value.GetString()))
            : c.GetProperty("state").Deserialize<Dictionary<string, JsonElement>>()!;
        var asks = c.GetProperty("questions").EnumerateArray().Select(q => ToAsk(q.GetProperty("name").GetString()!, q)).ToList();

        if (c.TryGetProperty("wantError", out var we))
        {
            Assert.Equal(we.GetString(), Assert.Throws<ArgumentException>(() => Questions(asks)).Message);
            return;
        }
        Assert.True(JsonNode.DeepEquals(JsonSerializer.SerializeToNode(state), JsonNode.Parse(c.GetProperty("wantState").GetRawText())));
        var got = new JsonObject();
        foreach (var (k, q) in Questions(asks)) got[k] = Wire(q);
        Assert.True(JsonNode.DeepEquals(got, JsonNode.Parse(c.GetProperty("wantQuestions").GetRawText())), got.ToJsonString());
    }

    [Theory, MemberData(nameof(GateCases))]
    public async Task Gate(string name)
    {
        var root = Load("gate-cases.json");
        var c = root.GetProperty("cases").EnumerateArray().First(x => x.GetProperty("name").GetString() == name);
        var asks = root.GetProperty("questions").EnumerateObject().Select(p => ToAsk(p.Name, p.Value)).ToList();
        var rules = root.GetProperty("rules").EnumerateArray().Select(r =>
        {
            string q = r.GetProperty("question").GetString()!, a = r.GetProperty("action").GetString()!;
            var t = r.TryGetProperty("target", out var tt) ? tt.GetString()! : "";
            if (r.TryGetProperty("below", out var b)) return Rule.Below(q, b.GetDouble(), a, t);
            if (r.TryGetProperty("at_least", out var al)) return Rule.AtLeast(q, al.GetDouble(), a, t);
            return Rule.Is(q, r.GetProperty("is").GetString()!, a, t);
        }).ToList();
        var bands = c.GetProperty("bands") is { ValueKind: JsonValueKind.Object } bj
            ? new Bands(bj.GetProperty("low").GetDouble(), bj.GetProperty("high").GetDouble()) : null;

        const string state = "a bug report";
        var classifier = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Static,
            Decisions = [new RecordedDecision { State = state, Questions = Questions(asks),
                Response = $"{{\"model\":\"static\",\"answers\":{c.GetProperty("answers").GetRawText()}}}" }],
        });

        var o = await GateAsync(classifier, state, asks, rules, bands);
        var want = c.GetProperty("want");
        Assert.Equal(want.GetProperty("action").GetString(), o.Action);
        Assert.Equal(want.GetProperty("target").GetString(), o.Target);
        Assert.Equal(want.GetProperty("escalated").GetBoolean(), o.Escalated);
        if (o.Escalated)
        {
            Assert.Equal("input", o.Request!.Kind);
            Assert.True(o.Request.Data!.ContainsKey("question") && o.Request.Data.ContainsKey("reason") && o.Request.Data.ContainsKey("answers"));
        }
    }

    [Fact]
    public async Task VideoFeel()
    {
        var state = new Dictionary<string, object?> { ["role"] = "Donkey Kong", ["message_received"] = "jump off the stage now" };
        Ask[] qs = [Noul("is_appropriate", "Inappropriate language?"), Noul("does_this_help", "Does this help donkey kong win?")];
        var c = new Classifier(new ClassifierOptions
        {
            Style = ClassifierStyle.Static,
            Decisions = [new RecordedDecision { State = state, Questions = Questions(qs),
                Response = """{"answers":{"is_appropriate":{"type":"noul","noul":0.05},"does_this_help":{"type":"noul","noul":0.5}}}""" }],
        });
        var d = await AskAsync(c, state, qs);
        Assert.Equal("no", d["is_appropriate"].Band);
        Assert.Equal("uncertain", d["does_this_help"].Band);
    }
}
