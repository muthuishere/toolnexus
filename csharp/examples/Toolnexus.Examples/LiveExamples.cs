namespace Toolnexus.Examples;

/// <summary>Shared bootstrap for the live (LLM-backed) examples.</summary>
internal static class Live
{
    public static (string Key, string Model)? Env()
    {
        var key = Environment.GetEnvironmentVariable("OPENROUTER_API_KEY");
        if (string.IsNullOrEmpty(key))
        {
            Console.Error.WriteLine("OPENROUTER_API_KEY not set — this example needs a live endpoint.");
            return null;
        }
        var model = Environment.GetEnvironmentVariable("OPENROUTER_MODEL");
        if (string.IsNullOrEmpty(model)) model = "openai/gpt-4o-mini";
        return (key, model);
    }

    public static Task<Toolkit> ToolkitAsync() => Toolkit.CreateAsync(new Toolkit.Options()
        .WithMcpConfig(Examples.Fixture("mcp.json"))
        .WithSkillsDir(Examples.Fixture("skills")));

    public static LlmClient Client(string key, string model, LlmClient.Hooks? hooks = null) =>
        LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "https://openrouter.ai/api/v1",
            Style = "openai",
            Model = model,
            ApiKey = key,
            SystemPrompt = "You are a helpful tool-using agent.",
            Hooks = hooks,
        });
}

/// <summary>Run a single instruction through the unified client and print the answer + telemetry.</summary>
internal static class Agent
{
    public static async Task<int> Run()
    {
        if (Live.Env() is not var (key, model) || key is null) return 1;
        await using var tk = await Live.ToolkitAsync();
        var client = Live.Client(key, model);

        var res = await client.RunAsync(
            "Echo the string \"hello from the agent\" using the everything_echo tool, then summarize.", tk);

        Console.WriteLine("Tool calls: [" + string.Join(", ", res.ToolCalls.Select(c => c.Name)) + "]");
        Console.WriteLine($"Turns: {res.Turns}  Tokens: {res.Usage.TotalTokens}");
        Console.WriteLine("\nFINAL:\n" + res.Text);
        return 0;
    }
}

/// <summary>Lifecycle hooks: observe every LLM round trip and guard/observe tool calls.</summary>
internal static class HooksExample
{
    public static async Task<int> Run()
    {
        if (Live.Env() is not var (key, model) || key is null) return 1;
        await using var tk = await Live.ToolkitAsync();

        var hooks = new LlmClient.Hooks
        {
            AfterLLM = e => Console.WriteLine($"[afterLLM] turn={e.Turn}"),
            BeforeTool = e =>
            {
                Console.WriteLine($"[beforeTool] {e.Name} args={Json.Stringify(e.Args)}");
                return null; // allow it to run
            },
            AfterTool = e =>
            {
                Console.WriteLine($"[afterTool] {e.Name} isError={e.Result.IsError}");
                return null;
            },
        };

        var client = Live.Client(key, model, hooks);
        var res = await client.RunAsync("Echo \"hooked\" with everything_echo and report back.", tk);
        Console.WriteLine("\nFINAL:\n" + res.Text);
        return 0;
    }
}

/// <summary>Streaming: print token deltas + tool-call/result events as they arrive.</summary>
internal static class Streaming
{
    public static async Task<int> Run()
    {
        if (Live.Env() is not var (key, model) || key is null) return 1;
        await using var tk = await Live.ToolkitAsync();
        var client = Live.Client(key, model);

        await client.StreamAsync("Echo \"streamed\" with everything_echo, then tell me what it returned.", tk, ev =>
        {
            switch (ev.Type)
            {
                case LlmClient.StreamKind.Text: Console.Write(ev.Delta); break;
                case LlmClient.StreamKind.ToolCall: Console.WriteLine($"\n[tool_call] {ev.Name}({Json.Stringify(ev.Args)})"); break;
                case LlmClient.StreamKind.ToolResult: Console.WriteLine($"[tool_result] {ev.Name} -> {ev.Output}"); break;
                case LlmClient.StreamKind.Done: Console.WriteLine($"\n[done] turns={ev.Result!.Turns} tokens={ev.Result.Usage.TotalTokens}"); break;
            }
        });
        return 0;
    }
}

/// <summary>Conversation memory: a stateful multi-turn session that retains history.</summary>
internal static class Memory
{
    public static async Task<int> Run()
    {
        if (Live.Env() is not var (key, model) || key is null) return 1;
        await using var tk = await Live.ToolkitAsync();
        var convo = Live.Client(key, model).NewConversation(tk);

        var r1 = await convo.SendAsync("My favorite word is \"toolnexus\". Remember it.");
        Console.WriteLine("Turn 1:\n" + r1.Text);

        var r2 = await convo.SendAsync("Echo my favorite word using the everything_echo tool.");
        Console.WriteLine("\nTurn 2:\n" + r2.Text);
        Console.WriteLine("\nTool calls (turn 2): [" + string.Join(", ", r2.ToolCalls.Select(c => c.Name)) + "]");
        Console.WriteLine($"Transcript length: {convo.Messages.Count} messages");
        return 0;
    }
}
