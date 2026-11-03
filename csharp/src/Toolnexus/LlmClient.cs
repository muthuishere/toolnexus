using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

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
    private readonly ConcurrentDictionary<string, (List<object?> Messages, DateTimeOffset UpdatedAt, long Seq)> _map = new();
    private long _seq;

    public Task<List<object?>?> GetAsync(string id)
        => Task.FromResult(_map.TryGetValue(id, out var m) ? new List<object?>(m.Messages) : null);

    public Task SaveAsync(string id, List<object?> messages)
    {
        _map[id] = (new List<object?>(messages), DateTimeOffset.UtcNow, Interlocked.Increment(ref _seq));
        return Task.CompletedTask;
    }

    /// <summary>Every stored conversation, newest first (ties by id ascending).</summary>
    public Task<List<ConversationInfo>> ListAsync()
        => Task.FromResult(_map
            .OrderByDescending(kv => kv.Value.Seq)
            .ThenBy(kv => kv.Key, StringComparer.Ordinal)
            .Select(kv => new ConversationInfo(kv.Key, kv.Value.UpdatedAt))
            .ToList());

    /// <summary>Remove a conversation; succeeds when it is absent.</summary>
    public Task DeleteAsync(string id)
    {
        _map.TryRemove(id, out _);
        return Task.CompletedTask;
    }
}

/// <summary>One entry of a conversation store's <c>ListAsync</c>: the id and when it was last saved.</summary>
public sealed record ConversationInfo(string Id, DateTimeOffset UpdatedAt);

/// <summary>
/// Unified LLM client — the host loop. Give it a base URL + a style
/// (<c>"openai" | "anthropic"</c>) and it runs the tool-calling agent loop against a
/// <see cref="Toolkit"/>. Mirrors the JS/Java reference.
/// </summary>
public sealed partial class LlmClient
{
    private static readonly HttpClient DefaultHttp = new() { Timeout = Timeout.InfiniteTimeSpan };
    /// <summary>
    /// The default retryable set (§8 Resilience): <c>429</c> plus the 5xx worth another try — and
    /// <c>529 Overloaded</c>, which TypeSafe documents as "retry with backoff" and which this
    /// enumeration made terminal.
    /// <para>
    /// It is an ENUMERATION on purpose. "Any 5xx" would sweep in permanently-broken statuses
    /// (<c>501 Not Implemented</c>, <c>505 HTTP Version Not Supported</c>) and change the retry
    /// behaviour of every existing host without asking. A backend with its own transient status — a
    /// Cloudflare origin answering <c>520</c>–<c>527</c>, say — opts in declaratively through
    /// <c>RetryableStatuses</c>, which ADDS to this set and cannot remove from it.
    /// </para>
    /// Shared with the §8B <see cref="Classifier"/> (which adds <c>408</c>), so there is never a
    /// second copy of this policy.
    /// </summary>
    private static readonly HashSet<int> RetryableDefaults = new() { 429, 500, 502, 503, 504, 529 };

    /// <summary>
    /// Whether a status is retryable by default, optionally widened by a host's
    /// <c>RetryableStatuses</c>.
    /// <para>
    /// <paramref name="extra"/> is ADDITIVE: it can only make more statuses retryable, never fewer,
    /// so a host cannot accidentally drop <c>429</c> and lose <c>Retry-After</c> handling with it.
    /// It decides the DEFAULT classification only — <c>OnError</c> still runs afterwards and has the
    /// final say on every attempt, so <c>OnError</c> returning <see cref="Tier.Fail"/> overrides a
    /// status the host itself listed here.
    /// </para>
    /// </summary>
    internal static bool IsRetryableStatus(int status, IReadOnlyCollection<int>? extra = null) =>
        RetryableDefaults.Contains(status) || (extra is not null && extra.Contains(status));

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
        // Prompt caching (SPEC §4C) runs first, so RequestParams and BodyTransform can override it.
        if (_opts.PromptCache != null) ApplyPromptCache(body, _opts.Style, _opts.PromptCache);
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

    /// <summary>Emit the terminal <c>run</c> metric event and build the <see cref="RunResult"/>.
    /// <paramref name="status"/> is <c>"done"</c> for a final answer, <c>"incomplete"</c> when the
    /// loop stopped at <c>MaxTurns</c> still emitting tool calls (SPEC §8 addendum — loud, never a
    /// silent done).</summary>
    private RunResult EndRun(long runStart, string text, List<object?> messages, List<ToolCall> toolCalls, int turns, Usage usage, string model, string status = "done")
    {
        Emit(MetricEvent.Run(model, turns, toolCalls.Count, usage.TotalTokens, NowMs() - runStart));
        return new RunResult(text, messages, toolCalls, turns, usage, model, status,
            limit: status == "incomplete" ? "maxTurns" : null);
    }

    /// <summary>Emit a <c>run</c> error metric event (once, on a thrown run).</summary>
    private void EmitRunError(long runStart, List<ToolCall> toolCalls, int turns, Usage usage, Exception e, string model)
        => Emit(MetricEvent.Run(model, turns, toolCalls.Count, usage.TotalTokens, NowMs() - runStart, e.Message));

    // ---------------------------------------------------------------- options

    public sealed class Options
    {
        public string BaseUrl { get; set; } = "";
        public string Style { get; set; } = "openai"; // "openai" | "anthropic"
        public string Model { get; set; } = "";
        /// <summary>
        /// The API key. When null/empty it is read from <see cref="ApiKeyEnv"/>, else from the ONE env
        /// var that belongs to the <see cref="BaseUrl"/> host over https: <c>api.openai.com</c> →
        /// <c>OPENAI_API_KEY</c>, <c>api.anthropic.com</c> → <c>ANTHROPIC_API_KEY</c>,
        /// <c>openrouter.ai</c> (and subdomains) → <c>OPENROUTER_API_KEY</c>. Any other host, or plain
        /// http, needs <see cref="ApiKey"/> or <see cref="ApiKeyEnv"/> — a key is never sent to a host
        /// it does not belong to (SPEC §4C). Never printed.
        /// </summary>
        public string? ApiKey { get; set; }

        /// <summary>The NAME (never the value) of the env var to read the key from when
        /// <see cref="ApiKey"/> is empty — for a host not in the table above.</summary>
        public string? ApiKeyEnv { get; set; }
        public IDictionary<string, string>? Headers { get; set; }
        public string? SystemPrompt { get; set; }
        public int? MaxTurns { get; set; }             // default 10
        /// <summary>(SPEC §8 "Tool scheduling") Cap on concurrency-safe tool calls of one response
        /// running at once. <c>null</c> ⇒ env <c>TOOLNEXUS_MAX_TOOL_CONCURRENCY</c> when a positive
        /// integer, else 10. Unsafe calls (anything not read-only) always run alone.</summary>
        public int? MaxToolConcurrency { get; set; }
        public Hooks? Hooks { get; set; }
        public int? Retries { get; set; }              // default 2

        /// <summary>
        /// Extra HTTP statuses to treat as retryable, ADDED to the default set
        /// (<c>429</c>/<c>500</c>/<c>502</c>/<c>503</c>/<c>504</c>/<c>529</c>). It can only widen: a
        /// host cannot remove <c>429</c> and lose <c>Retry-After</c> handling with it. This sets the
        /// DEFAULT classification; <c>OnError</c> still runs per attempt and has the final say, so
        /// <c>OnError</c> returning <see cref="Tier.Fail"/> overrides a status listed here.
        /// Example: a Cloudflare-fronted origin that answers <c>520</c>–<c>527</c>.
        /// <c>Retry-After</c> handling is untouched. Null ⇒ the defaults alone.
        /// </summary>
        public IReadOnlyCollection<int>? RetryableStatuses { get; set; }
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
        /// (SPEC §4C) <see cref="WaitFor"/> that also receives the turn's
        /// <see cref="CancellationToken"/>, so a pending ask ends when the turn is cancelled (throw
        /// <see cref="OperationCanceledException"/>). Wins over <see cref="WaitFor"/> when both are
        /// set. Same §10 contract otherwise.
        /// </summary>
        public Func<Request, CancellationToken, Task<Answer>>? WaitForCtx { get; set; }

        /// <summary>
        /// (SPEC §4C prompt caching) When set, mark the stable request prefix for the provider's
        /// prompt cache (see <see cref="PromptCacheOptions"/>). Null ⇒ body byte-identical. Applied
        /// before <see cref="RequestParams"/> and <see cref="BodyTransform"/>, so both can override it.
        /// </summary>
        public PromptCacheOptions? PromptCache { get; set; }

        /// <summary>
        /// (SPEC §4C tool tiers) When set, send only core tool schemas up front plus a
        /// <c>tool_search</c> tool that loads the rest on demand (see <see cref="ToolTierOptions"/>).
        /// Null ⇒ every tool is sent, as before.
        /// </summary>
        public ToolTierOptions? ToolTiers { get; set; }

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

        /// <summary>
        /// (§8 Resilience) Classify each failed LLM call into <see cref="Tier.Retry"/> or
        /// <see cref="Tier.Fail"/>. Null ⇒ the default classifier (<c>Retryable ? Retry : Fail</c>) —
        /// byte-identical to today. A <see cref="Tier.Retry"/> is always bounded by <see cref="Retries"/>
        /// (the classifier cannot loop unbounded); a <see cref="Tier.Fail"/> surfaces the error
        /// immediately, skipping remaining retries. There is no "suspend" tier — suspension (§10) stays a
        /// user-action pause, not an error path. Aborts (timeout/cancel) bypass <c>OnError</c>.
        /// </summary>
        public Func<ErrorInfo, Tier>? OnError { get; set; }

