namespace Toolnexus.Examples;

/// <summary>
/// Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as ITool is the contract for an ACTION.
///
///   dotnet run -- judge
///
/// Three backends, picked by what is in the environment: TYPESAFE_API_KEY calls TypeSafe's own API,
/// OPENROUTER_API_KEY calls the same wire through OpenRouter's gateway, and with no key at all it
/// replays one recorded decision through the <c>static</c> backend — so this runs offline,
/// uncredentialed.
/// </summary>
internal static class Judge
{
    // ---- the state: whatever the host already has. Sent verbatim, never canonicalised. ----
    private const string Ticket =
        "Ticket 4021: my card was charged twice for the annual plan on Tuesday, and the second charge " +
        "has not been refunded. I am not blocked from working, but I would like the money back this week.";

    /// <summary>OpenRouter's name for the model; <c>static</c> replays a recording made under it.</summary>
    private const string Model = "typesafe/jev-1.13";

    /// <summary>Which of the three backends the environment selects.</summary>
    private enum Backend { TypeSafe, OpenRouter, Static }

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
        // Same wire, two ways in. TypeSafe's own API is a first-party key and no gateway in the
        // path; OpenRouter is a gateway you may already hold a key for, and the only one of the two
        // that reports `usage.cost`. They are equivalent in latency — neither is the "fast" one.
        var backend =
            !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("TYPESAFE_API_KEY")) ? Backend.TypeSafe
            : !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("OPENROUTER_API_KEY")) ? Backend.OpenRouter
            : Backend.Static;

        Action<MetricEvent> onMetric = ev =>
        {
            if (ev.Event == Classifier.MetricWarning) Console.WriteLine("warning: " + ev.Warning);
        };

        var judge = backend switch
        {
            Backend.TypeSafe => Classifier.Create(new ClassifierOptions
            {
                BaseUrl = "https://api.typesafe.ai/v1", // the library default; spelled out so it is visible
                Model = "jev-latest",
                ApiKeyEnv = "TYPESAFE_API_KEY", // the NAME of an env var, never the value
                OnMetric = onMetric,
            }),
            Backend.OpenRouter => Classifier.Create(new ClassifierOptions
            {
                BaseUrl = "https://openrouter.ai/api/v1", // a gateway that serves the same System One wire
                Model = Model,
                ApiKeyEnv = "OPENROUTER_API_KEY",
                OnMetric = onMetric,
            }),
            _ => Classifier.Create(new ClassifierOptions
            {
                Style = ClassifierStyle.Static,
                Model = Model,
                Decisions = new[]
                {
                    new RecordedDecision { State = Ticket, Questions = Questions, Response = RecordedResponse },
                },
            }),
        };

        Console.WriteLine(backend switch
        {
            Backend.TypeSafe => "backend: systemone via api.typesafe.ai (live)",
            Backend.OpenRouter => "backend: systemone via openrouter.ai (live)",
            _ => "backend: static (recorded — set TYPESAFE_API_KEY or OPENROUTER_API_KEY to go live)",
        });

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

        // Cost is a gateway field. TypeSafe's own API does not return one, and absent is NOT zero —
        // print "cost: not reported" rather than a $0.00 that would read as a free call.
        var cost = d.Usage.Cost is { } c ? $" / ${c:0.############}" : " / cost: not reported by this backend";
        Console.WriteLine($"\nusage: {d.Usage.InputTokens} in / {d.Usage.OutputTokens} out{cost}");
        return 0;
    }
}
