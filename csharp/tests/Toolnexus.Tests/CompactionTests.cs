using System.Net;
using System.Text;
using System.Text.Json;
using Toolnexus;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>
/// Context compaction (SPEC §7F) — the C1–C6 acceptance scenarios (11 checks) ported from the JS
/// spike, run against the shared <c>examples/compaction/fixture.json</c> generator + options. The
/// compactor is a pure <c>beforeLLM</c> helper: no-op below budget (byte-identical), summarize the
/// head + keep a recent tail above it, tool-pair safe, system prompt preserved, optional
/// flush-to-memory nudge, and — C6 — wired through the SHIPPED client loop via the §8 seam.
/// </summary>
public class CompactionTests
{
    // ---- the shared fixture: options + the deterministic transcript generator ----

    private sealed record Fixture(int UserTurns, string SystemPrompt, int MaxTokens, int KeepTail);

    private static Fixture LoadFixture()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(TestFixtures.Fixture("compaction/fixture.json")));
        var root = doc.RootElement;
        var gen = root.GetProperty("input").GetProperty("generator");
        var opts = root.GetProperty("options");
        return new Fixture(
            gen.GetProperty("userTurns").GetInt32(),
            root.GetProperty("input").GetProperty("systemPrompt").GetString()!,
            opts.GetProperty("maxTokens").GetInt32(),
            opts.GetProperty("keepTail").GetInt32());
    }

    /// <summary>Build the fixture transcript deterministically: systemPrompt, then `userTurns`
    /// groups of user / assistant+tool_calls / tool / assistant (same bytes as every port).</summary>
    private static List<object?> Transcript(string systemPrompt, int userTurns)
    {
        var m = new List<object?>
        {
            new Dictionary<string, object?> { ["role"] = "system", ["content"] = systemPrompt },
        };
        for (var i = 0; i < userTurns; i++)
        {
            m.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = $"question {i} {Repeat("pad ", 40)}" });
            m.Add(new Dictionary<string, object?>
            {
                ["role"] = "assistant",
                ["content"] = null,
                ["tool_calls"] = new List<object?>
                {
                    new Dictionary<string, object?>
                    {
                        ["id"] = $"c{i}",
                        ["type"] = "function",
                        ["function"] = new Dictionary<string, object?> { ["name"] = "lookup", ["arguments"] = "{}" },
                    },
                },
            });
            m.Add(new Dictionary<string, object?> { ["role"] = "tool", ["tool_call_id"] = $"c{i}", ["content"] = $"result {i} {Repeat("data ", 40)}" });
            m.Add(new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = $"answer {i}" });
        }
        return m;
    }

    private static string Repeat(string s, int n) => string.Concat(Enumerable.Repeat(s, n));

    private static string Summarize(IReadOnlyList<object?> older) => $"summarized {older.Count} messages";

    private static LlmClient.LLMOverride? Run(Compaction.Options opts, List<object?> msgs)
        => Compaction.Compactor(opts)(new LlmClient.BeforeLLMEvent(msgs, new List<Dictionary<string, object?>>(), "m", 0));

    // ---- small readers over the boxed JSON-object messages ----

    private static string? Role(object? m) =>
        m is IDictionary<string, object?> d && d.TryGetValue("role", out var r) ? r as string : null;

    private static string ContentStr(object? m) =>
        m is IDictionary<string, object?> d && d.TryGetValue("content", out var c) ? c?.ToString() ?? "" : "";

    private static string? Str(object? m, string key) =>
        m is IDictionary<string, object?> d && d.TryGetValue(key, out var v) ? v as string : null;

    private static IEnumerable<object?> ToolCalls(object? m) =>
        m is IDictionary<string, object?> d && d.TryGetValue("tool_calls", out var tc) && tc is IEnumerable<object?> list
            ? list : Enumerable.Empty<object?>();

    // ------------------------------------------------------------------ C1
    [Fact]
    public void C1_BelowBudget_IsNoOp()
    {
        var f = LoadFixture();
        var msgs = Transcript(f.SystemPrompt, 3);
        var outp = Run(new Compaction.Options { MaxTokens = 100_000, Summarize = Summarize }, msgs);
        Assert.Null(outp); // returns nothing when under budget → byte-identical no-op
    }

    // ------------------------------------------------------------------ C2
    [Fact]
    public void C2_AboveBudget_SummarizesHeadKeepsTail()
    {
        var f = LoadFixture();
        var msgs = Transcript(f.SystemPrompt, f.UserTurns);
        var before = Compaction.EstimateTokens(msgs);
        var outp = Run(new Compaction.Options { MaxTokens = f.MaxTokens, KeepTail = f.KeepTail, Summarize = Summarize }, msgs);

        Assert.NotNull(outp);                                                    // compacts when over budget
        Assert.True(Compaction.EstimateTokens(outp!.Messages!) < before);        // result is smaller than the original
        Assert.Contains(outp.Messages!, m =>                                     // a summary system message is inserted
            Role(m) == "system" && ContentStr(m).StartsWith("[Summary of earlier conversation]"));
    }

    // ------------------------------------------------------------------ C3
    [Fact]
    public void C3_ToolPairSafety_NoOrphanedToolResult()
    {
        var f = LoadFixture();
        var msgs = Transcript(f.SystemPrompt, f.UserTurns);
        var outp = Run(new Compaction.Options { MaxTokens = f.MaxTokens, KeepTail = f.KeepTail, Summarize = Summarize }, msgs);
        var t = outp!.Messages!;

        var safe = true;
        for (var i = 0; i < t.Count; i++)
        {
            if (Role(t[i]) != "tool") continue;
            var id = Str(t[i], "tool_call_id");
            var hasParent = t.Take(i).Any(m => Role(m) == "assistant"
                && ToolCalls(m).Any(c => Str(c, "id") == id));
            if (!hasParent) safe = false;
        }
        Assert.True(safe, "no tool message is orphaned from its tool_calls");
        Assert.Equal("user", Role(t.First(m => Role(m) != "system"))); // the tail begins at a clean user turn
    }

    // ------------------------------------------------------------------ C4
    [Fact]
    public void C4_SystemPromptPreservedVerbatim()
    {
        var f = LoadFixture();
        var msgs = Transcript(f.SystemPrompt, f.UserTurns);
        var outp = Run(new Compaction.Options { MaxTokens = f.MaxTokens, Summarize = Summarize }, msgs);

        Assert.True(Role(outp!.Messages![0]) == "system" && ContentStr(outp.Messages![0]).Contains("SOUL"),
            "original SOUL system prompt is still first");
    }

    // ------------------------------------------------------------------ C5
    [Fact]
    public void C5_FlushToMemory_InjectsPreCompactReminder()
    {
        var f = LoadFixture();
        var msgs = Transcript(f.SystemPrompt, f.UserTurns);
        var outp = Run(new Compaction.Options { MaxTokens = f.MaxTokens, Summarize = Summarize, FlushToMemory = true }, msgs);

        Assert.Contains(outp!.Messages!, m => ContentStr(m).Contains("save it with the memory tool"));
    }

    // ------------------------------------------------------------------ C6
    [Fact]
    public async Task C6_WiredViaBeforeLLM_LoopContinuesPastCompaction()
    {
        var f = LoadFixture();
        var pad = NativeTool.Of("pad", "pads",
            new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() },
            _ => Repeat("x ", 600));
        await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        toolkit.Register(pad);

        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://mock.local", Style = "openai", Model = "m", ApiKey = "spike", MaxTurns = 20,
            SystemPrompt = f.SystemPrompt,
            HttpHandler = new CompactionMockHandler(),
            Hooks = new LlmClient.Hooks
            {
                BeforeLLM = Compaction.Compactor(new Compaction.Options
                {
                    MaxTokens = 600, KeepTail = 250, Summarize = Summarize,
                }),
            },
        });

        var r = await client.RunAsync("start", toolkit);

        Assert.True(r.Status == "done" && r.Text.StartsWith("final"), r.Text); // completes despite mid-run compaction
        Assert.Contains("compacted=true", r.Text);                             // compaction actually fired during the run
        Assert.True(Compaction.EstimateTokens(r.Messages) < 4000,             // final transcript is bounded, not raw history
            Compaction.EstimateTokens(r.Messages).ToString());
    }

    /// <summary>Mock LLM (openai): makes 6 tool calls (padding the transcript), then a final answer
    /// that reports whether the transcript it saw had already been compacted.</summary>
    private sealed class CompactionMockHandler : HttpMessageHandler
    {
        private int _calls;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var body = await request.Content!.ReadAsStringAsync(ct).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(body);
            var compacted = doc.RootElement.GetProperty("messages").EnumerateArray().Any(m =>
                m.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String
                && (c.GetString() ?? "").StartsWith("[Summary"));

            // "done" is a call-count decision (like a real model deciding it has enough), NOT a count
            // of tool messages — compaction legitimately removes those.
            if (_calls >= 6)
                return MockLlm.Text($"final (compacted={compacted.ToString().ToLowerInvariant()})");
            return MockLlm.Call("pad", new { }, $"c{_calls++}");
        }
    }
}
