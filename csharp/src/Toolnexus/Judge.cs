using System.Collections.Concurrent;
using System.Text.Json;

namespace Toolnexus;

// §8B "Simple judgments — ask / gate" (change add-judge-adapters). A thin layer over any
// Classifier: the wire is unchanged, and a host that uses none of this sees no difference.

/// <summary>One named question — the element of the ORDERED list callers write.
/// Build with <see cref="Judge.Noul"/>, <see cref="Judge.Choice"/>, <see cref="Judge.Score"/>.</summary>
public sealed record Ask(string Name, Question Question);

/// <summary>Cut-offs, exclusive on the confident side: exactly <see cref="Low"/> or
/// <see cref="High"/> is uncertain. Default 0.30 / 0.70.</summary>
public sealed record Bands(double Low = 0.30, double High = 0.70)
{
    public static readonly Bands Default = new();

    /// <summary>"no" if p &lt; Low, "yes" if p &gt; High, else "uncertain".</summary>
    public string Noul(double p) => p < Low ? "no" : p > High ? "yes" : "uncertain";

    /// <summary>A choice is sure iff confidence &gt; High and not near-uniform.</summary>
    public bool Sure(ChoiceAnswer a) => !a.NearUniform && a.Confidence > High;

    /// <summary>A score is sure iff confidence &gt; High.</summary>
    public bool Sure(ScoreAnswer a) => a.Confidence > High;
}

/// <summary>One answer of <see cref="Judge.AskAsync"/>: the typed answer plus its band
/// (noul) or sureness (choice/score).</summary>
public sealed record JudgeAnswer(DecisionAnswer Raw, string? Band, bool Sure)
{
    /// <summary>The one number: noul probability, score value, or choice confidence.</summary>
    public double Value() => Raw switch
    {
        NoulAnswer n => n.Noul,
        ScoreAnswer s => s.Score,
        ChoiceAnswer c => c.Confidence,
        _ => 0,
    };

    /// <summary>The picked option of a choice answer; throws on any other answer.</summary>
    public string Choice() => Raw is ChoiceAnswer c
        ? c.Choice
        : throw new ClassifierException($"judge: answer is a {Raw.AnswerType} answer, not choice");
}

/// <summary>First-match gate rule. Build with <see cref="Below"/>, <see cref="AtLeast"/>, <see cref="Is"/>.</summary>
public sealed record Rule(string Question, string Action, string Target = "")
{
    public double? BelowValue { get; init; }
    public double? AtLeastValue { get; init; }
    public string? IsValue { get; init; }

    public static Rule Below(string question, double v, string action, string target = "")
        => new(question, action, target) { BelowValue = v };
    public static Rule AtLeast(string question, double v, string action, string target = "")
        => new(question, action, target) { AtLeastValue = v };
    public static Rule Is(string question, string option, string action, string target = "")
        => new(question, action, target) { IsValue = option };
}

/// <summary>The result of a gate: an action fired, an escalation carrying a §10 <c>input</c>
/// <see cref="Request"/>, or neither (fall through: empty action).</summary>
public sealed record GateOutcome(string Action = "", string Target = "", bool Escalated = false, Request? Request = null);

/// <summary>Rules plus a declared fall-through. Empty <see cref="Default"/> escalates with
/// reason <c>no rule fired</c>; <see cref="SkipUncertain"/> skips uncertain rules.</summary>
public sealed record Policy
{
    public IReadOnlyList<Rule> Rules { get; init; } = Array.Empty<Rule>();
    public string Default { get; init; } = "";
    public Bands? Bands { get; init; }
    public bool SkipUncertain { get; init; }
}

public static class Judge
{
    // ---------------------------------------------------------------- builders

    public static Ask Noul(string name, string instructions)
        => new(name, new NoulQuestion { Instructions = instructions });

    /// <summary>A noul with criteria: what the true and the false case mean.</summary>
    public static Ask Noul(string name, string instructions, string whenTrue, string whenFalse)
        => new(name, new NoulQuestion { Instructions = instructions, Criteria = new NoulCriteria { True = whenTrue, False = whenFalse } });

