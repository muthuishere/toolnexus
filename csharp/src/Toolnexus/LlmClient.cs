using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;

namespace Toolnexus;

/// <summary>
/// Where <see cref="LlmClient.AskAsync"/> conversations are remembered — two methods. Ship the
/// in-memory default (<see cref="InMemoryConversationStore"/>); implement this for a
/// file/db/redis provider to persist transcripts across processes.
/// </summary>
public interface IConversationStore
{
    /// <summary>Return the stored transcript for <paramref name="id"/>, or <c>null</c> if none.</summary>
    Task<List<object?>?> GetAsync(string id);

    /// <summary>Persist the (updated) transcript for <paramref name="id"/>.</summary>
    Task SaveAsync(string id, List<object?> messages);
}

/// <summary>Default conversation provider — keeps transcripts in memory for the client's lifetime.</summary>
public sealed class InMemoryConversationStore : IConversationStore
{
    private readonly ConcurrentDictionary<string, List<object?>> _map = new();

    public Task<List<object?>?> GetAsync(string id)
        => Task.FromResult(_map.TryGetValue(id, out var m) ? new List<object?>(m) : null);

    public Task SaveAsync(string id, List<object?> messages)
    {
        _map[id] = new List<object?>(messages);
        return Task.CompletedTask;
    }
}

/// <summary>
/// Unified LLM client — the host loop. Give it a base URL + a style
/// (<c>"openai" | "anthropic"</c>) and it runs the tool-calling agent loop against a
/// <see cref="Toolkit"/>. Mirrors the JS/Java reference.
/// </summary>
public sealed class LlmClient
{
    private static readonly HttpClient DefaultHttp = new() { Timeout = Timeout.InfiniteTimeSpan };
    private static readonly HashSet<int> Retryable = new() { 429, 500, 502, 503, 504 };

    private readonly Options _opts;

    /// <summary>HTTP client for LLM requests — from <c>opts.HttpClient</c>/<c>opts.HttpHandler</c>, else the
    /// shared default (§8 Gap 2). LLM path only; MCP transports use their own clients.</summary>
    private readonly HttpClient _http;

    /// <summary>Conversation provider for <see cref="AskAsync"/> — from <c>opts.Store</c>, else in-memory.</summary>
    private readonly IConversationStore _store;

    /// <summary>Cumulative Prometheus registry fed by the same events <c>OnMetric</c> sees.</summary>
    private readonly MetricsRegistry _registry = new();

    private LlmClient(Options opts)
    {
        _opts = opts;
        _store = opts.Store ?? new InMemoryConversationStore();
        _http = opts.HttpClient
            ?? (opts.HttpHandler != null
                ? new HttpClient(opts.HttpHandler, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan }
                : DefaultHttp);
    }

    public static LlmClient Create(Options opts) => new(opts);

    /// <summary>
    /// (§8 Gap 4) The client's conversation provider — the exact instance passed in
    /// <see cref="Options.Store"/>, else the default in-memory store the client created. Callers may
    /// read (<c>GetAsync</c>) and write (<c>SaveAsync</c>) it directly to share state with
    /// <see cref="AskAsync"/>, so no consumer needs a shadow copy.
    /// </summary>
    public IConversationStore ConversationStore() => _store;

    /// <summary>
    /// (§8 Gap 1 + Gap 5) Finalize an assembled request body just before marshal: shallow-merge
    /// <see cref="Options.RequestParams"/> (caller WINS on collision) after stripping the forbidden
    /// <c>messages</c>/<c>tools</c>/<c>stream</c> keys, then run <see cref="Options.BodyTransform"/>
    /// last (returning null ⇒ unchanged). Applied at every LLM body-build site.
    /// </summary>
    private Dictionary<string, object?> FinalizeBody(Dictionary<string, object?> body)
    {
        if (_opts.RequestParams != null)
        {
            foreach (var (k, v) in _opts.RequestParams)
            {
                if (k is "messages" or "tools" or "stream")
                {
                    Console.Error.WriteLine($"[toolnexus] requestParams key \"{k}\" is not allowed — ignored (use BodyTransform)");
                    continue;
                }
                body[k] = v;
            }
        }
        if (_opts.BodyTransform != null)
        {
            var output = _opts.BodyTransform(body);
            if (output != null) return output is Dictionary<string, object?> d ? d : new Dictionary<string, object?>(output);
        }
        return body;
    }

    /// <summary>Prometheus text exposition of cumulative metrics (SPEC §8). Always valid — before any
    /// activity, only the <c># HELP</c>/<c># TYPE</c> lines render.</summary>
    public string Metrics() => _registry.Render();

    /// <summary>Feed a semantic metric event to the built-in registry and the optional <c>OnMetric</c> sink.</summary>
    private void Emit(MetricEvent ev)
    {
        _registry.Record(ev);
        _opts.OnMetric?.Invoke(ev);
    }

    /// <summary>Monotonic milliseconds for metric timing.</summary>
    private static long NowMs() => Stopwatch.GetTimestamp() * 1000L / Stopwatch.Frequency;

    /// <summary>Per-call token counts from one raw usage payload (not summed).</summary>
    private static (long Prompt, long Completion) PerCall(IDictionary<string, object?>? raw, string style)
    {
        if (raw == null) return (0, 0);
        return style == "anthropic"
            ? (AsLong(raw.Get("input_tokens")), AsLong(raw.Get("output_tokens")))
            : (AsLong(raw.Get("prompt_tokens")), AsLong(raw.Get("completion_tokens")));
    }

    /// <summary>Emit the terminal <c>run</c> metric event and build the <see cref="RunResult"/>.</summary>
    private RunResult EndRun(long runStart, string text, List<object?> messages, List<ToolCall> toolCalls, int turns, Usage usage)
    {
        Emit(MetricEvent.Run(_opts.Model, turns, toolCalls.Count, usage.TotalTokens, NowMs() - runStart));
        return new RunResult(text, messages, toolCalls, turns, usage, _opts.Model);
    }

    /// <summary>Emit a <c>run</c> error metric event (once, on a thrown run).</summary>
    private void EmitRunError(long runStart, List<ToolCall> toolCalls, int turns, Usage usage, Exception e)
        => Emit(MetricEvent.Run(_opts.Model, turns, toolCalls.Count, usage.TotalTokens, NowMs() - runStart, e.Message));

    // ---------------------------------------------------------------- options

    public sealed class Options
    {
        public string BaseUrl { get; set; } = "";
        public string Style { get; set; } = "openai"; // "openai" | "anthropic"
        public string Model { get; set; } = "";
        public string? ApiKey { get; set; }           // from env if null
        public IDictionary<string, string>? Headers { get; set; }
        public string? SystemPrompt { get; set; }
        public int? MaxTurns { get; set; }             // default 10
        public Hooks? Hooks { get; set; }
        public int? Retries { get; set; }              // default 2
        public int? RetryBaseMs { get; set; }          // default 500
        public long? TimeoutMs { get; set; }           // whole-run deadline; null = none

        /// <summary>Conversation provider for <see cref="AskAsync"/>. Default: in-memory (process
        /// lifetime). Supply a file/db store to persist conversations across processes.</summary>
        public IConversationStore? Store { get; set; }

