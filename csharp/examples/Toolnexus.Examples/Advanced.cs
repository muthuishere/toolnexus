namespace Toolnexus.Examples;

/// <summary>
/// Offline showcase of the non-LLM building blocks: a native (annotation) tool, a
/// programmatic native tool, an HTTP tool declaration, and the three provider
/// adapters — all aggregated into one toolkit. No network / no LLM key required.
/// </summary>
internal static class Advanced
{
    private sealed class Calc
    {
        [ToolMethod("multiply", "Multiply two numbers")]
        public string Multiply([Param("a")] double a, [Param("b")] double b) => (a * b).ToString();
    }

    public static async Task<int> Run()
    {
        var weather = HttpTool.Of(new HttpTool.Options
        {
            Name = "weather",
            Description = "Get weather for a city",
            Method = "GET",
            Url = "https://example.com/weather/{city}",
            InputSchema = new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["city"] = new Dictionary<string, object?> { ["type"] = "string" },
                },
                ["required"] = new List<object?> { "city" },
            },
        });

        var greet = NativeTool.Of("greet", "Greet someone",
            new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["name"] = new Dictionary<string, object?> { ["type"] = "string" },
                },
            },
            args => $"Hello, {(args.TryGetValue("name", out var n) ? n : null) ?? "world"}!");

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
        {
            SkillsDir = new List<string> { Examples.Fixture("skills") },
            ExtraTools = new List<ITool> { weather, greet },
            AnnotatedObjects = new List<object> { new Calc() },
        });

        Console.WriteLine("Tools: [" + string.Join(", ", tk.Tools().Select(t => $"{t.Name} ({t.Source})")) + "]");

        var sum = await tk.ExecuteAsync("multiply", new Dictionary<string, object?> { ["a"] = 6, ["b"] = 7 });
        Console.WriteLine($"multiply(6,7) -> {sum.Output}");

        var hi = await tk.ExecuteAsync("greet", new Dictionary<string, object?> { ["name"] = "Ada" });
        Console.WriteLine($"greet(Ada) -> {hi.Output}");

        Console.WriteLine("\nAnthropic schema:\n" + Json.Pretty(tk.ToAnthropic()));
        Console.WriteLine("\nGemini schema:\n" + Json.Pretty(tk.ToGemini()));
        return 0;
    }
}
