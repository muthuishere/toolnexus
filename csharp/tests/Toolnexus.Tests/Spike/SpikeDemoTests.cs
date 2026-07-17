// SPIKE demo — 9 acceptance scenarios (S1–S9) for the agent-runtime substrate v2.
// C# port of js/spike/demo.ts. Zero network, zero cost: the "LLM" is a scripted in-process
// HttpMessageHandler keyed by the request body's "model" (the JS mockFetch pattern, on the
// shipped client's HttpHandler seam). Each scenario asserts observable state transitions.
//
// Run: cd csharp && dotnet test --filter Spike

using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Toolnexus.Tests.Spike;

// ---------- scripted mock LLM (openai style, keyed by body.model) ----------

internal sealed class MockLlmHandler : HttpMessageHandler
{
    /// <summary>S8: the m-slow model parks on this gate until released (or the turn is cancelled).</summary>
    public TaskCompletionSource? SlowGate;

    /// <summary>Small per-call latency so sibling turns genuinely overlap (the JS microtask analogue).</summary>
    public int DelayMs = 15;

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var body = await request.Content!.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        var model = root.GetProperty("model").GetString() ?? "";
        var msgs = root.GetProperty("messages").EnumerateArray().ToList();
        var toolMsgs = msgs.Where(m =>
            m.TryGetProperty("role", out var r) && r.GetString() == "tool").ToList();
        string ToolContent(JsonElement m) => m.GetProperty("content").GetString() ?? "";

        if (DelayMs > 0) await Task.Delay(DelayMs, ct).ConfigureAwait(false);

        switch (model)
        {
            case "m-coordinator":
                if (toolMsgs.Count == 0)
                    return SpikeLlm.OpenAi(new
                    {
                        role = "assistant",
                        content = (string?)null,
                        tool_calls = new object[]
                        {
                            SpikeLlm.RawCall("c1", "task", new { agent = "explore", prompt = "find A" }),
                            SpikeLlm.RawCall("c2", "task", new { agent = "explore", prompt = "find B" }),
                        },
                    });
                return SpikeLlm.OpenAi(new
                {
                    role = "assistant",
                    content = $"synthesis: {string.Join(" + ", toolMsgs.Select(ToolContent))}",
                });
            case "m-explore":
                if (toolMsgs.Count == 0) return SpikeLlm.Call("lookup", new { q = "x" }, "e1");
                return SpikeLlm.Text($"found:{ToolContent(toolMsgs[0])}");
            case "m-approver-parent":
                if (toolMsgs.Count == 0) return SpikeLlm.Call("task", new { agent = "asker", prompt = "get the secret" }, "p1");
                return SpikeLlm.Text($"parent-final: {ToolContent(toolMsgs[^1])}");
            case "m-asker":
            {
                var approved = toolMsgs.Any(m => ToolContent(m).Contains("secret-token"));
                if (!approved) return SpikeLlm.Call("check_secret", new { }, "a1");
                return SpikeLlm.Text("asker-done: secret-token");
            }
            case "m-peer":
            {
                var last = msgs.Count > 0 && msgs[^1].TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String
                    ? c.GetString() ?? "" : "";
                var items = Regex.Matches(last, @"^\d+\.", RegexOptions.Multiline).Count;
                return SpikeLlm.Text($"processed {items} items");
            }
            case "m-loop": // never finishes: always another tool call → maxTurns/incomplete
                return SpikeLlm.Call("lookup", new { q = "again" }, $"l{toolMsgs.Count}");
            case "m-slow":
                await (SlowGate ?? throw new InvalidOperationException("SlowGate unset"))
                    .Task.WaitAsync(ct).ConfigureAwait(false);
                return SpikeLlm.Text("slow-done");
            default:
                return SpikeLlm.Text("ok");
        }
    }
}

internal static class SpikeLlm
{
    public static HttpResponseMessage OpenAi(object message) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(JsonSerializer.Serialize(new
        {
            choices = new[] { new { message } },
            usage = new { prompt_tokens = 30, completion_tokens = 10, total_tokens = 40 },
        }), Encoding.UTF8, "application/json"),
    };

    public static object RawCall(string id, string name, object args) => new
    {
        id,
        type = "function",
        function = new { name, arguments = JsonSerializer.Serialize(args) },
    };

    public static HttpResponseMessage Call(string name, object args, string id)
        => OpenAi(new { role = "assistant", content = (string?)null, tool_calls = new[] { RawCall(id, name, args) } });

    public static HttpResponseMessage Text(string content)
        => OpenAi(new { role = "assistant", content });

    // ---------- scoped tools ----------
    public static ITool Lookup() => NativeTool.Of("lookup", "look something up",
        new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["q"] = new Dictionary<string, object?> { ["type"] = "string" },
            },
        },
        args => $"data({(args.TryGetValue("q", out var q) ? q : null)})");

    public static ITool CheckSecret() => NativeTool.Of("check_secret", "needs human approval",
        new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() },
        (IDictionary<string, object?> _, ToolContext? ctx) =>
            ctx?.Answer?.Ok == true
                ? (object)"secret-token"
                : ToolResult.Pending(new Request { Kind = "approval", Prompt = "approve secret access?" }));
}

