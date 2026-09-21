using Toolnexus.Agents;

namespace Toolnexus.Spike;

/// <summary>What a judge ruled: a band plus the numbers it ruled on.</summary>
public sealed record Verdict(string Kept, IReadOnlyDictionary<string, double> Evidence, string Model)
{
    public string Reason => $"{Kept} (risk {Evidence.GetValueOrDefault("score"):0.##}, " +
                            $"untrusted {Evidence.GetValueOrDefault("from_untrusted"):0.##})";
}

/// <summary>
/// The composable surface: the judge looks <c>On</c> this, <c>Ask</c>s these, and rules by
/// <c>Bands</c>. Only the bands rule is spiked.
/// </summary>
public sealed class Judge
{
    /// <summary>Upper bound (exclusive) of a band, and what that band is called.</summary>
    public readonly record struct Band(double Below, string Label);

    public required Func<LlmClient.BeforeToolEvent, object> On { get; init; }
    public required IReadOnlyDictionary<string, Question> Ask { get; init; }
    public required string ScoreKey { get; init; }
    public required IReadOnlyList<Band> Bands { get; init; }
    public required Classifier Classifier { get; init; }

    public async Task<Verdict> RuleAsync(LlmClient.BeforeToolEvent ev, CancellationToken ct = default)
    {
        var decision = await Classifier.EvaluateAsync(On(ev), Ask, ct).ConfigureAwait(false);
        var evidence = new Dictionary<string, double>();
        foreach (var (k, a) in decision.Answers)
            evidence[k == ScoreKey ? "score" : k] = a switch
            {
                NoulAnswer n => n.Noul,
                ScoreAnswer s => s.Score,
                ChoiceAnswer c => c.Confidence,
                _ => 0,
            };
        var score = evidence["score"];
        var label = Bands.FirstOrDefault(b => score < b.Below, Bands[^1]).Label;
        return new Verdict(label, evidence, decision.Model);
    }

    /// <summary>
    /// Adapter: the existing <see cref="Guardrail"/> type. "" =&gt; allow, reason =&gt; deny.
    /// A judge can only move a call toward ask/deny — there is no path here that returns
    /// "allow" as an override, so an earlier denial can never be widened.
    /// (§10 <c>ask</c> =&gt; Pending is out of spike scope: ask falls back to deny-with-reason.)
    /// </summary>
    public Guardrail AsGuardrail()
        => ev =>
        {
            // Guardrail is synchronous today; a network-backed judge must block here.
            var v = RuleAsync(ev).GetAwaiter().GetResult();
            return v.Kept == "allow" ? "" : v.Reason;
        };
}
