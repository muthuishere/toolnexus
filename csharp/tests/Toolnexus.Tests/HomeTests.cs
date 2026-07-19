using System.Text.Json;
using Toolnexus;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>
/// Scripted mock LLM for the agent-home (SPEC §7E) checks — openai style, keyed by the request
/// body's <c>model</c>, mirroring <c>examples/persona-agent/fixture.json → mockLLM.scripts</c>.
/// Zero network, zero cost.
/// </summary>
internal sealed class HomeMockHandler : HttpMessageHandler
{
    /// <summary>When set, a heartbeat turn parks here (in-flight) until released — lets a test post
    /// ticks WHILE a turn is running to prove they coalesce into one later turn.</summary>
    public TaskCompletionSource? Gate;

    /// <summary>Signalled once a gated turn has actually entered the LLM call (deterministic).</summary>
    public TaskCompletionSource? Entered;

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var body = await request.Content!.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        var model = root.GetProperty("model").GetString() ?? "";
        var msgs = root.GetProperty("messages").EnumerateArray().ToList();
        var toolMsgs = msgs.Where(m => m.TryGetProperty("role", out var r) && r.GetString() == "tool").ToList();
        var sys = msgs.FirstOrDefault(m =>
                m.TryGetProperty("role", out var r) && r.GetString() == "system") is { ValueKind: JsonValueKind.Object } sm
            && sm.TryGetProperty("content", out var sc) && sc.ValueKind == JsonValueKind.String
            ? sc.GetString() ?? "" : "";
        var last = msgs.Count > 0 && msgs[^1].TryGetProperty("content", out var lc) && lc.ValueKind == JsonValueKind.String
            ? lc.GetString() ?? "" : "";
        string ToolContent(JsonElement m) => m.GetProperty("content").GetString() ?? "";

        switch (model)
        {
            case "m-echo-soul": // reports which bootstrap sections it can see, in order
                return MockLlm.Text($"sections:[{string.Join(",", Home.BootstrapOrder.Where(f => sys.Contains($"## {f}")))}]");
            case "m-remember": // writes a memory then confirms
                if (toolMsgs.Count == 0)
                    return MockLlm.Call("memory", new { action = "add", target = "user", text = "Prefers dark roast" }, "w1");
                return MockLlm.Text($"saved: {ToolContent(toolMsgs[0])}");
            case "m-recall": // proves the next-session snapshot carries prior USER.md content
                return MockLlm.Text(sys.Contains("Prefers dark roast") ? "I recall: dark roast" : "no memory");
            case "m-heartbeat": // speaks only when the HEARTBEAT.md rule fires, else HEARTBEAT_OK
                if (Gate != null)
                {
                    Entered?.TrySetResult();
                    await Gate.Task.WaitAsync(ct).ConfigureAwait(false);
                }
                return MockLlm.Text(sys.Contains("remind about the 3pm sync") && last.Contains("Heartbeat")
                    ? "Reminder: 3pm sync 🔔"
                    : Home.HeartbeatOk);
            default:
                return MockLlm.Text("ok");
        }
    }
}

/// <summary>
/// SPEC §7E agent home — the persona archetype over the shipped §7D runtime. The 15 checks
/// (H1–H7) from the behavioral reference (js/spike/home.ts + home-demo.ts, 15/15), driven by the
/// shared <c>examples/persona-agent/fixture.json</c> on a scripted mock LLM and a virtual clock.
/// </summary>
public class HomeTests
{
    // examples/persona-agent/fixture.json → bootstrapDir (the §0 conformance artifact).
    private static readonly Dictionary<string, string> FixtureBootstrap = new()
    {
        ["SOUL.md"] = "You are Ava, a calm support assistant.",
        ["USER.md"] = "The user is Muthu; timezone IST.",
        ["HEARTBEAT.md"] = "If it is time, remind about the 3pm sync.",
        ["MEMORY.md"] = "- Onboarded 2026-07.",
    };

    private static string MkDir(IDictionary<string, string> files)
    {
        var dir = Directory.CreateTempSubdirectory("persona-").FullName;
        foreach (var (f, body) in files) File.WriteAllText(Path.Combine(dir, f), body);
        return dir;
    }

    private static RuntimeOptions Opts(TimeProvider? clock = null)
        => new() { Handler = new HomeMockHandler(), ApiKey = "test", Clock = clock };

    // ---- H1: the directory IS the agent — ordered bootstrap files → soul ----

