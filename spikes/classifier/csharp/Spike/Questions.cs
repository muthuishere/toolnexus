namespace Toolnexus.Spike;

/// <summary>
/// A pre-declared question. Three shapes, one field (<c>criteria</c>) that is an object, an
/// array, or absent depending on the shape — modelled as a closed record hierarchy so the
/// shape is the type, not a runtime tag.
/// </summary>
public abstract record Question
{
    public required string Instructions { get; init; }

    /// <summary>The wire form: <c>type</c> + <c>instructions</c> + whatever <c>criteria</c> is here.</summary>
    public abstract Dictionary<string, object?> ToWire();
}

/// <summary>0..1 truth. <c>criteria</c> is absent, or the two labels <c>{true,false}</c>.</summary>
public sealed record Noul : Question
{
    public (string True, string False)? Criteria { get; init; }

    public override Dictionary<string, object?> ToWire()
    {
        var w = new Dictionary<string, object?> { ["type"] = "noul", ["instructions"] = Instructions };
        if (Criteria is { } c)
            w["criteria"] = new Dictionary<string, object?> { ["true"] = c.True, ["false"] = c.False };
        return w;
    }
}

/// <summary>One of ≤255 named options. <c>criteria</c> is an OBJECT {name: description}.</summary>
public sealed record Choice : Question
{
    public required IReadOnlyDictionary<string, string?> Criteria { get; init; }

    public override Dictionary<string, object?> ToWire() => new()
    {
        ["type"] = "choice",
        ["instructions"] = Instructions,
        ["criteria"] = Criteria.ToDictionary(kv => kv.Key, kv => (object?)kv.Value),
    };
}

/// <summary>2..10 ordered levels. <c>criteria</c> is an ARRAY — index IS the level, never sorted.</summary>
public sealed record Score : Question
{
    public required IReadOnlyList<string> Criteria { get; init; }

    public override Dictionary<string, object?> ToWire() => new()
    {
        ["type"] = "score",
        ["instructions"] = Instructions,
        ["criteria"] = Criteria.Cast<object?>().ToList(),
    };
}
