using System.Text.Json;

namespace Toolnexus.Agents;

/// <summary>
/// A POLICY check on a tool call — "may it?", never "is it right?". Return
/// <c>"allow"</c> (or null) to permit; any other string DENIES with that reason.
/// </summary>
public delegate string? Guardrail(LlmClient.BeforeToolEvent ev);

/// <summary>What a completion verifier returns.</summary>
public sealed record Verdict(bool Ok, string Reason = "");

/// <summary>
/// The gate that stops an agent claiming <c>done</c> before its work verifies. It lives on the
/// agent spec, so it TRAVELS with the agent through delegation — which a host-side retry loop
/// cannot do.
/// </summary>
public sealed class Completion
{
    /// <summary>Judges the run. Receives the tool calls ACCUMULATED across attempts.</summary>
    public Func<LlmClient.RunResult, Verdict> Verify { get; set; } = _ => new Verdict(true);

    /// <summary>REQUIRED. An unbounded verify loop is a denial-of-service on the caller's bill.</summary>
    public int MaxAttempts { get; set; }
}

/// <summary>What varies PER CALL. <c>Model</c> is here — not on the spec's default and not on the
/// loop — so the same conversation may change model between turns.</summary>
public sealed class LoopRunOptions
{
    /// <summary>Overrides the agent's model for this call only. Null ⇒ the agent's.</summary>
    public string? Model { get; set; }
}

/// <summary>What a run reports. <c>Status</c> reuses the SHIPPED vocabulary — no new status
/// strings are minted (SPEC.md pins TaskStatus identical across ports).</summary>
public sealed record Outcome(
    string Text,
    string Status,
    string? StoppedBy,
    int Attempts,
    int Turns,
    LlmClient.RunResult? Result);

/// <summary>
/// Loop — a live execution of an Agent, and the completion gate. A layer over the shipped §8
/// client: nothing here changes existing behaviour.
///
/// <para>The placement law this encodes:</para>
/// <list type="bullet">
///   <item><description>AgentSpec (the harness) answers "MAY it?" — capability, ceilings. Per problem.</description></item>
///   <item><description>LoopRunOptions answers "with WHAT?" — model for this call. Per call.</description></item>
///   <item><description>Loop answers "DID it?" — status, turns. Observed.</description></item>
///   <item><description>none of them answers "is it RIGHT?" — that is a tool, skill or agent.</description></item>
/// </list>
/// <para>So Loop takes no options: it is read, not configured.</para>
/// </summary>
public sealed class Loop
{
    private readonly Agent _agent;
    private readonly LlmClient.Options _options;
    private readonly Toolkit _toolkit;
    private int _turns;

    /// <param name="options">Client OPTIONS rather than a built client, because a per-call
    /// <c>Model</c> override must be able to change the model — fixed at construction.</param>
    public Loop(Agent agent, LlmClient.Options options, Toolkit toolkit)
    {
        _agent = agent;
        _options = options;
        _toolkit = toolkit;
    }

    /// <summary>Observed, never set by the caller.</summary>
    public string Status { get; private set; } = "idle";

    /// <summary>Model round trips this loop has spent.</summary>
    public int Turns => _turns;

    public async Task<Outcome> RunAsync(string prompt, LoopRunOptions? opts = null)
    {
        var completion = _agent.Spec.Completion;
        var client = LlmClient.Create(ClientOptionsFor(opts));
        Status = "running";

        var attempts = 0;
        List<object>? history = null;

        async Task<LlmClient.RunResult> Ask(string text)
        {
            attempts++;
            var r = await client.RunAsync(text, _toolkit, history).ConfigureAwait(false);
            _turns += r.Turns;
            history = r.Messages;
            return r;
        }

        LlmClient.RunResult result;
        try
        {
            result = await RunGatedAsync(Ask, prompt, completion).ConfigureAwait(false);
        }
        catch
        {
            Status = "error";
            throw;
        }

        if (!string.IsNullOrEmpty(result.Status) && result.Status != "done")
        {
            Status = result.Status;
            var stoppedBy = result.Limit == "completion" ? result.Text : $"run reported {result.Status}";
            return new Outcome(result.Text, result.Status, stoppedBy, attempts, _turns, result);
        }

        Status = "idle";
        return new Outcome(result.Text, "done", null, attempts, _turns, result);
    }

