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

    /// <summary>(SPEC §7D "the §8 seams on an agent run") The §8 lifecycle hooks for THIS agent's
    /// runs. Set ⇒ REPLACES the runtime-wide <see cref="RuntimeOptions.Hooks"/> for this agent
    /// (never merged — composing two transcript rewrites has no defined order). Forwarded verbatim
    /// to the client the runtime builds; never composed, wrapped, reordered, or read. This is how a
    /// §7F compactor (a <c>beforeLLM</c> hook) attaches to one agent.</summary>
    public LlmClient.Hooks? Hooks { get; set; }

    /// <summary>(SPEC §7D) The §8 observability sink for THIS agent's runs. Set ⇒ REPLACES the
    /// runtime-wide <see cref="RuntimeOptions.OnMetric"/> for this agent. Resolves INDEPENDENTLY of
    /// <see cref="Hooks"/> — a def may override one and inherit the other.</summary>
    public Action<MetricEvent>? OnMetric { get; set; }

    /// <summary>(SPEC §7D) The completion gate, projected from the agent spec. Present ⇒ this
    /// agent's turns run through the gate even when it is a DELEGATED child.</summary>
    public Completion? Completion { get; set; }

    internal AgentDef CloneWith(Budget budget) => new()
    {
        Name = Name, Does = Does, Soul = Soul, Model = Model, Budget = budget,
        Tools = Tools, Team = Team, WaitFor = WaitFor, OnSpawn = OnSpawn, OnClose = OnClose,
        // Completion MUST ride along: the runtime clones a def to apply a budget, and
        // dropping the gate here would silently un-gate every budgeted agent.
        Hooks = Hooks, OnMetric = OnMetric, Completion = Completion,
    };
}

/// <summary>
/// The uniform outcome of one agent turn as seen by waiters (SPEC §7D). Failures cross the handle
/// boundary as <c>IsError</c> results — never exceptions; only the root may throw to the host.
/// <see cref="Status"/> is the CLOSED seven-string vocabulary (SPEC §7D):
/// <c>done | pending | incomplete | interrupted | closed | timeout | error</c> — identical strings
/// in every port; a failed run is <c>error</c>, never <c>done</c> + <c>IsError</c>. The constants
/// are <see cref="AgentStatus"/>; note they are a DIFFERENT vocabulary from the §8 client's
/// three-value <see cref="RunStatus"/> (ADR 0027 — <c>timeout</c> is in this set and not in that
/// one, which is the name collision behind #92.1).
/// </summary>
/// <param name="Turns">(ADR 0025 D1 / A13) The handle's CUMULATIVE round trips, on EVERY status.
/// It used to be the single run's turns on done/pending/incomplete and the handle's total on
/// error/closed/timeout — the same two-meanings defect #88 reports for tokens, one field over, so
/// a second turn on a handle could report fewer turns than the first. Unlike
/// <paramref name="TotalTokens"/> it does NOT include a delegated child's round trips: turns are
/// not billed, and a parent's turn count is its own. There is deliberately no <c>OwnTurns</c>.</param>
/// <param name="TotalTokens">(ADR 0025) The CUMULATIVE TREE TOTAL for this handle — coordinator
/// plus every worker plus anything they delegated to — on EVERY status. This is the number you
/// bill from. Before this fix it was the single run's usage on done/pending/incomplete and the
/// tree total on error/closed/timeout/settled, so its meaning depended on which status came back.</param>
/// <param name="OwnTokens">(ADR 0025) What THIS turn alone spent, excluding delegated children —
/// the figure <see cref="TotalTokens"/> used to carry on three of the seven statuses.</param>
/// <param name="Limit">(ADR 0025) WHICH limit stopped the run, as a value to branch on rather than
/// prose in <see cref="Text"/>: <c>"maxTurns"</c> | <c>"completion"</c> | a budget pool name
/// (<c>"tokens"</c>, <c>"toolCalls"</c>, <c>"wallMs"</c>). Empty when nothing was hit.</param>
public sealed record AgentResult(
    string Text, bool IsError, string Status, int Turns, long TotalTokens,
    Request? Pending = null, long OwnTokens = 0, string Limit = "");