        /// <summary>
        /// (§1B) Cap on a single content part, measured in <b>decoded</b> bytes (base64 is +33%).
        /// Enforced when the client assembles a part into a request; the edge constructors take
        /// the same limit as an argument. Null ⇒ no cap.
        /// </summary>
        public long? MaxPartBytes { get; set; }

        /// <summary>
        /// (§8A) Override the provenance rule for a part the provider style cannot represent.
        /// Null ⇒ the default: a part the CALLER attached is a typed error before any HTTP call;
        /// a part DERIVED from a tool/MCP result degrades to a text placeholder with a warning
        /// emitted at most once. <c>"error"</c> ⇒ always error; <c>"text"</c> ⇒ always degrade.
        /// A part is never dropped silently.
        /// </summary>
        public string? OnUnsupportedPart { get; set; }

        public Options WithMaxPartBytes(long v) { MaxPartBytes = v; return this; }
        public Options WithOnUnsupportedPart(string v) { OnUnsupportedPart = v; return this; }

        public Options WithOnError(Func<ErrorInfo, Tier> v) { OnError = v; return this; }
        public Options WithWaitFor(Func<Request, Task<Answer>> v) { WaitFor = v; return this; }
        public Options WithWaitForCtx(Func<Request, CancellationToken, Task<Answer>> v) { WaitForCtx = v; return this; }
        public Options WithApiKeyEnv(string v) { ApiKeyEnv = v; return this; }
        public Options WithPromptCache(PromptCacheOptions v) { PromptCache = v; return this; }
        public Options WithToolTiers(ToolTierOptions v) { ToolTiers = v; return this; }
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
        public Options WithMaxToolConcurrency(int v) { MaxToolConcurrency = v; return this; }
        public Options WithHooks(Hooks v) { Hooks = v; return this; }
        public Options WithRetries(int v) { Retries = v; return this; }
        public Options WithRetryableStatuses(IReadOnlyCollection<int> v) { RetryableStatuses = v; return this; }
        public Options WithRetryBaseMs(int v) { RetryBaseMs = v; return this; }
        public Options WithTimeoutMs(long v) { TimeoutMs = v; return this; }
        public Options WithStore(IConversationStore v) { Store = v; return this; }
        public Options WithOnMetric(Action<MetricEvent> v) { OnMetric = v; return this; }
    }

    /// <summary>
    /// Thrown when a run exceeds <see cref="Options.TimeoutMs"/> (or its in-flight request times
    /// out). An EXTERNAL cancellation (the <c>CancellationToken</c> passed to run/ask/stream) is
    /// NOT wrapped in this type — it surfaces as <see cref="OperationCanceledException"/> and is
    /// classified by token state (SPEC §7D per-port cancellation contract), so an interrupt is
    /// always distinguishable from a timeout without inspecting exception types.
    /// </summary>
    public sealed class RunTimeoutException : Exception
    {
        public RunTimeoutException(string message) : base(message) { }
    }

    /// <summary>
    /// (ADR 0027 D3.1) A provider failure carried as a VALUE, not a sentence. Before this, the only
    /// interface to a non-2xx was <c>InvalidOperationException.Message</c>, so a host deciding what
    /// to log had to parse prose. It still derives from <see cref="InvalidOperationException"/>, so
    /// existing <c>catch</c> blocks and message matching keep working.
    ///
    /// <para><see cref="Body"/> is the RAW provider body, unredacted, for a host that genuinely
    /// wants it. <see cref="Exception.Message"/> is the redacted, capped rendering — that is the
    /// one that reaches logs by default.</para>
    /// </summary>
    public sealed class ProviderException : InvalidOperationException
    {
        /// <summary>The HTTP status the provider returned.</summary>
        public int Status { get; }

        /// <summary>
        /// The response body, REDACTED but UNCAPPED (ADR 0027 A5). Account-identifier keys are
        /// replaced here exactly as they are in <see cref="Exception.Message"/> — a typed field is
        /// not a hole in the guarantee — but the 200-character cap is applied to the MESSAGE ONLY,
        /// because a host that opted into a typed error asked for the whole thing.
        /// Empty on 401/403, where the body may reflect the credential that was sent.
        /// </summary>
        public string Body { get; }

        /// <summary>
        /// The raw <c>Retry-After</c> header verbatim, or <c>null</c> when the response did not
        /// send one. Deliberately NOT pre-parsed: an HTTP-date, fractional or out-of-range value
        /// is information the response really supplied, and a numeric field would have to null it
        /// out. The library's own waiting rule (delay-seconds only, falling back to backoff) is
        /// separate and unchanged — see <see cref="RetryAfterMs"/>.
        /// </summary>
        public string? RetryAfter { get; }

        public ProviderException(int status, string body, string? retryAfter, string message)
            : base(message)
        {
            Status = status;
            Body = body ?? "";
            RetryAfter = retryAfter;
        }
    }

    /// <summary>
    /// (ADR 0027 D3.2) Account-identifier keys replaced with <c>«redacted»</c> — NOT dropped, so the
    /// body's shape survives and a reader can still see that the provider named an account. Pinned
    /// identically in every port; the value stays reachable on
    /// <see cref="ProviderException.Body"/>.
    /// </summary>
    public static readonly IReadOnlyList<string> RedactedBodyKeys =
        new[] { "user_id", "account_id", "org_id", "organization" };

    /// <summary>The replacement token. Byte-identical across ports.</summary>
    public const string RedactionToken = "«redacted»";

    /// <summary>The §8 message cap, lifted verbatim from the classifier's <c>cause()</c> (ADR 0027
    /// D3.3) so the policy is written once rather than an eighth time differently.</summary>
    public const int ErrorBodyCap = 200;

    /// <summary>
    /// Structure first, redact second, cap third (ADR 0027 D3).
    /// <list type="number">
    ///   <item><description>401/403 ⇒ <c>""</c>. A gateway happily reflects a bad
    ///   <c>Authorization</c> header into its own 401 text, so the body never reaches a message.</description></item>
    ///   <item><description>Every <see cref="RedactedBodyKeys"/> value ⇒ <see cref="RedactionToken"/>.
    ///   <b>A CAP IS NOT REDACTION</b> — the leaking body that motivated this is 96 bytes and sailed
    ///   through a 200-char cap untouched.</description></item>
    ///   <item><description>Then, and only then, cap at <see cref="ErrorBodyCap"/> — and (A5) the
    ///   cap applies to the MESSAGE only, never to <see cref="ProviderException.Body"/>.</description></item>
    /// </list>
    /// </summary>
    /// <param name="cap">False ⇒ redact without capping (the typed field).</param>
    internal static string RedactBody(int status, string? body, bool cap = true)
    {
        if (status is 401 or 403) return "";
        var s = (body ?? "").Trim();
        if (s.Length == 0) return "";
        foreach (var key in RedactedBodyKeys)
        {
            // JSON (`"user_id": "u_1"` / `"user_id":123`) and form/prose (`user_id=u_1`) spellings.
            s = Regex.Replace(s,
                "(\\\"?" + Regex.Escape(key) + "\\\"?\\s*[:=]\\s*)(\\\"[^\\\"]*\\\"|[^,;}\\s]+)",
                "$1\"" + RedactionToken + "\"");
        }
        if (cap && s.Length > ErrorBodyCap) s = s[..ErrorBodyCap] + "…";
        return s;
    }

    /// <summary>Builds the redacted, capped message for a provider failure.</summary>
    internal static string ProviderMessage(int status, string? body)
    {
        var redacted = RedactBody(status, body);
        return redacted.Length == 0 ? $"LLM {status}" : $"LLM {status}: {redacted}";
    }

    // ---------------------------------------------------------------- resilience classifier (§8)

    /// <summary>(§8 Resilience) What to do with a failed LLM call: retry it (within budget) or fail now.</summary>
    public enum Tier
    {
        Retry,
        Fail,
    }

    /// <summary>
    /// (§8 Resilience) Context passed to <see cref="Options.OnError"/> for each failed LLM attempt.
    /// <see cref="Status"/> is set on a non-ok HTTP response; <see cref="Error"/> on a transport/network
    /// throw; <see cref="Attempt"/> is zero-based; <see cref="Retryable"/> = whether the status/error is in
    /// the default retryable set (429/500/502/503/504/529/network, plus anything RetryableStatuses added).
    /// </summary>
    public sealed record ErrorInfo(Exception? Error, int? Status, int Attempt, bool Retryable);

    // ---------------------------------------------------------------- hooks

