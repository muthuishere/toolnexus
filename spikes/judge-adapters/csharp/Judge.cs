using System.Globalization;
using Toolnexus;

namespace JudgeSpike;

/// <summary>One named question: the ordered-list element callers write.</summary>
public sealed record Ask(string Name, Question Question);

/// <summary>Cut-offs. Exclusive on the confident side: exactly Low or High is uncertain.</summary>
public sealed record Bands(double Low = 0.30, double High = 0.70)
{
    public static readonly Bands Default = new();

    public string Noul(double p) => p < Low ? "no" : p > High ? "yes" : "uncertain";
    public bool Sure(ChoiceAnswer a) => !a.NearUniform && a.Confidence > High;
    public bool Sure(ScoreAnswer a) => a.Confidence > High;

    public string Of(DecisionAnswer a) => a switch
    {
        NoulAnswer n => Noul(n.Noul),
        ChoiceAnswer c => Sure(c) ? "yes" : "uncertain",
        ScoreAnswer s => Sure(s) ? "yes" : "uncertain",
        _ => "uncertain",
    };
}

/// <summary>An answer plus its band ("yes" | "no" | "uncertain").</summary>
public sealed record Verdict(DecisionAnswer Answer, string Band);

/// <summary>First-match gate rule. Build with <see cref="Below"/>, <see cref="AtLeast"/>, <see cref="Is"/>.</summary>
public sealed record Rule(string Question, string Action, string Target = "")
{
    public double? BelowValue { get; init; }
    public double? AtLeastValue { get; init; }
    public string? IsValue { get; init; }

    public static Rule Below(string q, double v, string action, string target = "") => new(q, action, target) { BelowValue = v };
    public static Rule AtLeast(string q, double v, string action, string target = "") => new(q, action, target) { AtLeastValue = v };
    public static Rule Is(string q, string option, string action, string target = "") => new(q, action, target) { IsValue = option };
}

/// <summary>Action fired, or Escalated with a §10 request, or neither (fall through).</summary>
public sealed record Outcome(string Action = "", string Target = "", bool Escalated = false, Request? Request = null);

public static class Judge
{
    // ---- builders
    public static Ask Noul(string name, string instructions) => new(name, new NoulQuestion { Instructions = instructions });
    public static Ask Choice(string name, string instructions, IReadOnlyDictionary<string, string> options)
        => new(name, new ChoiceQuestion { Instructions = instructions, Criteria = options });
    public static Ask Score(string name, string instructions, params string[] levels)
        => new(name, new ScoreQuestion { Instructions = instructions, Criteria = levels });

    /// <summary>Sugar: {context, message} + extra keys.</summary>
    public static Dictionary<string, object?> State(string context, string message, IReadOnlyDictionary<string, object?>? extra = null)
    {
        var s = new Dictionary<string, object?> { ["context"] = context, ["message"] = message };
        foreach (var (k, v) in extra ?? new Dictionary<string, object?>()) s[k] = v;
        return s;
    }

    /// <summary>The ordered list to the §8B map. A duplicate name throws, naming the key.</summary>
    public static Dictionary<string, Question> Questions(IEnumerable<Ask> asks)
    {
        var m = new Dictionary<string, Question>();
        foreach (var a in asks)
            if (!m.TryAdd(a.Name, a.Question))
                throw new ArgumentException($"duplicate question name \"{a.Name}\"");
        return m;
    }

    // ---- verbs
    public static async Task<Dictionary<string, Verdict>> AskAsync(Classifier c, object? state, IEnumerable<Ask> asks,
        Bands? bands = null, CancellationToken ct = default)
    {
        var b = bands ?? Bands.Default;
        var d = await c.EvaluateAsync(state, Questions(asks), ct);
        return d.Answers.ToDictionary(kv => kv.Key, kv => new Verdict(kv.Value, b.Of(kv.Value)));
    }

    public static async Task<Outcome> GateAsync(Classifier c, object? state, IEnumerable<Ask> asks, IEnumerable<Rule> rules,
        Bands? bands = null, CancellationToken ct = default)
        => Apply(await c.EvaluateAsync(state, Questions(asks), ct), rules, bands ?? Bands.Default);

    /// <summary>Pure half of the gate.</summary>
    public static Outcome Apply(Decision d, IEnumerable<Rule> rules, Bands b)
    {
        var i = 0;
        foreach (var r in rules)
        {
            var (fired, reason) = Check(d, r, b);
            if (reason != null)
                return new Outcome("needs_input", "", true, new Request
                {
                    Id = $"gate:{i}:{r.Question}",
                    Kind = "input",
                    Prompt = $"Classifier is unsure about \"{r.Question}\" ({reason}). Decide rule {i} ({r.Action}).",
                    Data = new Dictionary<string, object?> { ["question"] = r.Question, ["reason"] = reason, ["answers"] = d.Answers },
                });
            if (fired) return new Outcome(r.Action, r.Target);
            i++;
        }
        return new Outcome();
    }

    private static string F(double v) => v.ToString("0.00", CultureInfo.InvariantCulture);

    private static (bool Fired, string? Reason) Check(Decision d, Rule r, Bands b)
    {
        if (!d.Answers.TryGetValue(r.Question, out var a)) return (false, "missing answer");
        if (r.IsValue is { } opt)
            return a is ChoiceAnswer c
                ? b.Sure(c) ? (c.Choice == opt, null) : (false, $"choice \"{c.Choice}\" confidence {F(c.Confidence)} nearUniform={c.NearUniform}")
                : (false, $"is-rule on {a.AnswerType} answer");

        double v;
        switch (a)
        {
            case NoulAnswer n when b.Noul(n.Noul) == "uncertain":
                return (false, $"noul {F(n.Noul)} in uncertain band [{F(b.Low)},{F(b.High)}]");
            case NoulAnswer n: v = n.Noul; break;
            case ScoreAnswer s when !b.Sure(s):
                return (false, $"score confidence {F(s.Confidence)} <= {F(b.High)}");
            case ScoreAnswer s: v = s.Score; break;
            default: return (false, $"numeric rule on {a.AnswerType} answer");
        }
        if (r.BelowValue is { } lo) return (v < lo, null);
        if (r.AtLeastValue is { } hi) return (v >= hi, null);
        return (false, "rule has no condition");
    }
}