    /// <summary>Applies a per-call model override via <c>RequestParams</c> (<c>model</c> is not in
    /// the forbidden set — the client forbids only messages/tools/stream).</summary>
    private LlmClient.Options ClientOptionsFor(LoopRunOptions? opts)
    {
        var o = CloneOptions(_options);
        if (!string.IsNullOrEmpty(_agent.Spec.Soul) && string.IsNullOrEmpty(o.SystemPrompt))
            o.SystemPrompt = _agent.Spec.Soul;
        o.Hooks = LoopSupport.GuardedHooks(_agent.Spec.Guardrails, _agent.Spec.Hooks ?? o.Hooks);

        if (string.IsNullOrEmpty(opts?.Model)) return o;
        var rp = o.RequestParams is null
            ? new Dictionary<string, object?>()
            : new Dictionary<string, object?>(o.RequestParams);
        rp["model"] = opts!.Model;
        o.RequestParams = rp;
        return o;
    }

    /// <summary>
    /// Wraps a client run with the completion gate. SHARED by the standalone loop and the §7D
    /// runtime turn, so a delegated child gets exactly the same guarantee as a directly-driven one.
    ///
    /// <para>Rule 2 in force: a run that is <c>pending</c> (suspended on a human) or otherwise
    /// non-done already carries its own reason, so the gate never re-judges it. That keeps
    /// <c>pending</c> and <c>incomplete</c> distinct — the caller can always tell whether it owes
    /// an Answer or a fix.</para>
    /// </summary>
    internal static async Task<LlmClient.RunResult> RunGatedAsync(
        Func<string, Task<LlmClient.RunResult>> ask, string prompt, Completion? completion)
    {
        if (completion is null) return await ask(prompt).ConfigureAwait(false);
        if (completion.MaxAttempts < 1)
            throw new ArgumentException("toolnexus: Completion.MaxAttempts must be >= 1");
        if (completion.Verify is null)
            throw new ArgumentException("toolnexus: Completion.Verify is required");

        var accumulated = new List<LlmClient.ToolCall>();
        LlmClient.RunResult? last = null;
        var reason = "";

        for (var attempt = 1; attempt <= completion.MaxAttempts; attempt++)
        {
            var text = attempt == 1 ? prompt : $"Your work did not verify: {reason}. Fix it and finish.";
            var r = await ask(text).ConfigureAwait(false);

            // The gate judges the ACCUMULATED work, so an agent cannot escape it by declining to
            // re-declare its plan on a retry.
            accumulated.AddRange(r.ToolCalls);
            // RunResult is IMMUTABLE in this port, so the judged view is a rebuild rather
            // than a mutation. Same semantics, different idiom.
            r = Rebuild(r, toolCalls: new List<LlmClient.ToolCall>(accumulated));
            last = r;

            if (!string.IsNullOrEmpty(r.Status) && r.Status != "done")
            {
                // The run stopped for its own reason (suspension, budget). If the gate was
                // mid-retry the caller must learn BOTH — otherwise a budget stop masks the
                // verification failure and they never see why it was looping.
                if (reason.Length > 0 && r.Status != "pending")
                    r = Rebuild(r, text: $"{r.Text} [while verifying: attempt {attempt} last failed: {reason}]");
                return r;
            }

            var verdict = completion.Verify(r);
            if (verdict.Ok) return r;
            reason = string.IsNullOrEmpty(verdict.Reason) ? "unspecified" : verdict.Reason;
        }

        // Structured, not prose: `Limit` is how a caller (and the §7D runtime) tells WHICH limit
        // stopped the run. `Text` carries the human reason.
        return Rebuild(last!,
            text: $"completion.verify failed {completion.MaxAttempts}x: {reason}",
            status: "incomplete", limit: "completion");
    }

    /// <summary>LlmClient.Options has no Clone, so copy the fields the loop may change and
    /// carry the rest by reference — the loop never mutates the caller's object.</summary>
    private static LlmClient.Options CloneOptions(LlmClient.Options o) => new()
    {
        BaseUrl = o.BaseUrl, Style = o.Style, Model = o.Model, ApiKey = o.ApiKey,
        Headers = o.Headers, SystemPrompt = o.SystemPrompt, MaxTurns = o.MaxTurns,
        Hooks = o.Hooks, Retries = o.Retries, RetryBaseMs = o.RetryBaseMs, TimeoutMs = o.TimeoutMs,
        Store = o.Store, OnMetric = o.OnMetric, WaitFor = o.WaitFor,
        RequestParams = o.RequestParams, BodyTransform = o.BodyTransform,
        HttpClient = o.HttpClient, HttpHandler = o.HttpHandler, OnError = o.OnError,
    };

