using System.Globalization;
using System.Text.Json;

namespace Toolnexus;

// The Decisions API wire (SPEC.md §8B "Decisions wire", OpenSpec change
// add-classifier-openai-decisions, issue #157). OpenAI's POST /v1/decisions takes the same three
// question kinds as System One under other names (predicate / choice / score) as an ORDERED LIST,
// and answers in that order. This maps a §8B question set onto that list and reads the answers
// back into the one Decision type, so a caller never sees which wire answered.

public sealed partial class Classifier
{
    /// <summary>The question set as the Decisions API list, in ASCII key order. Caller keys are NOT
    /// sent (no <c>name</c>): §8B keys are addressing, not content, and answers come back in request
    /// order. Every check here fires before any request.</summary>
    private List<object?> DecisionsQuestions(IReadOnlyDictionary<string, Question> questions)
    {
        if (questions.Count > ClassifierBackends.MaxDecisionsQuestions)
            throw new ClassifierException(
                $"classifier: the decisions wire takes at most {ClassifierBackends.MaxDecisionsQuestions} questions, got {questions.Count}");
        var list = new List<object?>();
        foreach (var k in questions.Keys.OrderBy(k => k, StringComparer.Ordinal))
        {
            switch (questions[k])
            {
                case NoulQuestion q:
                {
                    var instr = q.Instructions;
                    if (q.Criteria != null)
                        instr += "\n\nTrue means: " + q.Criteria.True + "\nFalse means: " + q.Criteria.False;
                    list.Add(new Dictionary<string, object?> { ["type"] = "predicate", ["instructions"] = instr });
                    break;
                }
                case ChoiceQuestion q:
                {
                    if (q.Criteria.Count < 2)
                        throw new ClassifierException(
                            $"classifier: question \"{k}\": the decisions wire needs 2..{MaxChoiceOptions} choice options, got {q.Criteria.Count}");
                    var choices = new List<object?>();
                    foreach (var id in q.Criteria.Keys.OrderBy(x => x, StringComparer.Ordinal))
                    {
                        var c = new Dictionary<string, object?> { ["value"] = id };
                        if (q.Criteria[id] is { Length: > 0 } d) c["description"] = d;
                        choices.Add(c);
                    }
                    list.Add(new Dictionary<string, object?>
                    {
                        ["type"] = "choice", ["instructions"] = q.Instructions, ["choices"] = choices,
                    });
                    break;
                }
                case ScoreQuestion q:
                    list.Add(new Dictionary<string, object?>
                    {
                        ["type"] = "score", ["instructions"] = q.Instructions,
                        ["levels"] = q.Criteria.Select(l => (object?)new Dictionary<string, object?> { ["label"] = l }).ToList(),
                    });
                    break;
                case RawQuestion r:
                    throw new ClassifierUnsupportedException(ClassifierBackends.Name(BackendName), Model,
                        $"raw question type \"{r.Type}\"");
                default:
                    throw new ClassifierException($"classifier: question \"{k}\": unsupported question");
            }
        }
        return list;
    }