        /// <summary>Observability sink — receives semantic <see cref="MetricEvent"/>s as the loop runs
        /// (SPEC §8). Forward to statsd/logs/OTel/anything. No cost when unset. The same events also
        /// feed the built-in Prometheus registry behind <see cref="Metrics"/>.</summary>
        public Action<MetricEvent>? OnMetric { get; set; }

        /// <summary>
        /// §10 Suspension resolver — the ONE host slot. When a tool returns a suspension
        /// (<c>Metadata["pending"]</c> = <see cref="Request"/>), the client calls this, then retries the
        /// tool once with <c>ToolContext.Answer</c> = the resolution. Its interior is unconstrained (open
        /// a browser + poll; text a link to a channel; forward over A2A). Named <c>WaitFor</c>, never
        /// <c>await</c> (reserved in C#). When null, <c>run</c> does NOT hang — it returns
        /// <c>{ Status = "pending", Pending = request }</c> to resume later.
        /// </summary>
        public Func<Request, Task<Answer>>? WaitFor { get; set; }

        /// <summary>
        /// (§8 Gap 1) Extra top-level keys shallow-merged into EVERY LLM request body after the client
        /// builds its own — a RequestParams key WINS on collision (e.g. <c>max_tokens</c> overrides the
        /// anthropic default 4096). The keys <c>messages</c>/<c>tools</c>/<c>stream</c> are forbidden here
        /// (stripped + warned) — rewrite messages via <see cref="BodyTransform"/>. Applied on all four
        /// paths (run + stream × openai + anthropic). Null ⇒ body byte-identical to today.
        /// </summary>
        public IReadOnlyDictionary<string, object?>? RequestParams { get; set; }

        /// <summary>
        /// (§8 Gap 1) When non-null, receives the fully assembled request body (after the
        /// <see cref="RequestParams"/> merge) just before marshal and returns the body to send — the
        /// escape hatch for provider-specific rewriting (including <c>messages</c>) without a proxy.
        /// Returning null ⇒ unchanged. Runs on every LLM call, all four paths.
        /// </summary>
        public Func<IDictionary<string, object?>, IDictionary<string, object?>?>? BodyTransform { get; set; }

        /// <summary>
        /// (§8 Gap 2) Overrides the HTTP client used for LLM requests (retries included). Null ⇒ default.
        /// Scope is the LLM path only; MCP transports use the MCP SDK's own clients. Takes precedence
        /// over <see cref="HttpHandler"/>.
        /// </summary>
        public HttpClient? HttpClient { get; set; }

        /// <summary>
        /// (§8 Gap 2) An <see cref="HttpMessageHandler"/> to build the LLM HTTP client from (e.g. a
        /// recording/proxying handler). Ignored when <see cref="HttpClient"/> is set. Null ⇒ default.
        /// </summary>
        public HttpMessageHandler? HttpHandler { get; set; }

        public Options WithWaitFor(Func<Request, Task<Answer>> v) { WaitFor = v; return this; }
        public Options WithRequestParams(IReadOnlyDictionary<string, object?> v) { RequestParams = v; return this; }
        public Options WithBodyTransform(Func<IDictionary<string, object?>, IDictionary<string, object?>?> v) { BodyTransform = v; return this; }
        public Options WithHttpClient(HttpClient v) { HttpClient = v; return this; }
        public Options WithHttpHandler(HttpMessageHandler v) { HttpHandler = v; return this; }

        public Options WithBaseUrl(string v) { BaseUrl = v; return this; }
        public Options WithStyle(string v) { Style = v; return this; }
        public Options WithModel(string v) { Model = v; return this; }
        public Options WithApiKey(string? v) { ApiKey = v; return this; }
        public Options WithHeaders(IDictionary<string, string> v) { Headers = v; return this; }
        public Options WithSystemPrompt(string v) { SystemPrompt = v; return this; }
        public Options WithMaxTurns(int v) { MaxTurns = v; return this; }
        public Options WithHooks(Hooks v) { Hooks = v; return this; }
        public Options WithRetries(int v) { Retries = v; return this; }
        public Options WithRetryBaseMs(int v) { RetryBaseMs = v; return this; }
        public Options WithTimeoutMs(long v) { TimeoutMs = v; return this; }
        public Options WithStore(IConversationStore v) { Store = v; return this; }
        public Options WithOnMetric(Action<MetricEvent> v) { OnMetric = v; return this; }
    }

    /// <summary>Thrown when a run exceeds <see cref="Options.TimeoutMs"/> (or its in-flight request times out).</summary>
    public sealed class RunTimeoutException : Exception
    {
        public RunTimeoutException(string message) : base(message) { }
    }

    // ---------------------------------------------------------------- hooks

    public sealed record BeforeLLMEvent(List<object?> Messages, List<Dictionary<string, object?>> Tools, string Model, int Turn);
    public sealed record LLMOverride(List<object?>? Messages = null, List<Dictionary<string, object?>>? Tools = null);
    public sealed record AfterLLMEvent(Dictionary<string, object?> Response, string Model, int Turn);
    public sealed record BeforeToolEvent(string Name, IDictionary<string, object?> Args, string? Id, int Turn);
    public sealed record AfterToolEvent(string Name, IDictionary<string, object?> Args, ToolResult Result, string? Id, int Turn);

    public sealed record ToolOverride(IDictionary<string, object?>? Args = null, ToolResult? Result = null)
    {
        public static ToolOverride WithResult(ToolResult result) => new(null, result);
        public static ToolOverride WithArgs(IDictionary<string, object?> args) => new(args, null);
    }

    public sealed class Hooks
    {
        public Func<BeforeLLMEvent, LLMOverride?>? BeforeLLM { get; set; }
        public Action<AfterLLMEvent>? AfterLLM { get; set; }
        public Func<BeforeToolEvent, ToolOverride?>? BeforeTool { get; set; }
        public Func<AfterToolEvent, ToolOverride?>? AfterTool { get; set; }
    }

    private async Task<(IDictionary<string, object?> Args, ToolResult Result)> RunToolAsync(
        Toolkit toolkit, string name, IDictionary<string, object?> args, string? id, int turn)
    {
        var h = _opts.Hooks;
        var source = toolkit.Get(name)?.Source ?? "custom";
        var t0 = NowMs();
        var a = args;
        if (h?.BeforeTool != null)
        {
            var ov = h.BeforeTool(new BeforeToolEvent(name, a, id, turn));
            if (ov?.Result != null)
            {
                // short-circuit (deny/cache/dry-run, or a guard-raised suspension — path B); still a
                // tool call for metrics, but a suspension is classified pending, never an error.
                EmitTool(name, source, ov.Result, t0);
                return (a, ov.Result);
            }
            if (ov?.Args != null) a = ov.Args;
        }
        var result = await toolkit.ExecuteAsync(name, a).ConfigureAwait(false);
        // A suspension (§10) is not a real result: skip afterTool's failure path on it — the resolved
        // result (post-WaitFor) still flows through afterTool in ResolvePendingAsync — and never count
        // it as a tool error.
        if (h?.AfterTool != null && ToolResult.PendingOf(result) == null)
        {
            var ov = h.AfterTool(new AfterToolEvent(name, a, result, id, turn));
            if (ov?.Result != null) result = ov.Result;
        }
        EmitTool(name, source, result, t0);
        return (a, result);
    }

