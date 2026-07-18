using System.Text.Json;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

// `Toolnexus.Agent` is the A2A remote-agent descriptor; the §7D local agent lives in
// `Toolnexus.Agents`. Inside the Toolnexus.* namespace the enclosing lookup would win, so this
// alias (inside the namespace) pins `Agent` to the runtime one for this file.
using Agent = Toolnexus.Agents.Agent;

/// <summary>Scripted mock LLM for the Level-1/bridge surface checks (soul wiring, team wiring,
/// persona sections, the classic-API bridge).</summary>
internal sealed class SurfaceMockHandler : HttpMessageHandler
{
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var body = await request.Content!.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        var model = root.GetProperty("model").GetString() ?? "";
        var msgs = root.GetProperty("messages").EnumerateArray().ToList();
        var toolMsgs = msgs.Where(m =>
            m.TryGetProperty("role", out var r) && r.GetString() == "tool").ToList();
        var sys = msgs.FirstOrDefault(m =>
                m.TryGetProperty("role", out var r) && r.GetString() == "system") is { ValueKind: JsonValueKind.Object } sm
            && sm.TryGetProperty("content", out var sc) && sc.ValueKind == JsonValueKind.String
            ? sc.GetString() ?? "" : "";
        string ToolContent(JsonElement m) => m.GetProperty("content").GetString() ?? "";

        await Task.Delay(10, ct).ConfigureAwait(false);

        switch (model)
        {
            case "m-coder":
                if (toolMsgs.Count == 0) return MockLlm.Call("task", new { agent = "explore", prompt = "find the bug" }, "t1");
                return MockLlm.Text($"fixed using: {ToolContent(toolMsgs[0])} [soul:{(sys.Contains("You are the CODER") ? "loaded" : "missing")}]");
            case "m-explore":
                if (toolMsgs.Count == 0) return MockLlm.Call("lookup", new { q = "bug" }, "e1");
                return MockLlm.Text($"bug at line 42 ({ToolContent(toolMsgs[0])})");
            case "m-mia":
            {
                // persona: soul sections visible? heartbeat with nothing to do → HEARTBEAT_OK
                var last = msgs.Count > 0 && msgs[^1].TryGetProperty("content", out var lc) && lc.ValueKind == JsonValueKind.String
                    ? lc.GetString() ?? "" : "";
                if (last.Contains("Heartbeat"))
                {
                    var hasTicks = last.Contains("channel=timer");
                    return MockLlm.Text(sys.Contains("water the plants") && hasTicks
                        ? "Reminder: water the plants!"
                        : AgentSurfaceTests.HeartbeatOk);
                }
                var visible = new[] { "SOUL.md", "USER.md", "MEMORY.md" }.Where(f => sys.Contains($"## {f}"));
                return MockLlm.Text($"soul-sections:[{string.Join(",", visible)}]");
            }
            case "m-old-api":
                if (toolMsgs.Count == 0) return MockLlm.Call("explore", new { prompt = "scan the repo" }, "b1");
                return MockLlm.Text($"old-api summary: {ToolContent(toolMsgs[0])}");
            default:
                return MockLlm.Text("ok");
        }
    }
}

/// <summary>
/// SPEC §7D Level-1 surface + the Agent-is-a-Tool bridge: `agent(name, spec)` (does / uses /
/// soul|soulFile / team / budget / model / waitFor / onSpawn / onClose), `run(prompt)`,
/// `asTool()` into the classic API, team-scoped registries (transitive closure), and a
/// persona/heartbeat exercise of the unsolicited rail (the directory-persona SUGAR itself ships
/// with the agent-home change; the substrate behavior it rides is §7D and is tested here).
/// </summary>
public class AgentSurfaceTests
{
    /// <summary>A silent heartbeat wake produces no outbound report.</summary>
    public const string HeartbeatOk = "HEARTBEAT_OK";