    /// <summary>RunResult is immutable here, so a "modified" result is a new one. Everything not
    /// named is carried over verbatim.</summary>
    private static LlmClient.RunResult Rebuild(
        LlmClient.RunResult r,
        string? text = null,
        List<LlmClient.ToolCall>? toolCalls = null,
        string? status = null,
        string? limit = null) =>
        new(text ?? r.Text, r.Messages, toolCalls ?? r.ToolCalls, r.Turns, r.Usage, r.Model,
            status ?? r.Status, r.Pending, limit ?? r.Limit);
}

/// <summary>Helpers shared by the loop, the agent spec projection and the runtime turn.</summary>
public static class LoopSupport
{
    /// <summary>
    /// Compiles guardrails into one <c>BeforeTool</c> with FIRST-DENY-WINS, composed ahead of any
    /// hook already set. No guardrails ⇒ <paramref name="hooks"/> is returned untouched, so absent
    /// is byte-identical.
    /// </summary>
    public static LlmClient.Hooks? GuardedHooks(List<Guardrail>? guardrails, LlmClient.Hooks? hooks)
    {
        if (guardrails is null || guardrails.Count == 0) return hooks;
        var prior = hooks?.BeforeTool;
        var merged = hooks is null
            ? new LlmClient.Hooks()
            : new LlmClient.Hooks
            {
                BeforeLLM = hooks.BeforeLLM, AfterLLM = hooks.AfterLLM,
                BeforeTool = hooks.BeforeTool, AfterTool = hooks.AfterTool,
            };

        merged.BeforeTool = ev =>
        {
            foreach (var rail in guardrails)
            {
                var verdict = rail(ev);
                if (!string.IsNullOrEmpty(verdict) && verdict != "allow")
                    return new LlmClient.ToolOverride
                    {
                        Result = new ToolResult($"denied: {verdict}", true, null),
                    };
            }
            return prior?.Invoke(ev);
        };
        return merged;
    }

    /// <summary>
    /// The built-in completion verifier. Reads the SHIPPED <c>todowrite</c> builtin's result
    /// metadata and requires every item to be checked.
    ///
    /// <para>Structural, not domain: it counts unchecked boxes and never learns what a todo means,
    /// so the loop stays domain-blind. No plan declared ⇒ nothing to verify ⇒ pass, so the gate
    /// never punishes an agent that does not use the builtin.</para>
    /// </summary>
    public static Verdict AllTodosDone(LlmClient.RunResult result)
    {
        for (var i = result.ToolCalls.Count - 1; i >= 0; i--)
        {
            if (result.ToolCalls[i].Name != "todowrite") continue;
            var metadata = result.ToolCalls[i].Metadata;
            if (metadata is null || !metadata.TryGetValue("todos", out var raw)) return new Verdict(true);

            var open = new List<string>();
            if (raw is IEnumerable<object?> items)
            {
                foreach (var item in items)
                {
                    var (completed, text) = ReadTodo(item);
                    if (!completed) open.Add(text);
                }
            }
            else
            {
                return new Verdict(true);
            }

            return open.Count > 0
                ? new Verdict(false, $"{open.Count} item(s) still open: {string.Join("; ", open)}")
                : new Verdict(true);
        }
        return new Verdict(true);
    }

    private static (bool Completed, string Text) ReadTodo(object? item) => item switch
    {
        JsonElement je when je.ValueKind == JsonValueKind.Object => (
            je.TryGetProperty("completed", out var c) && c.ValueKind == JsonValueKind.True,
            je.TryGetProperty("text", out var t) ? t.GetString() ?? "" : ""),
        IDictionary<string, object?> d => (
            d.TryGetValue("completed", out var c) && c is true,
            d.TryGetValue("text", out var t) ? t?.ToString() ?? "" : ""),
        _ => (true, ""),
    };
}