    /// <summary>Emit the <c>tool</c> metric, classifying a §10 suspension as <c>pending</c>
    /// (<c>IsError = false</c>), never a failure.</summary>
    private void EmitTool(string name, string source, ToolResult result, long t0)
    {
        var req = ToolResult.PendingOf(result);
        Emit(MetricEvent.ToolCall(name, source, req != null ? false : result.IsError, NowMs() - t0, req != null));
    }

    /// <summary>
    /// §10: resolve a suspended tool result. Calls <c>WaitFor(request)</c>, and on <c>ok</c> re-executes
    /// the tool ONCE with <c>ToolContext.Answer = answer</c>. Returns the resolved result, or a non-null
    /// <c>Halted</c> when no <c>WaitFor</c> is configured (the run should stop and surface the request).
    /// </summary>
    private async Task<(ToolResult Result, Request? Halted)> ResolvePendingAsync(
        Toolkit toolkit, string name, IDictionary<string, object?> args, Request request, string? id, int turn)
    {
        if (_opts.WaitFor == null)
            return (ToolResult.Error(request.Prompt), request); // halt: durable host resumes later

        var answer = await _opts.WaitFor(request).ConfigureAwait(false);
        if (answer == null || !answer.Ok)
            return (ToolResult.Error($"declined/expired: {request.Prompt}"), null);

        var source = toolkit.Get(name)?.Source ?? "custom";
        var t0 = NowMs();
        var result = await toolkit.ExecuteAsync(name, args, new ToolContext(answer: answer)).ConfigureAwait(false);
        if (_opts.Hooks?.AfterTool != null)
        {
            var ov = _opts.Hooks.AfterTool(new AfterToolEvent(name, args, result, id, turn));
            if (ov?.Result != null) result = ov.Result;
        }
        EmitTool(name, source, result, t0);
        if (ToolResult.PendingOf(result) != null)
            result = ToolResult.Error($"unresolved: {request.Prompt}"); // never loop forever on the same request
        return (result, null);
    }

    /// <summary>§10: build the terminal <see cref="RunResult"/> for a run halted by an unresolved suspension.</summary>
    private RunResult PendingRun(long runStart, Request request, List<object?> messages, List<ToolCall> toolCalls, int turns, Usage usage)
    {
        Emit(MetricEvent.Run(_opts.Model, turns, toolCalls.Count, usage.TotalTokens, NowMs() - runStart));
        return new RunResult(request.Prompt, messages, toolCalls, turns, usage, _opts.Model, "pending", request);
    }

    // ---------------------------------------------------------------- results

    public sealed record ToolCall(string Name, IDictionary<string, object?> Args, string Output, bool IsError, IDictionary<string, object?>? Metadata);

    public sealed class Usage
    {
        public long PromptTokens;
        public long CompletionTokens;
        public long TotalTokens;

        public Usage Copy() => new() { PromptTokens = PromptTokens, CompletionTokens = CompletionTokens, TotalTokens = TotalTokens };
    }

    public sealed class RunResult
    {
        public string Text { get; }
        public List<object?> Messages { get; }
        public List<ToolCall> ToolCalls { get; }
        public int ToolCallCount => ToolCalls.Count;
        public int Turns { get; }
        public Usage Usage { get; }
        public string Model { get; }

        /// <summary>§10: "done" normally; "pending" iff a tool suspended and no <c>WaitFor</c> was configured.</summary>
        public string Status { get; }

        /// <summary>§10: the unresolved suspension — present iff <see cref="Status"/> == "pending".</summary>
        public Request? Pending { get; }

        public RunResult(string text, List<object?> messages, List<ToolCall> toolCalls, int turns, Usage usage,
            string model, string status = "done", Request? pending = null)
        {
            Text = text;
            Messages = messages;
            ToolCalls = toolCalls;
            Turns = turns;
            Usage = usage;
            Model = model;
            Status = status;
            Pending = pending;
        }
    }

    // ---------------------------------------------------------------- streaming events

    public enum StreamKind { Text, ToolCall, ToolResult, Usage, Pending, Done }

    public sealed record StreamEvent(
        StreamKind Type, string? Delta = null, string? Id = null, string? Name = null,
        IDictionary<string, object?>? Args = null, string? Output = null, bool IsError = false,
        Usage? Usage = null, RunResult? Result = null, Request? Request = null)
    {
        /// <summary>§10: a tool is waiting on an out-of-band resolution (emitted before <c>WaitFor</c> runs).</summary>
        internal static StreamEvent PendingEvent(Request request) => new(StreamKind.Pending, Request: request);
        internal static StreamEvent Text(string delta) => new(StreamKind.Text, Delta: delta);
        internal static StreamEvent ToolCallEvent(string? id, string name, IDictionary<string, object?> args)
            => new(StreamKind.ToolCall, Id: id, Name: name, Args: args);
        internal static StreamEvent ToolResultEvent(string? id, string name, string output, bool isError)
            => new(StreamKind.ToolResult, Id: id, Name: name, Output: output, IsError: isError);
        internal static StreamEvent UsageEvent(Usage usage) => new(StreamKind.Usage, Usage: usage);
        internal static StreamEvent DoneEvent(RunResult result) => new(StreamKind.Done, Result: result);
    }

    // ---------------------------------------------------------------- public API

    public Task<RunResult> RunAsync(string prompt, Toolkit toolkit, List<object?>? history = null, CancellationToken cancellationToken = default)
    {
        var deadline = new Deadline(_opts.TimeoutMs);
        return _opts.Style == "anthropic"
            ? RunAnthropicAsync(prompt, toolkit, history, deadline, cancellationToken)
            : RunOpenAIAsync(prompt, toolkit, history, deadline, cancellationToken);
    }

    /// <summary>
    /// Stateful ask. With an <paramref name="id"/>, the client's <see cref="IConversationStore"/>
    /// remembers the conversation: it loads that id's transcript, runs, saves the updated
    /// transcript, and returns the answer — so the next <c>AskAsync</c> with the same id continues
    /// it. Without an id it is a stateless one-shot (identical to <see cref="RunAsync"/>).
    /// </summary>
    /// <param name="onText">Optional block-style streaming sink. When set, the streaming loop runs and
    /// each text delta is forwarded here as it arrives — the final <see cref="RunResult"/> is still
    /// returned. Memory (<paramref name="id"/> load/save) is handled by the streaming path, so there
    /// is no duplication.</param>
    public async Task<RunResult> AskAsync(string prompt, Toolkit toolkit, string? id = null,
        Action<string>? onText = null, CancellationToken cancellationToken = default)
    {
        if (onText != null)
            return await StreamAsync(prompt, toolkit,
                ev => { if (ev.Type == StreamKind.Text && ev.Delta != null) onText(ev.Delta); },
                id, cancellationToken).ConfigureAwait(false);

        if (id == null)
            return await RunAsync(prompt, toolkit, null, cancellationToken).ConfigureAwait(false);
        var history = await _store.GetAsync(id).ConfigureAwait(false) ?? new List<object?>();
        var result = await RunAsync(prompt, toolkit, history, cancellationToken).ConfigureAwait(false);
        await _store.SaveAsync(id, result.Messages).ConfigureAwait(false);
        return result;
    }

