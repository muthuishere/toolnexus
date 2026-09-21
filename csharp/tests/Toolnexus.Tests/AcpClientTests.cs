using System.IO;
using System.Text.RegularExpressions;
using Toolnexus.Acp;

namespace Toolnexus.Tests;

/// <summary>
/// Hermetic tests for <see cref="AcpClient"/> (issue #96, openspec/changes/add-acp-model-source,
/// docs/adr/0025). Everything runs over REAL OS pipes against <c>FakeAcpServer</c> (a sibling
/// test-helper console app, tests/FakeAcpServer/), spawned as a real child process via
/// <c>dotnet &lt;FakeAcpServer.dll&gt; --scenario=&lt;name&gt;</c> — no network, no real ACP
/// agent. FakeAcpServer mirrors spikes/acp/fakeagent/main.go's five scenarios.
/// </summary>
public class AcpClientTests
{
    /// <summary>
    /// Locates the built FakeAcpServer.dll. The test project references FakeAcpServer.csproj
    /// (see Toolnexus.Tests.csproj), which copies its output next to Toolnexus.Tests.dll — that
    /// is the fast path. If it isn't there (e.g. a differently-shaped build), fall back to a
    /// search under the repo's tests/ directory for the most recently built copy.
    /// </summary>
    private static string FakeServerDllPath()
    {
        var sameDir = Path.Combine(AppContext.BaseDirectory, "FakeAcpServer.dll");
        if (File.Exists(sameDir)) return sameDir;

        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && dir.Name != "tests")
            dir = dir.Parent;
        if (dir is null)
            throw new InvalidOperationException("toolnexus: could not locate the 'tests' directory to find FakeAcpServer.dll");

