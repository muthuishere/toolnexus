using System.Net;
using System.Text.Json;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>
/// Shared fixture: <c>examples/agent-hooks/fixture.json</c> (scenarios H1-H6).
/// SPEC §7D "The §8 seams on an agent run" — <c>Hooks</c> / <c>OnMetric</c> reach an agent run only
/// by being handed to the runtime (runtime-wide) or to an <see cref="AgentDef"/> (per agent).
/// Def-over-runtime, REPLACE never merge, each field independently, forwarded verbatim.
/// </summary>
public class AgentHooksTests
{
    /// <summary>Scripted mock LLM that also RECORDS the exact transcript each turn put on the
    /// wire, keyed by model id. Always answers with a final text (one turn, no tools).</summary>
    private sealed class RecordingHandler : HttpMessageHandler
    {
        private readonly object _l = new();
        private readonly Dictionary<string, List<List<Dictionary<string, object?>>>> _sent = new();

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var body = await request.Content!.ReadAsStringAsync(ct).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(body);
            var model = doc.RootElement.GetProperty("model").GetString() ?? "";
            var msgs = doc.RootElement.GetProperty("messages").EnumerateArray()
                .Select(m => m.EnumerateObject().ToDictionary(
                    p => p.Name,
                    p => (object?)(p.Value.ValueKind == JsonValueKind.String ? p.Value.GetString() : p.Value.ToString())))
                .ToList();
            lock (_l)
            {
                if (!_sent.TryGetValue(model, out var runs)) _sent[model] = runs = new();
                runs.Add(msgs);
            }
            return MockLlm.Text("ok");
        }