    public Conversation NewConversation(Toolkit toolkit) => new(this, toolkit);

    /// <summary>
    /// Streaming variant: <paramref name="onEvent"/> receives live events (text deltas, tool
    /// calls/results, usage, done) and the final <see cref="RunResult"/> is returned. With an
    /// <paramref name="id"/> it is stateful (like <see cref="AskAsync"/>): the thread's transcript is
    /// loaded as history before streaming, and saved back to the <see cref="IConversationStore"/> once
    /// the run terminates. No <paramref name="id"/> ⇒ stateless.
    /// </summary>
    public async Task<RunResult> StreamAsync(string prompt, Toolkit toolkit, Action<StreamEvent> onEvent,
        string? id = null, CancellationToken cancellationToken = default)
    {
        var deadline = new Deadline(_opts.TimeoutMs);
        var history = id != null ? (await _store.GetAsync(id).ConfigureAwait(false) ?? new List<object?>()) : null;
        var result = _opts.Style == "anthropic"
            ? await StreamAnthropicAsync(prompt, toolkit, onEvent, history, deadline, cancellationToken).ConfigureAwait(false)
            : await StreamOpenAIAsync(prompt, toolkit, onEvent, history, deadline, cancellationToken).ConfigureAwait(false);
        if (id != null) await _store.SaveAsync(id, result.Messages).ConfigureAwait(false);
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private string ResolveKey()
    {
        if (!string.IsNullOrEmpty(_opts.ApiKey)) return _opts.ApiKey!;
        var openrouter = Environment.GetEnvironmentVariable("OPENROUTER_API_KEY");
        var openai = Environment.GetEnvironmentVariable("OPENAI_API_KEY");
        var anthropic = Environment.GetEnvironmentVariable("ANTHROPIC_API_KEY");
        var key = FirstNonEmpty(openrouter, _opts.Style == "anthropic" ? anthropic : openai, openai, anthropic);
        if (key == null)
            throw new InvalidOperationException(
                "No API key: set ApiKey or OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY");
        return key;
    }

    private static string? FirstNonEmpty(params string?[] vals) => vals.FirstOrDefault(v => !string.IsNullOrEmpty(v));

    private string System(Toolkit toolkit)
    {
        var parts = new List<string>();
        if (!string.IsNullOrEmpty(_opts.SystemPrompt)) parts.Add(_opts.SystemPrompt!);
        var sp = toolkit.SkillsPrompt();
        if (!string.IsNullOrEmpty(sp)) parts.Add(sp);
        return string.Join("\n\n", parts);
    }

    private int MaxTurns() => _opts.MaxTurns ?? 10;

    private static string StripTrailingSlash(string s) => s.EndsWith('/') ? s[..^1] : s;

    private static Dictionary<string, object?> Msg(string role, string content)
        => new() { ["role"] = role, ["content"] = content };

    internal static void AddUsage(Usage acc, IDictionary<string, object?>? raw, string style)
    {
        if (raw == null) return;
        if (style == "anthropic")
        {
            var inp = AsLong(raw.Get("input_tokens"));
            var outp = AsLong(raw.Get("output_tokens"));
            acc.PromptTokens += inp;
            acc.CompletionTokens += outp;
            acc.TotalTokens += inp + outp;
        }
        else
        {
            var inp = AsLong(raw.Get("prompt_tokens"));
            var outp = AsLong(raw.Get("completion_tokens"));
            var total = raw.Get("total_tokens");
            acc.PromptTokens += inp;
            acc.CompletionTokens += outp;
            acc.TotalTokens += total != null ? AsLong(total) : inp + outp;
        }
    }

    private static long AsLong(object? v) => v switch
    {
        null => 0,
        long l => l,
        int i => i,
        double d => (long)d,
        _ => long.TryParse(v.ToString(), out var p) ? p : 0,
    };

    private Dictionary<string, string> BaseHeaders(string key, bool anthropic)
    {
        var headers = new Dictionary<string, string>();
        if (anthropic)
        {
            headers["x-api-key"] = key;
            headers["anthropic-version"] = "2023-06-01";
        }
        else
        {
            headers["Authorization"] = "Bearer " + key;
        }
        if (_opts.Headers != null)
            foreach (var (k, v) in _opts.Headers) headers[k] = v;
        return headers;
    }

    private static string LastAssistantText(List<object?> messages)
    {
        for (var i = messages.Count - 1; i >= 0; i--)
        {
            if (messages[i] is IDictionary<string, object?> m
                && (m.Get("role") as string) == "assistant"
                && m.Get("content") is string s)
                return s;
        }
        return "";
    }

    // ---------------------------------------------------------------- OpenAI run

    private async Task<RunResult> RunOpenAIAsync(string prompt, Toolkit toolkit, List<object?>? history, Deadline deadline, CancellationToken external)
    {
        var key = ResolveKey();
        var messages = new List<object?>();
        if (history is { Count: > 0 })
        {
            messages.AddRange(history);
        }
        else
        {
            var system = System(toolkit);
            if (system.Length > 0) messages.Add(Msg("system", system));
        }
        messages.Add(Msg("user", prompt));
        var tools = toolkit.ToOpenAI();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;
        var runStart = NowMs();

        try
        {
            for (var turn = 0; turn < MaxTurns(); turn++)
            {
                turns++;
                ApplyBeforeLLM(ref messages, ref tools, turn);

                var body = new Dictionary<string, object?>
                {
                    ["model"] = _opts.Model,
                    ["messages"] = messages,
                };
                // §8 Gap 5: omit tools/tool_choice when the effective tool list is empty.
                if (tools.Count > 0)
                {
                    body["tools"] = tools;
                    body["tool_choice"] = "auto";
                }
                body = FinalizeBody(body);
                var url = StripTrailingSlash(_opts.BaseUrl) + "/chat/completions";
                var data = await LlmCallJsonAsync(url, BaseHeaders(key, false), body, deadline, external, "openai").ConfigureAwait(false);
                AddUsage(usage, data.Get("usage") as IDictionary<string, object?>, "openai");
                _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(data, _opts.Model, turn));

                var choices = data.Get("choices") as List<object?> ?? new List<object?>();
                var message = (IDictionary<string, object?>)((IDictionary<string, object?>)choices[0]!)["message"]!;
                messages.Add(message);
                var calls = message.Get("tool_calls") as List<object?>;
                if (calls == null || calls.Count == 0)
                {
                    var content = message.Get("content");
                    return EndRun(runStart, content?.ToString() ?? "", messages, toolCalls, turns, usage);
                }

                // Execute all tool calls in this turn concurrently.
                var n = calls.Count;
                var names = new string[n];
                var callIds = new string?[n];
                var runs = new (IDictionary<string, object?> Args, ToolResult Result)[n];
                var tasks = new Task[n];
                for (var i = 0; i < n; i++)
                {
                    var idx = i;
                    var call = (IDictionary<string, object?>)calls[i]!;
                    var fn = (IDictionary<string, object?>)call["function"]!;
                    names[idx] = fn.Get("name")?.ToString() ?? "";
                    callIds[idx] = call.Get("id")?.ToString();
                    var args = Json.ParseObjectLoose(fn.Get("arguments")?.ToString() ?? "{}");
                    tasks[idx] = Task.Run(async () => runs[idx] = await RunToolAsync(toolkit, names[idx], args, callIds[idx], turn).ConfigureAwait(false));
                }
                await Task.WhenAll(tasks).ConfigureAwait(false);

                // §10: resolve any suspensions (serially — WaitFor is a human-in-the-loop slot). Record
                // in tool-call order; on the FIRST durable halt, surface it and stop — deterministic, and
                // the later concurrent suspensions' placeholder results never enter the transcript (they
                // re-suspend on resume). Mirrors the streaming path.
                for (var i = 0; i < n; i++)
                {
                    var req = ToolResult.PendingOf(runs[i].Result);
                    Request? halted = null;
                    if (req != null)
                    {
                        var r = await ResolvePendingAsync(toolkit, names[i], runs[i].Args, req, callIds[i], turn).ConfigureAwait(false);
                        runs[i] = (runs[i].Args, r.Result);
                        halted = r.Halted;
                    }
                    toolCalls.Add(new ToolCall(names[i], runs[i].Args, runs[i].Result.Output, runs[i].Result.IsError, runs[i].Result.Metadata));
                    messages.Add(new Dictionary<string, object?>
                    {
                        ["role"] = "tool",
                        ["tool_call_id"] = callIds[i],
                        ["content"] = runs[i].Result.Output,
                    });
                    if (halted != null) return PendingRun(runStart, halted, messages, toolCalls, turns, usage);
                }
            }
            return EndRun(runStart, LastAssistantText(messages), messages, toolCalls, turns, usage);
        }
        catch (Exception e)
        {
            EmitRunError(runStart, toolCalls, turns, usage, e);
            throw;
        }
    }

