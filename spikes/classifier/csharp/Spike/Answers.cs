using System.Text.Json;
using System.Text.Json.Serialization;

namespace Toolnexus.Spike;

/// <summary>One answer. Discriminated by the wire's <c>type</c> field.</summary>
[JsonConverter(typeof(AnswerConverter))]
public abstract record Answer;

public sealed record NoulAnswer(double Noul) : Answer;

public sealed record ChoiceAnswer(
    string Choice,
    IReadOnlyDictionary<string, double> Probabilities,
    double Confidence) : Answer;

public sealed record ScoreAnswer(
    double Score,
    IReadOnlyDictionary<string, string> Legend,
    IReadOnlyDictionary<string, double> Probabilities,
    double Confidence) : Answer;

public sealed record Usage(long InputTokens, long OutputTokens, double? Cost);

public sealed record Decision(
    string Model,
    IReadOnlyDictionary<string, Answer> Answers,
    Usage Usage);

/// <summary>Reads the discriminated union off <c>type</c>. Write is not needed (answers are inbound).</summary>
public sealed class AnswerConverter : JsonConverter<Answer>
{
    public override Answer Read(ref Utf8JsonReader reader, Type t, JsonSerializerOptions o)
    {
        using var doc = JsonDocument.ParseValue(ref reader);
        var e = doc.RootElement;
        var type = e.GetProperty("type").GetString();
        return type switch
        {
            "noul" => new NoulAnswer(e.GetProperty("noul").GetDouble()),
            "choice" => new ChoiceAnswer(
                e.GetProperty("choice").GetString()!,
                Doubles(e.GetProperty("probabilities")),
                e.GetProperty("confidence").GetDouble()),
            "score" => new ScoreAnswer(
                e.GetProperty("score").GetDouble(),
                Strings(e.GetProperty("legend")),
                Doubles(e.GetProperty("probabilities")),
                e.GetProperty("confidence").GetDouble()),
            _ => throw new JsonException($"unknown answer type: {type}"),
        };
    }

    public override void Write(Utf8JsonWriter w, Answer v, JsonSerializerOptions o)
        => throw new NotSupportedException("answers are inbound only");

    private static Dictionary<string, double> Doubles(JsonElement e)
        => e.EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetDouble());

    private static Dictionary<string, string> Strings(JsonElement e)
        => e.EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetString()!);
}

public static class DecisionParser
{
    private static readonly JsonSerializerOptions Opts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
    };

    public static Decision Parse(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        var answers = new Dictionary<string, Answer>();
        foreach (var p in root.GetProperty("answers").EnumerateObject())
            answers[p.Name] = p.Value.Deserialize<Answer>(Opts)!;
        var u = root.GetProperty("usage");
        var usage = new Usage(
            u.GetProperty("input_tokens").GetInt64(),
            u.GetProperty("output_tokens").GetInt64(),
            u.TryGetProperty("cost", out var c) ? c.GetDouble() : null);
        return new Decision(root.GetProperty("model").GetString()!, answers, usage);
    }
}