/// <summary>
/// (ADR 0027) The §7D agent status vocabulary, by name. SEVEN values. This is NOT the §8 client's
/// <see cref="RunStatus"/> set — the two share the field name <c>status</c> and are distinct
/// vocabularies; <c>Timeout</c> exists only here, <c>Pending</c>/<c>Incomplete</c>/<c>Done</c> in both.
/// </summary>
public static class AgentStatus
{
    public const string Done = "done";
    public const string Pending = "pending";
    public const string Incomplete = "incomplete";
    public const string Interrupted = "interrupted";
    public const string Closed = "closed";
    public const string Timeout = "timeout";
    public const string Error = "error";

    /// <summary>All seven, in SPEC order.</summary>
    public static readonly IReadOnlyList<string> All =
        new[] { Done, Pending, Incomplete, Interrupted, Closed, Timeout, Error };
}

/// <summary>
/// (ADR 0027) The §8 CLIENT status vocabulary, by name. THREE values — <c>timeout</c> is NOT one
/// of them: a §8 run that exceeds its budget throws <see cref="LlmClient.RunTimeoutException"/>.
/// Distinct from <see cref="AgentStatus"/> despite the shared field name.
/// </summary>
public static class RunStatus
{
    public const string Done = "done";
    public const string Pending = "pending";
    public const string Incomplete = "incomplete";

    /// <summary>All three, in SPEC order.</summary>
    public static readonly IReadOnlyList<string> All = new[] { Done, Pending, Incomplete };
}

/// <summary>(ADR 0025) The values <see cref="AgentResult.Limit"/> and
/// <see cref="LlmClient.RunResult.Limit"/> take.</summary>
public static class StopLimit
{
    // The seven budget fields, spelled exactly as SPEC.md spells them on `Budget`.
    public const string MaxTurns = "maxTurns";
    public const string MaxTokens = "maxTokens";
    public const string MaxToolCalls = "maxToolCalls";
    public const string MaxWallMs = "maxWallMs";
    public const string MaxChildren = "maxChildren";
    public const string MaxConcurrent = "maxConcurrent";
    public const string MaxDepth = "maxDepth";

    // The two non-budget stops.
    public const string Completion = "completion";
    public const string Timeout = "timeout";

    /// <summary>The CLOSED vocabulary, in SPEC order. Nothing outside this may reach
    /// <see cref="AgentResult.Limit"/>.</summary>
    public static readonly IReadOnlyList<string> All = new[]
    {
        MaxTurns, MaxTokens, MaxToolCalls, MaxWallMs, MaxChildren, MaxConcurrent, MaxDepth,
        Completion, Timeout,
    };

    /// <summary>
    /// (A14) Maps this runtime's INTERNAL pool name onto the canonical spelling. An internal name
    /// is an implementation detail and may not leak into a field hosts branch on — "which limit
    /// stopped me" is only answerable if the answer is the same word in every port. The mapping
    /// happens AT THE BOUNDARY: internal names and the existing <c>"budget exhausted (…)"</c> text
    /// are left exactly as they were, so no prose changes.
    /// </summary>
    internal static string FromPool(string pool) => pool switch
    {
        "tokens" => MaxTokens,
        "toolCalls" => MaxToolCalls,
        "wallMs" => MaxWallMs,
        "children" => MaxChildren,
        "concurrent" => MaxConcurrent,
        "depth" => MaxDepth,
        _ => pool,
    };
}

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

    /// <summary>(SPEC §7D "the §8 seams on an agent run") Runtime-wide §8 lifecycle hooks: applied
    /// to EVERY agent whose def does not set its own <see cref="AgentDef.Hooks"/>. Forwarded
    /// verbatim into each handle's client — never composed, wrapped, reordered, or read. It cannot
    /// reach the runtime's own options (soul, §10 waitFor, the gated HTTP seam, the store).</summary>
    public LlmClient.Hooks? Hooks { get; set; }

    /// <summary>(SPEC §7D) Runtime-wide §8 observability sink: applied to every agent whose def does
    /// not set its own <see cref="AgentDef.OnMetric"/>. Resolves independently of
    /// <see cref="Hooks"/>.</summary>
    public Action<MetricEvent>? OnMetric { get; set; }

    internal RuntimeOptions CloneWithRegistry(Dictionary<string, AgentDef> registry) => new()
    {
        Handler = Handler, Registry = registry, InboxCap = InboxCap,
        MaxConcurrentTurns = MaxConcurrentTurns, ShutdownMs = ShutdownMs,
        Store = Store, Clock = Clock,
        BaseUrl = BaseUrl, Style = Style, ApiKey = ApiKey, Model = Model,
        Hooks = Hooks, OnMetric = OnMetric,
    };
}