    private static RuntimeOptions Opts() => new() { Handler = new SurfaceMockHandler(), ApiKey = "test" };

    // ---- Level 1: the 12-line coding agent --------------------------------

    [Fact]
    public async Task Level1_Agent_TeamWiring_SoulFileReachesSystemPrompt()
    {
        var soulPath = Path.Combine(Path.GetTempPath(), $"AGENTS-tn-{Guid.NewGuid():N}.md");
        File.WriteAllText(soulPath, "You are the CODER. Fix things surgically.");
        try
        {
            var explore = new Agent("explore", new AgentSpec
            {
                Does = "read-only research", Uses = new List<ITool> { MockLlm.Lookup() }, Model = "m-explore",
            });
            var coder = new Agent("coder", new AgentSpec
            {
                Does = "implements changes", SoulFile = soulPath, Team = new List<Agent> { explore },
                Model = "m-coder", Budget = new Budget { MaxTokens = 10_000 },
            });
            var r = await coder.RunAsync(Opts(), "fix the failing test");
            Assert.True(r.Status == "done" && r.Text.Contains("bug at line 42"),
                $"coding agent completes via its team: {r.Text}");
            Assert.True(r.Text.Contains("[soul:loaded]"), $"soul FILE reached the system prompt: {r.Text}");
        }
        finally
        {
            File.Delete(soulPath);
        }
    }

    // ---- team scoping: the registry is the reachable graph ----------------

    [Fact]
    public void TeamScoping_RegistryIsTransitiveClosure_TaskTargetsAreTheTeam()
    {
        var stranger = new Agent("stranger", new AgentSpec
        {
            Does = "should be unreachable", Model = "m-explore", Uses = new List<ITool> { MockLlm.Lookup() },
        });
        var explore = new Agent("explore", new AgentSpec
        {
            Does = "read-only research", Uses = new List<ITool> { MockLlm.Lookup() }, Model = "m-explore",
        });
        var coder = new Agent("coder", new AgentSpec
        {
            Does = "implements", Team = new List<Agent> { explore }, Model = "m-coder",
        });
        _ = stranger;
        var reg = coder.Registry();
        Assert.True(string.Join(",", reg.Keys.OrderBy(k => k, StringComparer.Ordinal)) == "coder,explore",
            $"registry contains only the reachable graph: {string.Join(",", reg.Keys)}");
        Assert.True(reg["coder"].Team != null && string.Join(",", reg["coder"].Team!) == "explore",
            "coder's task targets are exactly its team");
        Assert.True(reg["explore"].Team == null,
            "a team-less child gets NO task tool (recursion is opt-in, never default)");
    }

    // ---- persona/heartbeat: the unsolicited rail end-to-end ----------------
    // (Directory discovery + StartAgent sugar ship with agent-home; the helpers below are
    // test-local so this exercises ONLY the §7D substrate: soul injection, timer posts on the
    // unsolicited rail, tick coalescing, silent HEARTBEAT_OK wakes, clean close.)

    private static readonly string[] BootstrapOrder =
        { "AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md" };

    private static Agent AgentFromDir(string dir, string model)
    {
        var sections = BootstrapOrder
            .Select(f => Path.Combine(dir, f))
            .Where(File.Exists)
            .Select(p => $"## {Path.GetFileName(p)}\n\n{File.ReadAllText(p).Trim()}");
        return new Agent(Path.GetFileName(dir.TrimEnd(Path.DirectorySeparatorChar)), new AgentSpec
        {
            Does = $"persona agent from {dir}", Soul = string.Join("\n\n", sections), Model = model,
        });
    }

