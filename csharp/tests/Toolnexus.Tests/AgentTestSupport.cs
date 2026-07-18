using System.Net;
using System.Text;
using System.Text.Json;
using Toolnexus.Agents;

namespace Toolnexus.Tests;

/// <summary>
/// Shared support for the §7D agent-runtime tests: the scripted in-process mock LLM (openai
/// style, keyed by the request body's <c>model</c> — the shared <c>examples/subagent-*</c>
/// fixtures' <c>mockLLM</c> scripts), the fixture tools, and a virtual clock. Zero network,
/// zero cost.
/// </summary>
internal static class MockLlm
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

    // ---------- the fixture tools (examples/subagent-*/fixture.json "tools") ----------

    /// <summary>`lookup` — returns "data(&lt;q&gt;)".</summary>
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

    /// <summary>`check_secret` — pending(approval) without an Answer; "secret-token" with one.</summary>
    public static ITool CheckSecret() => NativeTool.Of("check_secret", "needs human approval",
        new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() },
        (IDictionary<string, object?> _, ToolContext? ctx) =>
            ctx?.Answer?.Ok == true
                ? (object)"secret-token"
                : ToolResult.Pending(new Request { Kind = "approval", Prompt = "approve secret access?" }));
}

/// <summary>
/// Scripted mock LLM for the runtime fixtures (fanout / escalation / durable-resume / budgets /
/// lifecycle). Behavior per model id mirrors <c>examples/subagent-*/fixture.json → mockLLM</c>.
/// </summary>
internal sealed class SubagentMockHandler : HttpMessageHandler
{
    /// <summary>The m-slow model parks on this gate until released (or the turn is cancelled).</summary>
    public TaskCompletionSource? SlowGate;

    /// <summary>Small per-call latency so sibling turns genuinely overlap.</summary>
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
                    return MockLlm.OpenAi(new
                    {
                        role = "assistant",
                        content = (string?)null,
                        tool_calls = new object[]
                        {
                            MockLlm.RawCall("c1", "task", new { agent = "explore", prompt = "find A" }),
                            MockLlm.RawCall("c2", "task", new { agent = "explore", prompt = "find B" }),
                        },
                    });
                return MockLlm.OpenAi(new
                {
                    role = "assistant",
                    content = $"synthesis: {string.Join(" + ", toolMsgs.Select(ToolContent))}",
                });
            case "m-explore":
                if (toolMsgs.Count == 0) return MockLlm.Call("lookup", new { q = "x" }, "e1");
                return MockLlm.Text($"found:{ToolContent(toolMsgs[0])}");
            case "m-approver-parent":
                if (toolMsgs.Count == 0) return MockLlm.Call("task", new { agent = "asker", prompt = "get the secret" }, "p1");
                return MockLlm.Text($"parent-final: {ToolContent(toolMsgs[^1])}");
            case "m-asker":
            {
                var approved = toolMsgs.Any(m => ToolContent(m).Contains("secret-token"));
                if (!approved) return MockLlm.Call("check_secret", new { }, "a1");
                return MockLlm.Text("asker-done: secret-token");
            }
            case "m-rogue": // targets an agent OUTSIDE its team, then echoes the tool result
                if (toolMsgs.Count == 0) return MockLlm.Call("task", new { agent = "stranger", prompt = "hi" }, "r1");
                return MockLlm.Text($"rogue-saw: {ToolContent(toolMsgs[0])}");
            case "m-peer":
            {
                var last = msgs.Count > 0 && msgs[^1].TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String
                    ? c.GetString() ?? "" : "";
                var items = System.Text.RegularExpressions.Regex.Matches(last, @"^\d+\.",
                    System.Text.RegularExpressions.RegexOptions.Multiline).Count;
                return MockLlm.Text($"processed {items} items");
            }
            case "m-loop": // never finishes: always another tool call → maxTurns/incomplete
                return MockLlm.Call("lookup", new { q = "again" }, $"l{toolMsgs.Count}");
            case "m-slow":
                await (SlowGate ?? throw new InvalidOperationException("SlowGate unset"))
                    .Task.WaitAsync(ct).ConfigureAwait(false);
                return MockLlm.Text("slow-done");
            default:
                return MockLlm.Text("ok");
        }
    }
}

/// <summary>
/// A deterministic virtual clock (SPEC §7D "injectable clock" — fixtures run virtual). Timers
/// created through it (e.g. <c>Task.Delay(…, clock, ct)</c>) fire only when <see cref="Advance"/>
/// moves time past their due point.
/// </summary>
internal sealed class VirtualClock : TimeProvider
{
    private readonly object _l = new();
    private long _nowTicks = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero).UtcTicks;
    private readonly List<VirtualTimer> _timers = new();

    public override DateTimeOffset GetUtcNow() { lock (_l) return new DateTimeOffset(_nowTicks, TimeSpan.Zero); }

    public override long GetTimestamp() { lock (_l) return _nowTicks; }

    public override long TimestampFrequency => TimeSpan.TicksPerSecond;

    public void Advance(TimeSpan by)
    {
        List<VirtualTimer> due;
        lock (_l)
        {
            _nowTicks += by.Ticks;
            due = _timers.Where(t => !t.Fired && t.DueTicks <= _nowTicks).ToList();
            foreach (var t in due) t.Fired = true;
        }
        foreach (var t in due) t.Fire();
    }

    public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
    {
        var t = new VirtualTimer(callback, state, this);
        lock (_l)
        {
            t.DueTicks = dueTime == Timeout.InfiniteTimeSpan ? long.MaxValue : _nowTicks + dueTime.Ticks;
            _timers.Add(t);
        }
        return t;
    }

    private sealed class VirtualTimer : ITimer
    {
        private readonly TimerCallback _callback;
        private readonly object? _state;
        private readonly VirtualClock _clock;
        public long DueTicks = long.MaxValue;
        public bool Fired;

        public VirtualTimer(TimerCallback callback, object? state, VirtualClock clock)
        {
            _callback = callback;
            _state = state;
            _clock = clock;
        }

        public void Fire() => _callback(_state);

        public bool Change(TimeSpan dueTime, TimeSpan period)
        {
            lock (_clock._l)
            {
                Fired = false;
                DueTicks = dueTime == Timeout.InfiniteTimeSpan ? long.MaxValue : _clock._nowTicks + dueTime.Ticks;
            }
            return true;
        }

        public void Dispose()
        {
            lock (_clock._l) _clock._timers.Remove(this);
        }

        public ValueTask DisposeAsync()
        {
            Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
