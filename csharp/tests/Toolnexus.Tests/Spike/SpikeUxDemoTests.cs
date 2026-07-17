// SPIKE demo 2 — the Level-1/2/bridge UX (S10–S13) on the same mock-LLM pattern.
// C# port of js/spike/demo-ux.ts. Run: cd csharp && dotnet test --filter Spike

using System.Net;
using System.Text.Json;

namespace Toolnexus.Tests.Spike;

internal sealed class UxMockLlmHandler : HttpMessageHandler
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
                if (toolMsgs.Count == 0) return SpikeLlm.Call("task", new { agent = "explore", prompt = "find the bug" }, "t1");
                return SpikeLlm.Text($"fixed using: {ToolContent(toolMsgs[0])} [soul:{(sys.Contains("You are the CODER") ? "loaded" : "missing")}]");
            case "m-explore":
                if (toolMsgs.Count == 0) return SpikeLlm.Call("lookup", new { q = "bug" }, "e1");
                return SpikeLlm.Text($"bug at line 42 ({ToolContent(toolMsgs[0])})");
            case "m-mia":
            {
                // persona: soul sections visible? heartbeat with nothing to do → HEARTBEAT_OK
                var last = msgs.Count > 0 && msgs[^1].TryGetProperty("content", out var lc) && lc.ValueKind == JsonValueKind.String
                    ? lc.GetString() ?? "" : "";
                if (last.Contains("Heartbeat"))
                {
                    var hasTicks = last.Contains("channel=timer");
                    return SpikeLlm.Text(sys.Contains("water the plants") && hasTicks
                        ? "Reminder: water the plants!"
                        : Agents.HeartbeatOk);
                }
                var visible = new[] { "SOUL.md", "USER.md", "MEMORY.md" }.Where(f => sys.Contains($"## {f}"));
                return SpikeLlm.Text($"soul-sections:[{string.Join(",", visible)}]");
            }
            case "m-old-api":
                if (toolMsgs.Count == 0) return SpikeLlm.Call("explore", new { prompt = "scan the repo" }, "b1");
                return SpikeLlm.Text($"old-api summary: {ToolContent(toolMsgs[0])}");
            default:
                return SpikeLlm.Text("ok");
        }
    }
}

public class SpikeUxDemoTests
{
    private static RuntimeOptions Opts() => new() { Handler = new UxMockLlmHandler() };

    // S10 — Level 1: the 12-line coding agent -------------------------------
    [Fact]
    public async Task S10_Level1Ux_Agent_TeamWiring_SoulFile()
    {
        var soulPath = Path.Combine(Path.GetTempPath(), $"AGENTS-spike-{Guid.NewGuid():N}.md");
        File.WriteAllText(soulPath, "You are the CODER. Fix things surgically.");
        try
        {
            var explore = new Agent("explore", new AgentSpec
            {
                Does = "read-only research", Uses = new List<ITool> { SpikeLlm.Lookup() }, Model = "m-explore",
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

    // S11 — team scoping: task cannot reach outside the declared team ---------
    [Fact]
    public void S11_TeamScoping_TaskTargetsAreTheTeam_NothingElse()
    {
        var stranger = new Agent("stranger", new AgentSpec
        {
            Does = "should be unreachable", Model = "m-explore", Uses = new List<ITool> { SpikeLlm.Lookup() },
        });
        var explore = new Agent("explore", new AgentSpec
        {
            Does = "read-only research", Uses = new List<ITool> { SpikeLlm.Lookup() }, Model = "m-explore",
        });
        var coder = new Agent("coder", new AgentSpec
        {
            Does = "implements", Team = new List<Agent> { explore }, Model = "m-coder",
        });
        _ = stranger;
        var reg = coder.Registry();
        Assert.True(string.Join(",", reg.Keys.OrderBy(k => k, StringComparer.Ordinal)) == "coder,explore",
            $"registry contains only the reachable graph: {string.Join(",", reg.Keys)}");
        Assert.True(reg["coder"].TaskTargets != null && string.Join(",", reg["coder"].TaskTargets!) == "explore",
            "coder's task targets are exactly its team");
    }

    // S12 — Level 2: agentFromDir + heartbeat + HEARTBEAT_OK silence ----------
    [Fact]
    public async Task S12_Level2Ux_DirectoryIsTheAgent_Heartbeat_SilentOk()
    {
        var dir = Directory.CreateTempSubdirectory("mia-").FullName;
        try
        {
            File.WriteAllText(Path.Combine(dir, "SOUL.md"), "You are Mia. Warm, brief.");
            File.WriteAllText(Path.Combine(dir, "USER.md"), "The user is Muthu.");
            File.WriteAllText(Path.Combine(dir, "MEMORY.md"), "- Likes green tea.");
            File.WriteAllText(Path.Combine(dir, "HEARTBEAT.md"), "On heartbeat: if it is watering day, remind to water the plants.");
            var mia = Agents.AgentFromDir(dir, new AgentSpec { Model = "m-mia" });

            var direct = await mia.RunAsync(Opts(), "hello");
            Assert.True(direct.Text == "soul-sections:[SOUL.md,USER.md,MEMORY.md]",
                $"bootstrap files discovered + injected as ## sections (openclaw order): {direct.Text}");

            var reports = new List<string>();
            var started = Agents.StartAgent(mia, Opts(), everyMs: 30, onReport: t => { lock (reports) reports.Add(t); });
            await Task.Delay(250);
            await started.StopAsync();
            var hbTurns = started.Runtime.TraceLines().Count(l => l.Contains("idle→running"));
            Assert.True(hbTurns >= 2, $"heartbeat woke the agent repeatedly: turns={hbTurns}");
            lock (reports)
                Assert.True(reports.All(t => t.Contains("water")),
                    $"HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats): [{string.Join("; ", reports)}]");
            Assert.True(started.Handle.State == "closed", "agent closed cleanly on stop()");
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    // S13 — the bridge: agent.AsTool() inside the OLD API ---------------------
    [Fact]
    public async Task S13_Bridge_AnAgentIsATool_InTheClassicApi()
    {
        var handler = new UxMockLlmHandler();
        var rtOpts = new RuntimeOptions { Handler = handler };
        var explore = new Agent("explore", new AgentSpec
        {
            Does = "read-only research", Uses = new List<ITool> { SpikeLlm.Lookup() }, Model = "m-explore",
        });
        await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        toolkit.Register(explore.AsTool(rtOpts));
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://mock.local", Style = "openai", Model = "m-old-api", ApiKey = "spike",
            HttpHandler = handler,
        });
        var r = await client.RunAsync("summarize", toolkit);
        Assert.True(r.Text.Contains("old-api summary:") && r.Text.Contains("bug at line 42"),
            $"old API called the agent like any tool: {r.Text}");
        Assert.True(r.ToolCalls.Count > 0 && r.ToolCalls[0].Metadata?["agent"] as string == "explore",
            "agent metadata surfaced through the tool result");
    }
}
