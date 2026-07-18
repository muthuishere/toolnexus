namespace Toolnexus.Agents;

/// <summary>
/// One live agent (SPEC §7D). State machine:
/// <c>idle → running → (idle | suspended | closed)</c>; <c>suspended → running</c> ONLY via the
/// Answer to its pending Request. The inbox is AGENT STATE (bounded, transactional drain,
/// checkpointable) — never a runtime/language mailbox. Ids are deterministic and parent-scoped
/// (<c>root/coordinator.1/explore.2</c>) — never random.
///
/// Handles are capabilities: post/wake what you hold, wait only on handles you spawned.
/// Handle→handle blocking calls flow strictly rootward; parent→child interaction is non-blocking;
/// downward traversal (close cascade, list, resume) runs from outside the tree.
/// All mutation happens under the runtime's lock; public getters are read-only views.
/// </summary>
public sealed class Handle
{
    public string Id { get; }
    public AgentDef Def { get; internal set; }
    public Handle? Parent { get; }
    public int Depth { get; }

    /// <summary>idle | running | suspended | closed.</summary>
    public string State { get; internal set; } = "idle";

    /// <summary>Remaining token pool (live ledger — usage roll-up drains every ancestor).</summary>
    public long PoolTokens { get; internal set; }

    /// <summary>Remaining tool-call pool.</summary>
    public long PoolToolCalls { get; internal set; }

    /// <summary>Wall-clock deadline (from <c>maxWallMs</c> at spawn, min'd with the parent's).</summary>
    public DateTimeOffset? WallDeadline { get; }

    public long UsageTotal { get; internal set; }
    public int TurnsTotal { get; internal set; }
    public long ToolCallsTotal { get; internal set; }

    /// <summary>The pending Request while suspended (already stamped with this handle's
    /// <c>data.path</c>).</summary>
    public Request? PendingRequest { get; internal set; }

    /// <summary>Last completed turn result — <c>wait</c> resolves with the NEXT result or this
    /// one when the handle is already settled.</summary>
    public AgentResult? LastResult { get; internal set; }

    public int InboxCount { get { lock (Sync) return InboxItems.Count; } }

    public IReadOnlyList<Handle> Children => ChildList;

    /// <summary>Conversation id in the runtime-wide store = the handle id.</summary>
    public string ConvId => Id;

    // ---- runtime-internal state (mutated under the runtime lock only) ----
    internal readonly object Sync;                       // the owning runtime's lock
    internal readonly List<InboxItem> InboxItems = new();
    internal readonly List<Handle> ChildList = new();
    internal readonly List<TaskCompletionSource<AgentResult>> Waiters = new();
    internal readonly List<string> WakeQueue = new();    // queued wake prompts (concurrency gate)
    internal List<InboxItem> Drained = new();            // consumed by the in-flight turn; restored on abort
    internal CancellationTokenSource? Cts;
    internal string? TaskKey;                            // set when spawned via `task` (reattachment)
    internal string? PendingInput;                       // the halted turn's full input (checkpoint)
    internal int? PoolChildren;                          // null = unlimited
    internal int RunningChildren;
    internal int Seq;
    internal readonly int EffMaxTurns;
    internal readonly int EffMaxConcurrent;
    internal readonly int EffMaxDepth;

    internal Handle(string id, AgentDef def, Handle? parent, object sync, DateTimeOffset now)
    {
        Id = id;
        Def = def;
        Parent = parent;
        Sync = sync;
        Depth = parent != null ? parent.Depth + 1 : 0;
        var own = def.Budget ?? new Budget();

        // Carve: effective = min(own, parent remaining) for every pool.
        var pTok = parent?.PoolTokens ?? long.MaxValue;
        PoolTokens = Math.Min(own.MaxTokens ?? pTok, pTok);
        var pCalls = parent?.PoolToolCalls ?? long.MaxValue;
        PoolToolCalls = Math.Min(own.MaxToolCalls ?? pCalls, pCalls);
        var ownDeadline = own.MaxWallMs is long w ? now.AddMilliseconds(w) : (DateTimeOffset?)null;
        WallDeadline = Min(ownDeadline, parent?.WallDeadline);

        PoolChildren = own.MaxChildren;
        EffMaxTurns = own.MaxTurns ?? 6;
        EffMaxConcurrent = own.MaxConcurrent ?? 8;
        EffMaxDepth = own.MaxDepth ?? 3;
    }

    private static DateTimeOffset? Min(DateTimeOffset? a, DateTimeOffset? b)
        => a == null ? b : b == null ? a : (a < b ? a : b);
}
