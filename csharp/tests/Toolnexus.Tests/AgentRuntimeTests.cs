using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>
/// SPEC §7D agent runtime — the substrate's acceptance checks, driven by the shared
/// <c>examples/subagent-*</c> fixtures (fanout, escalation, durable-resume, budgets, lifecycle)
/// on scripted mock LLMs (see <see cref="SubagentMockHandler"/>). Every assertion is an
/// observable state transition or result — never scheduling.
/// </summary>
public class AgentRuntimeTests
{
    // ---------- registry (mirrors the fixtures' "registry" blocks) ----------

    private static Dictionary<string, AgentDef> Registry(Action<Dictionary<string, AgentDef>>? mutate = null)
    {
        var reg = new Dictionary<string, AgentDef>
        {
            ["coordinator"] = new() { Name = "coordinator", Does = "splits and delegates", Soul = "coordinate", Model = "m-coordinator", Team = new List<string> { "explore" } },
            ["explore"] = new() { Name = "explore", Does = "read-only research", Soul = "explore", Model = "m-explore", Tools = new List<ITool> { MockLlm.Lookup() } },
            ["approverParent"] = new() { Name = "approverParent", Does = "delegates, holds approval authority", Model = "m-approver-parent", Team = new List<string> { "asker" } },
            ["asker"] = new() { Name = "asker", Does = "needs approvals", Model = "m-asker", Tools = new List<ITool> { MockLlm.CheckSecret() } },
            ["peer"] = new() { Name = "peer", Does = "team member", Model = "m-peer" },
            ["looper"] = new() { Name = "looper", Does = "never finishes", Model = "m-loop", Tools = new List<ITool> { MockLlm.Lookup() } },
            ["slow"] = new() { Name = "slow", Does = "slow worker", Model = "m-slow" },
            ["rogue"] = new() { Name = "rogue", Does = "targets outside its team", Model = "m-rogue", Team = new List<string> { "explore" } },
        };
        mutate?.Invoke(reg);
        return reg;
    }

    private static RuntimeOptions Opts(SubagentMockHandler handler, Dictionary<string, AgentDef> registry,
        int inboxCap = 8, int maxConcurrentTurns = 8)
        => new() { Handler = handler, Registry = registry, InboxCap = inboxCap, MaxConcurrentTurns = maxConcurrentTurns, ApiKey = "test" };

    // ---- examples/subagent-fanout: fan-out, isolation, parallelism, roll-up ----