public class SpikeDemoTests
{
    // ---------- registry ----------
    private static Dictionary<string, AgentDef> Registry(Action<Dictionary<string, AgentDef>>? mutate = null)
    {
        var reg = new Dictionary<string, AgentDef>
        {
            ["coordinator"] = new() { Name = "coordinator", Description = "splits and delegates", SystemPrompt = "coordinate", Model = "m-coordinator" },
            ["explore"] = new() { Name = "explore", Description = "read-only research", SystemPrompt = "explore", Model = "m-explore", Tools = new List<ITool> { SpikeLlm.Lookup() } },
            ["approverParent"] = new() { Name = "approverParent", Description = "delegates, holds approval authority", SystemPrompt = "", Model = "m-approver-parent" },
            ["asker"] = new() { Name = "asker", Description = "needs approvals", SystemPrompt = "", Model = "m-asker", Tools = new List<ITool> { SpikeLlm.CheckSecret() } },
            ["peer"] = new() { Name = "peer", Description = "team member", SystemPrompt = "", Model = "m-peer" },
            ["looper"] = new() { Name = "looper", Description = "never finishes", SystemPrompt = "", Model = "m-loop", Tools = new List<ITool> { SpikeLlm.Lookup() } },
            ["slow"] = new() { Name = "slow", Description = "slow worker", SystemPrompt = "", Model = "m-slow" },
        };
        mutate?.Invoke(reg);
        return reg;
    }

    private static RuntimeOptions Opts(MockLlmHandler handler, Dictionary<string, AgentDef> registry,
        int inboxCap = 8, int maxConcurrentTurns = 8)
        => new() { Handler = handler, Registry = registry, InboxCap = inboxCap, MaxConcurrentTurns = maxConcurrentTurns };

    // S1+S2 — fan-out, isolation, parallelism, usage roll-up ------------------
    [Fact]
    public async Task S1_S2_TaskFanOut_ContextIsolation_Parallel_UsageRollup()
    {
        var rt = new AgentRuntime(Opts(new MockLlmHandler(), Registry()));
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
        Assert.True(coord.UsageTotal == 240, $"usage rolls up to parent (G3 ledger): usage={coord.UsageTotal}");
        Assert.True(rt.List().All(h => h.Id == coord.Id || h.State == "closed"),
            "children auto-closed after task");
    }

