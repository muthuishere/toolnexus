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
        /// <summary>
        /// (SPEC §8C) A model that streams: call <c>emit</c> as the answer arrives and return the
        /// whole answer at the end. A <c>Text</c> event becomes a content delta the instant it is
        /// emitted; any other event (a <c>Thought</c>, or a tool call/result the model ran itself)
        /// goes straight to the stream consumer and never through the wire, so the loop never
        /// executes it. Without this, <c>StreamAsync</c> on a plain <c>Generate</c> refuses.
        /// When both are set, <c>Generate</c> serves non-streamed turns; when only this is set it
        /// serves them too, with a no-op <c>emit</c>.
        /// </summary>
        public Func<Request, Action<LlmClient.StreamEvent>, CancellationToken, Task<Response>>? GenerateStream { get; set; }
        public string? SystemPrompt { get; set; }
        public int? MaxTurns { get; set; }
        public int? MaxToolConcurrency { get; set; }
        public LlmClient.Hooks? Hooks { get; set; }
        public long? TimeoutMs { get; set; }
        public IConversationStore? Store { get; set; }
        public Action<MetricEvent>? OnMetric { get; set; }
        public Func<Toolnexus.Request, Task<Answer>>? WaitFor { get; set; }
        /// <summary>(SPEC §4C) <c>WaitFor</c> with the turn's cancellation token; wins over <c>WaitFor</c>.</summary>
        public Func<Toolnexus.Request, CancellationToken, Task<Answer>>? WaitForCtx { get; set; }
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
        if (opts is null || (opts.Generate is null && opts.GenerateStream is null))
            throw new ArgumentException("toolnexus: InProcess.CreateClient requires a `Generate` (or `GenerateStream`) function");
        var handler = new GenerateBackedHandler(opts.Generate, opts.GenerateStream);

        var o = new LlmClient.Options
        {
            BaseUrl = BaseUrl,
            Style = "openai",
            // An in-process model has no endpoint to authenticate to, so the host must never need a key — but the client resolves one from the environment and fails when it finds none. A sentinel keeps that resolution from ever running. Caught by CI, which has no OPENROUTER_API_KEY; every local run passed because a developer shell has one.
            ApiKey = "in-process",
            Model = opts.Model,
            HttpHandler = handler,
            SystemPrompt = opts.SystemPrompt,
            Hooks = opts.Hooks,
            Store = opts.Store,
            OnMetric = opts.OnMetric,
            WaitFor = opts.WaitFor,
            WaitForCtx = opts.WaitForCtx,
            RequestParams = opts.RequestParams is null ? null : new Dictionary<string, object?>(opts.RequestParams),
            // Zero retries by default: there is no wire, so there is no transient failure to ride
            // out, and retrying only buys backoff before the caller sees their own bug.
            Retries = opts.Retries ?? 0,
        };
        // Only when set: a 0 here is not "no timeout", it is "already expired".
        if (opts.MaxTurns is > 0) o.MaxTurns = opts.MaxTurns.Value;
        if (opts.MaxToolConcurrency is > 0) o.MaxToolConcurrency = opts.MaxToolConcurrency.Value;
        if (opts.TimeoutMs is > 0) o.TimeoutMs = opts.TimeoutMs.Value;
        return LlmClient.Create(o);
    }

    /// <summary>
    /// The adapter that turns a semantic <c>Generate</c> function into an
    /// <see cref="HttpMessageHandler"/> (SPEC §8 Gap 2, ADR 0030). Public so ANY caller that needs
    /// an in-process handler — not only <see cref="CreateClient"/> — can construct the same
    /// adapter instead of duplicating this translation; the agent runtime's semantic in-process
    /// option (<c>RuntimeOptions.InProcess</c>) is built by calling this constructor directly.
    /// </summary>
    public sealed class GenerateBackedHandler : HttpMessageHandler
    {
        private readonly Func<Request, Response>? _generate;
        private readonly Func<Request, Action<LlmClient.StreamEvent>, CancellationToken, Task<Response>>? _stream;
        public GenerateBackedHandler(Func<Request, Response> generate) => _generate = generate;

        /// <summary>A handler over a plain and/or a streaming generate (at least one required).</summary>
        public GenerateBackedHandler(Func<Request, Response>? generate,
            Func<Request, Action<LlmClient.StreamEvent>, CancellationToken, Task<Response>>? generateStream)
        {
            if (generate is null && generateStream is null)
                throw new ArgumentException("toolnexus: GenerateBackedHandler requires a `Generate` or `GenerateStream` function");
            _generate = generate;
            _stream = generateStream;
        }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var raw = request.Content is null
                ? "{}" : await request.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(raw) ? "{}" : raw);
            var root = doc.RootElement;

            // A Generate returns a whole answer, so a stream would be one chunk pretending to be
            // many — indistinguishable from a real stream by content or delta count. Refuse.
            var streaming = root.TryGetProperty("stream", out var s) && s.ValueKind == JsonValueKind.True;
            if (streaming && _stream != null)
                return StreamResponse(root, raw, cancellationToken);
            if (streaming)
                throw new NotSupportedException(
                    "toolnexus: InProcess.CreateClient does not support streaming — `Generate` returns "
                    + "a complete answer. Use RunAsync, or supply an HttpHandler that streams.");

            // Force a real async suspension before running the callback. `ReadAsStringAsync` above
            // completes synchronously for the small in-memory request bodies this adapter always
            // sees, so without this yield the entire call — including whatever `Generate` does —
            // would run inline on the caller's thread. That is invisible to a bare top-level client
            // call, but it is NOT invisible to a caller like the agent runtime, which invokes this
            // handler from inside a lock it means to release before the turn runs (see
            // AgentRuntime.Wake): an inline `Generate` would hold that lock for the callback's full
            // duration and silently serialize turns that should run concurrently.
            await Task.Yield();

            var ipReq = BuildRequest(root, raw);
            var answer = (_generate != null
                ? _generate(ipReq)
                : await _stream!(ipReq, static _ => { }, cancellationToken).ConfigureAwait(false))
                ?? Response.FromContent("");

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

        private static Request BuildRequest(JsonElement root, string raw) => new()
        {
            Messages = ToList(root, "messages"),
            Tools = ToList(root, "tools"),
            Model = root.TryGetProperty("model", out var m) ? m.GetString() ?? "" : "",
            Body = JsonSerializer.Deserialize<Dictionary<string, object?>>(raw)
                   ?? new Dictionary<string, object?>(),
        };

        /// <summary>
        /// Runs <c>GenerateStream</c> in the background and answers at once with an OpenAI SSE body
        /// fed as it emits: each non-empty text event is a <c>delta.content</c> chunk the moment it
        /// arrives; other events go to the stream consumer directly (never through the wire, so the
        /// loop never executes them). After it returns: tool calls ⇒ one tool_calls delta, else —
        /// only if no text was emitted — the content as one delta; then finish_reason, usage, [DONE].
        /// </summary>
        private HttpResponseMessage StreamResponse(JsonElement root, string raw, CancellationToken ct)
        {
            var ipReq = BuildRequest(root, raw);
            var side = LlmClient.StreamSide.Value;
            var ch = System.Threading.Channels.Channel.CreateUnbounded<byte[]>(
                new System.Threading.Channels.UnboundedChannelOptions { SingleReader = true });
            void Chunk(object v) => ch.Writer.TryWrite(Encoding.UTF8.GetBytes("data: " + JsonSerializer.Serialize(v) + "\n\n"));
            static Dictionary<string, object?> Delta(Dictionary<string, object?> delta, string? finish = null)
            {
                var c = new Dictionary<string, object?> { ["index"] = 0, ["delta"] = delta };
                if (finish != null) c["finish_reason"] = finish;
                return new Dictionary<string, object?> { ["choices"] = new object[] { c } };
            }
            _ = Task.Run(async () =>
            {
                try
                {
                    var sawText = false;
                    var answer = await _stream!(ipReq, ev =>
                    {
                        if (ev is null) return;
                        if (ev.Type == LlmClient.StreamKind.Text)
                        {
                            if (!string.IsNullOrEmpty(ev.Delta))
                            {
                                sawText = true;
                                Chunk(Delta(new() { ["content"] = ev.Delta }));
                            }
                            return;
                        }
                        side?.Invoke(ev);
                    }, ct).ConfigureAwait(false) ?? Response.FromContent("");
                    var finish = "stop";
                    if (answer.ToolCalls is { Count: > 0 })
                    {
                        var calls = new List<object?>();
                        for (var i = 0; i < answer.ToolCalls.Count; i++)
                        {
                            var c = answer.ToolCalls[i];
                            calls.Add(new Dictionary<string, object?>
                            {
                                ["index"] = i, ["id"] = c.Id ?? $"call_{i}", ["type"] = "function",
                                ["function"] = new Dictionary<string, object?> { ["name"] = c.Name, ["arguments"] = EncodeArgs(c.Arguments) },
                            });
                        }
                        Chunk(Delta(new() { ["tool_calls"] = calls }));
                        finish = "tool_calls";
                    }
                    else if (!sawText && !string.IsNullOrEmpty(answer.Content))
                    {
                        Chunk(Delta(new() { ["content"] = answer.Content }));
                    }
                    Chunk(Delta(new(), finish));
                    if (answer.Usage is { } u)
                    {
                        var total = u.TotalTokens > 0 ? u.TotalTokens : u.PromptTokens + u.CompletionTokens;
                        Chunk(new Dictionary<string, object?>
                        {
                            ["choices"] = Array.Empty<object>(),
                            ["usage"] = new Dictionary<string, object?>
                            {
                                ["prompt_tokens"] = u.PromptTokens, ["completion_tokens"] = u.CompletionTokens, ["total_tokens"] = total,
                            },
                        });
                    }
                    ch.Writer.TryWrite(Encoding.UTF8.GetBytes("data: [DONE]\n\n"));
                    ch.Writer.TryComplete();
                }
                catch (Exception e)
                {
                    ch.Writer.TryComplete(e);
                }
            });
            var content = new StreamContent(new ChannelReadStream(ch.Reader));
            content.Headers.TryAddWithoutValidation("Content-Type", "text/event-stream");
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = content };
        }

        /// <summary>A read-only stream over a channel of byte chunks; a faulted channel rethrows its error.</summary>
        private sealed class ChannelReadStream : Stream
        {
            private readonly System.Threading.Channels.ChannelReader<byte[]> _r;
            private byte[]? _cur;
            private int _pos;
            public ChannelReadStream(System.Threading.Channels.ChannelReader<byte[]> r) => _r = r;
            public override bool CanRead => true;
            public override bool CanSeek => false;
            public override bool CanWrite => false;
            public override long Length => throw new NotSupportedException();
            public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
            public override void Flush() { }
            public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
            public override void SetLength(long value) => throw new NotSupportedException();
            public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
            public override int Read(byte[] buffer, int offset, int count) =>
                ReadAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();
            public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
                ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();
            public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
            {
                while (_cur == null || _pos >= _cur.Length)
                {
                    if (!await _r.WaitToReadAsync(ct).ConfigureAwait(false)) return 0;
                    if (_r.TryRead(out var next)) { _cur = next; _pos = 0; }
                }
                var n = Math.Min(buffer.Length, _cur.Length - _pos);
                _cur.AsMemory(_pos, n).CopyTo(buffer);
                _pos += n;
                return n;
            }
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