    [Fact]
    public async Task Fanout_TaskFanOut_ContextIsolation_Parallel_UsageRollup()
    {
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry()));
        var coord = rt.Spawn(rt.Root, "coordinator").Handle!;
        var wait = rt.WaitAsync(coord);
        rt.Wake(coord, "answer using two lookups");
        var r = await wait;

        Assert.True(r.Status == "done", $"parent reaches done: {r.Text}");
        Assert.True(r.Text.Contains("found:data(x)") && r.Text.Split("found:").Length == 3,
            $"parent got BOTH child answers: {r.Text}");
        Assert.True(r.Turns == 2, $"parent ran 2 turns only (children's turns not in parent): turns={r.Turns}");
        Assert.True(rt.TraceContains("/coordinator.1/explore.1:") && rt.TraceContains("/explore.2:"),
            "two children spawned with deterministic ids");
        Assert.True(rt.MaxObservedConcurrentTurns >= 2,
            $"children ran CONCURRENTLY (observed >=2 turns in flight): max={rt.MaxObservedConcurrentTurns}");
        // roll-up: coord usage = own 2 turns×40 + children 2×(2 turns×40) = 240
        Assert.True(coord.UsageTotal == 240, $"usage rolls up to parent (the ledger): usage={coord.UsageTotal}");
        Assert.True(rt.List().All(h => h.Id == coord.Id || h.State == "closed"),
            "children auto-closed after task");
    }

    // ---- examples/subagent-escalation: nearest interpreter wins ----

    [Fact]
    public async Task Escalation_ChildSuspends_ParentWaitForAnswers_NearestInterpreter()
    {
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry(reg =>
            reg["approverParent"].WaitFor = req => Task.FromResult(new Answer { Id = req.Id, Ok = true }))));
        var p = rt.Spawn(rt.Root, "approverParent").Handle!;
        var wait = rt.WaitAsync(p);
        rt.Wake(p, "do the secret thing");
        var r = await wait;

        Assert.True(r.Status == "done", $"run completed (no durable pending): {r.Status}");
        Assert.True(r.Text.Contains("asker-done: secret-token"),
            $"child's approval flowed through parent authority: {r.Text}");
        Assert.True(rt.TraceContains("running→suspended") && rt.TraceContains("suspended→running"),
            "trace shows suspended→running round-trip");
        Assert.True(rt.TraceContains("escalate → root/approverParent.1 answers"),
            "escalation chose an ANCESTOR (not self)");
    }

    // ---- examples/subagent-durable-resume: durable pending + resume cascade ----

    [Fact]
    public async Task DurableResume_NoInterpreter_PendingAtRoot_ResumeCascadesFromCheckpoint()
    {
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry()));
        var p = rt.Spawn(rt.Root, "approverParent").Handle!;
        var wait = rt.WaitAsync(p);
        rt.Wake(p, "do the secret thing");
        var r1 = await wait;

        Assert.True(r1.Status == "pending", $"root run returned status=pending: {r1.Status}");
        var path = (r1.Pending?.Data?["path"] as IEnumerable<object?>)?.Select(s => s?.ToString()) ?? Array.Empty<string>();
        Assert.True(string.Join("/", path).Contains("approverParent.1"),
            $"Request carries the handle PATH in data.path: {string.Join("/", path)}");
        Assert.True(rt.List().All(h => h.State == "suspended"),
            "both levels parked (suspended), zero tokens burning");
        var before = rt.List().First(h => h.Id.Contains("asker")).Tokens;

        await rt.ResumeAsync(new Answer { Id = r1.Pending!.Id, Ok = true });
        await Task.Delay(50);
        var trace = string.Join("\n", rt.TraceLines());

        Assert.True(trace.Contains("resume with Answer(ok=true) at checkpoint"),
            "leaf resumed AT checkpoint (prior turns preserved)");
        Assert.True(trace.Contains("task replay → REATTACH"),
            "parent cascade REATTACHED to the finished child (no re-execution)");
        Assert.True(p.State == "idle", $"parent reached done after cascade: {p.State}");
        Assert.True(rt.List().First(h => h.Id.Contains("asker")).Tokens > before,
            "child did not restart from scratch (usage grew, not reset)");
        Assert.True(rt.List().Count(h => h.Id.Contains("asker")) == 1,
            "no duplicate child id appeared (reattachment, not respawn)");
        Assert.True(p.LastResult!.Text.Contains("parent-final: asker-done: secret-token"),
            $"cascade delivered the leaf's real result upward: {p.LastResult.Text}");
    }

    // ---- examples/subagent-lifecycle (coalescedDrain): unsolicited rail ----

    [Fact]
    public async Task UnsolicitedRail_PostsCoalesce_ProvenanceMarked_TicksDedupe()
    {
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry()));
        var peer = rt.Spawn(rt.Root, "peer").Handle!;
        rt.Post(peer, new InboxItem("root/coordinator.1", "peer", "update 1"));
        rt.Post(peer, new InboxItem("external", "external", "webhook payload"));
        rt.Post(peer, new InboxItem("clock", "timer", "tick"));
        rt.Post(peer, new InboxItem("clock", "timer", "tick"));
        rt.Post(peer, new InboxItem("clock", "timer", "tick"));
        var wait = rt.WaitAsync(peer);
        rt.Wake(peer);
        var r = await wait;

        Assert.True(r.Turns == 1, $"one wake = ONE turn for all items: turns={r.Turns}");
        Assert.True(r.Text == "processed 3 items", $"2 messages + 3 ticks coalesced to 3 items: {r.Text}");
    }

    // ---- examples/subagent-lifecycle (gates): inbox, concurrency, turn gate ----

    [Fact]
    public async Task Backpressure_InboxReject_ConcurrencyQueue_GlobalTurnGate()
    {
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry(), inboxCap: 2));
        var peer = rt.Spawn(rt.Root, "peer").Handle!;
        rt.Post(peer, new InboxItem("a", "peer", "1"));
        rt.Post(peer, new InboxItem("a", "peer", "2"));
        var third = rt.Post(peer, new InboxItem("a", "peer", "3"));
        Assert.True(!third.Ok && third.Error!.Contains("inbox full"),
            "inbox gate: third post REJECTED loudly to sender");

        var rt2 = new AgentRuntime(Opts(new SubagentMockHandler(), Registry(reg =>
            reg["coordinator"].Budget = new Budget { MaxConcurrent = 1 })));
        var c2 = rt2.Spawn(rt2.Root, "coordinator").Handle!;
        var wait2 = rt2.WaitAsync(c2);
        rt2.Wake(c2, "go");
        var r2 = await wait2;
        Assert.True(r2.Status == "done" && rt2.TraceContains("wake QUEUED") && rt2.TraceContains("DEQUEUED wake"),
            $"concurrency gate: 2nd child QUEUED then DEQUEUED via slot transfer, still completes: {r2.Text}");
        Assert.True(rt2.MaxObservedConcurrentTurns <= 2,
            "concurrency gate: never >1 child turn in flight (parent turn allowed)");

        var rt3 = new AgentRuntime(Opts(new SubagentMockHandler(), Registry(), maxConcurrentTurns: 1));
        var c3 = rt3.Spawn(rt3.Root, "coordinator").Handle!;
        var wait3 = rt3.WaitAsync(c3);
        rt3.Wake(c3, "go");
        var r3 = await wait3;
        Assert.True(rt3.MaxObservedConcurrentTurns == 1,
            $"turn gate wraps ONLY the LLM HTTP call (no parent-vs-child deadlock, run completed " +
            $"'{r3.Status}'): max={rt3.MaxObservedConcurrentTurns}");
    }

    // ---- examples/subagent-budgets: carve, live-chain, loud incomplete ----

    [Fact]
    public async Task Budgets_HierarchicalCarve_ExhaustionIncomplete_MaxTurnsLoud()
    {
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry()));
        var c = rt.Spawn(rt.Root, "coordinator", new Budget { MaxTokens = 100, MaxChildren = 2 }).Handle!;
        var kid = rt.Spawn(c, "explore", new Budget { MaxTokens = 500 }).Handle!;
        Assert.True(kid.PoolTokens == 100, $"carve: child asked 500, parent pool 100 → effective 100: {kid.PoolTokens}");
        var kid2 = rt.Spawn(c, "explore").Handle!;
        var kid3 = rt.Spawn(c, "explore");
        Assert.True(kid3.Error != null && kid3.Error.Contains("maxChildren"), "maxChildren enforced at spawn");

        var waitKid = rt.WaitAsync(kid);
        rt.Wake(kid, "go");
        await waitKid; // burns 80 tokens (2 turns×40) from both kid and parent pools
        Assert.True(c.PoolTokens == 20, $"roll-up drains the PARENT pool too: {c.PoolTokens}");

        var waitKid2 = rt.WaitAsync(kid2);
        rt.Wake(kid2, "go");
        var r2 = await waitKid2;
        Assert.True(r2.Status == "done", "pool nearly gone: sibling still ran (20 left > 0 at admission)");
        var r3 = await rt.RunTurnAsync(kid2, "again");
        Assert.True(r3.Status == "incomplete" && r3.Text.Contains("budget exhausted"),
            $"LIVE ancestor walk: pool exhausted → status=incomplete, partial work preserved, NO crash: {r3.Status}");

        var rt4 = new AgentRuntime(Opts(new SubagentMockHandler(), Registry(reg =>
            reg["looper"].Budget = new Budget { MaxTurns = 3 })));
        var lp = rt4.Spawn(rt4.Root, "looper").Handle!;
        var waitLp = rt4.WaitAsync(lp);
        rt4.Wake(lp, "loop forever");
        var rl = await waitLp;
        Assert.True(rl.Status == "incomplete", $"maxTurns cap is LOUD: status=incomplete (not silent done): {rl.Status}");
    }

    // ---- examples/subagent-lifecycle (interrupt): turn-abort, not a kill ----

    [Fact]
    public async Task Interrupt_TurnAbortToIdle_InboxRestored_AgentStillUsable()
    {
        var handler = new SubagentMockHandler { SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
        var rt = new AgentRuntime(Opts(handler, Registry()));
        var s = rt.Spawn(rt.Root, "slow").Handle!;
        rt.Post(s, new InboxItem("root", "peer", "for later"));
        var wait = rt.WaitAsync(s);
        rt.Wake(s, "work slowly");
        await Task.Delay(60);
        Assert.True(s.State == "running", $"agent is mid-turn (running): {s.State}");
        rt.Interrupt(s);
        var r = await wait;
        Assert.True(r.IsError && r.Status == "interrupted",
            $"turn aborted → isError result, status=interrupted (classified by token state): {r.Status}");
        Assert.True(s.State == "idle", $"agent is IDLE (alive), not closed: {s.State}");
        Assert.True(s.InboxCount == 1, "transactional drain: the drained item was RESTORED to the inbox");

        // Not a kill — a subsequent wake runs normally AND the turn-gate slot survived the abort.
        handler.SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        handler.SlowGate.TrySetResult(); // released up-front: next turn completes immediately
        var r2 = await rt.RunTurnAsync(s, "work again");
        Assert.True(r2.Status == "done" && r2.Text == "slow-done",
            $"subsequent wake ran normally after interrupt: {r2.Status} {r2.Text}");
    }

    /// <summary>Gate slot survives a killed Run (SPEC §7D: release on acquirer death).</summary>
    [Fact]
    public async Task TurnGate_SlotReleasedWhenAcquirerDies()
    {
        var handler = new SubagentMockHandler { SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
        var rt = new AgentRuntime(Opts(handler, Registry(), maxConcurrentTurns: 1));
        var s = rt.Spawn(rt.Root, "slow").Handle!;
        var wait = rt.WaitAsync(s);
        rt.Wake(s, "park"); // holds the ONLY gate slot, parked on SlowGate
        await Task.Delay(60);
        rt.Interrupt(s);    // hard-abort the Run mid-HTTP-call
        await wait;

        var peer = rt.Spawn(rt.Root, "peer").Handle!;
        var r = await rt.RunTurnAsync(peer, "1. hello").WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(r.Status == "done", $"queued acquirer proceeded — the slot was released on death: {r.Status}");
    }

    // ---- examples/subagent-lifecycle (closeCascade): leaf-first ----

    [Fact]
    public async Task CloseCascade_LeafFirst_PostAfterCloseRejected()
    {
        var closeOrder = new List<string>();
        Func<Handle, string, Task> record = (hh, _) => { lock (closeOrder) closeOrder.Add(hh.Id); return Task.CompletedTask; };
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry(reg =>
        {
            reg["coordinator"].OnClose = record;
            reg["peer"].OnClose = record;
        })));
        var c = rt.Spawn(rt.Root, "coordinator").Handle!;
        var k1 = rt.Spawn(c, "peer").Handle!;
        var k2 = rt.Spawn(k1, "peer").Handle!; // grandchild

        await rt.CloseAsync(rt.Root);
        Assert.True(closeOrder.Count == 3 && closeOrder[0] == k2.Id && closeOrder[1] == k1.Id && closeOrder[2] == c.Id,
            $"leaf-first order (grandchild → child → parent): {string.Join(" → ", closeOrder)}");
        var p = rt.Post(k1, new InboxItem("x", "peer", "late"));
        Assert.True(!p.Ok && p.Error!.Contains("closed"), "post after close = loud isError");
    }

    // ---- runtime-owned infrastructure ----

    /// <summary>ONE runtime-wide ConversationStore, conversation id = handle id — transcripts
    /// genuinely survive turns (SPEC §7D "Runtime owns cross-cutting infrastructure").</summary>
    [Fact]
    public async Task ConversationStore_TranscriptSurvivesTurns()
    {
        var store = new InMemoryConversationStore();
        var opts = Opts(new SubagentMockHandler(), Registry());
        opts.Store = store;
        var rt = new AgentRuntime(opts);
        var peer = rt.Spawn(rt.Root, "peer").Handle!;
        await rt.RunTurnAsync(peer, "1. first turn");
        await rt.RunTurnAsync(peer, "1. second turn");

        var transcript = await store.GetAsync(peer.ConvId);
        Assert.NotNull(transcript);
        var texts = transcript!.OfType<IDictionary<string, object?>>()
            .Where(m => (m.TryGetValue("role", out var role) ? role as string : null) == "user")
            .Select(m => m.TryGetValue("content", out var content) ? content as string : null)
            .ToList();
        Assert.True(texts.Any(t => t?.Contains("first turn") == true) && texts.Any(t => t?.Contains("second turn") == true),
            $"stored transcript contains BOTH turns' messages: [{string.Join(" | ", texts)}]");
        Assert.True(peer.TurnsTotal == 2, $"turn accounting across the surviving transcript: {peer.TurnsTotal}");
    }

    /// <summary>The stored transcript REWINDS to the pre-turn checkpoint on a durable pending:
    /// the §10 placeholder tool result never persists (it would make a resumed parent see the
    /// task as resolved and skip reattachment); after resume the store holds the REPLAYED turn.</summary>
    [Fact]
    public async Task ConversationStore_PendingTurnNotPersisted_ResumeReplaysFromCheckpoint()
    {
        var store = new InMemoryConversationStore();
        var opts = Opts(new SubagentMockHandler(), Registry());
        opts.Store = store;
        var rt = new AgentRuntime(opts);
        var p = rt.Spawn(rt.Root, "approverParent").Handle!;
        var wait = rt.WaitAsync(p);
        rt.Wake(p, "do the secret thing");
        var r1 = await wait;
        Assert.True(r1.Status == "pending", $"phase 1 halts durably: {r1.Status}");

        var leafId = rt.List().First(h => h.Id.Contains("asker")).Id;
        Assert.True(await store.GetAsync(leafId) is null or { Count: 0 },
            "halted LEAF turn was NOT persisted — checkpoint stays pre-turn");
        Assert.True(await store.GetAsync(p.ConvId) is null or { Count: 0 },
            "halted PARENT turn was NOT persisted — checkpoint stays pre-turn");

        await rt.ResumeAsync(new Answer { Id = r1.Pending!.Id, Ok = true });
        await Task.Delay(50);
        var leaf = await store.GetAsync(leafId);
        Assert.NotNull(leaf);
        var toolContents = leaf!.OfType<IDictionary<string, object?>>()
            .Where(m => (m.TryGetValue("role", out var role) ? role as string : null) == "tool")
            .Select(m => m.TryGetValue("content", out var content) ? content as string : null)
            .ToList();
        Assert.True(toolContents.Contains("secret-token"),
            $"resume REPLAYED the turn — the resolved tool result is stored: [{string.Join(" | ", toolContents)}]");
        Assert.True(!toolContents.Contains("approve secret access?"),
            "the §10 placeholder result never entered the persisted transcript");
    }

    /// <summary>Transactional drain restores items on FORCED CLOSE too, not only interrupt
    /// (agent-runtime spec delta: "interrupt or forced close"); final state stays queryable.</summary>
    [Fact]
    public async Task ForcedClose_RestoresDrainedItems_FinalStateQueryable()
    {
        var handler = new SubagentMockHandler { SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
        var rt = new AgentRuntime(Opts(handler, Registry()));
        var s = rt.Spawn(rt.Root, "slow").Handle!;
        rt.Post(s, new InboxItem("root", "peer", "for later"));
        rt.Wake(s, "park"); // drains the item into the turn, then parks mid-LLM-call
        await Task.Delay(60);
        Assert.True(s.State == "running" && s.InboxCount == 0, "the item was drained into the aborted turn");
        await rt.CloseAsync(s, force: true);
        Assert.True(s.State == "closed", $"forced close reached closed: {s.State}");
        Assert.True(s.InboxCount == 1,
            "the drained item was RESTORED on the forced-close abort (close ≠ loss; checkpoint queryable)");
    }

    /// <summary>wait resolves with the next result OR the last result — a settled handle answers
    /// immediately; registration order is unobservable (SPEC §7D wait).</summary>
    [Fact]
    public async Task Wait_AfterCompletion_ResolvesImmediatelyWithLastResult()
    {
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry()));
        var peer = rt.Spawn(rt.Root, "peer").Handle!;
        var done = await rt.RunTurnAsync(peer, "1. x"); // completes BEFORE any wait registers
        var late = rt.WaitAsync(peer);
        Assert.True(late.IsCompleted, "wait on a settled handle resolves immediately");
        Assert.True((await late).Text == done.Text, "…with the LAST result");
    }

    /// <summary>Wait timeout on the injectable (virtual) clock: explicit timeout error, child
    /// keeps running (SPEC §7D wait + virtual-clock determinism).</summary>
    [Fact]
    public async Task Wait_TimeoutOnVirtualClock_ChildKeepsRunning()
    {
        var clock = new VirtualClock();
        var handler = new SubagentMockHandler { SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
        var opts = Opts(handler, Registry());
        opts.Clock = clock;
        var rt = new AgentRuntime(opts);
        var s = rt.Spawn(rt.Root, "slow").Handle!;
        rt.Wake(s, "park");
        await Task.Delay(40); // let the turn reach the parked LLM call
        var wait = rt.WaitAsync(s, timeoutMs: 5_000);
        Assert.False(wait.IsCompleted, "virtual clock has not advanced — no timeout yet");
        clock.Advance(TimeSpan.FromMilliseconds(5_001));
        var r = await wait.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(r.IsError && r.Status == "timeout" && r.Text.Contains("wait timeout"),
            $"expired timeout = explicit timeout error: {r.Status} {r.Text}");
        Assert.True(s.State == "running", $"the child KEEPS RUNNING through a wait timeout: {s.State}");
        handler.SlowGate.TrySetResult();
        var final = await rt.WaitAsync(s).WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(final.Status == "done", $"the same run still completes for a later wait: {final.Status}");
    }

    /// <summary>task outside the caller's team ⇒ uniform error listing the team (SPEC §7D team
    /// scoping); the parent's model judges the error result (never an exception).</summary>
    [Fact]
    public async Task TeamScoping_OutOfTeamTask_ErrorListsTeam_AsToolResult()
    {
        var rt = new AgentRuntime(Opts(new SubagentMockHandler(), Registry()));
        var rogue = rt.Spawn(rt.Root, "rogue").Handle!;
        var r = await rt.RunTurnAsync(rogue, "delegate to a stranger");
        Assert.True(r.Status == "done", $"the parent run continued past the refused call: {r.Status}");
        Assert.True(r.Text.Contains("not in this agent's team") && r.Text.Contains("explore"),
            $"refusal names the team: {r.Text}");
    }
}