    // S3 — suspension escalated INLINE: nearest interpreter wins --------------
    [Fact]
    public async Task S3_ChildSuspends_ParentWaitForAnswers_NearestInterpreter()
    {
        var rt = new AgentRuntime(Opts(new MockLlmHandler(), Registry(reg =>
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

    // S4 — durable pending at root + resume cascade from checkpoint -----------
    [Fact]
    public async Task S4_NoInterpreter_DurablePending_ResumeCascadesFromCheckpoint()
    {
        var rt = new AgentRuntime(Opts(new MockLlmHandler(), Registry()));
        var p = rt.Spawn(rt.Root, "approverParent").Handle!;
        var wait = rt.WaitAsync(p);
        rt.Wake(p, "do the secret thing");
        var r1 = await wait;

        Assert.True(r1.Status == "pending", $"root run returned status=pending: {r1.Status}");
        Assert.True(r1.PendingPath != null && string.Join("/", r1.PendingPath).Contains("approverParent.1"),
            $"Request carries the handle PATH to the leaf: {string.Join("/", r1.PendingPath ?? Array.Empty<string>())}");
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
    }

    // S5 — team peer: coalesced drain + provenance + timer dedupe -------------
    [Fact]
    public async Task S5_UnsolicitedRail_PostsCoalesce_ProvenanceMarked_TicksDedupe()
    {
        var rt = new AgentRuntime(Opts(new MockLlmHandler(), Registry()));
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

    // S6 — backpressure: inbox gate, concurrency gate, global turn gate -------
    [Fact]
    public async Task S6_Backpressure_InboxReject_ConcurrencyQueue_GlobalTurnGate()
    {
        var rt = new AgentRuntime(Opts(new MockLlmHandler(), Registry(), inboxCap: 2));
        var peer = rt.Spawn(rt.Root, "peer").Handle!;
        rt.Post(peer, new InboxItem("a", "peer", "1"));
        rt.Post(peer, new InboxItem("a", "peer", "2"));
        var third = rt.Post(peer, new InboxItem("a", "peer", "3"));
        Assert.True(!third.Ok && third.Error!.Contains("inbox full"),
            "inbox gate: third post REJECTED loudly to sender");

        var rt2 = new AgentRuntime(Opts(new MockLlmHandler(), Registry(reg =>
            reg["coordinator"].Budget = new Budget { MaxConcurrent = 1 })));
        var c2 = rt2.Spawn(rt2.Root, "coordinator").Handle!;
        var wait2 = rt2.WaitAsync(c2);
        rt2.Wake(c2, "go");
        var r2 = await wait2;
        Assert.True(r2.Status == "done" && rt2.TraceContains("wake QUEUED") && rt2.TraceContains("DEQUEUED wake"),
            $"concurrency gate: 2nd child QUEUED then DEQUEUED, still completes: {r2.Text}");
        Assert.True(rt2.MaxObservedConcurrentTurns <= 2,
            "concurrency gate: never >1 child turn in flight... but parent turn allowed");

        var rt3 = new AgentRuntime(Opts(new MockLlmHandler(), Registry(), maxConcurrentTurns: 1));
        var c3 = rt3.Spawn(rt3.Root, "coordinator").Handle!;
        var wait3 = rt3.WaitAsync(c3);
        rt3.Wake(c3, "go");
        await wait3;
        Assert.True(rt3.MaxObservedConcurrentTurns == 1,
            $"global turn gate: with cap 1, max observed concurrent turns = 1: max={rt3.MaxObservedConcurrentTurns}");
    }

    // S7 — budgets: carve, exhaustion=incomplete, maxTurns visible ------------
    [Fact]
    public async Task S7_Budgets_HierarchicalCarve_ExhaustionIncomplete_MaxTurnsLoud()
    {
        var rt = new AgentRuntime(Opts(new MockLlmHandler(), Registry()));
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
        Assert.True(r2.Status == "done", "pool nearly gone: sibling still ran (20 left > 0)");
        var r3 = await rt.RunTurnAsync(kid2, "again");
        Assert.True(r3.Status == "incomplete" && r3.Text.Contains("budget exhausted"),
            $"pool exhausted → status=incomplete, partial work preserved, NO crash: {r3.Status}");

        var rt4 = new AgentRuntime(Opts(new MockLlmHandler(), Registry(reg =>
            reg["looper"].Budget = new Budget { MaxTurns = 3 })));
        var lp = rt4.Spawn(rt4.Root, "looper").Handle!;
        var waitLp = rt4.WaitAsync(lp);
        rt4.Wake(lp, "loop forever");
        var rl = await waitLp;
        Assert.True(rl.Status == "incomplete", $"maxTurns cap is LOUD: status=incomplete (not silent done): {rl.Status}");
    }

    // S8 — interrupt: aborts the TURN, not the agent --------------------------
    [Fact]
    public async Task S8_Interrupt_TurnAbortToIdle_InboxIntact_NotAKill()
    {
        var handler = new MockLlmHandler { SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
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
            $"turn aborted → isError result, status=interrupted: {r.Status}");
        Assert.True(s.State == "idle", $"agent is IDLE (alive), not closed: {s.State}");
        Assert.True(s.Inbox.Count == 1, "inbox survived the interrupt");
        handler.SlowGate.TrySetResult();
    }

    // S9 — close cascade: leaf-first; post-after-close rejected ---------------
    [Fact]
    public async Task S9_StopAll_CloseCascadesLeafFirst_ClosedHandlesRejectPosts()
    {
        var rt = new AgentRuntime(Opts(new MockLlmHandler(), Registry()));
        var c = rt.Spawn(rt.Root, "coordinator").Handle!;
        var k1 = rt.Spawn(c, "peer").Handle!;
        var k2 = rt.Spawn(k1, "peer").Handle!; // grandchild
        var closeOrder = new List<string>();
        foreach (var h in new[] { c, k1, k2 })
            h.Def = h.Def.CloneWith(h.Def.Budget ?? new Budget());
        c.Def.OnClose = (hh, _) => { lock (closeOrder) closeOrder.Add(hh.Id); return Task.CompletedTask; };
        k1.Def.OnClose = (hh, _) => { lock (closeOrder) closeOrder.Add(hh.Id); return Task.CompletedTask; };
        k2.Def.OnClose = (hh, _) => { lock (closeOrder) closeOrder.Add(hh.Id); return Task.CompletedTask; };

        await rt.CloseAsync(rt.Root);
        Assert.True(closeOrder.Count == 3 && closeOrder[0] == k2.Id && closeOrder[1] == k1.Id && closeOrder[2] == c.Id,
            $"leaf-first order (grandchild → child → parent): {string.Join(" → ", closeOrder)}");
        var p = rt.Post(k1, new InboxItem("x", "peer", "late"));
        Assert.True(!p.Ok && p.Error!.Contains("closed"), "post after close = loud isError");
    }
}