    public sealed record BeforeLLMEvent(List<object?> Messages, List<Dictionary<string, object?>> Tools, string Model, int Turn);
    /// <summary>A <see cref="Hooks.BeforeLLM"/> override. <paramref name="Model"/>, when non-empty, is
    /// transmitted for THIS turn only (request body and the AfterLLM event); null or empty ⇒ the
    /// configured model, verbatim (SPEC §8 "Right-size routing"; an opt-in
    /// <see cref="ModelRouterClassifier"/> sets it).</summary>
    public sealed record LLMOverride(List<object?>? Messages = null, List<Dictionary<string, object?>>? Tools = null, string? Model = null);
    public sealed record AfterLLMEvent(Dictionary<string, object?> Response, string Model, int Turn);
    /// <summary>A <see cref="Hooks.BeforeTool"/> event. <paramref name="Hints"/> are the called tool's
    /// <see cref="ITool.Hints"/> (null = unknown, or no such tool), for permission gates (SPEC §4C).
    /// For a ToolTiers <c>call_tool</c> call, Name/Args/Hints are the REAL tool's.</summary>
    public sealed record BeforeToolEvent(string Name, IDictionary<string, object?> Args, string? Id, int Turn, ToolHints? Hints = null);
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
        Toolkit? toolkit, string name, IDictionary<string, object?> args, string? id, int turn,
        CancellationToken cancellationToken = default)
    {
        if (_opts.ToolTiers != null && name == "tool_search" && toolkit != null)
        {
            // Engine-internal and read-only: no BeforeTool/AfterTool (SPEC §4C tool tiers).
            var ts = NowMs();
            var sr = ToolSearch(toolkit, args);
            EmitTool(name, "builtin", sr, ts);
            return (args, sr);
        }
        // call_tool (ToolTiers) is unwrapped HERE, so hooks, hints and permission gates see the
        // real tool. The returned args keep the wrapper shape (with any BeforeTool rewrite inside),
        // so a §10 re-run unwraps the same way.
        var (real, inner, wrapped) = UnwrapCall(name, args);
        var (ra, rr) = await RunToolDirectAsync(toolkit, real, inner, id, turn, cancellationToken).ConfigureAwait(false);
        if (wrapped) ra = new Dictionary<string, object?> { ["name"] = real, ["arguments"] = ra };
        return (ra, rr);
    }

    /// <summary>One call's safety; looks through <c>call_tool</c> to the tool it names. Unknown ⇒ unsafe.</summary>
    private bool CallSafe(Toolkit? toolkit, string name, IDictionary<string, object?> args)
    {
        if (_opts.ToolTiers != null && name == "tool_search") return true;
        var (real, inner, _) = UnwrapCall(name, args);
        var tool = toolkit?.Get(real);
        return ToolScheduler.IsConcurrencySafe(tool, inner);
    }

    private ToolScheduler NewToolScheduler() => new(ToolScheduler.ResolveMaxToolConcurrency(_opts.MaxToolConcurrency));

    /// <summary>The RunResult record for a tool call: a ToolTiers <c>call_tool</c> call records the REAL
    /// tool name and its inner arguments (after any BeforeTool rewrite), as hooks and stream events see
    /// it; the transcript keeps the wrapper. tool_search stays tool_search.</summary>
    private ToolCall ToolCallRecord(string name, IDictionary<string, object?> args, ToolResult result, string? callId)
    {
        var (real, inner, _) = UnwrapCall(name, args);
        return new ToolCall(real, inner, result.Output, result.IsError, result.Metadata, result.Parts, callId);
    }

    private async Task<(IDictionary<string, object?> Args, ToolResult Result)> RunToolDirectAsync(
        Toolkit? toolkit, string name, IDictionary<string, object?> args, string? id, int turn,
        CancellationToken cancellationToken)
    {
        var h = _opts.Hooks;
        var tool = toolkit?.Get(name);
        var source = tool?.Source ?? "custom";
        var t0 = NowMs();
        var a = args;
        if (h?.BeforeTool != null)
        {
            var ov = h.BeforeTool(new BeforeToolEvent(name, a, id, turn, tool?.Hints));
            if (ov?.Result != null)
            {
                // short-circuit (deny/cache/dry-run, or a guard-raised suspension — path B); still a
                // tool call for metrics, but a suspension is classified pending, never an error.
                EmitTool(name, source, ov.Result, t0);
                return (a, ov.Result);
            }
            if (ov?.Args != null) a = ov.Args;
        }
        var result = toolkit == null
            ? ToolResult.Error($"tool \"{name}\" not found: this run declared no toolkit")
            : await toolkit.ExecuteAsync(name, a, new ToolContext(cancellationToken: cancellationToken, callId: id)).ConfigureAwait(false);
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
        Toolkit? toolkit, string name, IDictionary<string, object?> args, Request request, string? id, int turn,
        CancellationToken cancellationToken = default)
    {
        (name, args, _) = UnwrapCall(name, args);
        if (_opts.WaitFor == null && _opts.WaitForCtx == null)
            return (ToolResult.Error(request.Prompt), request); // halt: durable host resumes later

        var answer = _opts.WaitForCtx != null
            ? await _opts.WaitForCtx(request, cancellationToken).ConfigureAwait(false)
            : await _opts.WaitFor!(request).ConfigureAwait(false);
        if (answer == null || !answer.Ok)
            return (ToolResult.Error($"declined/expired: {request.Prompt}"), null);

        var source = toolkit?.Get(name)?.Source ?? "custom";
        var t0 = NowMs();
        var result = toolkit == null
            ? ToolResult.Error($"tool \"{name}\" not found: this run declared no toolkit")
            : await toolkit.ExecuteAsync(name, args, new ToolContext(cancellationToken: cancellationToken, answer: answer, callId: id)).ConfigureAwait(false);
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
    private RunResult PendingRun(long runStart, Request request, List<object?> messages, List<ToolCall> toolCalls, int turns, Usage usage, string model)
    {
        Emit(MetricEvent.Run(model, turns, toolCalls.Count, usage.TotalTokens, NowMs() - runStart));
        return new RunResult(request.Prompt, messages, toolCalls, turns, usage, model, "pending", request);
    }

    // ---------------------------------------------------------------- results

    /// <summary>One tool call of a run. <paramref name="Parts"/> is the ToolResult's parts (null iff it had
    /// none); <paramref name="CallId"/> is the model's tool-call id (SPEC §8).</summary>
    public sealed record ToolCall(string Name, IDictionary<string, object?> Args, string Output, bool IsError,
        IDictionary<string, object?>? Metadata, IReadOnlyList<ContentPart>? Parts = null, string? CallId = null);

    /// <summary>(SPEC §8 / §7G) A non-text part a tool produced during a run, tagged with the tool name
    /// (<paramref name="From"/>) and the model's tool-call id (<paramref name="CallId"/>).</summary>
    public sealed record RunFile(string Type, string MimeType, string? Url, string? Data, string From, string? CallId, string? Name = null)
    {
        /// <summary>§7G.12 D20: the absolute local path when the part came from a media file saved
        /// locally; null otherwise. Never inside a part or anything sent to a model.</summary>
        public string? Path { get; init; }
    }

    /// <summary>The run-files function (§8 <c>RunResult.files</c>): every non-text part of
    /// <paramref name="toolCalls"/>, in call order.</summary>
    public static IReadOnlyList<RunFile> RunFilesOf(IEnumerable<ToolCall> toolCalls) => toolCalls
        .SelectMany(c => (c.Parts ?? Array.Empty<ContentPart>())
            .Where(p => p.Type != "text")
            .Select(p => new RunFile(p.Type, p.MimeType ?? "", p.Url, p.Data, c.Name, c.CallId, p.Name) { Path = p.LocalPath }))
        .ToList();

    public sealed class Usage
    {
        public long PromptTokens;
        public long CompletionTokens;
        public long TotalTokens;

        /// <summary>
        /// (SPEC §4C prompt caching) Prompt tokens served from the provider's prompt cache
        /// (anthropic <c>cache_read_input_tokens</c>; openai <c>prompt_tokens_details.cached_tokens</c>).
        /// Under anthropic <see cref="PromptTokens"/> EXCLUDES cached tokens, so the context actually
        /// sent is <c>PromptTokens + CacheReadTokens + CacheWriteTokens</c>; under openai
        /// <see cref="PromptTokens"/> already includes this (do not add it again).
        /// </summary>
        public long CacheReadTokens;

        /// <summary>Tokens written to the prompt cache (anthropic <c>cache_creation_input_tokens</c>;
        /// openai reports none).</summary>
        public long CacheWriteTokens;

        public Usage Copy() => new()
        {
            PromptTokens = PromptTokens, CompletionTokens = CompletionTokens, TotalTokens = TotalTokens,
            CacheReadTokens = CacheReadTokens, CacheWriteTokens = CacheWriteTokens,
        };
    }

    public sealed class RunResult
    {
        public string Text { get; }
        public List<object?> Messages { get; }
        public List<ToolCall> ToolCalls { get; }
        public int ToolCallCount => ToolCalls.Count;

        /// <summary>(SPEC §8) Every non-text part the run's tool calls produced, in call order — never
        /// null (<c>[]</c> when none). Derived from <see cref="ToolCalls"/>, so every run path fills it.</summary>
        public IReadOnlyList<RunFile> Files => RunFilesOf(ToolCalls);
        public int Turns { get; }
        public Usage Usage { get; }
        public string Model { get; }

        /// <summary>
        /// <c>"done"</c> — a final answer; <c>"pending"</c> (§10) — a tool suspended and no
        /// <c>WaitFor</c> was configured; <c>"incomplete"</c> (SPEC §8 addendum / §7D) — the loop
        /// stopped at <c>MaxTurns</c> while the model was still emitting tool calls. A limit stop is
        /// loud, never a silent done.
        /// </summary>
        public string Status { get; }

        /// <summary>§10: the unresolved suspension — present iff <see cref="Status"/> == "pending".</summary>
        public Request? Pending { get; }

        /// <summary>§7D: which limit stopped the run (e.g. <c>"maxTurns"</c>) — set ONLY when
        /// <see cref="Status"/> == "incomplete"; null otherwise.</summary>
        public string? Limit { get; }

        public RunResult(string text, List<object?> messages, List<ToolCall> toolCalls, int turns, Usage usage,
            string model, string status = "done", Request? pending = null, string? limit = null)
        {
            Text = text;
            Messages = messages;
            ToolCalls = toolCalls;
            Turns = turns;
            Usage = usage;
            Model = model;
            Status = status;
            Pending = pending;
            Limit = limit;
        }
    }

    // ---------------------------------------------------------------- streaming events

    public enum StreamKind { Text, ToolCall, ToolResult, Usage, Pending, Done }

    public sealed record StreamEvent(
        StreamKind Type, string? Delta = null, string? Id = null, string? Name = null,
        IDictionary<string, object?>? Args = null, string? Output = null, bool IsError = false,
        Usage? Usage = null, RunResult? Result = null, Request? Request = null,
        IDictionary<string, object?>? Metadata = null, string? Via = null)
    {
        /// <summary>§10: a tool is waiting on an out-of-band resolution (emitted before <c>WaitFor</c> runs).</summary>
        internal static StreamEvent PendingEvent(Request request) => new(StreamKind.Pending, Request: request);
        internal static StreamEvent Text(string delta) => new(StreamKind.Text, Delta: delta);
        internal static StreamEvent UsageEvent(Usage usage) => new(StreamKind.Usage, Usage: usage);
        internal static StreamEvent DoneEvent(RunResult result) => new(StreamKind.Done, Result: result);
    }

    // ---------------------------------------------------------------- public API

    public Task<RunResult> RunAsync(string prompt, Toolkit? toolkit, List<object?>? history = null, CancellationToken cancellationToken = default)
        => RunAsync((object)prompt, toolkit, history, cancellationToken);

    /// <summary>
    /// (§1B/§7) Multimodal entry: the prompt is a list of <see cref="ContentPart"/> in the same
    /// first position, so the caller's text/image ordering — which is semantic to a model — is
    /// preserved. With an implicit <c>string ⇒ ContentPart</c> lift and a collection expression
    /// this reads as
    /// <c>await client.RunAsync(["what's in this?", ContentPart.FromFile("shot.png")], toolkit)</c>.
    /// A text-only list assembles byte-identically to the string path.
    /// </summary>
    public Task<RunResult> RunAsync(IReadOnlyList<ContentPart> prompt, Toolkit? toolkit, List<object?>? history = null, CancellationToken cancellationToken = default)
        => RunAsync((object)prompt, toolkit, history, cancellationToken);

    private Task<RunResult> RunAsync(object prompt, Toolkit? toolkit, List<object?>? history, CancellationToken cancellationToken)
    {
        var deadline = new Deadline(_opts.TimeoutMs);
        return _opts.Style == "anthropic"
            ? RunAnthropicAsync(prompt, toolkit, history, deadline, cancellationToken)
            : RunOpenAIAsync(prompt, toolkit, history, deadline, cancellationToken);
    }

    /// <summary>
    /// Single-turn translation (§11): exactly ONE provider call, returned in OpenAI shape.
    /// No agent loop, no tool execution, no conversation state — every call is self-contained,
    /// so this may be run statelessly and concurrently.
    /// <para>
    /// The inbound half of the adapters: <c>ToAnthropic</c>/<c>ToGemini</c> send declarations
    /// out, this reads the provider's tool calls back in. A <c>Toolkit</c> on the request is
    /// DECLARED and never executed.
    /// </para>
    /// <para>
    /// Retries/backoff, request-param merging and the <c>llm</c> metric are shared with the
    /// loop. <c>BeforeLLM</c>/<c>AfterLLM</c> fire once; tool hooks never fire, as no tool runs.
    /// </para>
    /// </summary>
    public Task<Translate.Result> TranslateAsync(Translate.Request request, CancellationToken cancellationToken = default)
    {
        var deadline = new Deadline(_opts.TimeoutMs);
        return _opts.Style == "anthropic"
            ? TranslateAnthropicAsync(request, deadline, cancellationToken)
            : TranslateOpenAIAsync(request, deadline, cancellationToken);
    }

    /// <summary>OpenAI-style upstream: near-passthrough.</summary>
    private async Task<Translate.Result> TranslateOpenAIAsync(Translate.Request req, Deadline deadline, CancellationToken external)
    {
        var key = ResolveKey();
        var url = StripTrailingSlash(_opts.BaseUrl) + "/chat/completions";

        var messages = new List<object?>(req.Messages ?? new List<object?>());
        var sys = req.System ?? _opts.SystemPrompt ?? "";
        if (sys.Length > 0 && !Translate.HasSystemMessage(messages)) messages.Insert(0, Msg("system", sys));

        var declared = new List<Dictionary<string, object?>>();
        if (req.Toolkit != null) declared.AddRange(req.Toolkit.ToOpenAI());
        foreach (var t in req.Tools ?? new List<object?>())
            if (t is IDictionary<string, object?> tm) declared.Add(new Dictionary<string, object?>(tm));

        var turnModel = ApplyBeforeLLM(ref messages, ref declared, 0);

        var body = new Dictionary<string, object?> { ["model"] = turnModel, ["messages"] = messages };
        if (declared.Count > 0) body["tools"] = declared;
        if (req.ToolChoice != null) body["tool_choice"] = req.ToolChoice;
        if (req.MaxTokens > 0) body["max_tokens"] = req.MaxTokens;
        body = FinalizeBody(body);

        var data = await LlmCallJsonAsync(url, BaseHeaders(key, false), body, deadline, external, "openai").ConfigureAwait(false);
        _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(data, turnModel, 0));

        var result = new Translate.Result { Model = turnModel, Raw = data };
        AddUsage(result.Usage, data.Get("usage") as IDictionary<string, object?>, "openai");

        if (data.Get("choices") is not List<object?> choices || choices.Count == 0
            || choices[0] is not IDictionary<string, object?> choice)
        {
            result.FinishReason = "stop";
            return result;
        }
        if (choice.Get("message") is IDictionary<string, object?> message)
        {
            result.Text = message.Get("content") as string ?? "";
            result.ToolCalls = Translate.ToolCallsOf(message);
        }
        var fr = choice.Get("finish_reason") as string;
        result.FinishReason = string.IsNullOrEmpty(fr)
            ? Translate.FinishReasonFor(result.ToolCalls.Count > 0, null)
            : fr!;
        return result;
    }

    /// <summary>Anthropic-style upstream: the real translation.</summary>
    private async Task<Translate.Result> TranslateAnthropicAsync(Translate.Request req, Deadline deadline, CancellationToken external)
    {
        var key = ResolveKey();
        var endpoint = AnthropicEndpoint();

        var conv = Translate.OpenAIMessagesToAnthropic(req.Messages);
        var messages = new List<object?>(conv.Messages);
        var sys = req.System
                  ?? (!string.IsNullOrEmpty(_opts.SystemPrompt) ? _opts.SystemPrompt! : conv.System);

        var declared = new List<Dictionary<string, object?>>();
        if (req.Toolkit != null) declared.AddRange(req.Toolkit.ToAnthropic());
        declared.AddRange(Translate.OpenAIToolsToAnthropic(req.Tools));

        var turnModel = ApplyBeforeLLM(ref messages, ref declared, 0);

        var body = new Dictionary<string, object?>
        {
            ["model"] = turnModel,
            ["max_tokens"] = req.MaxTokens > 0 ? req.MaxTokens : 4096,
            ["messages"] = messages,
        };
        if (!string.IsNullOrEmpty(sys)) body["system"] = sys;
        if (declared.Count > 0) body["tools"] = declared;
        var nativeChoice = Translate.OpenAIToolChoiceToAnthropic(req.ToolChoice);
        if (nativeChoice != null) body["tool_choice"] = nativeChoice;
        body = FinalizeBody(body);

        var data = await LlmCallJsonAsync(endpoint, BaseHeaders(key, true), body, deadline, external, "anthropic").ConfigureAwait(false);
        _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(data, turnModel, 0));

        var result = new Translate.Result { Model = turnModel, Raw = data };
        AddUsage(result.Usage, data.Get("usage") as IDictionary<string, object?>, "anthropic");

        var text = new StringBuilder();
        foreach (var b in (data.Get("content") as List<object?> ?? new List<object?>()).OfType<IDictionary<string, object?>>())
        {
            switch (b.Get("type") as string)
            {
                case "text":
                    text.Append(b.Get("text") as string ?? "");
                    break;
                case "tool_use":
                    result.ToolCalls.Add(new Translate.ToolCall(
                        b.Get("id") as string ?? "",
                        b.Get("name") as string ?? "",
                        b.Get("input") is IDictionary<string, object?> input ? Json.Stringify(input) : "{}"));
                    break;
            }
        }
        result.Text = text.ToString();
        result.FinishReason = Translate.FinishReasonFor(result.ToolCalls.Count > 0, data.Get("stop_reason") as string);
        return result;
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
    public Task<RunResult> AskAsync(string prompt, Toolkit? toolkit, string? id = null,
        Action<string>? onText = null, CancellationToken cancellationToken = default)
        => AskAsync((object)prompt, toolkit, id, onText, cancellationToken);

    /// <summary>(§1B) Multimodal <see cref="AskAsync"/> — same memory semantics, parts prompt.</summary>
    public Task<RunResult> AskAsync(IReadOnlyList<ContentPart> prompt, Toolkit? toolkit, string? id = null,
        Action<string>? onText = null, CancellationToken cancellationToken = default)
        => AskAsync((object)prompt, toolkit, id, onText, cancellationToken);

    private async Task<RunResult> AskAsync(object prompt, Toolkit? toolkit, string? id,
        Action<string>? onText, CancellationToken cancellationToken)
    {
        if (onText != null)
            return await StreamAsync(prompt, toolkit,
                ev => { if (ev.Type == StreamKind.Text && ev.Delta != null) onText(ev.Delta); },
                id, cancellationToken).ConfigureAwait(false);

        if (id == null)
            return await RunAsync(prompt, toolkit, (List<object?>?)null, cancellationToken).ConfigureAwait(false);
        var history = await _store.GetAsync(id).ConfigureAwait(false) ?? new List<object?>();
        var intr = new Interrupt();
        RunResult result;
        try
        {
            var deadline = new Deadline(_opts.TimeoutMs);
            result = _opts.Style == "anthropic"
                ? await RunAnthropicAsync(prompt, toolkit, history, deadline, cancellationToken, intr).ConfigureAwait(false)
                : await RunOpenAIAsync(prompt, toolkit, history, deadline, cancellationToken, intr).ConfigureAwait(false);
        }
        catch
        {
            await SaveInterruptedAsync(id, intr, history, prompt, toolkit).ConfigureAwait(false);
            throw;
        }
        await _store.SaveAsync(id, result.Messages).ConfigureAwait(false);
        return result;
    }

    public Conversation NewConversation(Toolkit? toolkit) => new(this, toolkit);

    /// <summary>
    /// Streaming variant: <paramref name="onEvent"/> receives live events (text deltas, tool
    /// calls/results, usage, done) and the final <see cref="RunResult"/> is returned. With an
    /// <paramref name="id"/> it is stateful (like <see cref="AskAsync"/>): the thread's transcript is
    /// loaded as history before streaming, and saved back to the <see cref="IConversationStore"/> once
    /// the run terminates. No <paramref name="id"/> ⇒ stateless.
    /// </summary>
    public Task<RunResult> StreamAsync(string prompt, Toolkit? toolkit, Action<StreamEvent> onEvent,
        string? id = null, CancellationToken cancellationToken = default)
        => StreamAsync((object)prompt, toolkit, onEvent, id, cancellationToken);

    /// <summary>(§1B) Multimodal <see cref="StreamAsync"/> — same events, parts prompt.</summary>
    public Task<RunResult> StreamAsync(IReadOnlyList<ContentPart> prompt, Toolkit? toolkit, Action<StreamEvent> onEvent,
        string? id = null, CancellationToken cancellationToken = default)
        => StreamAsync((object)prompt, toolkit, onEvent, id, cancellationToken);

    private async Task<RunResult> StreamAsync(object prompt, Toolkit? toolkit, Action<StreamEvent> onEvent,
        string? id, CancellationToken cancellationToken)
    {
        var deadline = new Deadline(_opts.TimeoutMs);
        var history = id != null ? (await _store.GetAsync(id).ConfigureAwait(false) ?? new List<object?>()) : null;
        var intr = id != null ? new Interrupt() : null;
        RunResult result;
        try
        {
            result = _opts.Style == "anthropic"
                ? await StreamAnthropicAsync(prompt, toolkit, onEvent, history, deadline, cancellationToken, intr).ConfigureAwait(false)
                : await StreamOpenAIAsync(prompt, toolkit, onEvent, history, deadline, cancellationToken, intr).ConfigureAwait(false);
        }
        catch when (id != null)
        {
            await SaveInterruptedAsync(id, intr!, history!, prompt, toolkit).ConfigureAwait(false);
            throw;
        }
        if (id != null) await _store.SaveAsync(id, result.Messages).ConfigureAwait(false);
        return result;
    }

    // ---------------------------------------------------------------- interrupted turns (SPEC §7 memory)

    /// <summary>Carries the transcript a failed/cancelled loop had built, out of its catch block.</summary>
    private sealed class Interrupt { public List<object?>? Messages; }

    /// <summary>
    /// Save an interrupted stateful turn: the loop's sealed transcript, or — when it failed before
    /// building one — the prior transcript plus the user prompt. A store failure never masks the
    /// original error.
    /// </summary>
    private async Task SaveInterruptedAsync(string id, Interrupt intr, List<object?> history, object prompt, Toolkit? toolkit)
    {
        var messages = intr.Messages;
        if (messages == null)
        {
            messages = new List<object?>(history);
            if (history.Count == 0 && _opts.Style != "anthropic")
            {
                var system = System(toolkit);
                if (system.Length > 0) messages.Add(Msg("system", system));
            }
            AddUser(messages, prompt);
        }
        try { await _store.SaveAsync(id, messages).ConfigureAwait(false); }
        catch { /* the run's own error is the one the caller needs */ }
    }

    private const string InterruptedOutput = "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.";

    /// <summary>
    /// Close every unanswered tool call of the last assistant turn so the transcript stays valid in
    /// the active dialect: a finished (non-pending) result is used as-is, anything else gets
    /// <c>{isError:true, output:"interrupted: the turn ended before this tool returned. ..."}</c>.
    /// </summary>
    private List<object?> SealInterrupted(List<object?> source, IReadOnlyDictionary<string, ToolResult> done)
    {
        var messages = new List<object?>(source);
        ToolResult ResultFor(string? callId)
            => callId != null && done.TryGetValue(callId, out var r) && ToolResult.PendingOf(r) == null
                ? r : ToolResult.Error(InterruptedOutput);

        var last = messages.FindLastIndex(m => m is IDictionary<string, object?> d && (d.Get("role") as string) == "assistant");
        if (last < 0) return messages;
        var assistant = (IDictionary<string, object?>)messages[last]!;
        if (_opts.Style == "anthropic")
        {
            var useIds = (assistant.Get("content") as List<object?> ?? new List<object?>())
                .OfType<IDictionary<string, object?>>()
                .Where(b => (b.Get("type") as string) == "tool_use")
                .Select(b => b.Get("id")?.ToString()).ToList();
            if (useIds.Count == 0) return messages;
            var blocks = new List<object?>();
            if (last + 1 < messages.Count && messages[last + 1] is IDictionary<string, object?> next
                && (next.Get("role") as string) == "user" && next.Get("content") is List<object?> existing)
            {
                blocks.AddRange(existing);
                messages.RemoveAt(last + 1);
            }
            var answered = blocks.OfType<IDictionary<string, object?>>()
                .Where(b => (b.Get("type") as string) == "tool_result")
                .Select(b => b.Get("tool_use_id")?.ToString()).ToHashSet();
            foreach (var uid in useIds)
            {
                if (answered.Contains(uid)) continue;
                var r = ResultFor(uid);
                blocks.Add(new Dictionary<string, object?>
                {
                    ["type"] = "tool_result",
                    ["tool_use_id"] = uid,
                    ["content"] = AnthropicToolResultContent(r),
                    ["is_error"] = r.IsError,
                });
            }
            messages.Insert(last + 1, new Dictionary<string, object?> { ["role"] = "user", ["content"] = blocks });
            return messages;
        }
        if (assistant.Get("tool_calls") is not List<object?> { Count: > 0 } calls) return messages;
        var have = messages.Skip(last + 1).OfType<IDictionary<string, object?>>()
            .Where(m => (m.Get("role") as string) == "tool")
            .Select(m => m.Get("tool_call_id")?.ToString()).ToHashSet();
        foreach (var c in calls.OfType<IDictionary<string, object?>>())
        {
            var cid = c.Get("id")?.ToString();
            if (have.Contains(cid)) continue;
            messages.Add(new Dictionary<string, object?>
            {
                ["role"] = "tool",
                ["tool_call_id"] = cid,
                ["content"] = ToolMessageContent(ResultFor(cid)),
            });
        }
        return messages;
    }

    // ---------------------------------------------------------------- helpers

    private string System(Toolkit? toolkit)
    {
        var parts = new List<string>();
        if (!string.IsNullOrEmpty(_opts.SystemPrompt)) parts.Add(_opts.SystemPrompt!);
        // D1/#86: a toolkit-less completion is legal. The §0.10 system message is the ONE site
        // that dereferences the toolkit before anything touches tools; null-guard it here and the
        // whole no-tools path falls out (no `tools` key, no `tool_choice` key — not empty arrays).
        var sp = toolkit?.SkillsPrompt();
        if (!string.IsNullOrEmpty(sp)) parts.Add(sp);
        return string.Join("\n\n", parts);
    }

    private int MaxTurns() => _opts.MaxTurns ?? 10;

    private static string StripTrailingSlash(string s) => s.EndsWith('/') ? s[..^1] : s;

    private static Dictionary<string, object?> Msg(string role, string content)
        => new() { ["role"] = role, ["content"] = content };

    /// <summary>
    /// §8A: assemble a message from §1B parts. A text-only list collapses to the plain string
    /// form, so <c>["hello"]</c> is byte-identical to <c>"hello"</c>; anything else becomes the
    /// provider's block array, preserving the caller's ordering (which is semantic to a model).
    /// </summary>
    private Dictionary<string, object?> Msg(string role, IReadOnlyList<ContentPart> parts)
    {
        if (ContentEmit.AllText(parts)) return Msg(role, ContentEmit.JoinText(parts));
        return new Dictionary<string, object?>
        {
            ["role"] = role,
            ["content"] = EncodeParts(parts, ContentEmit.Provenance.Attached),
        };
    }

    /// <summary>Seed the transcript's user turn from either prompt form (§7 <c>run</c>).</summary>
    private void AddUser(List<object?> messages, object prompt)
    {
        if (prompt is IReadOnlyList<ContentPart> parts) messages.Add(Msg("user", parts));
        else messages.Add(Msg("user", prompt as string ?? prompt?.ToString() ?? ""));
    }

    /// <summary>Emitted at most once per client for a degraded (unrepresentable) part (§8A).</summary>
    private int _warnedUnsupportedPart;

    private void WarnOncePart(string message)
    {
        if (Interlocked.Exchange(ref _warnedUnsupportedPart, 1) == 0) Console.Error.WriteLine(message);
    }

    /// <summary>
    /// Encode parts to provider blocks, enforcing the §8A allowlist. <c>MaxPartBytes</c> is
    /// enforced inside <see cref="ContentEmit.Blocks"/> itself — at assembly, over every part
    /// regardless of provenance — so an oversize MCP-derived part cannot walk around it.
    /// </summary>
    private List<object?> EncodeParts(IEnumerable<ContentPart> parts, ContentEmit.Provenance provenance)
        => ContentEmit.Blocks(parts, _opts.Style, provenance, _opts.OnUnsupportedPart, WarnOncePart, _opts.MaxPartBytes);

    /// <summary>
    /// §8A relocation: the OpenAI <c>tool</c> message rejects an image (a hard 400), so it carries
    /// <c>output</c> plus any TEXT parts only, and every non-text part from every tool result
    /// answering one assistant turn is relocated, in tool-call order, into a SINGLE synthetic
    /// <c>user</c> message emitted immediately after the last tool message — each part preceded by
    /// a text part <c>Output of tool &lt;name&gt; (&lt;tool_call_id&gt;):</c>.
    /// <para>That synthetic message is an <b>adapter artifact only</b>: it is never appended to
    /// <c>messages</c>, so it never reaches <c>RunResult.Messages</c>, the
    /// <see cref="IConversationStore"/>, or <c>translate</c> output, and switching provider
    /// mid-conversation leaves no OpenAI-shaped residue.</para>
    /// </summary>
    private sealed class Relocated
    {
        /// <summary>Insertion index in <c>messages</c> ⇒ the synthetic user message to splice there.</summary>
        private readonly SortedDictionary<int, object?> _at = new();

        public bool Any => _at.Count > 0;

        public void Add(int index, object? message) => _at[index] = message;

        /// <summary>The wire message list: <paramref name="messages"/> with the synthetic messages
        /// spliced in. Returns the same list untouched when nothing was relocated.</summary>
        public List<object?> Wire(List<object?> messages)
        {
            if (_at.Count == 0) return messages;
            var wire = new List<object?>(messages.Count + _at.Count);
            for (var i = 0; i <= messages.Count; i++)
            {
                if (_at.TryGetValue(i, out var synth)) wire.Add(synth);
                if (i < messages.Count) wire.Add(messages[i]);
            }
            return wire;
        }
    }

    /// <summary>The OpenAI <c>tool</c> message content: the output string alone (byte-identical
    /// when there are no text parts), else an array of text blocks.</summary>
    private object? ToolMessageContent(ToolResult result)
    {
        var textParts = ContentEmit.TextOnly(result.Parts);
        if (textParts.Count == 0) return result.Output;
        var blocks = new List<object?> { new Dictionary<string, object?> { ["type"] = "text", ["text"] = result.Output } };
        foreach (var p in textParts)
            blocks.Add(new Dictionary<string, object?> { ["type"] = "text", ["text"] = p.Text ?? "" });
        return blocks;
    }

    /// <summary>Build the single synthetic user message for one assistant turn's relocated parts.</summary>
    private object? SyntheticUserMessage(List<(string Name, string? Id, IReadOnlyList<ContentPart> Parts)> relocations)
    {
        var blocks = new List<object?>();
        foreach (var (name, id, parts) in relocations)
        {
            if (parts.Count == 0) continue;
            blocks.Add(new Dictionary<string, object?>
            {
                ["type"] = "text",
                ["text"] = $"Output of tool {name} ({id}):",
            });
            blocks.AddRange(EncodeParts(parts, ContentEmit.Provenance.Derived));
        }
        return blocks.Count == 0 ? null : new Dictionary<string, object?> { ["role"] = "user", ["content"] = blocks };
    }

    /// <summary>Stage one turn's relocated parts as the synthetic user message that follows the
    /// last tool message at <paramref name="index"/> in the wire list.</summary>
    private void RecordRelocation(Relocated relocated,
        List<(string Name, string? Id, IReadOnlyList<ContentPart> Parts)> relocations, int index)
    {
        var synth = SyntheticUserMessage(relocations);
        if (synth != null) relocated.Add(index, synth);
        relocations.Clear();
    }

    /// <summary>The Anthropic <c>tool_result.content</c>: blocks natively, keyed to the
    /// <c>tool_use_id</c> — no relocation, so the association and cache breakpoints survive.</summary>
    private object? AnthropicToolResultContent(ToolResult result)
    {
        var parts = Media.ModelParts(result); // §7G.6: only image parts of a media result reach the model
        if (parts == null || parts.Count == 0) return result.Output;
        var blocks = new List<object?> { new Dictionary<string, object?> { ["type"] = "text", ["text"] = result.Output } };
        blocks.AddRange(EncodeParts(parts, ContentEmit.Provenance.Derived));
        return blocks;
    }

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
            acc.CacheReadTokens += AsLong(raw.Get("cache_read_input_tokens"));
            acc.CacheWriteTokens += AsLong(raw.Get("cache_creation_input_tokens"));
        }
        else
        {
            if (raw.Get("prompt_tokens_details") is IDictionary<string, object?> details)
                acc.CacheReadTokens += AsLong(details.Get("cached_tokens"));
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

    private async Task<RunResult> RunOpenAIAsync(object prompt, Toolkit? toolkit, List<object?>? history, Deadline deadline, CancellationToken external, Interrupt? intr = null)
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
        AddUser(messages, prompt);
        var tools = toolkit != null ? Adapters.ToOpenAI(RequestTools(toolkit)) : new List<Dictionary<string, object?>>();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;
        var runStart = NowMs();
        var lastModel = _opts.Model; // §8: the model of the last call this run made
        var finished = new ConcurrentDictionary<string, ToolResult>(); // finished tool results, for an interrupted save
        var relocated = new Relocated(); // §8A adapter artifact — never enters `messages`

        try
        {
            for (var turn = 0; turn < MaxTurns(); turn++)
            {
                turns++;
                var turnModel = ApplyBeforeLLM(ref messages, ref tools, turn);
                lastModel = turnModel;

                var body = new Dictionary<string, object?>
                {
                    ["model"] = turnModel,
                    ["messages"] = relocated.Wire(messages),
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
                _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(data, turnModel, turn));

                var choices = data.Get("choices") as List<object?> ?? new List<object?>();
                var message = (IDictionary<string, object?>)((IDictionary<string, object?>)choices[0]!)["message"]!;
                messages.Add(message);
                var calls = message.Get("tool_calls") as List<object?>;
                if (calls == null || calls.Count == 0)
                {
                    var content = message.Get("content");
                    return EndRun(runStart, content?.ToString() ?? "", messages, toolCalls, turns, usage, lastModel);
                }

                // Execute all tool calls in this turn concurrently.
                var n = calls.Count;
                var names = new string[n];
                var callIds = new string?[n];
                var runs = new (IDictionary<string, object?> Args, ToolResult Result)[n];
                var tasks = new Task[n];
                var sched = NewToolScheduler();
                for (var i = 0; i < n; i++)
                {
                    var idx = i;
                    var call = (IDictionary<string, object?>)calls[i]!;
                    var fn = (IDictionary<string, object?>)call["function"]!;
                    names[idx] = fn.Get("name")?.ToString() ?? "";
                    callIds[idx] = call.Get("id")?.ToString();
                    var args = Json.ParseObjectLoose(fn.Get("arguments")?.ToString() ?? "{}");
                    tasks[idx] = await sched.RunAsync(CallSafe(toolkit, names[idx], args), () => Task.Run(async () => { runs[idx] = await RunToolAsync(toolkit, names[idx], args, callIds[idx], turn, external).ConfigureAwait(false); if (!external.IsCancellationRequested && callIds[idx] is { } k) finished[k] = runs[idx].Result; }), external).ConfigureAwait(false);
                }
                await Task.WhenAll(tasks).WaitAsync(external).ConfigureAwait(false);
                external.ThrowIfCancellationRequested(); // a cancelled turn records no tool results; the save marks them interrupted

                // §10: resolve any suspensions (serially — WaitFor is a human-in-the-loop slot). Record
                // in tool-call order; on the FIRST durable halt, surface it and stop — deterministic, and
                // the later concurrent suspensions' placeholder results never enter the transcript (they
                // re-suspend on resume). Mirrors the streaming path.
                var relocations = new List<(string Name, string? Id, IReadOnlyList<ContentPart> Parts)>();
                for (var i = 0; i < n; i++)
                {
                    var req = ToolResult.PendingOf(runs[i].Result);
                    Request? halted = null;
                    if (req != null)
                    {
                        var r = await ResolvePendingAsync(toolkit, names[i], runs[i].Args, req, callIds[i], turn, external).ConfigureAwait(false);
                        runs[i] = (runs[i].Args, r.Result);
                        halted = r.Halted;
                    }
                    toolCalls.Add(ToolCallRecord(names[i], runs[i].Args, runs[i].Result, callIds[i]));
                    messages.Add(new Dictionary<string, object?>
                    {
                        ["role"] = "tool",
                        ["tool_call_id"] = callIds[i],
                        ["content"] = ToolMessageContent(runs[i].Result),
                    });
                    relocations.Add((names[i], callIds[i], ContentEmit.NonText(Media.ModelParts(runs[i].Result))));
                    if (halted != null)
                    {
                        RecordRelocation(relocated, relocations, messages.Count);
                        return PendingRun(runStart, halted, messages, toolCalls, turns, usage, lastModel);
                    }
                }
                RecordRelocation(relocated, relocations, messages.Count);
            }
            // MaxTurns exhausted while the model was still emitting tool calls (§8 addendum).
            return EndRun(runStart, LastAssistantText(messages), messages, toolCalls, turns, usage, lastModel, "incomplete");
        }
        catch (Exception e)
        {
            if (intr != null) intr.Messages = SealInterrupted(messages, finished);
            EmitRunError(runStart, toolCalls, turns, usage, e, lastModel);
            throw;
        }
    }

    // ---------------------------------------------------------------- Anthropic run

    private async Task<RunResult> RunAnthropicAsync(object prompt, Toolkit? toolkit, List<object?>? history, Deadline deadline, CancellationToken external, Interrupt? intr = null)
    {
        var key = ResolveKey();
        var endpoint = AnthropicEndpoint();
        var system = System(toolkit);
        var messages = new List<object?>();
        if (history is { Count: > 0 }) messages.AddRange(history);
        AddUser(messages, prompt);
        var tools = toolkit != null ? Adapters.ToAnthropic(RequestTools(toolkit)) : new List<Dictionary<string, object?>>();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;
        var runStart = NowMs();
        var lastModel = _opts.Model; // §8: the model of the last call this run made
        var finished = new ConcurrentDictionary<string, ToolResult>(); // finished tool results, for an interrupted save

        try
        {
            for (var turn = 0; turn < MaxTurns(); turn++)
            {
                turns++;
                var turnModel = ApplyBeforeLLM(ref messages, ref tools, turn);
                lastModel = turnModel;

                var body = new Dictionary<string, object?>
                {
                    ["model"] = turnModel,
                    ["max_tokens"] = 4096,
                    ["messages"] = messages,
                };
                if (system.Length > 0) body["system"] = system;
                // §8 Gap 5: omit tools when the effective tool list is empty.
                if (tools.Count > 0) body["tools"] = tools;
                body = FinalizeBody(body);

                var data = await LlmCallJsonAsync(endpoint, BaseHeaders(key, true), body, deadline, external, "anthropic").ConfigureAwait(false);
                AddUsage(usage, data.Get("usage") as IDictionary<string, object?>, "anthropic");
                _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(data, turnModel, turn));

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
                    return EndRun(runStart, text.ToString(), messages, toolCalls, turns, usage, lastModel);
                }

                var n = uses.Count;
                var names = new string[n];
                var useIds = new string?[n];
                var runs = new (IDictionary<string, object?> Args, ToolResult Result)[n];
                var tasks = new Task[n];
                var sched = NewToolScheduler();
                for (var i = 0; i < n; i++)
                {
                    var idx = i;
                    var use = uses[i];
                    var input = use.Get("input") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
                    names[idx] = use.Get("name")?.ToString() ?? "";
                    useIds[idx] = use.Get("id")?.ToString();
                    tasks[idx] = await sched.RunAsync(CallSafe(toolkit, names[idx], input), () => Task.Run(async () => { runs[idx] = await RunToolAsync(toolkit, names[idx], input, useIds[idx], turn, external).ConfigureAwait(false); if (!external.IsCancellationRequested && useIds[idx] is { } k) finished[k] = runs[idx].Result; }), external).ConfigureAwait(false);
                }
                await Task.WhenAll(tasks).WaitAsync(external).ConfigureAwait(false);
                external.ThrowIfCancellationRequested(); // a cancelled turn records no tool results; the save marks them interrupted

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
                        var r = await ResolvePendingAsync(toolkit, names[i], runs[i].Args, req, useIds[i], turn, external).ConfigureAwait(false);
                        runs[i] = (runs[i].Args, r.Result);
                        halted = r.Halted;
                    }
                    toolCalls.Add(ToolCallRecord(names[i], runs[i].Args, runs[i].Result, useIds[i]));
                    // §8A: native — the parts are blocks inside tool_result.content, keyed to the
                    // tool_use_id. No relocation: it would discard that association and break
                    // cache breakpoints.
                    resultBlocks.Add(new Dictionary<string, object?>
                    {
                        ["type"] = "tool_result",
                        ["tool_use_id"] = useIds[i],
                        ["content"] = AnthropicToolResultContent(runs[i].Result),
                        ["is_error"] = runs[i].Result.IsError,
                    });
                    if (halted != null)
                    {
                        messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = resultBlocks });
                        return PendingRun(runStart, halted, messages, toolCalls, turns, usage, lastModel);
                    }
                }
                messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = resultBlocks });
            }
            // MaxTurns exhausted while the model was still emitting tool calls (§8 addendum).
            return EndRun(runStart, "", messages, toolCalls, turns, usage, lastModel, "incomplete");
        }
        catch (Exception e)
        {
            if (intr != null) intr.Messages = SealInterrupted(messages, finished);
            EmitRunError(runStart, toolCalls, turns, usage, e, lastModel);
            throw;
        }
    }

    private string AnthropicEndpoint()
    {
        var b = StripTrailingSlash(_opts.BaseUrl);
        return b.EndsWith("/v1") ? b + "/messages" : b + "/v1/messages";
    }

    /// <summary>Runs BeforeLLM and returns the model for THIS turn: the override's non-empty
    /// <c>Model</c>, else the configured model verbatim.</summary>
    private string ApplyBeforeLLM(ref List<object?> messages, ref List<Dictionary<string, object?>> tools, int turn)
    {
        if (_opts.Hooks?.BeforeLLM == null) return _opts.Model;
        var ov = _opts.Hooks.BeforeLLM(new BeforeLLMEvent(messages, tools, _opts.Model, turn));
        if (ov?.Messages != null) messages = ov.Messages;
        if (ov?.Tools != null) tools = ov.Tools;
        return string.IsNullOrEmpty(ov?.Model) ? _opts.Model : ov!.Model!;
    }

    // ---------------------------------------------------------------- OpenAI stream

    private async Task<RunResult> StreamOpenAIAsync(object prompt, Toolkit? toolkit, Action<StreamEvent> onEvent, List<object?>? history, Deadline deadline, CancellationToken external, Interrupt? intr = null)
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
        AddUser(messages, prompt);
        var tools = toolkit != null ? Adapters.ToOpenAI(RequestTools(toolkit)) : new List<Dictionary<string, object?>>();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;
        var runStart = NowMs();
        var lastModel = _opts.Model; // §8: the model of the last call this run made
        var finished = new ConcurrentDictionary<string, ToolResult>(); // finished tool results, for an interrupted save
        var relocated = new Relocated(); // §8A adapter artifact — never enters `messages`

        try
        {
            for (var turn = 0; turn < MaxTurns(); turn++)
            {
                turns++;
                var turnModel = ApplyBeforeLLM(ref messages, ref tools, turn);
                lastModel = turnModel;
                var body = new Dictionary<string, object?>
                {
                    ["model"] = turnModel,
                    ["messages"] = relocated.Wire(messages),
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
                    Emit(MetricEvent.Llm(turnModel, "error", NowMs() - t0, 0, 0));
                    throw;
                }
                Emit(MetricEvent.Llm(turnModel, "ok", NowMs() - t0, usage.PromptTokens - beforeP, usage.CompletionTokens - beforeC));
                _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(new Dictionary<string, object?> { ["streamed"] = true }, turnModel, turn));

                if (order.Count == 0)
                {
                    messages.Add(Msg("assistant", content.ToString()));
                    onEvent(StreamEvent.UsageEvent(usage.Copy()));
                    var done0 = EndRun(runStart, content.ToString(), messages, toolCalls, turns, usage, lastModel);
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
                    onEvent(CallEvent(slot[0], slot[1], Json.ParseObjectLoose(slot[2])));
                }
                var n = order.Count;
                var runs = new (IDictionary<string, object?> Args, ToolResult Result)[n];
                var tasks = new Task[n];
                var sched = NewToolScheduler();
                for (var i = 0; i < n; i++)
                {
                    var idx = i;
                    var slot = acc[order[i]];
                    var args = Json.ParseObjectLoose(slot[2]);
                    tasks[idx] = await sched.RunAsync(CallSafe(toolkit, slot[1], args), () => Task.Run(async () => { runs[idx] = await RunToolAsync(toolkit, slot[1], args, slot[0], turn, external).ConfigureAwait(false); if (!external.IsCancellationRequested && slot[0] is { Length: > 0 } k) finished[k] = runs[idx].Result; }), external).ConfigureAwait(false);
                }
                await Task.WhenAll(tasks).WaitAsync(external).ConfigureAwait(false);
                external.ThrowIfCancellationRequested(); // a cancelled turn records no tool results; the save marks them interrupted
                // §10: resolve any suspensions in tool-call order; on the FIRST durable halt, record that
                // one tool result, surface the pending run and stop — later concurrent suspensions'
                // placeholder results never enter the transcript (they re-suspend on resume). Mirrors JS.
                var relocations = new List<(string Name, string? Id, IReadOnlyList<ContentPart> Parts)>();
                for (var i = 0; i < n; i++)
                {
                    var slot = acc[order[i]];
                    var req = ToolResult.PendingOf(runs[i].Result);
                    Request? halted = null;
                    if (req != null)
                    {
                        onEvent(StreamEvent.PendingEvent(req)); // surface BEFORE WaitFor so a channel can push the link
                        var r = await ResolvePendingAsync(toolkit, slot[1], runs[i].Args, req, slot[0], turn, external).ConfigureAwait(false);
                        runs[i] = (runs[i].Args, r.Result);
                        halted = r.Halted;
                    }
                    var rr = runs[i];
                    toolCalls.Add(ToolCallRecord(slot[1], rr.Args, rr.Result, slot[0]));
                    messages.Add(new Dictionary<string, object?> { ["role"] = "tool", ["tool_call_id"] = slot[0], ["content"] = ToolMessageContent(rr.Result) });
                    relocations.Add((slot[1], slot[0], ContentEmit.NonText(Media.ModelParts(rr.Result))));
                    if (halted != null)
                    {
                        RecordRelocation(relocated, relocations, messages.Count);
                        var p = PendingRun(runStart, halted, messages, toolCalls, turns, usage, lastModel);
                        onEvent(StreamEvent.DoneEvent(p));
                        return p;
                    }
                    onEvent(ResultEvent(slot[0], slot[1], Json.ParseObjectLoose(slot[2]), rr.Result));
                }
                RecordRelocation(relocated, relocations, messages.Count);
            }
            // MaxTurns exhausted while the model was still emitting tool calls (§8 addendum).
            var done = EndRun(runStart, LastAssistantText(messages), messages, toolCalls, turns, usage, lastModel, "incomplete");
            onEvent(StreamEvent.DoneEvent(done));
            return done;
        }
        catch (Exception e)
        {
            if (intr != null) intr.Messages = SealInterrupted(messages, finished);
            EmitRunError(runStart, toolCalls, turns, usage, e, lastModel);
            throw;
        }
    }

    // ---------------------------------------------------------------- Anthropic stream

    private async Task<RunResult> StreamAnthropicAsync(object prompt, Toolkit? toolkit, Action<StreamEvent> onEvent, List<object?>? history, Deadline deadline, CancellationToken external, Interrupt? intr = null)
    {
        var key = ResolveKey();
        var endpoint = AnthropicEndpoint();
        var system = System(toolkit);
        var messages = new List<object?>();
        if (history is { Count: > 0 }) messages.AddRange(history);
        AddUser(messages, prompt);
        var tools = toolkit != null ? Adapters.ToAnthropic(RequestTools(toolkit)) : new List<Dictionary<string, object?>>();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;
        var runStart = NowMs();
        var lastModel = _opts.Model; // §8: the model of the last call this run made
        var finished = new ConcurrentDictionary<string, ToolResult>(); // finished tool results, for an interrupted save

        try
        {
            for (var turn = 0; turn < MaxTurns(); turn++)
            {
                turns++;
                var turnModel = ApplyBeforeLLM(ref messages, ref tools, turn);
                lastModel = turnModel;
                var body = new Dictionary<string, object?>
                {
                    ["model"] = turnModel,
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
                    Emit(MetricEvent.Llm(turnModel, "error", NowMs() - t0, 0, 0));
                    throw;
                }
                Emit(MetricEvent.Llm(turnModel, "ok", NowMs() - t0, usage.PromptTokens - beforeP, usage.CompletionTokens - beforeC));
                _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(new Dictionary<string, object?> { ["streamed"] = true }, turnModel, turn));

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
                    var done0 = EndRun(runStart, text.ToString(), messages, toolCalls, turns, usage, lastModel);
                    onEvent(StreamEvent.DoneEvent(done0));
                    return done0;
                }

                var n = uses.Count;
                foreach (var u in uses)
                    onEvent(CallEvent(u["id"]?.ToString(), u["name"]?.ToString() ?? "", (IDictionary<string, object?>)u["input"]!));
                var runs = new (IDictionary<string, object?> Args, ToolResult Result)[n];
                var tasks = new Task[n];
                var sched = NewToolScheduler();
                for (var i = 0; i < n; i++)
                {
                    var idx = i;
                    var u = uses[i];
                    var input = u.Get("input") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
                    var name = u.Get("name")?.ToString() ?? "";
                    var id = u.Get("id")?.ToString();
                    tasks[idx] = await sched.RunAsync(CallSafe(toolkit, name, input), () => Task.Run(async () => { runs[idx] = await RunToolAsync(toolkit, name, input, id, turn, external).ConfigureAwait(false); if (!external.IsCancellationRequested && id is { } k) finished[k] = runs[idx].Result; }), external).ConfigureAwait(false);
                }
                await Task.WhenAll(tasks).WaitAsync(external).ConfigureAwait(false);
                external.ThrowIfCancellationRequested(); // a cancelled turn records no tool results; the save marks them interrupted
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
                        var rp = await ResolvePendingAsync(toolkit, name, runs[i].Args, req, id, turn, external).ConfigureAwait(false);
                        runs[i] = (runs[i].Args, rp.Result);
                        halted = rp.Halted;
                    }
                    var r = runs[i];
                    toolCalls.Add(ToolCallRecord(name, r.Args, r.Result, id));
                    results.Add(new Dictionary<string, object?>
                    {
                        ["type"] = "tool_result",
                        ["tool_use_id"] = id,
                        ["content"] = AnthropicToolResultContent(r.Result), // §8A native
                        ["is_error"] = r.Result.IsError,
                    });
                    if (halted != null)
                    {
                        messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = results });
                        var p = PendingRun(runStart, halted, messages, toolCalls, turns, usage, lastModel);
                        onEvent(StreamEvent.DoneEvent(p));
                        return p;
                    }
                    onEvent(ResultEvent(id, name, u.Get("input") as IDictionary<string, object?> ?? new Dictionary<string, object?>(), r.Result));
                }
                messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = results });
            }
            // MaxTurns exhausted while the model was still emitting tool calls (§8 addendum).
            var done = EndRun(runStart, "", messages, toolCalls, turns, usage, lastModel, "incomplete");
            onEvent(StreamEvent.DoneEvent(done));
            return done;
        }
        catch (Exception e)
        {
            if (intr != null) intr.Messages = SealInterrupted(messages, finished);
            EmitRunError(runStart, toolCalls, turns, usage, e, lastModel);
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
                throw new RunTimeoutException($"run timeout after {TimeoutMs}ms (Options.TimeoutMs budget)");
        }
    }

    private async Task<HttpResponseMessage> LlmSendAsync(string url, Dictionary<string, string> headers,
        Dictionary<string, object?> body, Deadline deadline, bool streaming, CancellationToken external)
    {
        var retries = Retries();
        var baseMs = RetryBaseMs();
        var payload = Json.Stringify(body);
        Exception? lastErr = null;
        // Default classifier = today's behavior, expressed as an OnError. A Retry is always capped by
        // the budget below (attempt == retries stops regardless of the classifier).
        var classify = _opts.OnError ?? (info => info.Retryable ? Tier.Retry : Tier.Fail);

        for (var attempt = 0; attempt <= retries; attempt++)
        {
            deadline.Check();
            external.ThrowIfCancellationRequested();

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(external);
            if (deadline.Bounded)
            {
                var remain = deadline.RemainingMs;
                if (remain <= 0) throw new RunTimeoutException($"run timeout after {deadline.TimeoutMs}ms (Options.TimeoutMs budget)");
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
                var retryable = IsRetryableStatus(status, _opts.RetryableStatuses);
                var tier = classify(new ErrorInfo(null, status, attempt, retryable));
                if (tier == Tier.Fail || attempt == retries) return res; // caller handles non-2xx
                var wait = RetryAfterMs(res) ?? (long)(baseMs * Math.Pow(2, attempt) + Random.Shared.Next(0, 100));
                res.Dispose();
                await SleepAsync(wait, deadline, external).ConfigureAwait(false);
            }
            // Cancellation classification (SPEC §7D cancellation contract): an EXTERNAL cancel is
            // classified by TOKEN STATE — it propagates as OperationCanceledException so callers
            // (e.g. the agent runtime's `interrupt`) branch on their token, never on exception
            // type. Only the run-deadline expiry becomes RunTimeoutException. Neither is retried.
            catch (OperationCanceledException) when (!external.IsCancellationRequested)
            {
                throw new RunTimeoutException($"run timeout after {deadline.TimeoutMs}ms (Options.TimeoutMs budget)"); // not retried
            }
            catch (HttpRequestException e)
            {
                lastErr = new InvalidOperationException("LLM request failed: " + e.Message, e);
                var tier = classify(new ErrorInfo(e, null, attempt, true)); // network errors are retryable by default
                if (tier == Tier.Fail || attempt == retries) throw lastErr;
                await SleepAsync((long)(baseMs * Math.Pow(2, attempt) + Random.Shared.Next(0, 100)), deadline, external).ConfigureAwait(false);
            }
        }
        throw lastErr ?? new InvalidOperationException("LLM request failed");
    }

    /// <summary>~68 years; the widest whole-second count all seven ports represent exactly.</summary>
    private const long RetryAfterMaxSeconds = 2147483647L;

    /// <summary>
    /// Honours <c>Retry-After</c> only in its delay-seconds form: a run of ASCII digits
    /// (RFC 9110 §10.2.3) in 0…2147483647. <c>long.TryParse</c> alone is too permissive —
    /// it accepts a leading sign, so <c>Retry-After: -5</c> parsed to −5000 ms and, since
    /// <c>SleepAsync</c> returns at once for a non-positive delay, produced an immediate
    /// retry against a server that had just asked for calm. Fractional, signed, HTTP-date
    /// and out-of-range values are not delays we can honour, so the caller falls back to
    /// backoff rather than guessing. Zero is a real answer — "retry now" — so it is
    /// returned as 0 rather than null.
    /// </summary>
    internal static long? RetryAfterMs(HttpResponseMessage res)
    {
        var v = RawRetryAfter(res)?.Trim();
        if (string.IsNullOrEmpty(v)) return null;
        foreach (var c in v) if (c < '0' || c > '9') return null;
        if (!long.TryParse(v, out var secs) || secs > RetryAfterMaxSeconds) return null;
        return secs * 1000L;
    }

    /// <summary>The raw <c>Retry-After</c> header, verbatim and unparsed, or null.</summary>
    internal static string? RawRetryAfter(HttpResponseMessage res)
        => res.Headers.TryGetValues("retry-after", out var values) ? values.FirstOrDefault() : null;

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
        var callModel = body.Get("model")?.ToString() ?? _opts.Model;
        var t0 = NowMs();
        try
        {
            var data = await PostJsonAsync(url, headers, body, deadline, external).ConfigureAwait(false);
            var tok = PerCall(data.Get("usage") as IDictionary<string, object?>, style);
            Emit(MetricEvent.Llm(callModel, "ok", NowMs() - t0, tok.Prompt, tok.Completion));
            return data;
        }
        catch (Exception)
        {
            Emit(MetricEvent.Llm(callModel, "error", NowMs() - t0, 0, 0));
            throw;
        }
    }

    private async Task<Dictionary<string, object?>> PostJsonAsync(string url, Dictionary<string, string> headers,
        Dictionary<string, object?> body, Deadline deadline, CancellationToken external)
    {
        using var res = await LlmSendAsync(url, headers, body, deadline, false, external).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(external).ConfigureAwait(false);
        if ((int)res.StatusCode is < 200 or >= 300)
        {
            var st = (int)res.StatusCode;
            throw new ProviderException(st, RedactBody(st, text, cap: false), RawRetryAfter(res),
                ProviderMessage(st, text));
        }
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
            var st = (int)res.StatusCode;
            throw new ProviderException(st, RedactBody(st, b, cap: false), RawRetryAfter(res),
                ProviderMessage(st, b));
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
        private readonly Toolkit? _toolkit;
        private List<object?> _messages = new();

        internal Conversation(LlmClient client, Toolkit? toolkit)
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
