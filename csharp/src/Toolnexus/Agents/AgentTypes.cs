namespace Toolnexus.Agents;

/// <summary>
/// One unsolicited item in a handle's inbox (SPEC §7D "two delivery rails"). Provenance is part
/// of the data: <see cref="From"/> is a handle path or <c>"external"</c>; <see cref="Channel"/>
/// tags the rail (<c>"peer"</c>, <c>"timer"</c>, <c>"external"</c>, ...). Timer ticks coalesce
/// to one counted entry at drain time.
/// </summary>
public sealed record InboxItem(string From, string Channel, string Text);

/// <summary>
/// Hierarchical budget (SPEC §7D). Carved at spawn (<c>effective = min(own, parent remaining)</c>)
/// and enforced by a LIVE ancestor-chain walk before each turn and each spawn — carve alone misses
/// sibling spend. Any limit stop surfaces as <c>status:"incomplete"</c> with the limit named,
/// never a silent <c>"done"</c>, never a crash. Monetary budgets are excluded (vendor data).
/// </summary>
public sealed class Budget
{
    public int? MaxTurns { get; set; }
    public long? MaxTokens { get; set; }
    public long? MaxToolCalls { get; set; }
    public long? MaxWallMs { get; set; }
    public int? MaxChildren { get; set; }
    public int? MaxConcurrent { get; set; }
    public int? MaxDepth { get; set; }
}

/// <summary>
/// A registered agent definition — (identity/system prompt × a filtered toolkit view × the §8
/// client loop). The runtime registry maps name → def; ids derive from the name
/// (<c>root/coordinator.1/explore.2</c>).
/// </summary>
public sealed class AgentDef
{
    public string Name { get; set; } = "";

    /// <summary>The routing description the delegating model sees (SPEC §7D `does`).</summary>
    public string Does { get; set; } = "";

    /// <summary>Identity / system prompt ("soul") injected into this agent's runs.</summary>
    public string Soul { get; set; } = "";

    /// <summary>Model id; <c>"inherit"</c> = the runtime default model.</summary>
    public string Model { get; set; } = "inherit";

    public Budget? Budget { get; set; }

    /// <summary>The filtered toolkit view for this agent — scoping is the security model.</summary>
    public List<ITool>? Tools { get; set; }

    /// <summary>
    /// Team scoping (SPEC §7D): the `task` tool's targets are EXACTLY these agent names. Null ⇒
    /// this agent gets NO `task` tool — recursion is opt-in, never default.
    /// </summary>
    public List<string>? Team { get; set; }

    /// <summary>This agent's §10 interpreter authority. Absent ⇒ escalate to the nearest
    /// interpreting ancestor, else the pending goes durable.</summary>
    public Func<Request, Task<Answer>>? WaitFor { get; set; }

    /// <summary>Runs once, pre-first-turn — the session-start injection point.</summary>
    public Func<Handle, Task>? OnSpawn { get; set; }

    /// <summary>Runs pre-final-checkpoint with the close reason
    /// (<c>closed | interrupted | budget | error</c>).</summary>
    public Func<Handle, string, Task>? OnClose { get; set; }

    internal AgentDef CloneWith(Budget budget) => new()
    {
        Name = Name, Does = Does, Soul = Soul, Model = Model, Budget = budget,
        Tools = Tools, Team = Team, WaitFor = WaitFor, OnSpawn = OnSpawn, OnClose = OnClose,
    };
}

/// <summary>
/// The uniform outcome of one agent turn as seen by waiters (SPEC §7D). Failures cross the handle
/// boundary as <c>IsError</c> results — never exceptions; only the root may throw to the host.
/// <see cref="Status"/> is the CLOSED seven-string vocabulary (SPEC §7D):
/// <c>done | pending | incomplete | interrupted | closed | timeout | error</c> — identical strings
/// in every port; a failed run is <c>error</c>, never <c>done</c> + <c>IsError</c>.
/// </summary>
public sealed record AgentResult(
    string Text, bool IsError, string Status, int Turns, long TotalTokens,
    Request? Pending = null);

/// <summary>Spawn outcome: a handle, or a uniform error naming the refused limit.</summary>
public sealed record SpawnResult(Handle? Handle, string? Error);

/// <summary>Post/wake outcome — loud, never silent (inbox gate rejects synchronously).</summary>
public sealed record PostResult(bool Ok, string? Error);

/// <summary>Read-only view of one handle for <c>list</c>/<c>inspect</c>.</summary>
public sealed record HandleView(string Id, string State, long Tokens, int Inbox);

/// <summary>
/// Host-level configuration for an <see cref="AgentRuntime"/>. The runtime owns the cross-cutting
/// infrastructure (SPEC §7D): ONE <see cref="IConversationStore"/> for all handles (conversation
/// id = handle id — transcripts genuinely survive turns), an injectable clock
/// (<see cref="TimeProvider"/> — fixtures run virtual), and the handle table.
/// </summary>
public sealed class RuntimeOptions
{
    /// <summary>The LLM HTTP seam (§8 Gap 2). The runtime wraps it with the global turn gate;
    /// tests inject a scripted in-process handler. Null ⇒ the default HTTP stack.</summary>
    public HttpMessageHandler? Handler { get; set; }

    /// <summary>name → definition. With the Level-1 surface this is the transitive closure of the
    /// entry agent's team graph (<see cref="Agent.Registry"/>).</summary>
    public Dictionary<string, AgentDef> Registry { get; set; } = new();

    /// <summary>Inbox gate: bounded; a post to a full inbox is rejected synchronously.</summary>
    public int InboxCap { get; set; } = 8;

    /// <summary>Turn gate: global cap on concurrent LLM HTTP calls (never whole Runs).</summary>
    public int MaxConcurrentTurns { get; set; } = 8;

    /// <summary>Graceful-close bound: a running turn may finish within this before the close
    /// escalates to interrupt.</summary>
    public int ShutdownMs { get; set; } = 200;

    /// <summary>The ONE runtime-wide conversation store (conversation id = handle id).
    /// Null ⇒ a fresh in-memory store owned by the runtime.</summary>
    public IConversationStore? Store { get; set; }

    /// <summary>Injectable clock for every timer, timeout, and deadline. Null ⇒ the system clock.</summary>
    public TimeProvider? Clock { get; set; }

    // LLM endpoint defaults (a def's Model == "inherit" resolves to Model here).
    public string? BaseUrl { get; set; }
    public string? Style { get; set; }
    public string? ApiKey { get; set; }
    public string? Model { get; set; }

    internal RuntimeOptions CloneWithRegistry(Dictionary<string, AgentDef> registry) => new()
    {
        Handler = Handler, Registry = registry, InboxCap = InboxCap,
        MaxConcurrentTurns = MaxConcurrentTurns, ShutdownMs = ShutdownMs,
        Store = Store, Clock = Clock,
        BaseUrl = BaseUrl, Style = Style, ApiKey = ApiKey, Model = Model,
    };
}