        var candidates = Directory.GetFiles(Path.Combine(dir.FullName, "FakeAcpServer"), "FakeAcpServer.dll", SearchOption.AllDirectories);
        if (candidates.Length == 0)
            throw new InvalidOperationException("toolnexus: FakeAcpServer.dll not found — build tests/FakeAcpServer first");
        return candidates.OrderByDescending(File.GetLastWriteTimeUtc).First();
    }

    private static Task<AcpClient> StartAsync(string scenario, CancellationToken ct = default) =>
        AcpClient.StartAsync("dotnet", new[] { FakeServerDllPath(), $"--scenario={scenario}" },
            cwd: Directory.GetCurrentDirectory(), ct: ct);

    private static InProcess.Request UserRequest(string text) => new()
    {
        Messages = new List<object?>
        {
            new Dictionary<string, object?> { ["role"] = "user", ["content"] = text },
        },
        Model = "acp",
    };

    // ---------------------------------------------------------------------------------------
    // Warm session reuse
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task WarmSession_OneProcessOneSessionNew_ServesManyTurns()
    {
        var client = await StartAsync("warm");
        try
        {
            Assert.Equal("sess-1", client.SessionId);

            var r1 = await client.PromptAsync(UserRequest("turn one"));
            var r2 = await client.PromptAsync(UserRequest("turn two"));
            var r3 = await client.PromptAsync(UserRequest("turn three"));

            // Every reply is prefixed by the fake server with sessionNewCalls=<n>;promptN=<n>;
            // so we can prove, from OUTSIDE the client, that session/new fired exactly once and
            // session/prompt fired once per call on the SAME warm session/process.
            AssertSessionNewCalls(r1, 1);
            AssertSessionNewCalls(r2, 1);
            AssertSessionNewCalls(r3, 1);
            AssertPromptN(r1, 1);
            AssertPromptN(r2, 2);
            AssertPromptN(r3, 3);
        }
        finally
        {
            client.Dispose();
        }
    }

    private static void AssertSessionNewCalls(string reply, int expected)
    {
        var m = Regex.Match(reply, @"sessionNewCalls=(\d+)");
        Assert.True(m.Success, $"reply did not carry sessionNewCalls: {reply}");
        Assert.Equal(expected, int.Parse(m.Groups[1].Value));
    }

    private static void AssertPromptN(string reply, int expected)
    {
        var m = Regex.Match(reply, @"promptN=(\d+)");
        Assert.True(m.Success, $"reply did not carry promptN: {reply}");
        Assert.Equal(expected, int.Parse(m.Groups[1].Value));
    }

    // ---------------------------------------------------------------------------------------
    // Thought / tool narration filtered
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task ThoughtAndToolNarration_AreFiltered_OnlyMessageChunksSurvive()
    {
        var client = await StartAsync("noisy");
        try
        {
            // Drive PromptAsync(string) directly (bypassing full-request assembly) so the fake
            // server's echoed text is exactly what this test asserts on — prompt assembly itself
            // is covered separately by SupersedesMarker_* below.
            var reply = await client.PromptAsync("55");

            // The fake server interleaves agent_thought_chunk + tool_call/tool_call_update around
            // a JSON payload split across two agent_message_chunk notifications. Only those two
            // chunks should survive, and concatenated they must be valid, parseable JSON.
            Assert.Equal("{\"answer\":\"55\"}", reply);
            using var doc = System.Text.Json.JsonDocument.Parse(reply); // throws if corrupted
            Assert.Equal("55", doc.RootElement.GetProperty("answer").GetString());
        }
        finally
        {
            client.Dispose();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Permission answered, not awaited by the caller
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task PermissionRequest_AnsweredAutomatically_TurnCompletesFast()
    {
        var client = await StartAsync("permission");
        try
        {
            var sw = System.Diagnostics.Stopwatch.StartNew();
            var reply = await client.PromptAsync("delete the database");
            sw.Stop();

            Assert.Equal("PERMITTED:delete the database", reply);
            // Well under AcpClient's own PromptTimeout (default 10s) — proves the permission
            // request was answered inline rather than the turn riding out any timeout.
            Assert.True(sw.Elapsed < TimeSpan.FromSeconds(3),
                $"turn took {sw.Elapsed}, expected well under 3s if permission was answered promptly");
        }
        finally
        {
            client.Dispose();
        }
    }

    /// <summary>The negative control for the test above: with AutoAnswerPermission off, the same
    /// scenario hangs until AcpClient's own PromptTimeout fires — proving the fast completion
    /// above is caused by the auto-answer, not something else in the plumbing.</summary>
    [Fact]
    public async Task PermissionRequest_UnansweredByChoice_TurnTimesOut()
    {
        var client = await StartAsync("hang");
        client.AutoAnswerPermission = false;
        client.PromptTimeout = TimeSpan.FromSeconds(2);
        try
        {
            await Assert.ThrowsAsync<TimeoutException>(() => client.PromptAsync("drop table users"));
        }
        finally
        {
            client.Dispose();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Stale-answer prevented by the supersedes marker
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task SupersedesMarker_MakesTheAgentAnswerTheFreshTurn_NotAStaleOne()
    {
        var client = await StartAsync("stale");
        try
        {
            // Turn 1: only "What is the capital of France?" has ever been sent.
            var r1 = await client.PromptAsync(UserRequest("What is the capital of France?"));
            Assert.Contains("What is the capital of France?", r1);

            // Turn 2: a full request that now contains BOTH questions (as PromptAsync(Request)
            // always assembles a full transcript) plus the second question as the fresh turn.
            // Without the supersedes marker the fake agent would answer the FIRST (stale)
            // question — AcpClient appends the marker itself, so it must answer the SECOND.
            var r2 = await client.PromptAsync(new InProcess.Request
            {
                Messages = new List<object?>
                {
                    new Dictionary<string, object?> { ["role"] = "user", ["content"] = "What is the capital of France?" },
                    new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = "Paris." },
                    new Dictionary<string, object?> { ["role"] = "user", ["content"] = "What is the capital of Japan?" },
                },
                Model = "acp",
            });

            Assert.StartsWith("FRESH-ANSWER-TO:", r2);
            Assert.Contains("What is the capital of Japan?", r2);
            Assert.DoesNotContain("STALE-ANSWER-TO", r2);
        }
        finally
        {
            client.Dispose();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Turn serialisation
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task ConcurrentCalls_AreSerialised_EachGetsItsOwnCorrectAnswer()
    {
        var client = await StartAsync("warm");
        try
        {
            var t1 = client.PromptAsync("alpha");
            var t2 = client.PromptAsync("beta");
            var t3 = client.PromptAsync("gamma");

            var results = await Task.WhenAll(t1, t2, t3);

            Assert.Contains(results, r => r.EndsWith("echo:alpha", StringComparison.Ordinal));
            Assert.Contains(results, r => r.EndsWith("echo:beta", StringComparison.Ordinal));
            Assert.Contains(results, r => r.EndsWith("echo:gamma", StringComparison.Ordinal));

            // Turns are serialised onto one session, so the fake server's own per-prompt counter
            // must have advanced through exactly 1,2,3 with no repeats/gaps, regardless of which
            // client-side call landed first.
            var seen = results.Select(r => int.Parse(Regex.Match(r, @"promptN=(\d+)").Groups[1].Value))
                               .OrderBy(n => n).ToArray();
            Assert.Equal(new[] { 1, 2, 3 }, seen);
        }
        finally
        {
            client.Dispose();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Idempotent close
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task Dispose_CalledTwice_DoesNotThrow()
    {
        var client = await StartAsync("warm");
        await client.PromptAsync(UserRequest("hello"));

        client.Dispose();
        var ex = Record.Exception(() => client.Dispose());
        Assert.Null(ex);
    }

    // ---------------------------------------------------------------------------------------
    // Generate() plugs directly into InProcess.Options.Generate
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task GenerateDelegate_PlugsIntoInProcessClient()
    {
        var client = await StartAsync("warm");
        try
        {
            await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
            var llm = InProcess.CreateClient(new InProcess.Options { Model = "acp", Generate = client.Generate });

            var result = await llm.RunAsync("ping", toolkit);
            Assert.Equal("done", result.Status);
            Assert.Contains("echo:", result.Text);
        }
        finally
        {
            client.Dispose();
        }
    }
}