    /// <summary>The state as sent: a string verbatim, anything else as its canonical JSON text;
    /// with images, one user message holding that text then the images as data URLs, in
    /// collection order.</summary>
    private static object? DecisionsInput(PreparedRequest p)
    {
        var text = p.State switch
        {
            string s => s,
            JsonElement { ValueKind: JsonValueKind.String } je => je.GetString()!,
            _ => CanonicalJsonString(p.State),
        };
        if (p.Images.Count == 0) return text;
        var content = new List<object?> { new Dictionary<string, object?> { ["type"] = "input_text", ["text"] = text } };
        foreach (var im in p.Images)
            content.Add(new Dictionary<string, object?>
            {
                ["type"] = "input_image", ["image_url"] = $"data:image/{im.Format};base64,{im.Data}",
            });
        return new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = content } };
    }

    /// <summary>A Decisions API reply as a System One-shaped body (canonical JSON), so decode,
    /// validation and the cache are the one path. <c>calibrated</c> is false: a Decisions model is
    /// not a System One model.</summary>
    internal static string DecisionsReply(string text, IReadOnlyDictionary<string, Question> questions)
    {
        var malformed = new ClassifierException("classifier: decisions: malformed reply");
        JsonDocument doc;
        try { doc = JsonDocument.Parse(text); }
        catch (JsonException) { throw malformed; }
        using (doc)
        {
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("answers", out var arr)
                || arr.ValueKind != JsonValueKind.Array)
                throw malformed;
            var keys = questions.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();
            if (arr.GetArrayLength() != keys.Count)
                throw new ClassifierException($"classifier: decisions: {arr.GetArrayLength()} answers for {keys.Count} questions");
            var answers = new Dictionary<string, object?>();
            var i = 0;
            foreach (var a in arr.EnumerateArray())
            {
                var k = keys[i++];
                if (a.ValueKind != JsonValueKind.Object) throw malformed;
                var type = a.TryGetProperty("type", out var t) && t.ValueKind == JsonValueKind.String ? t.GetString() : "";
                if (type == "refusal") throw new ClassifierException($"classifier: decisions: question \"{k}\" was refused");
                var q = questions[k];
                var want = q switch
                {
                    NoulQuestion => "predicate",
                    ChoiceQuestion => "choice",
                    ScoreQuestion => "score",
                    _ => "",
                };
                if (type != want)
                    throw new ClassifierException($"classifier: decisions: answer \"{k}\" is a {type} answer, not {want}");
                var probs = new Dictionary<string, object?>();
                if (a.TryGetProperty("probabilities", out var ps) && ps.ValueKind == JsonValueKind.Array)
                    foreach (var p in ps.EnumerateArray())
                        probs[ValueKey(p.TryGetProperty("value", out var v) ? v : default)] = Num(p, "probability");
                switch (q)
                {
                    case NoulQuestion:
                        answers[k] = new Dictionary<string, object?> { ["type"] = "noul", ["noul"] = Num(a, "probability") };
                        break;
                    case ChoiceQuestion:
                        answers[k] = new Dictionary<string, object?>
                        {
                            ["type"] = "choice",
                            ["choice"] = ValueKey(a.TryGetProperty("choice", out var c) ? c : default),
                            ["probabilities"] = probs, ["confidence"] = Num(a, "confidence"),
                        };
                        break;
                    case ScoreQuestion sq:
                        var legend = new Dictionary<string, object?>();
                        for (var j = 0; j < sq.Criteria.Count; j++) legend[j.ToString(CultureInfo.InvariantCulture)] = sq.Criteria[j];
                        answers[k] = new Dictionary<string, object?>
                        {
                            ["type"] = "score", ["score"] = Num(a, "score"), ["legend"] = legend,
                            ["probabilities"] = probs, ["confidence"] = Num(a, "confidence"),
                        };
                        break;
                }
            }
            long inTok = 0, outTok = 0;
            if (root.TryGetProperty("usage", out var u) && u.ValueKind == JsonValueKind.Object)
            {
                inTok = (long)Num(u, "input_tokens");
                outTok = (long)Num(u, "output_tokens");
            }
            return CanonicalJsonString(new Dictionary<string, object?>
            {
                ["answers"] = answers,
                ["calibrated"] = false,
                ["model"] = root.TryGetProperty("model", out var m) && m.ValueKind == JsonValueKind.String ? m.GetString() : "",
                ["usage"] = new Dictionary<string, object?> { ["input_tokens"] = inTok, ["output_tokens"] = outTok },
            });
        }
    }

    private static double Num(JsonElement o, string name)
        => o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : 0;

    /// <summary>A choice value or level index as the §8B key: a string as written, a boolean as
    /// "true"/"false", a number in its shortest form.</summary>
    private static string ValueKey(JsonElement v) => v.ValueKind switch
    {
        JsonValueKind.String => v.GetString() ?? "",
        JsonValueKind.True => "true",
        JsonValueKind.False => "false",
        JsonValueKind.Number => v.GetDouble().ToString("R", CultureInfo.InvariantCulture),
        JsonValueKind.Undefined => "",
        _ => v.GetRawText(),
    };
}