    [Fact]
    public async Task H1_ComposedSoul_OrderedSections_ReachesChildSystemPrompt()
    {
        var dir = MkDir(FixtureBootstrap);
        try
        {
            var (soul, found) = Home.ComposeSoul(dir);

            // H1_composedSoulSectionsInOrder: only present files, in canonical order.
            Assert.True(string.Join(",", found) == "SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md",
                $"discovers only present files in canonical order: {string.Join(",", found)}");
            Assert.True(soul.IndexOf("## SOUL.md", StringComparison.Ordinal) < soul.IndexOf("## USER.md", StringComparison.Ordinal)
                && soul.IndexOf("## USER.md", StringComparison.Ordinal) < soul.IndexOf("## HEARTBEAT.md", StringComparison.Ordinal)
                && soul.IndexOf("## HEARTBEAT.md", StringComparison.Ordinal) < soul.IndexOf("## MEMORY.md", StringComparison.Ordinal),
                "injected as ## sections in canonical order");

            // H1_soulReachesChildSystemPrompt: the composed soul reaches the child's system prompt.
            var ava = Home.FromDir(dir, new Home.FromDirOptions { Model = "m-echo-soul" });
            var r = await ava.RunAsync(Opts(), "who are you?");
            Assert.True(r.Text == "sections:[SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md]",
                $"the composed soul reaches the child system prompt: {r.Text}");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    // ---- H2: per-file byte cap ----

    [Fact]
    public void H2_Over2MBFile_TruncatedWithNotice_OnDiskUntouched()
    {
        var big = new string('x', 3 * 1024 * 1024);
        var dir = MkDir(new Dictionary<string, string> { ["SOUL.md"] = big });
        try
        {
            var (soul, _) = Home.ComposeSoul(dir);
            var soulBytes = System.Text.Encoding.UTF8.GetByteCount(soul);
            Assert.True(soul.Contains("[truncated: exceeds 2 MB bootstrap cap]") && soulBytes < 2.1 * 1024 * 1024,
                $"a >2 MB file is truncated (byte-based) with a notice: bytes={soulBytes}");
            Assert.True(File.ReadAllText(Path.Combine(dir, "SOUL.md")).Length == big.Length,
                "the on-disk file is untouched");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    // ---- H3: memory tool — add/replace/remove persist to disk ----

    [Fact]
    public async Task H3_MemoryTool_AddReplaceRemove_MissingIsLoud_TargetUser()
    {
        var dir = MkDir(new Dictionary<string, string> { ["MEMORY.md"] = "- Likes green tea." });
        try
        {
            var tool = Home.MemoryTool(dir);
            string Memory() => File.ReadAllText(Path.Combine(dir, "MEMORY.md"));

            await tool.ExecuteAsync(new Dictionary<string, object?> { ["action"] = "add", ["text"] = "Likes hiking" });
            Assert.True(Memory().Contains("- Likes hiking"), "add appends an entry");

            await tool.ExecuteAsync(new Dictionary<string, object?> { ["action"] = "replace", ["text"] = "green tea", ["with"] = "oolong" });
            Assert.True(Memory().Contains("oolong"), "replace swaps a substring");

            await tool.ExecuteAsync(new Dictionary<string, object?> { ["action"] = "remove", ["text"] = "- Likes hiking\n" });
            Assert.True(!Memory().Contains("hiking"), "remove deletes an entry");

            var miss = await tool.ExecuteAsync(new Dictionary<string, object?> { ["action"] = "replace", ["text"] = "nonexistent", ["with"] = "x" });
            Assert.True(miss.IsError, "a missing substring is a loud isError");

            var userWrite = await tool.ExecuteAsync(new Dictionary<string, object?> { ["action"] = "add", ["target"] = "user", ["text"] = "Speaks Tamil" });
            Assert.True(!userWrite.IsError && File.ReadAllText(Path.Combine(dir, "USER.md")).Contains("Speaks Tamil"),
                "target=user writes USER.md, not MEMORY.md");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    // ---- H4: frozen-snapshot — mid-session write does NOT change the live prompt ----

    [Fact]
    public async Task H4_FrozenSnapshot_WriteLandsOnDisk_NextSessionCarriesIt()
    {
        var dir = MkDir(new Dictionary<string, string> { ["USER.md"] = "The user is new." });
        try
        {
            var ava = Home.FromDir(dir, new Home.FromDirOptions { Model = "m-remember" });
            await ava.RunAsync(Opts(), "note my coffee preference");
            Assert.True(File.ReadAllText(Path.Combine(dir, "USER.md")).Contains("Prefers dark roast"),
                "the write landed on disk (frozen snapshot: live prompt unchanged, disk updated)");

            // A SECOND, fresh fromDir run re-reads the file → the snapshot now carries the note.
            var ava2 = Home.FromDir(dir, new Home.FromDirOptions { Model = "m-recall" });
            var r2 = await ava2.RunAsync(Opts(), "what do you know about me?");
            Assert.True(r2.Text == "I recall: dark roast",
                $"next session's snapshot carries the persisted memory: {r2.Text}");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    // ---- H5: heartbeat — silent OK, speaks only on a due item, ticks coalesce ----

    [Fact]
    public async Task H5_Heartbeat_SilentOk()
    {
        // Persona WITHOUT the 3pm rule ⇒ m-heartbeat replies HEARTBEAT_OK ⇒ nothing surfaces.
        var dir = MkDir(new Dictionary<string, string> { ["SOUL.md"] = "You are Ava." });
        try
        {
            var clock = new VirtualClock();
            var ava = Home.FromDir(dir, new Home.FromDirOptions { Model = "m-heartbeat", Memory = false });
            var started = Home.StartAgent(ava, Opts(clock), everyMs: 20);
            clock.Advance(TimeSpan.FromMilliseconds(20)); // one beat fires
            await started.SettleAsync();
            var woke = started.Runtime.TraceLines().Any(l => l.Contains("idle→running"));
            await started.StopAsync();
            Assert.True(woke && started.Beats.Count == 0,
                $"a HEARTBEAT_OK beat woke the agent but stayed SILENT: beats=[{string.Join("; ", started.Beats)}]");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    [Fact]
    public async Task H5_Heartbeat_DueSurfaces()
    {
        var dir = MkDir(FixtureBootstrap); // HEARTBEAT.md = "…remind about the 3pm sync."
        try
        {
            var clock = new VirtualClock();
            var reports = new List<string>();
            var ava = Home.FromDir(dir, new Home.FromDirOptions { Model = "m-heartbeat" });
            var started = Home.StartAgent(ava, Opts(clock), everyMs: 20, onBeat: t => { lock (reports) reports.Add(t); });
            clock.Advance(TimeSpan.FromMilliseconds(20));
            await started.SettleAsync();
            await started.StopAsync();
            Assert.True(started.Beats.Count >= 1 && started.Beats.All(b => b.Contains("3pm sync")),
                $"a due heartbeat surfaces to the host onBeat: beats=[{string.Join("; ", started.Beats)}]");
            lock (reports) Assert.True(reports.Any(r => r.Contains("3pm sync")), "onBeat handler received the beat");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    [Fact]
    public async Task H5_Heartbeat_TicksCoalesceToOneTurn()
    {
        var dir = MkDir(FixtureBootstrap);
        try
        {
            var handler = new HomeMockHandler { Gate = new TaskCompletionSource(), Entered = new TaskCompletionSource() };
            var ava = Home.FromDir(dir, new Home.FromDirOptions { Model = "m-heartbeat" });
            var rt = new AgentRuntime(new RuntimeOptions { Handler = handler, ApiKey = "test", Registry = ava.Registry() });
            var h = rt.Spawn(rt.Root, ava.Name).Handle!;

            // Turn 1 starts and PARKS in-flight (its admission already drained the empty inbox).
            const string prompt = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.";
            var wait1 = rt.WaitAsync(h);
            rt.Wake(h, prompt);
            await handler.Entered.Task; // T1 is genuinely in-flight now

            // WHILE T1 runs, several ticks arrive on the unsolicited rail (wake-while-running is a
            // no-op; they sit in the inbox). Release T1.
            rt.Post(h, new InboxItem("clock", "timer", "tick"));
            rt.Post(h, new InboxItem("clock", "timer", "tick"));
            rt.Post(h, new InboxItem("clock", "timer", "tick"));
            handler.Gate.SetResult();
            var r1 = await wait1;
            Assert.True(r1.Turns == 1, $"T1 was a single in-flight turn: turns={r1.Turns}");

            // The three ticks buffered during T1 now drain as ONE coalesced turn — not one per tick.
            handler.Gate = null;
            var wait2 = rt.WaitAsync(h);
            rt.Wake(h);
            var r2 = await wait2;
            await rt.CloseAsync(rt.Root);
            Assert.True(r2.Turns == 1, $"ticks posted while a turn was in-flight coalesce to ONE turn: turns={r2.Turns}");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    // ---- H6: opt-out — memory disabled ⇒ no memory tool ----

    [Fact]
    public void H6_MemoryDisabled_NoMemoryTool()
    {
        var dir = MkDir(new Dictionary<string, string> { ["SOUL.md"] = "Read-only Ava." });
        try
        {
            var ava = Home.FromDir(dir, new Home.FromDirOptions { Memory = false });
            Assert.True(ava.Spec.Uses == null || ava.Spec.Uses.All(t => t.Name != "memory"),
                "no memory tool appears in the toolkit view when memory is disabled");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    // ---- H7: dream/consolidation recipe = fromDir + memory (composition, not new API) ----

    [Fact]
    public void H7_DreamRecipe_IsFromDirPlusMemory()
    {
        var dir = MkDir(new Dictionary<string, string>
        {
            ["SOUL.md"] = "Nightly consolidator.",
            ["HEARTBEAT.md"] = "Consolidate: merge duplicate notes into MEMORY.md.",
        });
        try
        {
            var dream = Home.FromDir(dir);
            Assert.True(dream.Spec.Uses != null && dream.Spec.Uses.Any(t => t.Name == "memory"),
                "a dream agent is just fromDir + the memory tool (composition, not new surface)");
        }
        finally { Directory.Delete(dir, recursive: true); }
    }
}
