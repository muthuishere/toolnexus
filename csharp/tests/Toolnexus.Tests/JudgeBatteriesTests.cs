using System.Net;
using System.Text;
using System.Text.Json;

namespace Toolnexus.Tests;

/// <summary>§8B Batteries (add-judge-batteries), against examples/judge/batteries/, plus the
/// beforeLLM <c>Model</c> override (SPEC §8 "Right-size routing").</summary>
public class JudgeBatteriesTests
{
    // ================================================================ fixture runner

    private static readonly string[] Files =
    {
        "tool-guard.json", "tool-relevance.json", "skill-relevance.json", "tool-result-filter.json",
        "is-complete.json", "agent-router.json", "content-guard.json", "model-router.json",
    };

    private static JsonElement Load(string name)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(TestFixtures.Fixture(Path.Combine("judge", "batteries", name))));
        return doc.RootElement.Clone();
    }

    public static IEnumerable<object[]> Cases()
    {
        foreach (var f in Files)
        {
            var cases = Load(f).GetProperty("cases").EnumerateArray().ToList();
            Assert.NotEmpty(cases);
            foreach (var c in cases) yield return new object[] { f, c.GetProperty("name").GetString()! };
        }
    }

    [Fact]
    public void EveryFixtureFileHasCases()
    {
        foreach (var f in Files) Assert.True(Load(f).GetProperty("cases").GetArrayLength() > 0, f);
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task Fixture(string file, string name)
    {
        var c = Load(file).GetProperty("cases").EnumerateArray().Single(x => x.GetProperty("name").GetString() == name);
        var opts = c.TryGetProperty("options", out var o) ? o : default;
        var input = c.GetProperty("input");
        var called = false;
        var cl = ClassifierFor(c, () => called = true);

        object verdict = file switch
        {
            "tool-guard.json" => await new ToolGuardClassifier(cl, new ToolGuardOptions
            {
                OnError = OnErr(opts), Bands = BandsOf(opts), Role = Str(opts, "role"),
                AskAt = Num(opts, "askAt"), DenyAt = Num(opts, "denyAt"),
            }).CheckAsync(new GuardedCall(input.GetProperty("name").GetString()!,
                input.TryGetProperty("arguments", out var args) ? (IDictionary<string, object?>)Json.FromElement(args)! : null,
                Str(input, "description"))),
            "tool-relevance.json" => await new ToolRelevanceClassifier(cl, RelOpts(opts))
                .SelectAsync(input.GetProperty("prompt").GetString()!, Items(input.GetProperty("tools"))),
            "skill-relevance.json" => await new SkillRelevanceClassifier(cl, RelOpts(opts))
                .SelectAsync(input.GetProperty("prompt").GetString()!, Items(input.GetProperty("skills"))),
            "tool-result-filter.json" => await new ToolResultFilterClassifier(cl, RelOpts(opts))
                .FilterAsync(Json.FromElement(input.GetProperty("query")),
                    input.GetProperty("chunks").EnumerateArray().Select(e => e.GetString()!).ToList()),
            "is-complete.json" => await new IsCompleteClassifier(cl, RelOpts(opts))
                .CheckAsync(input.GetProperty("task").GetString()!, input.GetProperty("answer").GetString()!),
            "agent-router.json" => await new AgentRouterClassifier(cl, new RouterOptions { Bands = BandsOf(opts), Role = Str(opts, "role") })
                .PickAsync(input.GetProperty("task").GetString()!, Agents(input.GetProperty("agents")), input.GetProperty("fallback").GetString()!),
            "content-guard.json" => await new ContentGuardClassifier(cl, new ContentGuardOptions
            {
                OnError = OnErr(opts), Bands = BandsOf(opts), Role = Str(opts, "role"),
                Dimensions = opts.ValueKind == JsonValueKind.Object && opts.TryGetProperty("dimensions", out var ds)
                    ? ds.EnumerateArray().Select(d => new Dimension(d.GetProperty("name").GetString()!, d.GetProperty("instructions").GetString()!)).ToList()
                    : null,
            }).CheckAsync(input.GetProperty("text").GetString()!),
            "model-router.json" => await new ModelRouterClassifier(cl,
                    input.GetProperty("models").EnumerateArray().Select(m => new ModelOption(m.GetProperty("id").GetString()!, m.GetProperty("description").GetString()!)).ToList(),
                    new RouterOptions { Bands = BandsOf(opts), Role = Str(opts, "role") })
                .PickAsync(input.GetProperty("prompt").GetString()!, input.GetProperty("fallback").GetString()!),
            _ => throw new InvalidOperationException(file),
        };

        Assert.False(called, $"{file}/{name}: the classifier must not be called");
        AssertVerdict(c.GetProperty("want"), verdict, $"{file}/{name}");
    }

    /// <summary>static over the recorded calls; failing for <c>error: true</c>; a tripwire for <c>calls: []</c>.</summary>
    private static Classifier ClassifierFor(JsonElement c, Action onUnexpected)
    {
        if (c.TryGetProperty("error", out var e) && e.ValueKind == JsonValueKind.True)
            return Classifier.Create(new ClassifierOptions { Style = ClassifierStyle.Custom, Evaluate = (_, _, _) => throw new ClassifierException("boom") });
        var calls = c.GetProperty("calls").EnumerateArray().ToList();
        if (calls.Count == 0)
            return Classifier.Create(new ClassifierOptions
            {
                Style = ClassifierStyle.Custom,
                Evaluate = (_, _, _) => { onUnexpected(); throw new ClassifierException("unexpected call"); },
            });
        return Classifier.FromRecorded(calls.Select(k => new RecordedDecision
        {
            State = k.GetProperty("state").Clone(),
            Questions = WireQuestions(k.GetProperty("questions")),
            Response = k.GetProperty("response").GetRawText(),
        }));
    }

    private static Dictionary<string, Question> WireQuestions(JsonElement qs)
    {
        var m = new Dictionary<string, Question>();
        foreach (var p in qs.EnumerateObject())
        {
            var q = p.Value;
            var ins = q.GetProperty("instructions").GetString()!;
            var hasCrit = q.TryGetProperty("criteria", out var crit);
            m[p.Name] = q.GetProperty("type").GetString() switch
            {
                "noul" => new NoulQuestion
                {
                    Instructions = ins,
                    Criteria = hasCrit ? new NoulCriteria { True = crit.GetProperty("true").GetString()!, False = crit.GetProperty("false").GetString()! } : null,
                },
                "choice" => new ChoiceQuestion { Instructions = ins, Criteria = crit.EnumerateObject().ToDictionary(x => x.Name, x => x.Value.GetString()!) },
                "score" => new ScoreQuestion { Instructions = ins, Criteria = crit.EnumerateArray().Select(x => x.GetString()!).ToList() },
                var t => throw new InvalidOperationException(t),
            };
        }
        return m;
    }

    private static OnError? OnErr(JsonElement o) => Str(o, "onError") switch
    {
        "open" => OnError.Open,
        "closed" => OnError.Closed,
        _ => null,
    };

    private static RelevanceOptions RelOpts(JsonElement o) => new() { OnError = OnErr(o), Bands = BandsOf(o), Role = Str(o, "role") };

    private static Bands? BandsOf(JsonElement o)
        => o.ValueKind == JsonValueKind.Object && o.TryGetProperty("bands", out var b)
            ? new Bands(b.GetProperty("low").GetDouble(), b.GetProperty("high").GetDouble())
            : null;

    private static string? Str(JsonElement o, string k)
        => o.ValueKind == JsonValueKind.Object && o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static double? Num(JsonElement o, string k)
        => o.ValueKind == JsonValueKind.Object && o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : null;

    private static List<JudgeItem> Items(JsonElement a)
        => a.EnumerateArray().Select(x => new JudgeItem(x.GetProperty("name").GetString()!, Str(x, "description") ?? "")).ToList();

    private static List<AgentNode> Agents(JsonElement a)
        => a.EnumerateArray().Select(x => new AgentNode(x.GetProperty("name").GetString()!, x.GetProperty("description").GetString()!,
            x.TryGetProperty("agents", out var sub) ? Agents(sub) : null)).ToList();

    private static readonly JsonSerializerOptions Camel = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    /// <summary>Every <c>want</c> key against the verdict's JSON form; <c>error</c> is a presence check.</summary>
    private static void AssertVerdict(JsonElement want, object verdict, string label)
    {
        var got = JsonSerializer.SerializeToElement(verdict, verdict.GetType(), Camel);
        foreach (var w in want.EnumerateObject())
        {
            var has = got.TryGetProperty(w.Name, out var g);
            if (w.Name == "error")
            {
                Assert.True(w.Value.GetBoolean() == (has && g.ValueKind == JsonValueKind.String), $"{label}: error present, want {w.Value} (verdict {got})");
                continue;
            }
            Assert.True(has, $"{label}: verdict lacks {w.Name} ({got})");
            Assert.True(Same(w.Value, g), $"{label}: {w.Name} = {g}, want {w.Value}");
        }
    }

    private static bool Same(JsonElement a, JsonElement b)
    {
        if (a.ValueKind != b.ValueKind) return false;
        return a.ValueKind switch
        {
            JsonValueKind.Number => a.GetDouble() == b.GetDouble(),
            JsonValueKind.Array => a.GetArrayLength() == b.GetArrayLength() && a.EnumerateArray().Zip(b.EnumerateArray()).All(p => Same(p.First, p.Second)),
            JsonValueKind.Object => a.EnumerateObject().Count() == b.EnumerateObject().Count()
                && a.EnumerateObject().All(p => b.TryGetProperty(p.Name, out var q) && Same(p.Value, q)),
            JsonValueKind.String => a.GetString() == b.GetString(),
            _ => true,
        };
    }

    [Fact]
    public void UserTextCases()
    {
        var cases = Load("user-text-cases.json").GetProperty("cases").EnumerateArray().ToList();
        Assert.NotEmpty(cases);
        foreach (var c in cases)
        {
            var msgs = (List<object?>)Json.FromElement(c.GetProperty("messages"))!;
            Assert.True(c.GetProperty("want").GetString() == Batteries.LatestUserText(msgs), c.GetProperty("name").GetString());
        }
    }

    [Fact]
    public void OnErrorRequired()
    {
        var cl = Classifier.FromRecorded(Array.Empty<RecordedDecision>());
        foreach (var make in new Action[]
        {
            () => _ = new ToolGuardClassifier(cl, new ToolGuardOptions()),
            () => _ = new ToolRelevanceClassifier(cl, new RelevanceOptions()),
            () => _ = new SkillRelevanceClassifier(cl, new RelevanceOptions()),
            () => _ = new ToolResultFilterClassifier(cl, new RelevanceOptions { OnError = (OnError)7 }),
            () => _ = new IsCompleteClassifier(cl, new RelevanceOptions()),
            () => _ = new ContentGuardClassifier(cl, new ContentGuardOptions()),
        })
        {
            var ex = Assert.Throws<ArgumentException>(make);
            Assert.Contains("onError", ex.Message);
            Assert.Contains("OnError", ex.Message);
        }
    }

    // ================================================================ hooks

    /// <summary>Every evaluate answers with the given answers map.</summary>
    private static Classifier Fixed(string answers)
        => Classifier.Create(new ClassifierOptions
        {
            Style = ClassifierStyle.Custom,
            Evaluate = (_, _, _) => Task.FromResult(Decision.FromJson("{\"model\":\"m\",\"answers\":" + answers + "}")),
        });

    private static Classifier Failing()
        => Classifier.Create(new ClassifierOptions { Style = ClassifierStyle.Custom, Evaluate = (_, _, _) => throw new ClassifierException("boom") });

    private static Classifier Risk(string score)
        => Fixed("{\"risk\":{\"type\":\"score\",\"score\":" + score + ",\"confidence\":0.9,\"probabilities\":{\"0\":0.25,\"1\":0.25,\"2\":0.25,\"3\":0.25},\"legend\":{\"0\":\"a\",\"1\":\"b\",\"2\":\"c\",\"3\":\"d\"}}}");

    /// <summary>A scripted openai endpoint recording every request body: turn 1 calls <c>deploy</c> (id c1), then "done".</summary>
    private sealed class BodyRecorder : HttpMessageHandler
    {
        public readonly List<Dictionary<string, object?>> Bodies = new();

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Bodies.Add(Json.ParseObjectLoose(await request.Content!.ReadAsStringAsync(ct)));
            var body = Bodies.Count == 1
                ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"deploy\",\"arguments\":\"{}\"}}]}}]}"
                : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}";
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        }
    }

    private static LlmClient Client(BodyRecorder rec, LlmClient.Hooks? hooks, Func<Request, Task<Answer>>? waitFor = null)
        => LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://stub.invalid/v1", Style = "openai", Model = "configured", ApiKey = "k",
            HttpClient = new HttpClient(rec), Hooks = hooks, MaxTurns = 4, WaitFor = waitFor,
        });

    private static async Task<Toolkit> DeployToolkit(params (string Name, string Output)[] extra)
    {
        var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var tools = extra.Length > 0 ? extra : new[] { ("deploy", "DEPLOYED") };
        foreach (var (n, output) in tools)
            tk.Register(NativeTool.Of(n, n, null, (IDictionary<string, object?> _) => (object)ToolResult.Ok(output)));
        return tk;
    }

    [Fact]
    public async Task BeforeLLM_ModelOverrideIsPerTurn()
    {
        var seen = new List<string>();
        var rec = new BodyRecorder();
        await using var tk = await DeployToolkit();
        var hooks = new LlmClient.Hooks
        {
            BeforeLLM = ev => ev.Turn == 0 ? new LlmClient.LLMOverride(Model: "small-fast") : null,
            AfterLLM = ev => seen.Add(ev.Model),
        };
        await Client(rec, hooks).RunAsync("go", tk);
        Assert.Equal(2, rec.Bodies.Count);
        Assert.Equal("small-fast", rec.Bodies[0]["model"]);
        Assert.Equal("configured", rec.Bodies[1]["model"]);
        Assert.Equal(new[] { "small-fast", "configured" }, seen);
    }

    [Fact]
    public async Task BeforeLLM_EmptyModelIsConfigured()
    {
        var rec = new BodyRecorder();
        await using var tk = await DeployToolkit();
        await Client(rec, new LlmClient.Hooks { BeforeLLM = _ => new LlmClient.LLMOverride(Model: "") }).RunAsync("go", tk);
        Assert.All(rec.Bodies, b => Assert.Equal("configured", b["model"]));
    }

    [Fact]
    public async Task ToolGuardHook()
    {
        var ran = false;
        Func<LlmClient.BeforeToolEvent, LlmClient.ToolOverride?> next = _ => { ran = true; return null; };
        await using var tk = await DeployToolkit();

        // ask: halts pending with the guard's Request; the tool never runs, next not called.
        var g = new ToolGuardClassifier(Risk("1.8"), new ToolGuardOptions { OnError = OnError.Closed });
        var r = await Client(new BodyRecorder(), new LlmClient.Hooks { BeforeTool = g.AsHook(next) }).RunAsync("ship it", tk);
        Assert.Equal("pending", r.Status);
        Assert.NotNull(r.Pending);
        Assert.Equal("toolguard:c1", r.Pending!.Id);
        Assert.Equal("approval", r.Pending.Kind);
        Assert.Equal("Approve the call to deploy? (medium risk)", r.Pending.Prompt);
        Assert.Equal("deploy", r.Pending.Data!["tool"]);
        Assert.Equal("medium risk", r.Pending.Data["reason"]);
        Assert.Equal(1.8, r.Pending.Data["risk"]);
        Assert.NotNull(r.Pending.Data["arguments"]);
        Assert.False(ran);
        Assert.DoesNotContain(r.ToolCalls, c => c.Output == "DEPLOYED");

        // deny: short-circuits, the tool's output never appears.
        g = new ToolGuardClassifier(Risk("2.9"), new ToolGuardOptions { OnError = OnError.Closed });
        r = await Client(new BodyRecorder(), new LlmClient.Hooks { BeforeTool = g.AsHook(next) }).RunAsync("ship it", tk);
        Assert.False(ran);
        Assert.Single(r.ToolCalls);
        Assert.Equal("denied by tool guard: high risk", r.ToolCalls[0].Output);
        Assert.True(r.ToolCalls[0].IsError);

        // allow: next runs and the tool runs.
        g = new ToolGuardClassifier(Risk("0.1"), new ToolGuardOptions { OnError = OnError.Closed });
        r = await Client(new BodyRecorder(), new LlmClient.Hooks { BeforeTool = g.AsHook(next) }).RunAsync("ship it", tk);
        Assert.True(ran);
        Assert.Equal("DEPLOYED", r.ToolCalls[0].Output);

        // approved through WaitFor: the tool runs once, no re-ask.
        var asked = 0;
        g = new ToolGuardClassifier(Risk("1.8"), new ToolGuardOptions { OnError = OnError.Closed });
        r = await Client(new BodyRecorder(), new LlmClient.Hooks { BeforeTool = g.AsHook() },
            q => { asked++; return Task.FromResult(new Answer { Id = q.Id, Ok = true }); }).RunAsync("ship it", tk);
        Assert.NotEqual("pending", r.Status);
        Assert.Equal(1, asked);
        Assert.Single(r.ToolCalls);
        Assert.Equal("DEPLOYED", r.ToolCalls[0].Output);
    }

    [Fact]
    public async Task ModelRouterHook()
    {
        var models = new[] { new ModelOption("small-fast", "cheap"), new ModelOption("large-reasoning", "dear") };
        const string sure = "{\"model\":{\"type\":\"choice\",\"choice\":\"small-fast\",\"confidence\":0.91,\"probabilities\":{\"small-fast\":0.91,\"large-reasoning\":0.09}}}";
        const string unsure = "{\"model\":{\"type\":\"choice\",\"choice\":\"small-fast\",\"confidence\":0.6,\"probabilities\":{\"small-fast\":0.6,\"large-reasoning\":0.4}}}";
        await using var tk = await DeployToolkit();
        foreach (var (ans, want) in new[] { (sure, "small-fast"), (unsure, "configured") })
        {
            var rec = new BodyRecorder();
            var r = new ModelRouterClassifier(Fixed(ans), models);
            await Client(rec, new LlmClient.Hooks { BeforeLLM = r.AsHook() }).RunAsync("capital of France?", tk);
            Assert.NotEmpty(rec.Bodies);
            Assert.All(rec.Bodies, b => Assert.Equal(want, b["model"]));
        }

        // unsure, or sure of the configured model itself: no override at all.
        var ev = new LlmClient.BeforeLLMEvent(new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = "x" } },
            new List<Dictionary<string, object?>>(), "small-fast", 0);
        foreach (var a in new[] { unsure, sure })
            Assert.Null(new ModelRouterClassifier(Fixed(a), models).AsHook()(ev));

        // no router attached: verbatim.
        var plain = new BodyRecorder();
        await Client(plain, null).RunAsync("x", tk);
        Assert.Equal("configured", plain.Bodies[0]["model"]);

        // next's model wins over the router's; next sees the routed model.
        string? nextSaw = null;
        var router = new ModelRouterClassifier(Fixed(sure), models);
        var rec2 = new BodyRecorder();
        await Client(rec2, new LlmClient.Hooks
        {
            BeforeLLM = router.AsHook(e => { nextSaw = e.Model; return new LlmClient.LLMOverride(Model: "pinned"); }),
        }).RunAsync("x", tk);
        Assert.Equal("small-fast", nextSaw);
        Assert.Equal("pinned", rec2.Bodies[0]["model"]);
    }

    [Fact]
    public async Task ToolRelevanceHook_DropsATool()
    {
        await using var tk = await DeployToolkit(("deploy", "D"), ("send_email", "E"));
        var rel = new ToolRelevanceClassifier(Fixed("{\"deploy\":{\"type\":\"noul\",\"noul\":0.9},\"send_email\":{\"type\":\"noul\",\"noul\":0.05}}"),
            new RelevanceOptions { OnError = OnError.Open });
        var rec = new BodyRecorder();
        await Client(rec, new LlmClient.Hooks { BeforeLLM = rel.AsHook() }).RunAsync("ship it", tk);
        var tools = (List<object?>)rec.Bodies[0]["tools"]!;
        Assert.Single(tools);
        Assert.Equal("deploy", Batteries.ProviderTool(tools[0]).Name);
    }

    [Fact]
    public async Task ContentGuardHook()
    {
        await using var tk = await DeployToolkit();
        var g = new ContentGuardClassifier(Fixed("{\"harmful\":{\"type\":\"noul\",\"noul\":0.96},\"prompt_injection\":{\"type\":\"noul\",\"noul\":0.9}}"),
            new ContentGuardOptions { OnError = OnError.Closed });
        var rec = new BodyRecorder();
        var ex = await Assert.ThrowsAnyAsync<Exception>(() => Client(rec, new LlmClient.Hooks { BeforeLLM = g.AsHook() }).RunAsync("idiot", tk));
        Assert.Contains("content guard blocked: harmful, prompt_injection", ex.Message);
        Assert.Empty(rec.Bodies);

        var called = false;
        g = new ContentGuardClassifier(Fixed("{\"harmful\":{\"type\":\"noul\",\"noul\":0.5},\"prompt_injection\":{\"type\":\"noul\",\"noul\":0.1}}"),
            new ContentGuardOptions { OnError = OnError.Closed });
        var ev = new LlmClient.BeforeLLMEvent(new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = "meh" } },
            new List<Dictionary<string, object?>>(), "m", 0);
        g.AsHook(_ => { called = true; return null; })(ev);
        Assert.True(called, "review must delegate");

        var gErr = new ContentGuardClassifier(Failing(), new ContentGuardOptions { OnError = OnError.Closed });
        var blocked = Assert.Throws<ContentBlockedException>(() => gErr.AsHook()(ev));
        Assert.Equal("content guard blocked: classifier error", blocked.Message);
    }

    [Fact]
    public void ToolResultFilterHook()
    {
        var f = new ToolResultFilterClassifier(Fixed("{\"0\":{\"type\":\"noul\",\"noul\":0.9},\"1\":{\"type\":\"noul\",\"noul\":0.05},\"2\":{\"type\":\"noul\",\"noul\":0.5}}"),
            new RelevanceOptions { OnError = OnError.Open });
        string? nextSaw = null;
        var h = f.AsHook(e => { nextSaw = e.Result.Output; return null; });
        var ov = h(new LlmClient.AfterToolEvent("t", new Dictionary<string, object?>(), ToolResult.Ok("a\n\nb\n\nc"), "c1", 0));
        Assert.Equal("a\n\nc", ov!.Result!.Output);
        Assert.Equal("a\n\nc", nextSaw);

        // single chunk, error result, non-text parts: untouched, no classifier call.
        var fe = new ToolResultFilterClassifier(Failing(), new RelevanceOptions { OnError = OnError.Closed });
        foreach (var r in new[]
        {
            ToolResult.Ok("one"),
            ToolResult.Error("a\n\nb"),
            ToolResult.OkWithParts("a\n\nb", new[] { new ContentPart { Type = "image" } }),
        })
            Assert.Null(fe.AsHook()(new LlmClient.AfterToolEvent("t", new Dictionary<string, object?>(), r, "c1", 0)));
    }
    [Fact]
    public async Task RoutersNeverThrowOnAWrongTypeAnswer()
    {
        // A custom classifier answering the choice question with a noul: a fallback, not an InvalidCastException.
        var noul = "{\"model\":{\"type\":\"noul\",\"noul\":0.9},\"agent\":{\"type\":\"noul\",\"noul\":0.9}}";
        var mv = await new ModelRouterClassifier(Fixed(noul), new[] { new ModelOption("small-fast", "cheap") }).PickAsync("x", "configured");
        Assert.Equal("configured", mv.Model);
        Assert.False(mv.Routed);
        var av = await new AgentRouterClassifier(Fixed(noul)).PickAsync("x", new[] { new AgentNode("a", "A") }, "fb");
        Assert.Equal("fb", av.Agent);
    }
}
