using System.Text.Json;
using System.IO;
using System.Text.RegularExpressions;
using Toolnexus.Acp;

namespace Toolnexus.Tests;

/// <summary>
/// Hermetic tests for <see cref="AcpClient"/> (issue #96, openspec/changes/add-acp-model-source,
/// docs/adr/0031). Everything runs over REAL OS pipes against <c>FakeAcpServer</c> (a sibling
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

    private static Task<AcpClient> StartAsync(string scenario, CancellationToken ct = default, string? outFile = null)
    {
        var args = new List<string> { FakeServerDllPath(), $"--scenario={scenario}" };
        if (outFile is not null) args.Add($"--out={outFile}");
        return AcpClient.StartAsync("dotnet", args, cwd: Directory.GetCurrentDirectory(), ct: ct);
    }

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

            // Default: the client executes tools, so the agent's own tool is refused — the first
            // reject-kind option is selected, not the allow one listed after it.
            Assert.Equal("PERMITTED[reject]:delete the database", reply);
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

    [Fact]
    public async Task PermissionRequest_AllowAgentTools_SelectsFirstAllowOption()
    {
        var client = await StartAsync("permission");
        client.AllowAgentTools = true;
        try
        {
            var sw = System.Diagnostics.Stopwatch.StartNew();
            var reply = await client.PromptAsync("go");
            sw.Stop();
            Assert.Equal("PERMITTED[allow-once]:go", reply);
            Assert.True(sw.Elapsed < TimeSpan.FromSeconds(3), $"permission was awaited, not answered ({sw.Elapsed})");
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

    // ---------------------------------------------------------------------------------------
    // ACP as a real tool-calling model (SPEC §8 "ACP model source",
    // openspec/changes/add-acp-tool-calling)
    // ---------------------------------------------------------------------------------------

    /// <summary>Splits a rendered prompt back into its preamble and REQUEST object.</summary>
    private static (string Preamble, JsonElement Request) SplitPrompt(string prompt)
    {
        var i = prompt.IndexOf("\nREQUEST:\n", StringComparison.Ordinal);
        var j = prompt.LastIndexOf("\n\n" + AcpClient.SupersedesMarker + " ", StringComparison.Ordinal);
        Assert.True(i >= 0 && j > i, $"prompt does not split: {prompt}");
        using var doc = JsonDocument.Parse(prompt[(i + "\nREQUEST:\n".Length)..j]);
        return (prompt[..i], doc.RootElement.Clone());
    }

    [Fact]
    public async Task ToolCallingLoop_EndToEnd_AgentCallsAddThenAnswersFromItsResult()
    {
        var outFile = Path.Combine(Path.GetTempPath(), $"acp-toolloop-{Guid.NewGuid():N}.ndjson");
        var client = await StartAsync("toolloop", outFile: outFile);
        try
        {
            await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
            var ran = new List<string>();
            toolkit.Register(NativeTool.Of("add", "Add two numbers", new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["a"] = new Dictionary<string, object?> { ["type"] = "number" },
                    ["b"] = new Dictionary<string, object?> { ["type"] = "number" },
                },
                ["required"] = new List<object?> { "a", "b" },
            }, args =>
            {
                var sum = (Convert.ToDouble(args["a"]) + Convert.ToDouble(args["b"])).ToString(System.Globalization.CultureInfo.InvariantCulture);
                lock (ran) ran.Add(sum);
                return sum;
            }));

            var llm = InProcess.CreateClient(new InProcess.Options { Model = "acp", Generate = client.Generate });
            var result = await llm.RunAsync("What is 2 + 3?", toolkit);

            Assert.Equal("The answer is 5.", result.Text);
            var call = Assert.Single(result.ToolCalls);
            Assert.Equal("add", call.Name);
            Assert.Equal("5", call.Output);
            Assert.Equal(new[] { "5" }, ran);
        }
        finally
        {
            client.Dispose();
        }

        var requests = File.ReadAllLines(outFile).Where(l => l.Length > 0)
            .Select(l => JsonDocument.Parse(l).RootElement).ToList();
        File.Delete(outFile);
        Assert.Equal(2, requests.Count); // ask, then answer

        // Turn 1: add's OpenAI-shaped schema reached the agent.
        Assert.Contains(requests[0].GetProperty("tools").EnumerateArray(), t =>
            t.TryGetProperty("function", out var fn) &&
            fn.GetProperty("name").GetString() == "add" &&
            fn.TryGetProperty("parameters", out var ps) && ps.ValueKind == JsonValueKind.Object);

        // Turn 2: the assistant tool_calls message and the tool result are both in the REQUEST.
        var msgs = requests[1].GetProperty("messages").EnumerateArray().ToList();
        Assert.Contains(msgs, m => m.GetProperty("role").GetString() == "assistant" &&
                                   m.TryGetProperty("tool_calls", out var tc) && tc.ValueKind == JsonValueKind.Array);
        Assert.Contains(msgs, m => m.GetProperty("role").GetString() == "tool" &&
                                   m.TryGetProperty("tool_call_id", out var id) && id.GetString() == "c1" &&
                                   m.TryGetProperty("content", out var c) && c.GetString() == "5");
    }

    [Fact]
    public void PromptShape_PreambleRequestJsonAndMarker()
    {
        var prompt = AcpClient.BuildPromptText(new InProcess.Request
        {
            Messages = new List<object?>
            {
                new Dictionary<string, object?> { ["role"] = "system", ["content"] = "be terse" },
                new Dictionary<string, object?>
                {
                    ["role"] = "user",
                    ["content"] = new List<object?>
                    {
                        new Dictionary<string, object?> { ["type"] = "text", ["text"] = "a <b> & c" },
                        new Dictionary<string, object?> { ["type"] = "image_url" },
                        new Dictionary<string, object?> { ["type"] = "text", ["text"] = "d" },
                    },
                },
            },
        });

        var (preamble, request) = SplitPrompt(prompt);
        Assert.Equal(AcpClient.Preamble, preamble);
        Assert.EndsWith("\n\nSUPERSEDES-ALL-PRIOR: a <b> & c d", prompt);
        Assert.DoesNotContain("\\u003c", prompt); // REQUEST JSON is not HTML-escaped
        Assert.DoesNotContain("\\u0026", prompt);
        Assert.Equal(JsonValueKind.Array, request.GetProperty("tools").ValueKind);
        Assert.Equal(0, request.GetProperty("tools").GetArrayLength()); // absent tools ⇒ []
        Assert.Contains("\nREQUEST:\n{\"messages\":", prompt);      // JSON leads with messages
    }

    [Fact]
    public void PromptShape_NoUserMessage_FallsBackToLastMessage_NoMessagesIsEmpty()
    {
        var p1 = AcpClient.BuildPromptText(new InProcess.Request
        {
            Messages = new List<object?> { new Dictionary<string, object?> { ["role"] = "system", ["content"] = "sys" } },
        });
        Assert.EndsWith("\n\nSUPERSEDES-ALL-PRIOR: sys", p1);
        var p2 = AcpClient.BuildPromptText(new InProcess.Request());
        Assert.EndsWith("\nREQUEST:\n{\"messages\":[],\"tools\":[]}\n\nSUPERSEDES-ALL-PRIOR: ", p2);
    }

    /// <summary>The preamble is byte-pinned by SPEC.md §8; read it back from the spec so the
    /// constant cannot drift from the contract every port is held to. Skipped (passes) when the
    /// spec is not reachable from the test's working directory.</summary>
    [Fact]
    public void Preamble_MatchesSpecBytes()
    {
        string? specPath = null;
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "SPEC.md");
            if (File.Exists(candidate)) { specPath = candidate; break; }
        }
        if (specPath is null) return; // SPEC.md not reachable — nothing to compare against

        var spec = File.ReadAllText(specPath).Replace("\r\n", "\n");
        var i = spec.IndexOf("`PREAMBLE` is these seven lines", StringComparison.Ordinal);
        Assert.True(i >= 0, "SPEC.md has no ACP preamble block");
        spec = spec[i..];
        var start = spec.IndexOf("```\n", StringComparison.Ordinal) + "```\n".Length;
        var end = spec.IndexOf("```", start, StringComparison.Ordinal);
        Assert.Equal(AcpClient.Preamble, spec[start..end]);
    }

    public static IEnumerable<object?[]> ParseReplyCases()
    {
        var add = "[{\"id\":\"c1\",\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}]";
        var addStr = "[{\"id\":\"c1\",\"name\":\"add\",\"arguments\":\"{\\\"a\\\":2,\\\"b\\\":3}\"}]";
        // name, input, expected tool calls (JSON of {id,name,arguments}, null = none), expected content
        yield return new object?[] { "plain prose passes through", "just text {not json", null, "just text {not json" };
        yield return new object?[] { "content envelope", "{\"content\":\"The answer is 5.\"}", null, "The answer is 5." };
        yield return new object?[] { "null content", "{\"content\":null}", null, "" };
        yield return new object?[] { "non-string content encodes", "{\"content\":{\"x\":1}}", null, "{\"x\":1}" };
        yield return new object?[] { "string arguments pre-encoded", "{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"add\",\"arguments\":\"{\\\"a\\\":2,\\\"b\\\":3}\"}}]}", addStr, null };
        yield return new object?[] { "object arguments", "{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]}", add, null };
        yield return new object?[] { "fenced", "```json\n{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]}\n```", add, null };
        yield return new object?[] { "prose around", "Calling now: {\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]} done", add, null };
        yield return new object?[] { "choices envelope", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]}}]}", add, null };
        yield return new object?[] { "message envelope", "{\"message\":{\"content\":\"hi\"}}", null, "hi" };
        yield return new object?[] { "flat call, no id, no arguments", "{\"tool_calls\":[{\"name\":\"ping\"}]}", "[{\"id\":null,\"name\":\"ping\",\"arguments\":{}}]", null };
        yield return new object?[] { "nameless call skipped, falls to content", "{\"tool_calls\":[{\"function\":{\"arguments\":\"{}\"}}],\"content\":\"fallback\"}", null, "fallback" };
        yield return new object?[] { "structured output passes through", " {\"answer\":true} ", null, " {\"answer\":true} " };
    }

    [Theory]
    [MemberData(nameof(ParseReplyCases))]
    public void ParseReply_Table(string name, string input, string? expectedCalls, string? expectedContent)
    {
        var got = AcpClient.ParseReply(input);
        if (expectedCalls is null)
        {
            Assert.True(got.ToolCalls is null || got.ToolCalls.Count == 0, $"{name}: unexpected tool calls");
            Assert.Equal(expectedContent, got.Content);
            return;
        }
        Assert.Null(got.Content);
        var actual = Json.Stringify(got.ToolCalls!.Select(c => new Dictionary<string, object?>
        {
            ["id"] = c.Id, ["name"] = c.Name, ["arguments"] = c.Arguments,
        }).ToList());
        Assert.Equal(expectedCalls, actual);
        // A string argument stays a string (pre-encoded); an object stays structured.
        if (name == "string arguments pre-encoded") Assert.IsType<string>(got.ToolCalls![0].Arguments);
    }
}