        public List<Dictionary<string, object?>> Last(string model)
        {
            lock (_l)
                return _sent.TryGetValue(model, out var runs) && runs.Count > 0
                    ? runs[^1] : new List<Dictionary<string, object?>>();
        }
    }

    private static Dictionary<string, AgentDef> Reg(params AgentDef[] defs)
        => defs.ToDictionary(d => d.Name, d => d);

    private static LlmClient.Hooks Injecting(string marker, Action? onFire = null) => new()
    {
        BeforeLLM = ev =>
        {
            onFire?.Invoke();
            var msgs = new List<object?>(ev.Messages)
            {
                new Dictionary<string, object?> { ["role"] = "system", ["content"] = marker },
            };
            return new LlmClient.LLMOverride(Messages: msgs);
        },
    };

    /// <summary>Seed a handle's stored transcript: a leading system soul + <paramref name="pairs"/>
    /// 200-char user/assistant pairs (1 + 2*pairs messages).</summary>
    private static async Task SeedAsync(IConversationStore store, string id, int pairs)
    {
        var msgs = new List<object?>
        {
            new Dictionary<string, object?> { ["role"] = "system", ["content"] = "SOUL-VERBATIM" },
        };
        for (var i = 0; i < pairs; i++)
        {
            msgs.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = new string('q', 200) });
            msgs.Add(new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = new string('a', 200) });
        }
        await store.SaveAsync(id, msgs);
    }

    // 1 — a runtime-level beforeLLM hook runs inside an agent turn AND reaches the wire.
    [Fact]
    public async Task RuntimeHooks_RunInsideAnAgentTurn_AndReachTheWire()
    {
        var mock = new RecordingHandler();
        var saw = 0;
        var rt = new AgentRuntime(new RuntimeOptions
        {
            Handler = mock, ApiKey = "test",
            Registry = Reg(new AgentDef { Name = "a", Does = "x", Soul = "soul-a", Model = "m-a" }),
            Hooks = Injecting("INJECTED-BY-HOOK", () => Interlocked.Increment(ref saw)),
        });
        var h = rt.Spawn(rt.Root, "a").Handle!;
        var r = await rt.RunTurnAsync(h, "hello");

        Assert.False(r.IsError, r.Text);
        Assert.True(saw > 0, "beforeLLM never ran inside the agent turn");
        Assert.Contains(mock.Last("m-a"), m => (m.TryGetValue("content", out var c) ? c as string : null) == "INJECTED-BY-HOOK");
        await rt.CloseAsync(rt.Root);
    }

    // 2 — a runtime-level OnMetric receives the §8 semantic events from an agent turn.
    [Fact]
    public async Task RuntimeOnMetric_ReceivesLlmAndRunEvents()
    {
        var events = new List<string>();
        var rt = new AgentRuntime(new RuntimeOptions
        {
            Handler = new RecordingHandler(), ApiKey = "test",
            Registry = Reg(new AgentDef { Name = "a", Does = "x", Model = "m-a" }),
            OnMetric = ev => { lock (events) events.Add(ev.Event); },
        });
        var h = rt.Spawn(rt.Root, "a").Handle!;
        var r = await rt.RunTurnAsync(h, "hi");

        Assert.False(r.IsError, r.Text);
        lock (events)
        {
            Assert.Contains("llm", events);
            Assert.Contains("run", events);
        }
        await rt.CloseAsync(rt.Root);
    }

    // 3 — def-level REPLACES runtime-level for that agent only (never merged).
    [Fact]
    public async Task DefHooks_ReplaceRuntimeHooks_PerAgent()
    {
        var fired = new Dictionary<string, int>();
        void Mark(string tag) { lock (fired) fired[tag] = fired.GetValueOrDefault(tag) + 1; }

        var rt = new AgentRuntime(new RuntimeOptions
        {
            Handler = new RecordingHandler(), ApiKey = "test",
            Hooks = Injecting("runtime", () => Mark("runtime")),
            Registry = Reg(
                new AgentDef { Name = "a", Does = "x", Model = "m-a", Hooks = Injecting("defA", () => Mark("defA")) },
                new AgentDef { Name = "b", Does = "x", Model = "m-b" }),
        });
        var ha = rt.Spawn(rt.Root, "a").Handle!;
        var hb = rt.Spawn(rt.Root, "b").Handle!;
        Assert.False((await rt.RunTurnAsync(ha, "hi")).IsError);
        Assert.False((await rt.RunTurnAsync(hb, "hi")).IsError);

        lock (fired)
        {
            Assert.Equal(1, fired.GetValueOrDefault("defA"));
            Assert.True(fired.GetValueOrDefault("runtime") == 1,
                $"runtime hook must run ONLY for B (not merged into A), got {fired.GetValueOrDefault("runtime")}");
        }
        await rt.CloseAsync(rt.Root);
    }

    // 4 — the two fields resolve INDEPENDENTLY: overriding Hooks keeps the runtime's OnMetric.
    [Fact]
    public async Task HooksAndOnMetric_ResolveIndependently()
    {
        var events = new List<string>();
        var defHookFired = 0;
        var rt = new AgentRuntime(new RuntimeOptions
        {
            Handler = new RecordingHandler(), ApiKey = "test",
            Hooks = Injecting("runtime"),
            OnMetric = ev => { lock (events) events.Add(ev.Event); },
            Registry = Reg(new AgentDef
            {
                Name = "a", Does = "x", Model = "m-a",
                Hooks = Injecting("defA", () => Interlocked.Increment(ref defHookFired)),
            }),
        });
        var h = rt.Spawn(rt.Root, "a").Handle!;
        Assert.False((await rt.RunTurnAsync(h, "hi")).IsError);

        Assert.True(defHookFired == 1, "the def's own hooks ran");
        lock (events) Assert.Contains("run", events); // …and the runtime's OnMetric was inherited
        await rt.CloseAsync(rt.Root);
    }

    // 5 — THE TRAP: the hand-written subtree clone must carry both fields.
    [Fact]
    public void CloneWithRegistry_PreservesHooksAndOnMetric()
    {
        var hooks = new LlmClient.Hooks();
        Action<MetricEvent> onMetric = _ => { };
        var opts = new RuntimeOptions { Hooks = hooks, OnMetric = onMetric };
        var clone = opts.CloneWithRegistry(new Dictionary<string, AgentDef>());

        Assert.Same(hooks, clone.Hooks);
        Assert.Same(onMetric, clone.OnMetric);
    }

    // 6 — a real §7F compactor attached per-agent compacts an agent run.
    [Fact]
    public async Task Compactor_AttachedViaDefHooks_CompactsAnAgentRun()
    {
        var mock = new RecordingHandler();
        var store = new InMemoryConversationStore();
        var compactor = Compaction.Compactor(new Compaction.Options
        {
            MaxTokens = 200, KeepTail = 80, Summarize = _ => "SUMMARY-OF-HEAD",
        });
        var rt = new AgentRuntime(new RuntimeOptions
        {
            Handler = mock, ApiKey = "test", Store = store,
            Registry = Reg(new AgentDef
            {
                Name = "long", Does = "x", Soul = "SOUL-VERBATIM", Model = "m-a",
                Hooks = new LlmClient.Hooks { BeforeLLM = compactor },
            }),
        });
        var h = rt.Spawn(rt.Root, "long").Handle!;
        await SeedAsync(store, h.ConvId, 40);
        var pre = (await store.GetAsync(h.ConvId))!;
        Assert.Equal(81, pre.Count);

        var r = await rt.RunTurnAsync(h, "next question");
        Assert.False(r.IsError, r.Text);

        var sent = mock.Last("m-a");
        Assert.True(sent.Count < pre.Count, $"compaction did not shrink the transcript: pre={pre.Count} sent={sent.Count}");
        Assert.Equal("SOUL-VERBATIM", sent[0].TryGetValue("content", out var c0) ? c0 as string : null);
        Assert.Contains(sent, m => ((m.TryGetValue("content", out var c) ? c as string : null) ?? "").Contains("SUMMARY-OF-HEAD"));
        await rt.CloseAsync(rt.Root);
    }

    // 7 — §10: a turn that compacts and THEN suspends is rewound to its FULL pre-turn transcript.
    [Fact]
    public async Task CompactedTurnThatSuspends_IsRewoundToTheFullPreTurnTranscript()
    {
        var store = new InMemoryConversationStore();
        var compactor = Compaction.Compactor(new Compaction.Options
        {
            MaxTokens = 200, KeepTail = 80, Summarize = _ => "SUMMARY-OF-HEAD",
        });
        var rt = new AgentRuntime(new RuntimeOptions
        {
            Handler = new SubagentMockHandler(), ApiKey = "test", Store = store,
            Registry = Reg(new AgentDef
            {
                Name = "asker", Does = "needs approvals", Model = "m-asker",
                Tools = new List<ITool> { MockLlm.CheckSecret() },
                Hooks = new LlmClient.Hooks { BeforeLLM = compactor },
            }),
        });
        var h = rt.Spawn(rt.Root, "asker").Handle!;
        await SeedAsync(store, h.ConvId, 40);
        var pre = (await store.GetAsync(h.ConvId))!;

        var r = await rt.RunTurnAsync(h, "get the secret");
        Assert.Equal("pending", r.Status);

        var post = (await store.GetAsync(h.ConvId))!;
        Assert.True(post.Count == pre.Count,
            $"a compacted turn that suspends must rewind to the {pre.Count}-message pre-turn checkpoint, got {post.Count}");
        Assert.Equal("SOUL-VERBATIM", (post[0] as IDictionary<string, object?>)?["content"] as string);
    }
}
