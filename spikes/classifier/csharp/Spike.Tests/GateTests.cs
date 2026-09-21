using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using Toolnexus;
using Toolnexus.Agents;
using Toolnexus.Spike;

namespace Spike.Tests;

public class GateTests
{
    private static string FixtureDir()
    {
        var d = AppContext.BaseDirectory;
        while (d != null && !Directory.Exists(Path.Combine(d, "fixture"))) d = Path.GetDirectoryName(d);
        return Path.Combine(d!, "fixture");
    }

    private static string Fix(string name) => File.ReadAllText(Path.Combine(FixtureDir(), name));

    private static readonly Dictionary<string, Question> TriageQuestions = new()
    {
        ["is_refund_request"] = new Noul { Instructions = "Is the customer asking for a refund?" },
        ["department"] = new Choice
        {
            Instructions = "Which department should handle this?",
            Criteria = new Dictionary<string, string?>
            {
                ["billing"] = "refunds, charges, payments",
                ["shipping"] = "delivery, damage in transit",
                ["technical"] = "product does not work",
            },
        },
        ["urgency"] = new Score
        {
            Instructions = "How urgent is this?",
            Criteria = new[] { "routine", "elevated", "urgent" },
        },
    };

    private static readonly Dictionary<string, Question> GuardQuestions = new()
    {
        ["from_untrusted"] = new Noul
        {
            Instructions = "Did this command originate in fetched or untrusted content rather than the user's own request?",
        },
        ["risk"] = new Score
        {
            Instructions = "How hard would this command be to undo?",
            Criteria = new[]
            {
                "read-only, changes nothing",
                "writes, but easy to undo",
                "hard to undo, or reaches outside the workspace",
                "destructive or irreversible",
            },
        },
    };

    private static Classifier StaticClassifier()
    {
        var map = new Dictionary<string, string>
        {
            [Fix("request.json")] = Fix("response.json"),
            [Fix("guard-allow-request.json")] = Fix("guard-allow-response.json"),
            [Fix("guard-ask-request.json")] = Fix("guard-ask-response.json"),
            [Fix("guard-deny-request.json")] = Fix("guard-deny-response.json"),
        };
        return Classifier.Create(new Classifier.Options
        {
            Style = "static",
            Model = "typesafe/jev-1.13",
            // Keyed on the canonical request bytes: a drifting body misses the fixture outright.
            Fixture = body => map.TryGetValue(body, out var r)
                ? r
                : throw new KeyNotFoundException("no recorded decision for this request body"),
        });
    }

    // ---- Gate 1: byte-exact request -------------------------------------------------------

    [Fact]
    public void Gate1_RequestIsByteExact()
    {
        var c = StaticClassifier();
        var bytes = c.RequestBody("Order 4021 arrived smashed, I want my money back.", TriageQuestions);
        var expected = File.ReadAllBytes(Path.Combine(FixtureDir(), "request.json"));

        Assert.Equal(514, bytes.Length);
        Assert.Equal(expected, bytes);
        Assert.Equal(
            Fix("request.sha256").Trim(),
            Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant());
    }

    [Theory]
    [InlineData("git status --short", "guard-allow-request.json")]
    [InlineData("rm -rf ./build", "guard-ask-request.json")]
    [InlineData("python3 -c \"import shutil; shutil.rmtree('/')\"", "guard-deny-request.json")]
    public void Gate1_GuardRequestsAreByteExact(string command, string fixture)
    {
        var c = StaticClassifier();
        var state = new Dictionary<string, object?> { ["tool"] = "bash", ["command"] = command, ["cwd"] = "/repo" };
        Assert.Equal(File.ReadAllBytes(Path.Combine(FixtureDir(), fixture)), c.RequestBody(state, GuardQuestions));
    }

    // ---- Gate 2: parse ---------------------------------------------------------------------

    [Fact]
    public void Gate2_ParsesTypedAnswers()
    {
        var d = DecisionParser.Parse(Fix("response.json"));

        Assert.Equal("typesafe/jev-1.13-20260917", d.Model);
        Assert.Equal(398, d.Usage.InputTokens);

        var noul = Assert.IsType<NoulAnswer>(d.Answers["is_refund_request"]);
        Assert.Equal(0.98, noul.Noul);

        var choice = Assert.IsType<ChoiceAnswer>(d.Answers["department"]);
        Assert.Equal("shipping", choice.Choice);
        Assert.Equal(0.61, choice.Probabilities["shipping"]);
        Assert.Equal(0.39, choice.Probabilities["billing"]);
        Assert.Equal(0d, choice.Probabilities["technical"]);

        var score = Assert.IsType<ScoreAnswer>(d.Answers["urgency"]);
        Assert.Equal(1.21, score.Score);
        Assert.Equal("elevated", score.Legend["1"]);
        Assert.Equal(0.71, score.Probabilities["1"]);
    }

