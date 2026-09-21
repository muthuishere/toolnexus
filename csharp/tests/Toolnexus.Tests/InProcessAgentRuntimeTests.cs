using System.Threading;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>
/// Tests for ADR 0030 (docs/adr/0030-the-in-process-seam-stops-at-the-top-level-client.md):
/// <c>RuntimeOptions.InProcess</c>, the semantic counterpart to <c>RuntimeOptions.Handler</c>, so
/// a host whose model is a C# function doesn't have to hand-build an
/// <see cref="System.Net.Http.HttpMessageHandler"/> to reach the sub-agent runtime. Mirrors
/// golang/agents/inprocess_test.go.
/// </summary>
public class InProcessAgentRuntimeTests
{
    /// <summary>
    /// The fake model shared by BOTH the top-level client and the sub-agent runtime below — the
    /// reporter's actual case. It never touches HTTP: it is handed the assembled
    /// <see cref="InProcess.Request"/> and returns one <see cref="InProcess.Response"/>. It
    /// instruments concurrency so the gate test can assert on real overlap, not just a
    /// trust-me counter.
    /// </summary>
    private sealed class ScriptedModel
    {
        private int _inFlight;
        private int _maxSeen;
        private int _overlaps;
        private int _calls;
        public TimeSpan Hold { get; set; }

        public int Calls => Volatile.Read(ref _calls);
        public int MaxSeen => Volatile.Read(ref _maxSeen);
        public int Overlaps => Volatile.Read(ref _overlaps);

        public InProcess.Response Generate(InProcess.Request req)
        {
            var n = Interlocked.Increment(ref _inFlight);
            try
            {
                Interlocked.Increment(ref _calls);
                int seen;
                while (n > (seen = Volatile.Read(ref _maxSeen)))
                    Interlocked.CompareExchange(ref _maxSeen, n, seen);
                if (n > 1) Interlocked.Increment(ref _overlaps);
                if (Hold > TimeSpan.Zero) Thread.Sleep(Hold); // widen the window so a real bypass WOULD overlap
                return InProcess.Response.FromContent($"ok model={req.Model} turn-done");
            }
            finally
            {
                Interlocked.Decrement(ref _inFlight);
            }
        }
    }

    private static Dictionary<string, AgentDef> WorkerRegistry() => new()
    {
        ["worker"] = new AgentDef { Name = "worker", Does = "a scripted worker" },
    };

    /// <summary>
    /// Proves the reporter's actual case: ONE generate function serving BOTH a top-level
    /// <see cref="InProcess.CreateClient"/> AND a sub-agent runtime (via the new
    /// <see cref="RuntimeOptions.InProcess"/>), with no copied adapter code — the runtime builds
    /// its handler by calling the SAME public <see cref="InProcess.GenerateBackedHandler"/> that
    /// <see cref="InProcess.CreateClient"/> uses.
    /// </summary>
    [Fact]
    public async Task SharedGenerate_TopLevelAndSubAgent_NoCopiedAdapter()
    {
        var m = new ScriptedModel();

        // Top-level client, driven by m.Generate directly.
        var client = InProcess.CreateClient(new InProcess.Options { Model = "spike-model", Generate = m.Generate });
        await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var topRes = await client.RunAsync("hello", toolkit);
        Assert.Equal("done", topRes.Status);

        // Sub-agent runtime, driven by the SAME m.Generate via RuntimeOptions.InProcess.
        var rt = new AgentRuntime(new RuntimeOptions { InProcess = m.Generate, Registry = WorkerRegistry() });
        var h = rt.Spawn(rt.Root, "worker").Handle!;
        var wait = rt.WaitAsync(h);
        rt.Wake(h, "do the thing");
        var subRes = await wait;
        Assert.Equal("done", subRes.Status);

        Assert.Equal(2, m.Calls); // one top-level RunAsync, one sub-agent turn
    }

