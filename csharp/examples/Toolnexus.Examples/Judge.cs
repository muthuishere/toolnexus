namespace Toolnexus.Examples;

/// <summary>
/// Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as ITool is the contract for an ACTION.
///
///   dotnet run -- judge
///
/// With OPENROUTER_API_KEY set it calls the live System One backend; with no key it replays one
/// recorded decision through the <c>static</c> backend, so the example runs offline with no credential.
/// </summary>
internal static class Judge
{
    // ---- the state: whatever the host already has. Sent verbatim, never canonicalised. ----
    private const string Ticket =
        "Ticket 4021: my card was charged twice for the annual plan on Tuesday, and the second charge " +
        "has not been refunded. I am not blocked from working, but I would like the money back this week.";

    private const string Model = "typesafe/jev-1.13";

    // All three question types in ONE call: many questions, one round trip, one state ingest.
    // The questions are INDEPENDENT — one answer is never context for another.
    //
    // The `choice` descriptions are the whole ball game (ADR 0021): Criteria[id] is the only thing
    // that tells the model what picking `billing` rather than `technical` would MEAN. Options
    // described by their own id are schema-valid, return HTTP 200 — and rank at chance (17 apples
    // -> 0). So: every option carries a real sentence, all three use the SAME template ("own it
    // here when the problem is X: a, b, c"), and no arithmetic is pushed onto the model — the host
    // does the counting and hands over the conclusion.
    private static readonly IReadOnlyDictionary<string, Question> Questions = new Dictionary<string, Question>
    {
        ["wants_money_back"] = new NoulQuestion
        {
            Instructions = "Is the customer asking for money to be returned?",
        },
        ["department"] = new ChoiceQuestion
        {
            Instructions = "Which desk should own this ticket?",
            Criteria = new Dictionary<string, string>
            {
                ["billing"] = "own it here when the problem is money that moved: a duplicate charge, a wrong invoice, a refund owed",
                ["shipping"] = "own it here when the problem is a physical parcel: a late delivery, a package damaged in transit",
                ["technical"] = "own it here when the problem is the product itself: a login that fails, a feature that errors",
            },
        },
        ["urgency"] = new ScoreQuestion
        {
            Instructions = "How fast does this ticket need a human?",
            Criteria = new[]
            {
                "the customer is working normally and is waiting on an answer",
                "the customer is inconvenienced and will chase if nobody replies today",
                "the customer is blocked from working right now and every hour costs them",
            },
        },
    };

    /// <summary>One decision recorded off the live backend, so this file runs with no key and no network.</summary>
    private const string RecordedResponse = """
        {"model":"typesafe/jev-1.13-20260917",
         "answers":{
           "wants_money_back":{"type":"noul","noul":0.99},
           "department":{"type":"choice","choice":"billing","probabilities":{"technical":0,"shipping":0,"billing":1},"confidence":1},
           "urgency":{"type":"score","score":0.49,
             "legend":{"0":"the customer is working normally and is waiting on an answer","1":"the customer is inconvenienced and will chase if nobody replies today","2":"the customer is blocked from working right now and every hour costs them"},
             "probabilities":{"0":0.52,"1":0.48,"2":0},"confidence":0.27}},
         "usage":{"input_tokens":516,"output_tokens":72,"cost":0.000021672}}
        """;

    public static async Task<int> Run()
    {
        var live = !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("OPENROUTER_API_KEY"));

        var judge = live
            ? Classifier.Create(new ClassifierOptions
            {
                BaseUrl = "https://openrouter.ai/api/v1", // serves the System One wire today
                Model = Model,
                ApiKeyEnv = "OPENROUTER_API_KEY", // the NAME of an env var, never the value
                OnMetric = ev =>
                {
                    if (ev.Event == Classifier.MetricWarning) Console.WriteLine("warning: " + ev.Warning);
                },
            })
            : Classifier.Create(new ClassifierOptions
            {
                Style = ClassifierStyle.Static,
                Model = Model,
                Decisions = new[]
                {
                    new RecordedDecision { State = Ticket, Questions = Questions, Response = RecordedResponse },
                },
            });

        Console.WriteLine(live
            ? "backend: systemone (live)"
            : "backend: static (recorded — set OPENROUTER_API_KEY to go live)");

        var d = await judge.EvaluateAsync(Ticket, Questions);

        var want = d.Noul("wants_money_back");
        var dept = d.Choice("department");
        var urg = d.Score("urgency");
        var level = (int)Math.Round(urg.Score, MidpointRounding.AwayFromZero);

        Console.WriteLine($"\nmodel answering: {d.Model}");
        Console.WriteLine($"wants_money_back: {want.Noul}   (a noul carries NO confidence — the number IS the answer)");
        Console.WriteLine($"department:       {dept.Choice}  p={Json.Stringify(dept.Probabilities)} confidence={dept.Confidence}");
        Console.WriteLine($"urgency:          {urg.Score}  of 0..{urg.Legend.Count - 1}  p={Json.Stringify(urg.Probabilities)}");
        Console.WriteLine($"  level {level}: {urg.Levels()[level]}   (a score MAY fall between levels)");

        // The two health flags, and what they actually mean.
        Console.WriteLine(
            $"\ncalibrated: {d.Calibrated}  — these probabilities came from a calibrated backend, so a threshold " +
            "tuned here transfers. An 'llm'-style backend reports false and your thresholds do NOT carry over.");
        Console.WriteLine(
            $"nearUniform(department): {dept.NearUniform}  — max|p - 1/n| <= 0.05, derived from the response. " +
            "True would mean the model had nothing to rank on (usually undescribed options). Advisory, NOT correctness.");

        Console.WriteLine($"\nusage: {d.Usage.InputTokens} in / {d.Usage.OutputTokens} out / ${d.Usage.Cost:0.############}");
        return 0;
    }
}