    public static Ask Choice(string name, string instructions, IReadOnlyDictionary<string, string> options)
        => new(name, new ChoiceQuestion { Instructions = instructions, Criteria = options });

    public static Ask Score(string name, string instructions, params string[] levels)
        => new(name, new ScoreQuestion { Instructions = instructions, Criteria = levels });

    /// <summary>The ordered list to the §8B question map. A duplicate name throws, naming it.</summary>
    public static Dictionary<string, Question> Questions(IEnumerable<Ask> asks)
    {
        var m = new Dictionary<string, Question>();
        foreach (var a in asks)
            if (!m.TryAdd(a.Name, a.Question))
                throw new ClassifierException($"duplicate question name \"{a.Name}\"");
        return m;
    }

    /// <summary><c>{role, ...data}</c>: the role sits next to the data's fields. Data that is not an
    /// object goes under <c>data</c>. The role is never copied into question instructions.</summary>
    public static Dictionary<string, object?> State(string role, object? data)
    {
        var s = new Dictionary<string, object?> { ["role"] = role };
        switch (data)
        {
            case IEnumerable<KeyValuePair<string, object?>> map:
                foreach (var (k, v) in map) s[k] = v;
                break;
            case JsonElement { ValueKind: JsonValueKind.Object } el:
                foreach (var p in el.EnumerateObject()) s[p.Name] = p.Value.Clone();
                break;
            default:
                s["data"] = data;
                break;
        }
        return s;
    }

    /// <summary>Sugar: <c>{context, message}</c> plus any extra fields.</summary>
    public static Dictionary<string, object?> Context(string context, string message,
        IReadOnlyDictionary<string, object?>? extra = null)
    {
        var s = new Dictionary<string, object?> { ["context"] = context, ["message"] = message };
        if (extra != null) foreach (var (k, v) in extra) s[k] = v;
        return s;
    }

    // ---------------------------------------------------------------- verbs

    /// <summary>Evaluate and return one <see cref="JudgeAnswer"/> per question name.</summary>
    public static async Task<Dictionary<string, JudgeAnswer>> AskAsync(Classifier classifier, object? state,
        IEnumerable<Ask> questions, Bands? bands = null, CancellationToken ct = default)
    {
        var b = bands ?? Bands.Default;
        var d = await classifier.EvaluateAsync(state, Questions(questions), ct).ConfigureAwait(false);
        return d.Answers.ToDictionary(kv => kv.Key, kv => Wrap(kv.Value, b));
    }

    /// <summary>Band a raw answer.</summary>
    public static JudgeAnswer Wrap(DecisionAnswer a, Bands? bands = null)
    {
        var b = bands ?? Bands.Default;
        return a switch
        {
            NoulAnswer n => new JudgeAnswer(a, b.Noul(n.Noul), b.Noul(n.Noul) != "uncertain"),
            ChoiceAnswer c => new JudgeAnswer(a, null, b.Sure(c)),
            ScoreAnswer s => new JudgeAnswer(a, null, b.Sure(s)),
            _ => new JudgeAnswer(a, null, false),
        };
    }

    /// <summary>Evaluate, then apply rules first-match. Nothing fired ⇒ empty action (fall through).</summary>
    public static async Task<GateOutcome> GateAsync(Classifier classifier, object? state, IEnumerable<Ask> questions,
        IEnumerable<Rule> rules, Bands? bands = null, CancellationToken ct = default)
        => Apply(await classifier.EvaluateAsync(state, Questions(questions), ct).ConfigureAwait(false),
            new Policy { Rules = rules.ToList(), Bands = bands }, fallThrough: true);

    /// <summary>Evaluate, then apply a <see cref="Policy"/> including its default.</summary>
    public static async Task<GateOutcome> GateAsync(Classifier classifier, object? state, IEnumerable<Ask> questions,
        Policy policy, CancellationToken ct = default)
        => Apply(await classifier.EvaluateAsync(state, Questions(questions), ct).ConfigureAwait(false), policy);

