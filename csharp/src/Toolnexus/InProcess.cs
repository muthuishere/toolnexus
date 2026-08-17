using System.Text;
using System.Text.Json;

namespace Toolnexus;

/// <summary>
/// In-process models (SPEC §8 Gap 2, semantic form).
///
/// <para>A model running in this process is not a network endpoint, so it should not need a base
/// URL, an API key, a style, or an <see cref="HttpMessageHandler"/>. You supply
/// <c>Generate</c>; everything HTTP-shaped is built here.</para>
///
/// <para>This is a second constructor, not a second seam: it is built on the shipped injectable
/// transport, so the tool-calling loop, MCP servers, skills, sub-agents, hooks, metrics and the
/// completion gate behave identically.</para>
/// </summary>
public static class InProcess
{
    /// <summary>
    /// One tool call an in-process model asks for. Flat on purpose: the nested <c>function:{}</c>
    /// wrapper is a wire detail. <c>Arguments</c> may be any value (encoded for you) or an
    /// already-encoded string, passed through unchanged.
    /// </summary>
    public sealed class ToolCall
    {
        public string? Id { get; set; }
        public string Name { get; set; } = "";
        public object? Arguments { get; set; }
    }

    /// <summary>Optional token reporting. Absent ⇒ the run reports zero rather than failing.</summary>
    public sealed class Usage
    {
        public int PromptTokens { get; set; }
        public int CompletionTokens { get; set; }
        public int TotalTokens { get; set; }
    }

    /// <summary>What an in-process model is handed: the assembled request for THIS call.</summary>
    public sealed class Request
    {
        public IReadOnlyList<object?> Messages { get; init; } = Array.Empty<object?>();
        public IReadOnlyList<object?> Tools { get; init; } = Array.Empty<object?>();
        public string Model { get; init; } = "";
        /// <summary>Every key the client assembled, for a model that wants them.</summary>
        public IReadOnlyDictionary<string, object?> Body { get; init; } =
            new Dictionary<string, object?>();
    }

    /// <summary>Exactly one assistant message. Set Content to finish, or ToolCalls to ask for tools.</summary>
    public sealed class Response
    {
        public string? Content { get; set; }
        public List<ToolCall>? ToolCalls { get; set; }
        public Usage? Usage { get; set; }

        public static Response FromContent(string text) => new() { Content = text };
        public static Response FromToolCalls(params ToolCall[] calls) => new() { ToolCalls = calls.ToList() };
    }

    /// <summary>Every client option EXCEPT the three that describe a wire, plus <c>Generate</c>.</summary>
    public sealed class Options
    {
        public string Model { get; set; } = "";
        public Func<Request, Response>? Generate { get; set; }
        public string? SystemPrompt { get; set; }
        public int? MaxTurns { get; set; }
        public LlmClient.Hooks? Hooks { get; set; }
        public long? TimeoutMs { get; set; }
        public IConversationStore? Store { get; set; }
        public Action<MetricEvent>? OnMetric { get; set; }
        public Func<Toolnexus.Request, Task<Answer>>? WaitFor { get; set; }
        public IDictionary<string, object?>? RequestParams { get; set; }
        /// <summary>Defaults to 0 for an in-process client. Set only if your model is genuinely flaky.</summary>
        public int? Retries { get; set; }
    }

    /// <summary>
    /// A sentinel. Never dialled — the handler answers every request before the network is reached —
    /// but a URL string is built internally, so it must be syntactically valid. <c>.invalid</c> is
    /// reserved by RFC 2606 precisely so a name can never resolve.
    /// </summary>
    private const string BaseUrl = "http://in-process.invalid/v1";