    // ---------------------------------------------------------------- Anthropic run

    private async Task<RunResult> RunAnthropicAsync(string prompt, Toolkit toolkit, List<object?>? history, Deadline deadline, CancellationToken external)
    {
        var key = ResolveKey();
        var endpoint = AnthropicEndpoint();
        var system = System(toolkit);
        var messages = new List<object?>();
        if (history is { Count: > 0 }) messages.AddRange(history);
        messages.Add(Msg("user", prompt));
        var tools = toolkit.ToAnthropic();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;
        var runStart = NowMs();

        try
        {
            for (var turn = 0; turn < MaxTurns(); turn++)
            {
                turns++;
                ApplyBeforeLLM(ref messages, ref tools, turn);

                var body = new Dictionary<string, object?>
                {
                    ["model"] = _opts.Model,
                    ["max_tokens"] = 4096,
                    ["messages"] = messages,
                };
                if (system.Length > 0) body["system"] = system;
                // §8 Gap 5: omit tools when the effective tool list is empty.
                if (tools.Count > 0) body["tools"] = tools;
                body = FinalizeBody(body);

                var data = await LlmCallJsonAsync(endpoint, BaseHeaders(key, true), body, deadline, external, "anthropic").ConfigureAwait(false);
                AddUsage(usage, data.Get("usage") as IDictionary<string, object?>, "anthropic");
                _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(data, _opts.Model, turn));

                var content = data.Get("content") as List<object?> ?? new List<object?>();
                messages.Add(new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = content });

                var uses = content.OfType<IDictionary<string, object?>>()
                    .Where(b => (b.Get("type") as string) == "tool_use").ToList();
                if (uses.Count == 0)
                {
                    var text = new StringBuilder();
                    foreach (var b in content.OfType<IDictionary<string, object?>>())
                        if ((b.Get("type") as string) == "text")
                            text.Append(b.Get("text"));
                    return EndRun(runStart, text.ToString(), messages, toolCalls, turns, usage);
                }

                var n = uses.Count;
                var names = new string[n];
                var useIds = new string?[n];
                var runs = new (IDictionary<string, object?> Args, ToolResult Result)[n];
                var tasks = new Task[n];
                for (var i = 0; i < n; i++)
                {
                    var idx = i;
                    var use = uses[i];
                    var input = use.Get("input") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
                    names[idx] = use.Get("name")?.ToString() ?? "";
                    useIds[idx] = use.Get("id")?.ToString();
                    tasks[idx] = Task.Run(async () => runs[idx] = await RunToolAsync(toolkit, names[idx], input, useIds[idx], turn).ConfigureAwait(false));
                }
                await Task.WhenAll(tasks).ConfigureAwait(false);

                // §10: resolve any suspensions serially. Record in tool-call order; on the FIRST durable
                // halt, push the results so far (incl. the halted one) and surface it — later concurrent
                // suspensions' placeholder results never enter the transcript (they re-suspend on resume).
                var resultBlocks = new List<object?>(n);
                for (var i = 0; i < n; i++)
                {
                    var req = ToolResult.PendingOf(runs[i].Result);
                    Request? halted = null;
                    if (req != null)
                    {
                        var r = await ResolvePendingAsync(toolkit, names[i], runs[i].Args, req, useIds[i], turn).ConfigureAwait(false);
                        runs[i] = (runs[i].Args, r.Result);
                        halted = r.Halted;
                    }
                    toolCalls.Add(new ToolCall(names[i], runs[i].Args, runs[i].Result.Output, runs[i].Result.IsError, runs[i].Result.Metadata));
                    resultBlocks.Add(new Dictionary<string, object?>
                    {
                        ["type"] = "tool_result",
                        ["tool_use_id"] = useIds[i],
                        ["content"] = runs[i].Result.Output,
                        ["is_error"] = runs[i].Result.IsError,
                    });
                    if (halted != null)
                    {
                        messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = resultBlocks });
                        return PendingRun(runStart, halted, messages, toolCalls, turns, usage);
                    }
                }
                messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = resultBlocks });
            }
            return EndRun(runStart, "", messages, toolCalls, turns, usage);
        }
        catch (Exception e)
        {
            EmitRunError(runStart, toolCalls, turns, usage, e);
            throw;
        }
    }

    private string AnthropicEndpoint()
    {
        var b = StripTrailingSlash(_opts.BaseUrl);
        return b.EndsWith("/v1") ? b + "/messages" : b + "/v1/messages";
    }

    private void ApplyBeforeLLM(ref List<object?> messages, ref List<Dictionary<string, object?>> tools, int turn)
    {
        if (_opts.Hooks?.BeforeLLM == null) return;
        var ov = _opts.Hooks.BeforeLLM(new BeforeLLMEvent(messages, tools, _opts.Model, turn));
        if (ov?.Messages != null) messages = ov.Messages;
        if (ov?.Tools != null) tools = ov.Tools;
    }

    // ---------------------------------------------------------------- OpenAI stream

    private async Task<RunResult> StreamOpenAIAsync(string prompt, Toolkit toolkit, Action<StreamEvent> onEvent, List<object?>? history, Deadline deadline, CancellationToken external)
    {
        var key = ResolveKey();
        var messages = new List<object?>();
        if (history is { Count: > 0 })
        {
            messages.AddRange(history);
        }
        else
        {
            var system = System(toolkit);
            if (system.Length > 0) messages.Add(Msg("system", system));
        }
        messages.Add(Msg("user", prompt));
        var tools = toolkit.ToOpenAI();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;
        var runStart = NowMs();

        try
        {
            for (var turn = 0; turn < MaxTurns(); turn++)
            {
                turns++;
                ApplyBeforeLLM(ref messages, ref tools, turn);
                var body = new Dictionary<string, object?>
                {
                    ["model"] = _opts.Model,
                    ["messages"] = messages,
                    ["stream"] = true,
                    ["stream_options"] = new Dictionary<string, object?> { ["include_usage"] = true },
                };
                // §8 Gap 5: omit tools/tool_choice when the effective tool list is empty.
                if (tools.Count > 0)
                {
                    body["tools"] = tools;
                    body["tool_choice"] = "auto";
                }
                body = FinalizeBody(body);
                var url = StripTrailingSlash(_opts.BaseUrl) + "/chat/completions";

                var content = new StringBuilder();
                var acc = new Dictionary<int, string[]>(); // index -> [id, name, args]
                var order = new List<int>();

                var t0 = NowMs();
                var beforeP = usage.PromptTokens;
                var beforeC = usage.CompletionTokens;
                try
                {
                    await foreach (var line in SseLinesAsync(url, BaseHeaders(key, false), body, deadline, external).ConfigureAwait(false))
                    {
                        if (!line.StartsWith("data:")) continue;
                        var payload = line[5..].Trim();
                        if (payload == "[DONE]") break;
                        if (Json.ParseLoose(payload) is not IDictionary<string, object?> j) continue;
                        AddUsage(usage, j.Get("usage") as IDictionary<string, object?>, "openai");
                        if (j.Get("choices") is not List<object?> { Count: > 0 } choices) continue;
                        if (((IDictionary<string, object?>)choices[0]!).Get("delta") is not IDictionary<string, object?> delta) continue;
                        if (delta.Get("content") is string ds && ds.Length > 0)
                        {
                            content.Append(ds);
                            onEvent(StreamEvent.Text(ds));
                        }
                        if (delta.Get("tool_calls") is List<object?> tcs)
                        {
                            foreach (var o in tcs)
                            {
                                var tc = (IDictionary<string, object?>)o!;
                                var index = (int)AsLong(tc.Get("index"));
                                if (!acc.TryGetValue(index, out var slot)) { slot = new[] { "", "", "" }; acc[index] = slot; order.Add(index); }
                                if (tc.Get("id") != null) slot[0] = tc["id"]!.ToString()!;
                                if (tc.Get("function") is IDictionary<string, object?> fn)
                                {
                                    if (fn.Get("name") != null) slot[1] += fn["name"];
                                    if (fn.Get("arguments") != null) slot[2] += fn["arguments"];
                                }
                            }
                        }
                    }
                }
                catch (Exception)
                {
                    Emit(MetricEvent.Llm(_opts.Model, "error", NowMs() - t0, 0, 0));
                    throw;
                }
                Emit(MetricEvent.Llm(_opts.Model, "ok", NowMs() - t0, usage.PromptTokens - beforeP, usage.CompletionTokens - beforeC));
                _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(new Dictionary<string, object?> { ["streamed"] = true }, _opts.Model, turn));

                if (order.Count == 0)
                {
                    messages.Add(Msg("assistant", content.ToString()));
                    onEvent(StreamEvent.UsageEvent(usage.Copy()));
                    var done0 = EndRun(runStart, content.ToString(), messages, toolCalls, turns, usage);
                    onEvent(StreamEvent.DoneEvent(done0));
                    return done0;
                }

                var assembledCalls = new List<object?>();
                foreach (var index in order)
                {
                    var slot = acc[index];
                    assembledCalls.Add(new Dictionary<string, object?>
                    {
                        ["id"] = slot[0],
                        ["type"] = "function",
                        ["function"] = new Dictionary<string, object?> { ["name"] = slot[1], ["arguments"] = slot[2] },
                    });
                }
                messages.Add(new Dictionary<string, object?>
                {
                    ["role"] = "assistant",
                    ["content"] = content.Length == 0 ? null : content.ToString(),
                    ["tool_calls"] = assembledCalls,
                });

                foreach (var index in order)
                {
                    var slot = acc[index];
                    onEvent(StreamEvent.ToolCallEvent(slot[0], slot[1], Json.ParseObjectLoose(slot[2])));
                }
                var n = order.Count;
                var runs = new (IDictionary<string, object?> Args, ToolResult Result)[n];
                var tasks = new Task[n];
                for (var i = 0; i < n; i++)
                {
                    var idx = i;
                    var slot = acc[order[i]];
                    var args = Json.ParseObjectLoose(slot[2]);
                    tasks[idx] = Task.Run(async () => runs[idx] = await RunToolAsync(toolkit, slot[1], args, slot[0], turn).ConfigureAwait(false));
                }
                await Task.WhenAll(tasks).ConfigureAwait(false);
                // §10: resolve any suspensions in tool-call order; on the FIRST durable halt, record that
                // one tool result, surface the pending run and stop — later concurrent suspensions'
                // placeholder results never enter the transcript (they re-suspend on resume). Mirrors JS.
                for (var i = 0; i < n; i++)
                {
                    var slot = acc[order[i]];
                    var req = ToolResult.PendingOf(runs[i].Result);
                    Request? halted = null;
                    if (req != null)
                    {
                        onEvent(StreamEvent.PendingEvent(req)); // surface BEFORE WaitFor so a channel can push the link
                        var r = await ResolvePendingAsync(toolkit, slot[1], runs[i].Args, req, slot[0], turn).ConfigureAwait(false);
                        runs[i] = (runs[i].Args, r.Result);
                        halted = r.Halted;
                    }
                    var rr = runs[i];
                    toolCalls.Add(new ToolCall(slot[1], rr.Args, rr.Result.Output, rr.Result.IsError, rr.Result.Metadata));
                    messages.Add(new Dictionary<string, object?> { ["role"] = "tool", ["tool_call_id"] = slot[0], ["content"] = rr.Result.Output });
                    if (halted != null)
                    {
                        var p = PendingRun(runStart, halted, messages, toolCalls, turns, usage);
                        onEvent(StreamEvent.DoneEvent(p));
                        return p;
                    }
                    onEvent(StreamEvent.ToolResultEvent(slot[0], slot[1], rr.Result.Output, rr.Result.IsError));
                }
            }
            var done = EndRun(runStart, LastAssistantText(messages), messages, toolCalls, turns, usage);
            onEvent(StreamEvent.DoneEvent(done));
            return done;
        }
        catch (Exception e)
        {
            EmitRunError(runStart, toolCalls, turns, usage, e);
            throw;
        }
    }

    // ---------------------------------------------------------------- Anthropic stream

    private async Task<RunResult> StreamAnthropicAsync(string prompt, Toolkit toolkit, Action<StreamEvent> onEvent, List<object?>? history, Deadline deadline, CancellationToken external)
    {
        var key = ResolveKey();
        var endpoint = AnthropicEndpoint();
        var system = System(toolkit);
        var messages = new List<object?>();
        if (history is { Count: > 0 }) messages.AddRange(history);
        messages.Add(Msg("user", prompt));
        var tools = toolkit.ToAnthropic();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;
        var runStart = NowMs();

        try
        {
            for (var turn = 0; turn < MaxTurns(); turn++)
            {
                turns++;
                ApplyBeforeLLM(ref messages, ref tools, turn);
                var body = new Dictionary<string, object?>
                {
                    ["model"] = _opts.Model,
                    ["max_tokens"] = 4096,
                    ["messages"] = messages,
                    ["stream"] = true,
                };
                if (system.Length > 0) body["system"] = system;
                // §8 Gap 5: omit tools when the effective tool list is empty.
                if (tools.Count > 0) body["tools"] = tools;
                body = FinalizeBody(body);

                var blocks = new Dictionary<int, Dictionary<string, object?>>();
                var order = new List<int>();
                var stopReason = "";

                var t0 = NowMs();
                var beforeP = usage.PromptTokens;
                var beforeC = usage.CompletionTokens;
                try
                {
                    await foreach (var line in SseLinesAsync(endpoint, BaseHeaders(key, true), body, deadline, external).ConfigureAwait(false))
                    {
                        if (!line.StartsWith("data:")) continue;
                        if (Json.ParseLoose(line[5..].Trim()) is not IDictionary<string, object?> j) continue;
                        var type = j.Get("type") as string;
                        if (type == "message_start")
                        {
                            if (j.Get("message") is IDictionary<string, object?> m)
                                AddUsage(usage, m.Get("usage") as IDictionary<string, object?>, "anthropic");
                        }
                        else if (type == "content_block_start")
                        {
                            var index = (int)AsLong(j.Get("index"));
                            var cb = (IDictionary<string, object?>)j["content_block"]!;
                            blocks[index] = new Dictionary<string, object?>
                            {
                                ["type"] = cb.Get("type"),
                                ["id"] = cb.Get("id"),
                                ["name"] = cb.Get("name"),
                                ["text"] = new StringBuilder(),
                                ["json"] = new StringBuilder(),
                            };
                            order.Add(index);
                        }
                        else if (type == "content_block_delta")
                        {
                            var index = (int)AsLong(j.Get("index"));
                            if (!blocks.TryGetValue(index, out var b)) continue;
                            var delta = (IDictionary<string, object?>)j["delta"]!;
                            var dtype = delta.Get("type") as string;
                            if (dtype == "text_delta")
                            {
                                var t = delta.Get("text")?.ToString() ?? "";
                                ((StringBuilder)b["text"]!).Append(t);
                                onEvent(StreamEvent.Text(t));
                            }
                            else if (dtype == "input_json_delta")
                            {
                                ((StringBuilder)b["json"]!).Append(delta.Get("partial_json"));
                            }
                        }
                        else if (type == "message_delta")
                        {
                            if (j.Get("delta") is IDictionary<string, object?> delta && delta.Get("stop_reason") != null)
                                stopReason = delta["stop_reason"]!.ToString()!;
                            AddUsage(usage, j.Get("usage") as IDictionary<string, object?>, "anthropic");
                        }
                    }
                }
                catch (Exception)
                {
                    Emit(MetricEvent.Llm(_opts.Model, "error", NowMs() - t0, 0, 0));
                    throw;
                }
                Emit(MetricEvent.Llm(_opts.Model, "ok", NowMs() - t0, usage.PromptTokens - beforeP, usage.CompletionTokens - beforeC));
                _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(new Dictionary<string, object?> { ["streamed"] = true }, _opts.Model, turn));

                var contentBlocks = new List<object?>();
                var uses = new List<Dictionary<string, object?>>();
                foreach (var index in order)
                {
                    var b = blocks[index];
                    if ((b["type"] as string) == "tool_use")
                    {
                        var json = b["json"]!.ToString()!;
                        var tu = new Dictionary<string, object?>
                        {
                            ["type"] = "tool_use",
                            ["id"] = b["id"],
                            ["name"] = b["name"],
                            ["input"] = Json.ParseObjectLoose(json.Length == 0 ? "{}" : json),
                        };
                        contentBlocks.Add(tu);
                        uses.Add(tu);
                    }
                    else
                    {
                        contentBlocks.Add(new Dictionary<string, object?> { ["type"] = "text", ["text"] = b["text"]!.ToString() });
                    }
                }
                messages.Add(new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = contentBlocks });

                if (stopReason != "tool_use" || uses.Count == 0)
                {
                    var text = new StringBuilder();
                    foreach (var b in contentBlocks.OfType<IDictionary<string, object?>>())
                        if ((b.Get("type") as string) == "text")
                            text.Append(b.Get("text"));
                    onEvent(StreamEvent.UsageEvent(usage.Copy()));
                    var done0 = EndRun(runStart, text.ToString(), messages, toolCalls, turns, usage);
                    onEvent(StreamEvent.DoneEvent(done0));
                    return done0;
                }

                var n = uses.Count;
                foreach (var u in uses)
                    onEvent(StreamEvent.ToolCallEvent(u["id"]?.ToString(), u["name"]?.ToString() ?? "", (IDictionary<string, object?>)u["input"]!));
                var runs = new (IDictionary<string, object?> Args, ToolResult Result)[n];
                var tasks = new Task[n];
                for (var i = 0; i < n; i++)
                {
                    var idx = i;
                    var u = uses[i];
                    var input = u.Get("input") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
                    var name = u.Get("name")?.ToString() ?? "";
                    var id = u.Get("id")?.ToString();
                    tasks[idx] = Task.Run(async () => runs[idx] = await RunToolAsync(toolkit, name, input, id, turn).ConfigureAwait(false));
                }
                await Task.WhenAll(tasks).ConfigureAwait(false);
                // §10: resolve any suspensions in tool-call order; on the FIRST durable halt, push the
                // results so far (incl. the halted one), surface the pending run and stop — later
                // concurrent suspensions' placeholders never enter the transcript. Mirrors JS.
                var results = new List<object?>(n);
                for (var i = 0; i < n; i++)
                {
                    var u = uses[i];
                    var name = u.Get("name")?.ToString() ?? "";
                    var id = u.Get("id")?.ToString();
                    var req = ToolResult.PendingOf(runs[i].Result);
                    Request? halted = null;
                    if (req != null)
                    {
                        onEvent(StreamEvent.PendingEvent(req)); // surface BEFORE WaitFor
                        var rp = await ResolvePendingAsync(toolkit, name, runs[i].Args, req, id, turn).ConfigureAwait(false);
                        runs[i] = (runs[i].Args, rp.Result);
                        halted = rp.Halted;
                    }
                    var r = runs[i];
                    toolCalls.Add(new ToolCall(name, r.Args, r.Result.Output, r.Result.IsError, r.Result.Metadata));
                    results.Add(new Dictionary<string, object?>
                    {
                        ["type"] = "tool_result",
                        ["tool_use_id"] = id,
                        ["content"] = r.Result.Output,
                        ["is_error"] = r.Result.IsError,
                    });
                    if (halted != null)
                    {
                        messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = results });
                        var p = PendingRun(runStart, halted, messages, toolCalls, turns, usage);
                        onEvent(StreamEvent.DoneEvent(p));
                        return p;
                    }
                    onEvent(StreamEvent.ToolResultEvent(id, name, r.Result.Output, r.Result.IsError));
                }
                messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = results });
            }
            var done = EndRun(runStart, "", messages, toolCalls, turns, usage);
            onEvent(StreamEvent.DoneEvent(done));
            return done;
        }
        catch (Exception e)
        {
            EmitRunError(runStart, toolCalls, turns, usage, e);
            throw;
        }
    }

    // ---------------------------------------------------------------- resilience / HTTP

    private int Retries() => _opts.Retries ?? 2;
    private long RetryBaseMs() => _opts.RetryBaseMs ?? 500L;

    /// <summary>A run-scoped monotonic deadline. Null timeoutMs =&gt; no deadline.</summary>
    private sealed class Deadline
    {
        private readonly long _endMs;
        public readonly bool Bounded;
        public readonly long TimeoutMs;
        private static long Now => Stopwatch.GetTimestamp() * 1000L / Stopwatch.Frequency;

        public Deadline(long? timeoutMs)
        {
            if (timeoutMs == null) { Bounded = false; TimeoutMs = 0; _endMs = 0; }
            else { Bounded = true; TimeoutMs = timeoutMs.Value; _endMs = Now + timeoutMs.Value; }
        }

        public long RemainingMs => Bounded ? _endMs - Now : long.MaxValue;

        public void Check()
        {
            if (Bounded && Now >= _endMs)
                throw new RunTimeoutException($"run timeout after {TimeoutMs}ms");
        }
    }

    private async Task<HttpResponseMessage> LlmSendAsync(string url, Dictionary<string, string> headers,
        Dictionary<string, object?> body, Deadline deadline, bool streaming, CancellationToken external)
    {
        var retries = Retries();
        var baseMs = RetryBaseMs();
        var payload = Json.Stringify(body);
        Exception? lastErr = null;

        for (var attempt = 0; attempt <= retries; attempt++)
        {
            deadline.Check();
            external.ThrowIfCancellationRequested();

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(external);
            if (deadline.Bounded)
            {
                var remain = deadline.RemainingMs;
                if (remain <= 0) throw new RunTimeoutException($"run timeout after {deadline.TimeoutMs}ms");
                cts.CancelAfter(TimeSpan.FromMilliseconds(remain));
            }

            var req = new HttpRequestMessage(HttpMethod.Post, url)
            {
                Content = new StringContent(payload, Encoding.UTF8, "application/json"),
            };
            foreach (var (k, v) in headers)
            {
                if (!req.Headers.TryAddWithoutValidation(k, v))
                    req.Content.Headers.TryAddWithoutValidation(k, v);
            }

            try
            {
                var completion = streaming ? HttpCompletionOption.ResponseHeadersRead : HttpCompletionOption.ResponseContentRead;
                var res = await _http.SendAsync(req, completion, cts.Token).ConfigureAwait(false);
                var status = (int)res.StatusCode;
                if (status is >= 200 and < 300) return res;
                if (!Retryable.Contains(status) || attempt == retries) return res; // caller handles non-2xx
                var wait = RetryAfterMs(res) ?? (long)(baseMs * Math.Pow(2, attempt) + Random.Shared.Next(0, 100));
                res.Dispose();
                await SleepAsync(wait, deadline, external).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (external.IsCancellationRequested)
            {
                throw new RunTimeoutException("run aborted");
            }
            catch (OperationCanceledException)
            {
                throw new RunTimeoutException($"run timeout after {deadline.TimeoutMs}ms"); // not retried
            }
            catch (HttpRequestException e)
            {
                lastErr = new InvalidOperationException("LLM request failed: " + e.Message, e);
                if (attempt == retries) throw lastErr;
                await SleepAsync((long)(baseMs * Math.Pow(2, attempt) + Random.Shared.Next(0, 100)), deadline, external).ConfigureAwait(false);
            }
        }
        throw lastErr ?? new InvalidOperationException("LLM request failed");
    }

    private static long? RetryAfterMs(HttpResponseMessage res)
    {
        if (res.Headers.TryGetValues("retry-after", out var values))
        {
            var v = values.FirstOrDefault()?.Trim();
            if (v != null && long.TryParse(v, out var secs)) return secs * 1000L;
        }
        return null;
    }

    private static async Task SleepAsync(long ms, Deadline deadline, CancellationToken external)
    {
        if (ms <= 0) { deadline.Check(); return; }
        var capped = deadline.Bounded ? Math.Min(ms, Math.Max(0, deadline.RemainingMs)) : ms;
        await Task.Delay((int)Math.Max(0, capped), external).ConfigureAwait(false);
        deadline.Check();
    }

    /// <summary>One non-streaming LLM call, with an <c>llm</c> metric event (ok/error + per-call tokens + ms).</summary>
    private async Task<Dictionary<string, object?>> LlmCallJsonAsync(string url, Dictionary<string, string> headers,
        Dictionary<string, object?> body, Deadline deadline, CancellationToken external, string style)
    {
        var t0 = NowMs();
        try
        {
            var data = await PostJsonAsync(url, headers, body, deadline, external).ConfigureAwait(false);
            var tok = PerCall(data.Get("usage") as IDictionary<string, object?>, style);
            Emit(MetricEvent.Llm(_opts.Model, "ok", NowMs() - t0, tok.Prompt, tok.Completion));
            return data;
        }
        catch (Exception)
        {
            Emit(MetricEvent.Llm(_opts.Model, "error", NowMs() - t0, 0, 0));
            throw;
        }
    }

    private async Task<Dictionary<string, object?>> PostJsonAsync(string url, Dictionary<string, string> headers,
        Dictionary<string, object?> body, Deadline deadline, CancellationToken external)
    {
        using var res = await LlmSendAsync(url, headers, body, deadline, false, external).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(external).ConfigureAwait(false);
        if ((int)res.StatusCode is < 200 or >= 300)
            throw new InvalidOperationException($"LLM {(int)res.StatusCode}: {text}");
        return Json.ToMap(text);
    }

    private async IAsyncEnumerable<string> SseLinesAsync(string url, Dictionary<string, string> headers,
        Dictionary<string, object?> body, Deadline deadline,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken external)
    {
        using var res = await LlmSendAsync(url, headers, body, deadline, true, external).ConfigureAwait(false);
        if ((int)res.StatusCode is < 200 or >= 300)
        {
            var b = await res.Content.ReadAsStringAsync(external).ConfigureAwait(false);
            throw new InvalidOperationException($"LLM {(int)res.StatusCode}: {b}");
        }
        await using var stream = await res.Content.ReadAsStreamAsync(external).ConfigureAwait(false);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        while (await reader.ReadLineAsync(external).ConfigureAwait(false) is { } line)
            yield return line;
    }

    // ---------------------------------------------------------------- conversation memory

    /// <summary>
    /// Stateful multi-turn conversation (memory). Each <see cref="SendAsync"/> continues the
    /// same transcript: the system prompt is added once (on the first turn), prior history
    /// is carried forward automatically.
    /// </summary>
    public sealed class Conversation
    {
        private readonly LlmClient _client;
        private readonly Toolkit _toolkit;
        private List<object?> _messages = new();

        internal Conversation(LlmClient client, Toolkit toolkit)
        {
            _client = client;
            _toolkit = toolkit;
        }

        public async Task<RunResult> SendAsync(string prompt, CancellationToken cancellationToken = default)
        {
            var result = await _client.RunAsync(prompt, _toolkit, _messages, cancellationToken).ConfigureAwait(false);
            _messages = result.Messages;
            return result;
        }

        public List<object?> Messages => _messages;

        public void Reset() => _messages = new List<object?>();
    }
}
