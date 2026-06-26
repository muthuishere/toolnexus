namespace Toolnexus.Examples;

/// <summary>
/// Live OpenRouter tool-calling round trip — proves the toolkit drives a real LLM
/// (mirrors the JS/Python/Go/Java ports). Reads OPENROUTER_API_KEY from the
/// environment — never hardcode it, never print it.
///
///   OPENROUTER_API_KEY=... dotnet run -- openrouter
///
/// Flow: build the toolkit from the shared fixtures, hand the model the toolkit's
/// tools, ask it to echo a string via the `everything_echo` MCP tool, let the unified
/// client run the tool call and feed the result back, then print the final answer.
/// </summary>
internal static class Openrouter
{
    public static async Task<int> Run()
    {
        var key = Environment.GetEnvironmentVariable("OPENROUTER_API_KEY");
        if (string.IsNullOrEmpty(key))
        {
            Console.Error.WriteLine("OPENROUTER_API_KEY not set");
            return 1;
        }
        var model = Environment.GetEnvironmentVariable("OPENROUTER_MODEL");
        if (string.IsNullOrEmpty(model)) model = "openai/gpt-4o-mini";

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options()
            .WithMcpConfig(Examples.Fixture("mcp.json"))
            .WithSkillsDir(Examples.Fixture("skills")));

        var agent = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "https://openrouter.ai/api/v1",
            Style = "openai",
            Model = model,
            ApiKey = key,
            SystemPrompt = "You are a tool-using agent. Use the provided tools when helpful.\n\n" + tk.SkillsPrompt(),
        });

        var res = await agent.RunAsync(
            "Use the everything_echo tool to echo the exact string \"toolnexus works\". "
            + "Then tell me what it returned.", tk);

        Console.WriteLine("Tool calls: [" + string.Join(", ", res.ToolCalls.Select(c => c.Name)) + "]");
        Console.WriteLine("\nFINAL ASSISTANT:\n" + res.Text);

        var echoed = res.ToolCalls.Any(c => c.Name.Contains("everything_echo"));
        if (!echoed)
        {
            Console.Error.WriteLine("\nFAIL: expected the model to call everything_echo");
            return 1;
        }
        Console.WriteLine("\nOK: C# OpenRouter round trip succeeded");
        return 0;
    }
}
