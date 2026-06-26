namespace Toolnexus;

/// <summary>
/// Provider adapters — turn the uniform tool list into each LLM's tool schema.
/// Schema only; execution is identical for every provider.
/// </summary>
public static class Adapters
{
    public static List<Dictionary<string, object?>> ToOpenAI(IEnumerable<ITool> tools)
    {
        var output = new List<Dictionary<string, object?>>();
        foreach (var t in tools)
        {
            var fn = new Dictionary<string, object?>
            {
                ["name"] = t.Name,
                ["description"] = t.Description,
                ["parameters"] = t.InputSchema,
            };
            output.Add(new Dictionary<string, object?>
            {
                ["type"] = "function",
                ["function"] = fn,
            });
        }
        return output;
    }

    public static List<Dictionary<string, object?>> ToAnthropic(IEnumerable<ITool> tools)
    {
        var output = new List<Dictionary<string, object?>>();
        foreach (var t in tools)
        {
            output.Add(new Dictionary<string, object?>
            {
                ["name"] = t.Name,
                ["description"] = t.Description,
                ["input_schema"] = t.InputSchema,
            });
        }
        return output;
    }

    public static List<Dictionary<string, object?>> ToGemini(IEnumerable<ITool> tools)
    {
        var decls = new List<object?>();
        foreach (var t in tools)
        {
            decls.Add(new Dictionary<string, object?>
            {
                ["name"] = t.Name,
                ["description"] = t.Description,
                ["parameters"] = t.InputSchema,
            });
        }
        return new List<Dictionary<string, object?>>
        {
            new() { ["functionDeclarations"] = decls },
        };
    }
}
