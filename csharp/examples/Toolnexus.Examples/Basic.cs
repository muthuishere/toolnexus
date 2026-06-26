namespace Toolnexus.Examples;

/// <summary>
/// Minimal end-to-end example. Loads the shared fixtures, prints MCP status, tools,
/// the skill catalog, the first two OpenAI tool schemas, then loads the hello-world
/// skill (progressive disclosure).
/// </summary>
internal static class Basic
{
    public static async Task<int> Run()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options()
            .WithMcpConfig(Examples.Fixture("mcp.json"))
            .WithSkillsDir(Examples.Fixture("skills")));

        Console.WriteLine("MCP status: " + string.Join(", ", tk.McpStatus().Select(kv => $"{kv.Key}={kv.Value}")));

        var tools = tk.Tools();
        Console.WriteLine("Tools: [" + string.Join(", ", tools.Select(t => $"{t.Name} ({t.Source})")) + "]");

        Console.WriteLine("\nSystem-prompt skill catalog:\n" + tk.SkillsPrompt());

        Console.WriteLine("\nOpenAI tool schema (first 2):");
        var openai = tk.ToOpenAI();
        Console.WriteLine(Json.Pretty(openai.Take(2).ToList()));

        var res = await tk.ExecuteAsync("skill", new Dictionary<string, object?> { ["name"] = "hello-world" });
        Console.WriteLine("\nskill(hello-world) ->\n" + res.Output);

        return 0;
    }
}