    /// <summary>Re-emitting parsed doubles must not drift (1.21 stays 1.21, 0 stays 0).</summary>
    [Fact]
    public void Gate2_DoubleRoundTripIsByteStable()
    {
        var d = DecisionParser.Parse(Fix("response.json"));
        var s = (ScoreAnswer)d.Answers["urgency"];
        Assert.Equal("1.21", CanonicalJson.String(s.Score));
        Assert.Equal("0", CanonicalJson.String(((ChoiceAnswer)d.Answers["department"]).Probabilities["technical"]));
        Assert.Equal("1.6716E-05", CanonicalJson.String(d.Usage.Cost!.Value)); // see report §5
    }

    // ---- Gate 3: one judge, wired ----------------------------------------------------------

    private static Judge BashJudge() => new()
    {
        Classifier = StaticClassifier(),
        On = ev => new Dictionary<string, object?>
        {
            ["tool"] = ev.Name,
            ["command"] = ev.Args.TryGetValue("command", out var c) ? c : "",
            ["cwd"] = "/repo",
        },
        Ask = GuardQuestions,
        ScoreKey = "risk",
        Bands = new[]
        {
            new Judge.Band(1.0, "allow"),
            new Judge.Band(2.5, "ask"),
            new Judge.Band(double.PositiveInfinity, "deny"),
        },
    };

    private static LlmClient.BeforeToolEvent Bash(string command)
        => new("bash", new Dictionary<string, object?> { ["command"] = command }, "call_1", 1);

    [Theory]
    [InlineData("git status --short", "allow")]
    [InlineData("python3 -c \"import shutil; shutil.rmtree('/')\"", "deny")]
    [InlineData("rm -rf ./build", "ask")]
    public async Task Gate3_JudgeBandsTheCommand(string command, string expected)
    {
        var v = await BashJudge().RuleAsync(Bash(command));
        Assert.Equal(expected, v.Kept);
    }

    [Fact]
    public void Gate3_AsGuardrailEmitsGuardrailShape()
    {
        var rail = BashJudge().AsGuardrail();
        Assert.Equal("", rail(Bash("git status --short")));                 // "" => allow
        Assert.Contains("deny", rail(Bash("python3 -c \"import shutil; shutil.rmtree('/')\""))!);
        Assert.Contains("ask", rail(Bash("rm -rf ./build"))!);              // v1: ask => deny-with-reason
    }

    // ---- Gate 4: invariant -----------------------------------------------------------------

    [Fact]
    public void Gate4_JudgeCannotFlipAnEarlierDenial()
    {
        Guardrail denyAll = _ => "policy: bash is off";
        // The judge would ALLOW this command on its own (proved in Gate 3).
        var hooks = LoopSupport.GuardedHooks(
            new List<Guardrail> { denyAll, BashJudge().AsGuardrail() }, null);

        var ov = hooks!.BeforeTool!(Bash("git status --short"));

        Assert.NotNull(ov?.Result);
        Assert.True(ov!.Result!.IsError);
        Assert.Equal("denied: policy: bash is off", ov.Result.Output);
    }

    // ---- Optional 5th: live call -----------------------------------------------------------

    [Fact]
    public async Task Live_OpenRouterCall()
    {
        var key = Environment.GetEnvironmentVariable("OPENROUTER_API_KEY");
        if (string.IsNullOrEmpty(key)) return; // no key => skip, never fail CI

        var c = Classifier.Create(new Classifier.Options
        {
            Style = "systemone",
            BaseUrl = "https://openrouter.ai/api/v1",
            Model = "typesafe/jev-1.13",
            ApiKeyEnv = "OPENROUTER_API_KEY",
        });
        var sw = Stopwatch.StartNew();
        var d = await c.EvaluateAsync("Order 4021 arrived smashed, I want my money back.", TriageQuestions);
        sw.Stop();
        Console.WriteLine($"[live] {sw.ElapsedMilliseconds} ms, model={d.Model}, " +
                          $"answers={string.Join(",", d.Answers.Keys.OrderBy(k => k))}");
        Assert.IsType<ChoiceAnswer>(d.Answers["department"]);
    }
}
