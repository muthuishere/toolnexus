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
    private static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };
    private static readonly HashSet<int> Retryable = new() { 429, 500, 502, 503, 504 };

    private readonly Options _opts;

    /// <summary>Conversation provider for <see cref="AskAsync"/> — from <c>opts.Store</c>, else in-memory.</summary>
    private readonly IConversationStore _store;

    private LlmClient(Options opts)
    {
        _opts = opts;
        _store = opts.Store ?? new InMemoryConversationStore();
    }

    public static LlmClient Create(Options opts) => new(opts);

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
        var a = args;
        if (h?.BeforeTool != null)
        {
            var ov = h.BeforeTool(new BeforeToolEvent(name, a, id, turn));
            if (ov?.Result != null) return (a, ov.Result);       // short-circuit
            if (ov?.Args != null) a = ov.Args;
        }
        var result = await toolkit.ExecuteAsync(name, a).ConfigureAwait(false);
        if (h?.AfterTool != null)
        {
            var ov = h.AfterTool(new AfterToolEvent(name, a, result, id, turn));
            if (ov?.Result != null) result = ov.Result;
        }
        return (a, result);
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

        public RunResult(string text, List<object?> messages, List<ToolCall> toolCalls, int turns, Usage usage, string model)
        {
            Text = text;
            Messages = messages;
            ToolCalls = toolCalls;
            Turns = turns;
            Usage = usage;
            Model = model;
        }
    }

    // ---------------------------------------------------------------- streaming events

    public enum StreamKind { Text, ToolCall, ToolResult, Usage, Done }

    public sealed record StreamEvent(
        StreamKind Type, string? Delta = null, string? Id = null, string? Name = null,
        IDictionary<string, object?>? Args = null, string? Output = null, bool IsError = false,
        Usage? Usage = null, RunResult? Result = null)
    {
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
    public async Task<RunResult> AskAsync(string prompt, Toolkit toolkit, string? id = null, CancellationToken cancellationToken = default)
    {
        if (id == null)
            return await RunAsync(prompt, toolkit, null, cancellationToken).ConfigureAwait(false);
        var history = await _store.GetAsync(id).ConfigureAwait(false) ?? new List<object?>();
        var result = await RunAsync(prompt, toolkit, history, cancellationToken).ConfigureAwait(false);
        await _store.SaveAsync(id, result.Messages).ConfigureAwait(false);
        return result;
    }

    public Conversation NewConversation(Toolkit toolkit) => new(this, toolkit);

    public Task<RunResult> StreamAsync(string prompt, Toolkit toolkit, Action<StreamEvent> onEvent, CancellationToken cancellationToken = default)
    {
        var deadline = new Deadline(_opts.TimeoutMs);
        return _opts.Style == "anthropic"
            ? StreamAnthropicAsync(prompt, toolkit, onEvent, deadline, cancellationToken)
            : StreamOpenAIAsync(prompt, toolkit, onEvent, deadline, cancellationToken);
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

        for (var turn = 0; turn < MaxTurns(); turn++)
        {
            turns++;
            ApplyBeforeLLM(ref messages, ref tools, turn);

            var body = new Dictionary<string, object?>
            {
                ["model"] = _opts.Model,
                ["messages"] = messages,
                ["tools"] = tools,
                ["tool_choice"] = "auto",
            };
            var url = StripTrailingSlash(_opts.BaseUrl) + "/chat/completions";
            var data = await PostJsonAsync(url, BaseHeaders(key, false), body, deadline, external).ConfigureAwait(false);
            AddUsage(usage, data.Get("usage") as IDictionary<string, object?>, "openai");
            _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(data, _opts.Model, turn));

            var choices = data.Get("choices") as List<object?> ?? new List<object?>();
            var message = (IDictionary<string, object?>)((IDictionary<string, object?>)choices[0]!)["message"]!;
            messages.Add(message);
            var calls = message.Get("tool_calls") as List<object?>;
            if (calls == null || calls.Count == 0)
            {
                var content = message.Get("content");
                return new RunResult(content?.ToString() ?? "", messages, toolCalls, turns, usage, _opts.Model);
            }

            // Execute all tool calls in this turn concurrently.
            var n = calls.Count;
            var toolMsgs = new Dictionary<string, object?>[n];
            var recorded = new ToolCall[n];
            var tasks = new Task[n];
            for (var i = 0; i < n; i++)
            {
                var idx = i;
                var call = (IDictionary<string, object?>)calls[i]!;
                var fn = (IDictionary<string, object?>)call["function"]!;
                var fnName = fn.Get("name")?.ToString() ?? "";
                var args = Json.ParseObjectLoose(fn.Get("arguments")?.ToString() ?? "{}");
                var callId = call.Get("id")?.ToString();
                tasks[idx] = Task.Run(async () =>
                {
                    var run = await RunToolAsync(toolkit, fnName, args, callId, turn).ConfigureAwait(false);
                    recorded[idx] = new ToolCall(fnName, run.Args, run.Result.Output, run.Result.IsError, run.Result.Metadata);
                    toolMsgs[idx] = new Dictionary<string, object?>
                    {
                        ["role"] = "tool",
                        ["tool_call_id"] = callId,
                        ["content"] = run.Result.Output,
                    };
                });
            }
            await Task.WhenAll(tasks).ConfigureAwait(false);
            toolCalls.AddRange(recorded);
            messages.AddRange(toolMsgs);
        }
        return new RunResult(LastAssistantText(messages), messages, toolCalls, turns, usage, _opts.Model);
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

        for (var turn = 0; turn < MaxTurns(); turn++)
        {
            turns++;
            ApplyBeforeLLM(ref messages, ref tools, turn);

            var body = new Dictionary<string, object?>
            {
                ["model"] = _opts.Model,
                ["max_tokens"] = 4096,
                ["messages"] = messages,
                ["tools"] = tools,
            };
            if (system.Length > 0) body["system"] = system;

            var data = await PostJsonAsync(endpoint, BaseHeaders(key, true), body, deadline, external).ConfigureAwait(false);
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
                return new RunResult(text.ToString(), messages, toolCalls, turns, usage, _opts.Model);
            }

            var n = uses.Count;
            var resultBlocks = new Dictionary<string, object?>[n];
            var recorded = new ToolCall[n];
            var tasks = new Task[n];
            for (var i = 0; i < n; i++)
            {
                var idx = i;
                var use = uses[i];
                var input = use.Get("input") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
                var useName = use.Get("name")?.ToString() ?? "";
                var useId = use.Get("id")?.ToString();
                tasks[idx] = Task.Run(async () =>
                {
                    var run = await RunToolAsync(toolkit, useName, input, useId, turn).ConfigureAwait(false);
                    recorded[idx] = new ToolCall(useName, run.Args, run.Result.Output, run.Result.IsError, run.Result.Metadata);
                    resultBlocks[idx] = new Dictionary<string, object?>
                    {
                        ["type"] = "tool_result",
                        ["tool_use_id"] = useId,
                        ["content"] = run.Result.Output,
                        ["is_error"] = run.Result.IsError,
                    };
                });
            }
            await Task.WhenAll(tasks).ConfigureAwait(false);
            toolCalls.AddRange(recorded);
            messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = resultBlocks.Cast<object?>().ToList() });
        }
        return new RunResult("", messages, toolCalls, turns, usage, _opts.Model);
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

    private async Task<RunResult> StreamOpenAIAsync(string prompt, Toolkit toolkit, Action<StreamEvent> onEvent, Deadline deadline, CancellationToken external)
    {
        var key = ResolveKey();
        var messages = new List<object?>();
        var system = System(toolkit);
        if (system.Length > 0) messages.Add(Msg("system", system));
        messages.Add(Msg("user", prompt));
        var tools = toolkit.ToOpenAI();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;

        for (var turn = 0; turn < MaxTurns(); turn++)
        {
            turns++;
            ApplyBeforeLLM(ref messages, ref tools, turn);
            var body = new Dictionary<string, object?>
            {
                ["model"] = _opts.Model,
                ["messages"] = messages,
                ["tools"] = tools,
                ["tool_choice"] = "auto",
                ["stream"] = true,
                ["stream_options"] = new Dictionary<string, object?> { ["include_usage"] = true },
            };
            var url = StripTrailingSlash(_opts.BaseUrl) + "/chat/completions";

            var content = new StringBuilder();
            var acc = new Dictionary<int, string[]>(); // index -> [id, name, args]
            var order = new List<int>();

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
            _opts.Hooks?.AfterLLM?.Invoke(new AfterLLMEvent(new Dictionary<string, object?> { ["streamed"] = true }, _opts.Model, turn));

            if (order.Count == 0)
            {
                messages.Add(Msg("assistant", content.ToString()));
                onEvent(StreamEvent.UsageEvent(usage.Copy()));
                var done0 = new RunResult(content.ToString(), messages, toolCalls, turns, usage, _opts.Model);
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
            for (var i = 0; i < n; i++)
            {
                var slot = acc[order[i]];
                var r = runs[i];
                toolCalls.Add(new ToolCall(slot[1], r.Args, r.Result.Output, r.Result.IsError, r.Result.Metadata));
                messages.Add(new Dictionary<string, object?> { ["role"] = "tool", ["tool_call_id"] = slot[0], ["content"] = r.Result.Output });
                onEvent(StreamEvent.ToolResultEvent(slot[0], slot[1], r.Result.Output, r.Result.IsError));
            }
        }
        var done = new RunResult(LastAssistantText(messages), messages, toolCalls, turns, usage, _opts.Model);
        onEvent(StreamEvent.DoneEvent(done));
        return done;
    }

    // ---------------------------------------------------------------- Anthropic stream

    private async Task<RunResult> StreamAnthropicAsync(string prompt, Toolkit toolkit, Action<StreamEvent> onEvent, Deadline deadline, CancellationToken external)
    {
        var key = ResolveKey();
        var endpoint = AnthropicEndpoint();
        var system = System(toolkit);
        var messages = new List<object?> { Msg("user", prompt) };
        var tools = toolkit.ToAnthropic();
        var toolCalls = new List<ToolCall>();
        var usage = new Usage();
        var turns = 0;

        for (var turn = 0; turn < MaxTurns(); turn++)
        {
            turns++;
            ApplyBeforeLLM(ref messages, ref tools, turn);
            var body = new Dictionary<string, object?>
            {
                ["model"] = _opts.Model,
                ["max_tokens"] = 4096,
                ["messages"] = messages,
                ["tools"] = tools,
                ["stream"] = true,
            };
            if (system.Length > 0) body["system"] = system;

            var blocks = new Dictionary<int, Dictionary<string, object?>>();
            var order = new List<int>();
            var stopReason = "";

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
                var done0 = new RunResult(text.ToString(), messages, toolCalls, turns, usage, _opts.Model);
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
            var results = new List<object?>(n);
            for (var i = 0; i < n; i++)
            {
                var u = uses[i];
                var r = runs[i];
                var name = u.Get("name")?.ToString() ?? "";
                var id = u.Get("id")?.ToString();
                toolCalls.Add(new ToolCall(name, r.Args, r.Result.Output, r.Result.IsError, r.Result.Metadata));
                results.Add(new Dictionary<string, object?>
                {
                    ["type"] = "tool_result",
                    ["tool_use_id"] = id,
                    ["content"] = r.Result.Output,
                    ["is_error"] = r.Result.IsError,
                });
                onEvent(StreamEvent.ToolResultEvent(id, name, r.Result.Output, r.Result.IsError));
            }
            messages.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = results });
        }
        var done = new RunResult("", messages, toolCalls, turns, usage, _opts.Model);
        onEvent(StreamEvent.DoneEvent(done));
        return done;
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
                var res = await Http.SendAsync(req, completion, cts.Token).ConfigureAwait(false);
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