    /// <summary>The pure half of the gate (no request). <paramref name="fallThrough"/> returns an
    /// empty outcome when no rule fires instead of applying the policy's default.</summary>
    public static GateOutcome Apply(Decision d, Policy policy, bool fallThrough = false)
    {
        var b = policy.Bands ?? Bands.Default;
        var i = 0;
        foreach (var r in policy.Rules)
        {
            var (fired, reason, unsure) = Check(d, r, b);
            if (reason != null)
            {
                if (!(policy.SkipUncertain && unsure)) return Escalate(d, r.Question, reason, $"gate:{i}:{r.Question}",
                    $"Classifier is unsure about \"{r.Question}\" ({reason}). Decide rule {i} ({r.Action}).");
            }
            else if (fired) return new GateOutcome(r.Action, r.Target);
            i++;
        }
        if (fallThrough) return new GateOutcome();
        if (policy.Default.Length > 0) return new GateOutcome(policy.Default);
        return Escalate(d, "", "no rule fired", "gate:default", "No rule fired. Decide the action.");
    }

    private static GateOutcome Escalate(Decision d, string question, string reason, string id, string prompt)
        => new("needs_input", "", true, new Request
        {
            Id = id,
            Kind = "input",
            Prompt = prompt,
            Data = new Dictionary<string, object?> { ["question"] = question, ["reason"] = reason, ["answers"] = d.Answers },
        });

    // Per rule, in this order (SPEC §8B): missing, then uncertain, then fit. A misfit rule escalates
    // and is never skipped by SkipUncertain.
    private static (bool Fired, string? Reason, bool Unsure) Check(Decision d, Rule r, Bands b)
    {
        if (!d.Answers.TryGetValue(r.Question, out var a)) return (false, $"missing answer \"{r.Question}\"", false);
        var sure = a switch
        {
            NoulAnswer n => b.Noul(n.Noul) != "uncertain",
            ChoiceAnswer c => b.Sure(c),
            ScoreAnswer s => b.Sure(s),
            _ => true,
        };
        if (!sure) return (false, $"uncertain answer \"{r.Question}\"", true);
        if (r.IsValue is { } opt)
            return a is ChoiceAnswer c2 ? (c2.Choice == opt, null, false) : (false, $"is-rule on {a.AnswerType} answer", false);
        double v;
        switch (a)
        {
            case NoulAnswer n: v = n.Noul; break;
            case ScoreAnswer s: v = s.Score; break;
            default: return (false, $"numeric rule on {a.AnswerType} answer", false);
        }
        if (r.BelowValue is { } lo) return (v < lo, null, false);
        if (r.AtLeastValue is { } hi) return (v >= hi, null, false);
        return (false, "rule has no condition", false);
    }
}

/// <summary>
/// Record live decisions by a caller-given call name, replay them with no network. A replay of an
/// unrecorded name throws, naming it.
/// </summary>
public sealed class Tape
{
    private readonly ConcurrentDictionary<string, Decision> _decisions = new();

    public IReadOnlyDictionary<string, Decision> Decisions => _decisions;

    /// <summary>Evaluate through <paramref name="live"/> and record the decision under <paramref name="call"/>.</summary>
    public async Task<Decision> RecordAsync(string call, Classifier live, object? state,
        IReadOnlyDictionary<string, Question> questions, CancellationToken ct = default)
    {
        var d = await live.EvaluateAsync(state, questions, ct).ConfigureAwait(false);
        _decisions[call] = d;
        return d;
    }

    /// <summary>Seed a decision (e.g. loaded from disk).</summary>
    public Tape Put(string call, Decision d) { _decisions[call] = d; return this; }

    /// <summary>A classifier that replays the decision recorded under <paramref name="call"/>.</summary>
    public Classifier Replay(string call) => new(new ClassifierOptions
    {
        Style = ClassifierStyle.Custom,
        Evaluate = (_, _, _) => _decisions.TryGetValue(call, out var d)
            ? Task.FromResult(d)
            : throw new ClassifierException($"tape: no recorded decision for call \"{call}\""),
    });
}