    [Fact]
    public async Task Persona_SoulSectionsInjected_HeartbeatTicksCoalesce_SilentOk()
    {
        var dir = Directory.CreateTempSubdirectory("mia-").FullName;
        try
        {
            File.WriteAllText(Path.Combine(dir, "SOUL.md"), "You are Mia. Warm, brief.");
            File.WriteAllText(Path.Combine(dir, "USER.md"), "The user is Muthu.");
            File.WriteAllText(Path.Combine(dir, "MEMORY.md"), "- Likes green tea.");
            File.WriteAllText(Path.Combine(dir, "HEARTBEAT.md"), "On heartbeat: if it is watering day, remind to water the plants.");
            var mia = AgentFromDir(dir, "m-mia");

            var direct = await mia.RunAsync(Opts(), "hello");
            Assert.True(direct.Text == "soul-sections:[SOUL.md,USER.md,MEMORY.md]",
                $"bootstrap files injected as ## sections in order: {direct.Text}");

            // Long-lived persona: a timer posts ticks on the unsolicited rail and wakes at idle.
            var rt = new AgentRuntime(Opts().CloneWithRegistryForTest(mia.Registry()));
            var handle = rt.Spawn(rt.Root, mia.Name).Handle!;
            var reports = new List<string>();
            using (var timerCts = new CancellationTokenSource())
            {
                var beat = Task.Run(async () =>
                {
                    using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(30));
                    while (await timer.WaitForNextTickAsync(timerCts.Token))
                    {
                        rt.Post(handle, new InboxItem("clock", "timer", "tick"));
                        if (handle.State == "idle")
                        {
                            var r = await rt.RunTurnAsync(handle,
                                "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.");
                            if (!r.IsError && !r.Text.Contains(HeartbeatOk)) { lock (reports) reports.Add(r.Text); }
                        }
                    }
                });
                await Task.Delay(250);
                timerCts.Cancel();
                try { await beat; } catch (OperationCanceledException) { }
            }
            await rt.CloseAsync(rt.Root);

            var hbTurns = rt.TraceLines().Count(l => l.Contains("idle→running"));
            Assert.True(hbTurns >= 2, $"heartbeat woke the agent repeatedly: turns={hbTurns}");
            lock (reports)
                Assert.True(reports.All(t => t.Contains("water")),
                    $"HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats): [{string.Join("; ", reports)}]");
            Assert.True(handle.State == "closed", "agent closed cleanly on stop");
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    // ---- the bridge: agent.AsTool() inside the classic API -----------------

    [Fact]
    public async Task Bridge_AnAgentIsATool_InTheClassicApi()
    {
        var handler = new SurfaceMockHandler();
        var rtOpts = new RuntimeOptions { Handler = handler, ApiKey = "test" };
        var explore = new Agent("explore", new AgentSpec
        {
            Does = "read-only research", Uses = new List<ITool> { MockLlm.Lookup() }, Model = "m-explore",
        });
        await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        toolkit.Register(explore.AsTool(rtOpts));
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://mock.local", Style = "openai", Model = "m-old-api", ApiKey = "test",
            HttpHandler = handler,
        });
        var r = await client.RunAsync("summarize", toolkit);
        Assert.True(r.Text.Contains("old-api summary:") && r.Text.Contains("bug at line 42"),
            $"the classic API called the agent like any tool: {r.Text}");
        Assert.True(r.ToolCalls.Count > 0 && r.ToolCalls[0].Metadata?["agent"] as string == "explore",
            "agent metadata surfaced through the tool result");
    }
}

/// <summary>Test-only bridge for building a runtime from an agent's registry with shared opts.</summary>
internal static class RuntimeOptionsTestExtensions
{
    public static RuntimeOptions CloneWithRegistryForTest(this RuntimeOptions opts, Dictionary<string, AgentDef> registry)
        => new()
        {
            Handler = opts.Handler, Registry = registry, InboxCap = opts.InboxCap,
            MaxConcurrentTurns = opts.MaxConcurrentTurns, ShutdownMs = opts.ShutdownMs,
            Store = opts.Store, Clock = opts.Clock,
            BaseUrl = opts.BaseUrl, Style = opts.Style, ApiKey = opts.ApiKey, Model = opts.Model,
        };
}