    /// <summary>Build a client backed by a model running IN THIS PROCESS.</summary>
    public static LlmClient CreateClient(Options opts)
    {
        if (opts?.Generate is null)
            throw new ArgumentException("toolnexus: InProcess.CreateClient requires a `Generate` function");

        var o = new LlmClient.Options
        {
            BaseUrl = BaseUrl,
            Style = "openai",
            Model = opts.Model,
            HttpHandler = new GenerateBackedHandler(opts.Generate),
            SystemPrompt = opts.SystemPrompt,
            Hooks = opts.Hooks,
            Store = opts.Store,
            OnMetric = opts.OnMetric,
            WaitFor = opts.WaitFor,
            RequestParams = opts.RequestParams is null ? null : new Dictionary<string, object?>(opts.RequestParams),
            // Zero retries by default: there is no wire, so there is no transient failure to ride
            // out, and retrying only buys backoff before the caller sees their own bug.
            Retries = opts.Retries ?? 0,
        };
        // Only when set: a 0 here is not "no timeout", it is "already expired".
        if (opts.MaxTurns is > 0) o.MaxTurns = opts.MaxTurns.Value;
        if (opts.TimeoutMs is > 0) o.TimeoutMs = opts.TimeoutMs.Value;
        return LlmClient.Create(o);
    }

    private sealed class GenerateBackedHandler : HttpMessageHandler
    {
        private readonly Func<Request, Response> _generate;
        public GenerateBackedHandler(Func<Request, Response> generate) => _generate = generate;

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var raw = request.Content is null
                ? "{}" : await request.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(raw) ? "{}" : raw);
            var root = doc.RootElement;

            // A Generate returns a whole answer, so a stream would be one chunk pretending to be
            // many — indistinguishable from a real stream by content or delta count. Refuse.
            if (root.TryGetProperty("stream", out var s) && s.ValueKind == JsonValueKind.True)
                throw new NotSupportedException(
                    "toolnexus: InProcess.CreateClient does not support streaming — `Generate` returns "
                    + "a complete answer. Use RunAsync, or supply an HttpHandler that streams.");

            var answer = _generate(new Request
            {
                Messages = ToList(root, "messages"),
                Tools = ToList(root, "tools"),
                Model = root.TryGetProperty("model", out var m) ? m.GetString() ?? "" : "",
                Body = JsonSerializer.Deserialize<Dictionary<string, object?>>(raw)
                       ?? new Dictionary<string, object?>(),
            }) ?? Response.FromContent("");

            var sb = new StringBuilder();
            sb.Append("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"");
            if (answer.ToolCalls is { Count: > 0 })
            {
                sb.Append(",\"tool_calls\":[");
                for (var i = 0; i < answer.ToolCalls.Count; i++)
                {
                    var c = answer.ToolCalls[i];
                    if (i > 0) sb.Append(',');
                    sb.Append("{\"id\":").Append(JsonSerializer.Serialize(c.Id ?? $"call_{i}"))
                      .Append(",\"type\":\"function\",\"function\":{\"name\":")
                      .Append(JsonSerializer.Serialize(c.Name))
                      .Append(",\"arguments\":").Append(JsonSerializer.Serialize(EncodeArgs(c.Arguments)))
                      .Append("}}");
                }
                sb.Append(']');
            }
            else
            {
                sb.Append(",\"content\":").Append(JsonSerializer.Serialize(answer.Content ?? ""));
            }
            var finish = answer.ToolCalls is { Count: > 0 } ? "tool_calls" : "stop";

            var prompt = answer.Usage?.PromptTokens ?? 0;
            var completion = answer.Usage?.CompletionTokens ?? 0;
            var total = answer.Usage?.TotalTokens is > 0 ? answer.Usage!.TotalTokens : prompt + completion;

            sb.Append("},\"finish_reason\":\"").Append(finish).Append("\"}],\"usage\":{")
              .Append("\"prompt_tokens\":").Append(prompt)
              .Append(",\"completion_tokens\":").Append(completion)
              .Append(",\"total_tokens\":").Append(total).Append("}}");

            return new HttpResponseMessage(System.Net.HttpStatusCode.OK)
            {
                Content = new StringContent(sb.ToString(), Encoding.UTF8, "application/json"),
            };
        }

        private static IReadOnlyList<object?> ToList(JsonElement root, string name) =>
            root.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.Array
                ? JsonSerializer.Deserialize<List<object?>>(el.GetRawText()) ?? new List<object?>()
                : new List<object?>();

        /// <summary>Pass an already-encoded string through; encode anything else.</summary>
        private static string EncodeArgs(object? v) => v switch
        {
            null => "{}",
            string s => s,
            _ => JsonSerializer.Serialize(v),
        };
    }
}