    /// <summary>Construction-time validation, never precedence: setting both
    /// <see cref="RuntimeOptions.Handler"/> and <see cref="RuntimeOptions.InProcess"/> must throw
    /// loudly.</summary>
    [Fact]
    public void Constructor_HandlerAndInProcessConflict_Throws()
    {
        var m = new ScriptedModel();
        Assert.Throws<InvalidOperationException>(() => new AgentRuntime(new RuntimeOptions
        {
            Handler = new InProcess.GenerateBackedHandler(m.Generate),
            InProcess = m.Generate,
            Registry = WorkerRegistry(),
        }));
    }

    /// <summary>Same validation for the InProcess / LLM-endpoint-fields pair.</summary>
    [Fact]
    public void Constructor_InProcessAndLlmConflict_Throws()
    {
        var m = new ScriptedModel();
        Assert.Throws<InvalidOperationException>(() => new AgentRuntime(new RuntimeOptions
        {
            BaseUrl = "http://example.invalid",
            InProcess = m.Generate,
            Registry = WorkerRegistry(),
        }));
    }

    /// <summary>
    /// The falsifiable gate test (ADR 0030 gate item 2): MaxConcurrentTurns=1, five workers woken
    /// concurrently on the NEW <see cref="RuntimeOptions.InProcess"/> path. If the global turn
    /// gate (GateHandler, AgentRuntime.cs) stopped wrapping the handler built from
    /// RuntimeOptions.InProcess, ScriptedModel would observe &gt;1 in flight and this test fails.
    /// </summary>
    [Fact]
    public async Task Gate_HoldsAtConcurrencyOne_ZeroOverlaps()
    {
        var m = new ScriptedModel { Hold = TimeSpan.FromMilliseconds(15) };
        var rt = new AgentRuntime(new RuntimeOptions
        {
            InProcess = m.Generate,
            MaxConcurrentTurns = 1,
            Registry = WorkerRegistry(),
        });

        const int n = 5;
        var tasks = new Task[n];
        for (var i = 0; i < n; i++)
        {
            var idx = i;
            tasks[i] = Task.Run(async () =>
            {
                var h = rt.Spawn(rt.Root, "worker").Handle!;
                var wait = rt.WaitAsync(h);
                rt.Wake(h, $"job {idx}");
                var r = await wait;
                Assert.Equal("done", r.Status);
            });
        }
        await Task.WhenAll(tasks);

        Assert.True(m.Overlaps == 0,
            $"gate FALSIFIED: {m.Overlaps} call(s) observed >1 in flight (maxSeen={m.MaxSeen}) "
            + "with MaxConcurrentTurns=1 — the global turn gate did not wrap the "
            + "RuntimeOptions.InProcess handler");
        Assert.Equal(1, rt.MaxObservedConcurrentTurns);
    }

    /// <summary>
    /// The negative control: same 5 concurrent workers, but MaxConcurrentTurns=5 (effectively no
    /// gate). Proves the assertion above is a real detector, not a tautology — with room to run
    /// concurrently, ScriptedModel DOES observe overlap.
    /// </summary>
    [Fact]
    public async Task Gate_Control_MaxConcurrencyFive_ObservesOverlap()
    {
        var m = new ScriptedModel { Hold = TimeSpan.FromMilliseconds(15) };
        var rt = new AgentRuntime(new RuntimeOptions
        {
            InProcess = m.Generate,
            MaxConcurrentTurns = 5,
            Registry = WorkerRegistry(),
        });

        const int n = 5;
        var tasks = new Task[n];
        for (var i = 0; i < n; i++)
        {
            var idx = i;
            tasks[i] = Task.Run(async () =>
            {
                var h = rt.Spawn(rt.Root, "worker").Handle!;
                var wait = rt.WaitAsync(h);
                rt.Wake(h, $"job {idx}");
                await wait;
            });
        }
        await Task.WhenAll(tasks);

        Assert.True(m.Overlaps > 0,
            $"control INVALID: expected overlap with MaxConcurrentTurns=5 and a 15ms hold, got 0 "
            + $"overlaps (maxSeen={m.MaxSeen}) — the detector proves nothing");
    }
}
