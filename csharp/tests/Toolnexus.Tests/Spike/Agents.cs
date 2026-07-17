// SPIKE — Level-1/2 UX sugar over the substrate, C# port of js/spike/agents.ts.
// One new noun: Agent. Everything compiles to the six verbs in AgentRuntime.cs.
//
//   var explore = new Agent("explore", new AgentSpec { Does = "...", Uses = [lookup] });
//   var coder   = new Agent("coder",   new AgentSpec { Does = "...", SoulFile = "AGENTS.md", Team = [explore] });
//   var r = await coder.RunAsync(rtOpts, "fix the bug");
//
//   var mia = Agents.AgentFromDir("~/mia");        // the directory IS the agent
//   var started = Agents.StartAgent(mia, rtOpts, everyMs: 30_000);

namespace Toolnexus.Tests.Spike;

public sealed class AgentSpec
{
    /// <summary>What the delegating model sees — the routing description.</summary>
    public string Does = "";
    /// <summary>The toolkit VIEW for this agent (scoping = the whole security model).</summary>
    public List<ITool>? Uses;
    /// <summary>Identity: inline text (soul file contents).</summary>
    public string? Soul;
    /// <summary>Path form of soul — read at build time.</summary>
    public string? SoulFile;
    /// <summary>Task-tool targets: listing agents here IS the subagent wiring.</summary>
    public List<Agent>? Team;
    public Budget? Budget;
    /// <summary>Model id; "inherit" = runtime default.</summary>
    public string? Model;
    /// <summary>§10 interpreter authority for this agent's subtree.</summary>
    public Func<Request, Task<Answer>>? WaitFor;
    public Func<Handle, Task>? OnSpawn;
    public Func<Handle, string, Task>? OnClose;
}

public sealed class Agent
{
    public readonly string Name;
    public readonly AgentSpec Spec;

    public Agent(string name, AgentSpec spec)
    {
        Name = name;
        Spec = spec;
    }

    /// <summary>Collect this agent + its whole team graph into a runtime registry.</summary>
    public Dictionary<string, AgentDef> Registry(Dictionary<string, AgentDef>? acc = null)
    {
        acc ??= new Dictionary<string, AgentDef>();
        if (acc.ContainsKey(Name)) return acc;
        var soul = Spec.Soul ?? "";
        if (Spec.SoulFile != null) soul = File.ReadAllText(Spec.SoulFile);
        acc[Name] = new AgentDef
        {
            Name = Name,
            Description = Spec.Does,
            SystemPrompt = soul,
            Model = Spec.Model ?? "inherit",
            Tools = Spec.Uses,
            Budget = Spec.Budget,
            WaitFor = Spec.WaitFor,
            OnSpawn = Spec.OnSpawn,
            OnClose = Spec.OnClose,
            // team scoping: task targets = ONLY this agent's team (not the whole registry)
            TaskTargets = (Spec.Team ?? new List<Agent>()).Select(a => a.Name).ToList(),
        };
        foreach (var t in Spec.Team ?? new List<Agent>()) t.Registry(acc);
        return acc;
    }

    /// <summary>Level 1: one-shot — build a runtime, run to completion, tear down.</summary>
    public async Task<TaskResult> RunAsync(RuntimeOptions rtOpts, string prompt)
    {
        var rt = new AgentRuntime(rtOpts.CloneWithRegistry(Registry()));
        var spawned = rt.Spawn(rt.Root, Name);
        if (spawned.Error != null) return new TaskResult(spawned.Error, true, "done", 0, 0);
        var wait = rt.WaitAsync(spawned.Handle!);
        rt.Wake(spawned.Handle!, prompt);
        var r = await wait.ConfigureAwait(false);
        if (r.Status != "pending") await rt.CloseAsync(rt.Root).ConfigureAwait(false);
        return r;
    }

    /// <summary>Bridge: an Agent IS a Tool — drop it into the OLD API's extra tools.</summary>
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

public sealed class StartedAgent
{
    public required Handle Handle;
    public required AgentRuntime Runtime;
    internal CancellationTokenSource? TimerCts;
    internal Task? TimerTask;

    public async Task StopAsync()
    {
        TimerCts?.Cancel();
        if (TimerTask != null)
        {
            try { await TimerTask.ConfigureAwait(false); }
            catch (OperationCanceledException) { }
        }
        await Runtime.CloseAsync(Runtime.Root).ConfigureAwait(false);
    }
}

public static class Agents
{
    /// <summary>Level 2 bootstrap discovery order (openclaw order; all optional).</summary>
    public static readonly string[] BootstrapOrder =
    {
        "AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md",
    };

    public const long MaxBootstrapFileBytes = 2 * 1024 * 1024;

    /// <summary>The HEARTBEAT_OK sentinel — a silent wake produces no outbound message.</summary>
    public const string HeartbeatOk = "HEARTBEAT_OK";

    /// <summary>
    /// Level 2: the directory IS the agent. Discovers the bootstrap files (2MB cap each) and
    /// injects them as named "## file" sections at session start (cache doctrine: read memory in).
    /// </summary>
    public static Agent AgentFromDir(string dir, AgentSpec? opts = null, string? name = null)
    {
        var sections = new List<string>();
        foreach (var f in BootstrapOrder)
        {
            var p = Path.Combine(dir, f);
            if (!File.Exists(p)) continue;
            var size = new FileInfo(p).Length;
            var body = File.ReadAllText(p);
            if (size > MaxBootstrapFileBytes)
                body = body[..(int)Math.Min(body.Length, MaxBootstrapFileBytes)] + "\n[truncated: file exceeds bootstrap cap]";
            sections.Add($"## {f}\n\n{body.Trim()}");
        }
        var spec = opts ?? new AgentSpec();
        if (string.IsNullOrEmpty(spec.Does)) spec.Does = $"persona agent from {dir}";
        spec.Soul = string.Join("\n\n", sections);
        return new Agent(name ?? Path.GetFileName(dir.TrimEnd(Path.DirectorySeparatorChar)), spec);
    }

    /// <summary>
    /// Level 2: start a long-lived persona — the heartbeat posts a tick to its own inbox
    /// (unsolicited rail; ticks coalesce) and wakes it; HEARTBEAT_OK results stay silent.
    /// </summary>
    public static StartedAgent StartAgent(Agent a, RuntimeOptions rtOpts, int? everyMs = null, Action<string>? onReport = null)
    {
        var rt = new AgentRuntime(rtOpts.CloneWithRegistry(a.Registry()));
        var spawned = rt.Spawn(rt.Root, a.Name);
        if (spawned.Error != null) throw new InvalidOperationException(spawned.Error);
        var handle = spawned.Handle!;
        var started = new StartedAgent { Handle = handle, Runtime = rt };
        if (everyMs is int ms)
        {
            var cts = new CancellationTokenSource();
            started.TimerCts = cts;
            started.TimerTask = Task.Run(async () =>
            {
                using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(ms));
                while (await timer.WaitForNextTickAsync(cts.Token).ConfigureAwait(false))
                {
                    rt.Post(handle, new InboxItem("clock", "timer", "tick"));
                    if (handle.State == "idle")
                    {
                        _ = rt.RunTurnAsync(handle,
                                "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.")
                            .ContinueWith(t =>
                            {
                                if (!t.IsCompletedSuccessfully) return;
                                var r = t.Result;
                                if (!r.IsError && !r.Text.Contains(HeartbeatOk)) onReport?.Invoke(r.Text);
                            }, TaskScheduler.Default);
                    }
                }
            });
        }
        return started;
    }
}
