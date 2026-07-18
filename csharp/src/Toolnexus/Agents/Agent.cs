namespace Toolnexus.Agents;

/// <summary>
/// Level-1 agent spec (SPEC §7D): <c>does</c> (required routing description), <c>uses</c> (the
/// toolkit view), <c>soul</c>/<c>soulFile</c> (identity → the child's system prompt),
/// <c>team</c> (task-tool targets — listing agents here IS the subagent wiring), <c>budget</c>,
/// <c>model</c> (default inherit), optional <c>waitFor</c>/<c>onSpawn</c>/<c>onClose</c>.
/// </summary>
public sealed class AgentSpec
{
    /// <summary>What the delegating model sees — the routing description.</summary>
    public string Does { get; set; } = "";

    /// <summary>The toolkit VIEW for this agent (scoping = the whole security model).</summary>
    public List<ITool>? Uses { get; set; }

    /// <summary>Identity: inline soul text.</summary>
    public string? Soul { get; set; }

    /// <summary>Path form of soul — read when the registry is built.</summary>
    public string? SoulFile { get; set; }

    /// <summary>Task-tool targets. Null ⇒ no `task` tool for this agent (recursion is opt-in).</summary>
    public List<Agent>? Team { get; set; }

    public Budget? Budget { get; set; }

    /// <summary>Model id; null/"inherit" = the runtime default.</summary>
    public string? Model { get; set; }

    /// <summary>§10 interpreter authority for this agent's subtree.</summary>
    public Func<Request, Task<Answer>>? WaitFor { get; set; }

    public Func<Handle, Task>? OnSpawn { get; set; }
    public Func<Handle, string, Task>? OnClose { get; set; }
}

/// <summary>
/// An Agent is a Tool (SPEC §7D axiom): (system prompt × a filtered toolkit view × the §8 loop),
/// invocable one-shot via <see cref="RunAsync"/> or dropped into the classic API's extra tools via
/// <see cref="AsTool"/> — name, `does` description, a prompt-taking schema, and an execute that
/// runs the agent's loop and returns only its final text + metadata {agent, turns, totalTokens}.
/// </summary>
public sealed class Agent
{
    public string Name { get; }
    public AgentSpec Spec { get; }

    public Agent(string name, AgentSpec spec)
    {
        Name = name;
        Spec = spec;
    }

    /// <summary>
    /// The runtime registry = the transitive closure of this agent's team graph (unreachable
    /// agents are not present). Reads <c>SoulFile</c> here.
    /// </summary>
    public Dictionary<string, AgentDef> Registry(Dictionary<string, AgentDef>? acc = null)
    {
        acc ??= new Dictionary<string, AgentDef>();
        if (acc.ContainsKey(Name)) return acc;
        var soul = Spec.Soul ?? "";
        if (Spec.SoulFile != null) soul = File.ReadAllText(Spec.SoulFile);
        acc[Name] = new AgentDef
        {
            Name = Name,
            Does = Spec.Does,
            Soul = soul,
            Model = Spec.Model ?? "inherit",
            Tools = Spec.Uses,
            Budget = Spec.Budget,
            WaitFor = Spec.WaitFor,
            OnSpawn = Spec.OnSpawn,
            OnClose = Spec.OnClose,
            // Team scoping: task targets = ONLY this agent's team; null team ⇒ no task tool.
            Team = Spec.Team?.Select(a => a.Name).ToList(),
        };
        foreach (var t in Spec.Team ?? new List<Agent>()) t.Registry(acc);
        return acc;
    }

    /// <summary>One-shot: build a runtime, run to completion, tear down (kept alive on a durable
    /// pending so the host can <see cref="AgentRuntime.ResumeAsync"/>).</summary>
    public async Task<AgentResult> RunAsync(RuntimeOptions rtOpts, string prompt)
    {
        var rt = new AgentRuntime(rtOpts.CloneWithRegistry(Registry()));
        var spawned = rt.Spawn(rt.Root, Name);
        if (spawned.Error != null) return new AgentResult(spawned.Error, true, "error", 0, 0);
        var wait = rt.WaitAsync(spawned.Handle!);
        rt.Wake(spawned.Handle!, prompt);
        var r = await wait.ConfigureAwait(false);
        if (r.Status != "pending") await rt.CloseAsync(rt.Root).ConfigureAwait(false);
        return r;
    }

    /// <summary>The bridge: this agent as a uniform <see cref="ITool"/> for the classic API's
    /// extra tools. Each invocation runs the agent's own loop in isolation and returns one tool
    /// result carrying the final text + metadata.</summary>
    public ITool AsTool(RuntimeOptions rtOpts)
        => NativeTool.OfAsync(Name, Spec.Does,
            new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["prompt"] = new Dictionary<string, object?> { ["type"] = "string" },
                },
                ["required"] = new List<object?> { "prompt" },
            },
            async (args, _) =>
            {
                var prompt = args.TryGetValue("prompt", out var p) ? p?.ToString() ?? "" : "";
                var r = await RunAsync(rtOpts, prompt).ConfigureAwait(false);
                return new ToolResult(r.Text, r.IsError, new Dictionary<string, object?>
                {
                    ["agent"] = Name, ["turns"] = r.Turns, ["totalTokens"] = r.TotalTokens,
                });
            });
}
