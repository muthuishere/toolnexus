using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace Toolnexus.Acp;

/// <summary>
/// A warm connection to an ACP (Agent Client Protocol) agent — <c>devin acp</c>, Gemini CLI,
/// Zed's agents — spoken to as a child process over JSON-RPC 2.0, one object per line, on its
/// stdin/stdout. Exposed as a model source: <see cref="Generate"/> is a
/// <c>Func&lt;InProcess.Request, InProcess.Response&gt;</c> that plugs directly into
/// <see cref="InProcess.Options.Generate"/> or <c>RuntimeOptions.InProcess</c>.
///
/// <para>openspec/changes/add-acp-model-source. Design: docs/adr/0031 (why a warm session is
/// the feature, and why the client assembles a full request every turn plus a supersedes
/// marker instead of sending only the delta). Spike/reference: spikes/acp/client/client.go —
/// this is the same shape, ported to idiomatic C#.</para>
///
/// <para>One <see cref="AcpClient"/> is one ACP session: one child process, one <c>sessionId</c>,
/// alive across many <see cref="PromptAsync(string,CancellationToken)"/> calls. Turns on that
/// session are serialised — one <c>session/prompt</c> is never sent before the prior one's reply
/// has been received — because one ACP session is one conversation and concurrent prompts would
/// interleave into it.</para>
/// </summary>
public sealed class AcpClient : IDisposable
{
    /// <summary>
    /// When true (the default), a server-initiated <c>session/request_permission</c> is answered
    /// immediately with the first option whose <c>kind</c> starts with <c>allow</c>. An
    /// unanswered permission request hangs the turn forever — even in a supposed "bypass" mode —
    /// so this must stay on for any real use; it exists as a knob only so a test can reproduce
    /// the hang deliberately.
    /// </summary>
    public bool AutoAnswerPermission { get; set; } = true;

    /// <summary>How long a single <c>initialize</c> / <c>session/new</c> / <c>session/set_mode</c>
    /// call waits for its reply before throwing <see cref="TimeoutException"/>.</summary>
    public TimeSpan CallTimeout { get; set; } = TimeSpan.FromSeconds(10);

    /// <summary>How long a <c>session/prompt</c> waits for its final reply before throwing
    /// <see cref="TimeoutException"/>. A broken or silent agent (e.g. one that asked for
    /// permission and was never answered) must not hang a caller forever.</summary>
    public TimeSpan PromptTimeout { get; set; } = TimeSpan.FromSeconds(10);

    /// <summary>The marker the library appends to every assembled prompt, naming the current
    /// turn as authoritative over anything earlier in this stateful session (ADR 0031).</summary>
    public const string SupersedesMarker = "SUPERSEDES-ALL-PRIOR:";

    private readonly Process _process;
    private readonly CancellationTokenSource _lifetimeCts = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly SemaphoreSlim _turnLock = new(1, 1);
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonElement>> _pending = new();
    private long _nextId;
    private readonly ConcurrentDictionary<string, string> _pendingMethods = new();
    private volatile Action<JsonElement>? _updateSink;
    private int _closed;

    // SPEC §8C "ACP model source additions" — set by the options overload of StartAsync.
    private Func<JsonElement, string?>? _onPermission;
    private Action<JsonElement>? _onUpdate;
    private Action<string>? _stderr;

    /// <summary>The exact <c>mcpServers</c> list sent in <c>session/new</c> (SPEC §8C).</summary>
    public IReadOnlyList<object?> McpServersSent { get; private set; } = Array.Empty<object?>();

    /// <summary>The agent's raw <c>initialize</c> result.</summary>
    public JsonElement InitializeResult { get; private set; }

    /// <summary>The <c>sessionId</c> negotiated by <c>session/new</c>.</summary>
    public string SessionId { get; private set; } = "";

    private AcpClient(Process process, Action<string>? stderr = null)
    {
        _process = process;
        _stderr = stderr;
        _ = Task.Run(() => ReadLoopAsync(_lifetimeCts.Token));
        _ = Task.Run(() => DrainStderrAsync(_lifetimeCts.Token));
    }

    /// <summary>
    /// Spawns <paramref name="command"/> as a child process, speaks ACP <c>initialize</c> then
    /// <c>session/new</c>, and returns a warm client ready for many <see cref="PromptAsync"/>
    /// calls. <paramref name="cwd"/> MUST be absolute — real <c>devin acp</c> rejects
    /// <c>session/new</c> with <c>-32602</c> otherwise; it defaults to
    /// <see cref="Directory.GetCurrentDirectory"/>, which already is.
    /// </summary>
    public static Task<AcpClient> StartAsync(
        string command,
        IReadOnlyList<string>? arguments = null,
        string? cwd = null,
        CancellationToken ct = default) =>
        StartAsync(new AcpStartOptions { Command = command, Arguments = arguments, Cwd = cwd }, ct);

    /// <summary>
    /// Starts the agent with the full option set (SPEC §8C "ACP model source additions"):
    /// <c>mcpServers</c> for <c>session/new</c>, initialize <c>clientCapabilities._meta</c>, a
    /// model passed through after <c>session/new</c>, a stderr sink, a permission decider and a
    /// raw update observer. A failure to start the process or to complete <c>initialize</c>
    /// throws <see cref="AcpLaunchException"/>.
    /// </summary>
    public static async Task<AcpClient> StartAsync(AcpStartOptions options, CancellationToken ct = default)
    {
        var command = options.Command;
        var cwd = options.Cwd;
        var psi = new ProcessStartInfo
        {
            FileName = command,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        if (options.Arguments != null)
            foreach (var a in options.Arguments) psi.ArgumentList.Add(a);
        if (cwd != null) psi.WorkingDirectory = cwd;
        if (options.Env != null)
            foreach (var (k, v) in options.Env) psi.Environment[k] = v;

        var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        try
        {
            if (!process.Start())
                throw new InvalidOperationException("process did not start");
        }
        catch (Exception e)
        {
            process.Dispose();
            throw new AcpLaunchException("start", $"toolnexus: acp: start \"{command}\": {e.Message}", e);
        }

        var client = new AcpClient(process, options.Stderr)
        {
            _onPermission = options.OnPermission,
            _onUpdate = options.OnUpdate,
        };
        if (options.CallTimeout is { } callTimeout) client.CallTimeout = callTimeout;
        if (options.PromptTimeout is { } promptTimeout) client.PromptTimeout = promptTimeout;
        try
        {
            var caps = new Dictionary<string, object?>
            {
                // A model host serves no files and no terminal.
                ["fs"] = new Dictionary<string, object?> { ["readTextFile"] = false, ["writeTextFile"] = false },
                ["terminal"] = false,
            };
            if (options.ClientCapsMeta != null) caps["_meta"] = options.ClientCapsMeta;
            JsonElement init;
            try
            {
                init = await client.CallAsync("initialize", new Dictionary<string, object?>
                {
                    ["protocolVersion"] = 1,
                    ["clientCapabilities"] = caps,
                }, ct).ConfigureAwait(false);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                throw new AcpLaunchException("initialize", "toolnexus: acp: initialize: " + e.Message, e);
            }
            client.InitializeResult = init;
            var httpOk = init.ValueKind == JsonValueKind.Object &&
                         init.TryGetProperty("agentCapabilities", out var ac) && ac.ValueKind == JsonValueKind.Object &&
                         ac.TryGetProperty("mcpCapabilities", out var mc) && mc.ValueKind == JsonValueKind.Object &&
                         mc.TryGetProperty("http", out var h) && h.ValueKind == JsonValueKind.True;

            var sessionCwd = cwd ?? Directory.GetCurrentDirectory();
            if (!Path.IsPathRooted(sessionCwd))
                throw new ArgumentException("toolnexus: ACP session/new requires an absolute cwd", nameof(cwd));

            var servers = (options.McpServers ?? Array.Empty<AcpMcpServer>()).Select(s => (object?)s.Wire(httpOk)).ToList();
            client.McpServersSent = servers;
            var sessionResult = await client.CallAsync("session/new", new Dictionary<string, object?>
            {
                ["cwd"] = sessionCwd,
                ["mcpServers"] = servers,
            }, ct).ConfigureAwait(false);

            if (sessionResult.ValueKind == JsonValueKind.Object &&
                sessionResult.TryGetProperty("sessionId", out var sid) &&
                sid.ValueKind == JsonValueKind.String)
            {
                client.SessionId = sid.GetString() ?? "";
            }

            if (!string.IsNullOrEmpty(options.Model))
            {
                // Passed through unchanged: the stable spelling first, then the older
                // session/set_model. Keys in sorted order, as every port sends them.
                try
                {
                    await client.CallAsync("session/set_config_option", new Dictionary<string, object?>
                    {
                        ["configId"] = "model", ["sessionId"] = client.SessionId, ["value"] = options.Model,
                    }, ct).ConfigureAwait(false);
                }
                catch (Exception e1) when (e1 is not OperationCanceledException)
                {
                    try
                    {
                        await client.CallAsync("session/set_model", new Dictionary<string, object?>
                        {
                            ["modelId"] = options.Model, ["sessionId"] = client.SessionId,
                        }, ct).ConfigureAwait(false);
                    }
                    catch (Exception e2) when (e2 is not OperationCanceledException)
                    {
                        throw new InvalidOperationException(
                            $"toolnexus: acp: set model \"{options.Model}\": {e1.Message}; {e2.Message}", e2);
                    }
                }
            }
        }
        catch
        {
            client.Dispose();
            throw;
        }

        return client;
    }

    /// <summary>Optional <c>session/set_mode</c> negotiation on the already-open session.</summary>
    public Task SetModeAsync(string modeId, CancellationToken ct = default) =>
        CallAsync("session/set_mode", new Dictionary<string, object?>
        {
            ["sessionId"] = SessionId,
            ["modeId"] = modeId,
        }, ct);

    /// <summary>
    /// The model source: assembles the full request into one prompt string (plus the supersedes
    /// marker), runs one <c>session/prompt</c> on the warm session, and returns only the
    /// accumulated <c>agent_message_chunk</c> text. Blocking, so it plugs directly into
    /// <see cref="InProcess.Options.Generate"/> / <c>RuntimeOptions.InProcess</c>, both of which
    /// are synchronous seams; the work itself runs on the thread pool so a caller with a
    /// synchronization context (e.g. ASP.NET classic) cannot deadlock on it.
    /// </summary>
    public InProcess.Response Generate(InProcess.Request request) =>
        Task.Run(() => GenerateStreamAsync(request, null)).GetAwaiter().GetResult();

    /// <summary>
    /// The streaming model source (SPEC §8C): the same turn as <see cref="Generate"/>, but each
    /// <c>session/update</c> is reported to <paramref name="emit"/> the moment the read loop
    /// processes it — <c>agent_message_chunk</c> ⇒ a <c>Text</c> delta, <c>agent_thought_chunk</c> ⇒
    /// a <c>Thought</c> (never part of the answer), <c>tool_call</c> ⇒ a <c>ToolCall</c> and a
    /// completed/failed <c>tool_call_update</c> ⇒ a <c>ToolResult</c>, all with <c>Via = "acp"</c>
    /// (the agent ran them; the loop never does). Plugs into
    /// <see cref="InProcess.Options.GenerateStream"/>. A null <paramref name="emit"/> reports nothing.
    /// </summary>
    public async Task<InProcess.Response> GenerateStreamAsync(InProcess.Request request,
        Action<LlmClient.StreamEvent>? emit, CancellationToken ct = default)
    {
        var text = await PromptStreamAsync(BuildPromptText(request), emit, ct).ConfigureAwait(false);
        return InProcess.Response.FromContent(text);
    }

    /// <summary>Assembles <paramref name="request"/> into a full prompt (with the supersedes
    /// marker) and runs it as one turn on the warm session.</summary>
    public Task<string> PromptAsync(InProcess.Request request, CancellationToken ct = default) =>
        PromptAsync(BuildPromptText(request), ct);

    /// <summary>
    /// Runs one turn on the warm session with <paramref name="fullText"/> as the complete prompt
    /// (the caller is responsible for the supersedes marker if bypassing
    /// <see cref="PromptAsync(InProcess.Request,CancellationToken)"/>). Turns are serialised:
    /// concurrent callers queue rather than interleave into the one ACP session.
    /// </summary>
    public Task<string> PromptAsync(string fullText, CancellationToken ct = default) =>
        PromptStreamAsync(fullText, null, ct);

    /// <summary><see cref="PromptAsync(string,CancellationToken)"/>, reporting each update to
    /// <paramref name="emit"/> as it arrives (see <see cref="GenerateStreamAsync"/>).</summary>
    public async Task<string> PromptStreamAsync(string fullText, Action<LlmClient.StreamEvent>? emit, CancellationToken ct = default)
    {
        await _turnLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            return await SendPromptAsync(fullText, emit, ct).ConfigureAwait(false);
        }
        finally
        {
            _turnLock.Release();
        }
    }

    /// <summary>
    /// Builds the deterministic full-request prompt text (ADR 0031): every message rendered as
    /// <c>role: content</c> on its own line, followed by the library-assembled supersedes marker
    /// naming the freshest user turn as authoritative.
    /// </summary>
    public static string BuildPromptText(InProcess.Request request)
    {
        var sb = new StringBuilder();
        var lastUserText = "";
        var sawUser = false;
        for (var i = 0; i < request.Messages.Count; i++)
        {
            var (role, content) = ExtractRoleContent(request.Messages[i]);
            if (i > 0) sb.Append('\n');
            sb.Append(role).Append(": ").Append(content);
            if (string.Equals(role, "user", StringComparison.OrdinalIgnoreCase))
            {
                lastUserText = content;
                sawUser = true;
            }
        }
        if (!sawUser && request.Messages.Count > 0)
        {
            var (_, content) = ExtractRoleContent(request.Messages[^1]);
            lastUserText = content;
        }
        if (sb.Length > 0) sb.Append('\n');
        sb.Append(SupersedesMarker).Append(' ').Append(lastUserText);
        return sb.ToString();
    }

    private static (string Role, string Content) ExtractRoleContent(object? message)
    {
        var normalized = message is JsonElement je ? Json.FromElement(je) : message;
        if (normalized is Dictionary<string, object?> dict)
        {
            var role = dict.TryGetValue("role", out var r) ? r?.ToString() ?? "" : "";
            var content = ContentToText(dict.TryGetValue("content", out var c) ? c : null);
            return (role, content);
        }
        return ("", normalized?.ToString() ?? "");
    }

    private static string ContentToText(object? content) => content switch
    {
        null => "",
        string s => s,
        List<object?> parts => string.Join(" ", parts.Select(PartToText)),
        _ => Json.Stringify(content),
    };

    private static string PartToText(object? part) =>
        part is Dictionary<string, object?> d && d.TryGetValue("text", out var t)
            ? t?.ToString() ?? ""
            : Json.Stringify(part);

    private async Task<string> SendPromptAsync(string text, Action<LlmClient.StreamEvent>? emit, CancellationToken ct)
    {
        var id = NextId();
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pendingMethods[id] = "session/prompt";
        _pending[id] = tcs;

        var sb = new StringBuilder();
        var toolNames = new Dictionary<string, string>();
        _updateSink = el =>
        {
            if (el.ValueKind != JsonValueKind.Object) return;
            if (!el.TryGetProperty("update", out var upd) || upd.ValueKind != JsonValueKind.Object) return;
            if (!upd.TryGetProperty("sessionUpdate", out var kindEl) || kindEl.ValueKind != JsonValueKind.String) return;
            // ONLY agent_message_chunk forms the reply — agent_thought_chunk and any
            // tool_call/tool_call_update notification never reach it, or they wrap prose around
            // structured output (ADR 0031 gate item 3 / spec scenario "Only agent message
            // chunks form the reply"). They are reported to `emit` (on this read loop, as each
            // notification arrives) so a stream sees them live.
            switch (kindEl.GetString())
            {
                case "agent_message_chunk":
                {
                    var t = ChunkText(upd);
                    lock (sb) sb.Append(t);
                    if (emit != null && t.Length > 0) emit(new LlmClient.StreamEvent(LlmClient.StreamKind.Text, Delta: t));
                    break;
                }
                case "agent_thought_chunk":
                {
                    // Reported, never part of the answer.
                    var t = ChunkText(upd);
                    if (emit != null && t.Length > 0)
                        emit(new LlmClient.StreamEvent(LlmClient.StreamKind.Thought, Delta: t, Via: "acp"));
                    break;
                }
                case "tool_call":
                {
                    var callId = StrProp(upd, "toolCallId");
                    var name = StrProp(upd, "title");
                    if (name.Length == 0) name = StrProp(upd, "kind");
                    lock (toolNames) toolNames[callId] = name;
                    if (emit == null) break;
                    var args = upd.TryGetProperty("rawInput", out var ri) && ri.ValueKind == JsonValueKind.Object
                        ? Json.FromElement(ri) as IDictionary<string, object?> : null;
                    emit(new LlmClient.StreamEvent(LlmClient.StreamKind.ToolCall, Id: callId, Name: name,
                        Args: args ?? new Dictionary<string, object?>(), Via: "acp"));
                    break;
                }
                case "tool_call_update":
                {
                    var status = StrProp(upd, "status");
                    if (emit == null || (status != "completed" && status != "failed")) break;
                    var callId = StrProp(upd, "toolCallId");
                    string? name;
                    lock (toolNames) toolNames.TryGetValue(callId, out name);
                    var title = StrProp(upd, "title");
                    if (title.Length > 0) name = title;
                    emit(new LlmClient.StreamEvent(LlmClient.StreamKind.ToolResult, Id: callId, Name: name ?? "",
                        Output: ToolContentText(upd), IsError: status == "failed", Via: "acp"));
                    break;
                }
            }
        };

        try
        {
            await WriteAsync(new Dictionary<string, object?>
            {
                ["jsonrpc"] = "2.0",
                ["id"] = id,
                ["method"] = "session/prompt",
                ["params"] = new Dictionary<string, object?>
                {
                    ["sessionId"] = SessionId,
                    ["prompt"] = new List<object?>
                    {
                        new Dictionary<string, object?> { ["type"] = "text", ["text"] = text },
                    },
                },
            }, ct).ConfigureAwait(false);

            var delay = Task.Delay(PromptTimeout, ct);
            var winner = await Task.WhenAny(tcs.Task, delay).ConfigureAwait(false);
            if (winner == delay)
            {
                if (ct.IsCancellationRequested) throw new OperationCanceledException(ct);
                throw new TimeoutException(
                    $"toolnexus: ACP session/prompt timed out after {PromptTimeout} "
                    + "(a permission request may have gone unanswered)");
            }

            await tcs.Task.ConfigureAwait(false); // surfaces AcpRpcException, if any
            lock (sb) return sb.ToString();
        }
        finally
        {
            _pending.TryRemove(id, out _);
            _pendingMethods.TryRemove(id, out _);
            _updateSink = null;
        }
    }

    private static string StrProp(JsonElement e, string k) =>
        e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static string ChunkText(JsonElement upd) =>
        upd.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.Object ? StrProp(c, "text") : "";

    /// <summary>The text of a tool_call_update's <c>content</c> array (<c>[{content:{text}}]</c>), joined.</summary>
    private static string ToolContentText(JsonElement upd)
    {
        if (!upd.TryGetProperty("content", out var arr) || arr.ValueKind != JsonValueKind.Array) return "";
        var b = new StringBuilder();
        foreach (var p in arr.EnumerateArray())
            if (p.ValueKind == JsonValueKind.Object && p.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.Object)
                b.Append(StrProp(c, "text"));
        return b.ToString();
    }

    private async Task<JsonElement> CallAsync(string method, object? @params, CancellationToken ct)
    {
        var id = NextId();
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pendingMethods[id] = method;
        _pending[id] = tcs;
        try
        {
            await WriteAsync(new Dictionary<string, object?>
            {
                ["jsonrpc"] = "2.0",
                ["id"] = id,
                ["method"] = method,
                ["params"] = @params,
            }, ct).ConfigureAwait(false);

            var delay = Task.Delay(CallTimeout, ct);
            var winner = await Task.WhenAny(tcs.Task, delay).ConfigureAwait(false);
            if (winner == delay)
            {
                if (ct.IsCancellationRequested) throw new OperationCanceledException(ct);
                throw new TimeoutException($"toolnexus: ACP '{method}' timed out after {CallTimeout}");
            }
            return await tcs.Task.ConfigureAwait(false);
        }
        finally
        {
            _pending.TryRemove(id, out _);
            _pendingMethods.TryRemove(id, out _);
        }
    }

    private string NextId() => "c-" + Interlocked.Increment(ref _nextId);

    private async Task WriteAsync(object message, CancellationToken ct)
    {
        var json = Json.Stringify(message);
        await _writeLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var stdin = _process.StandardInput;
            await stdin.WriteLineAsync(json).ConfigureAwait(false);
            await stdin.FlushAsync().ConfigureAwait(false);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>
    /// Demultiplexes every line arriving on the agent's stdout: replies to OUR requests (by id,
    /// against <see cref="_pending"/>), <c>session/update</c> notifications (routed to the
    /// in-flight prompt's accumulator), and server-initiated requests such as
    /// <c>session/request_permission</c> (answered inline, independent of whatever
    /// <see cref="PromptAsync(string,CancellationToken)"/> call is in flight, so an answer never
    /// waits on the turn it is unblocking).
    /// </summary>
    private async Task ReadLoopAsync(CancellationToken ct)
    {
        var reader = _process.StandardOutput;
        while (!ct.IsCancellationRequested)
        {
            string? line;
            try
            {
                line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (IOException)
            {
                break;
            }
            if (line is null) break; // EOF: the child process closed stdout
            if (string.IsNullOrWhiteSpace(line)) continue;
            try
            {
                HandleLine(line);
            }
            catch
            {
                // A malformed line from a misbehaving agent must not take down the read loop.
            }
        }

        // The process is gone (or we were cancelled): unblock anything still waiting rather than
        // let it ride out its full timeout.
        // SPEC §8C: fail fast with the method that was waiting.
        foreach (var kv in _pending)
            if (_pending.TryRemove(kv.Key, out var tcs))
                tcs.TrySetException(new AcpExitedException(
                    _pendingMethods.TryGetValue(kv.Key, out var m) ? m : "request"));
    }

    private async Task DrainStderrAsync(CancellationToken ct)
    {
        var reader = _process.StandardError;
        while (!ct.IsCancellationRequested)
        {
            string? line;
            try
            {
                line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
            }
            catch
            {
                break;
            }
            if (line is null) break;
            // Handed to the stderr sink when one is set, else discarded. Draining it (rather
            // than leaving it unread) keeps the child from blocking on a full pipe buffer.
            try { _stderr?.Invoke(line); } catch { /* a sink must not stop the drain */ }
        }
    }

    private void HandleLine(string line)
    {
        using var doc = JsonDocument.Parse(line);
        var root = doc.RootElement;
        var hasId = root.TryGetProperty("id", out var idEl) && idEl.ValueKind != JsonValueKind.Null;
        var hasMethod = root.TryGetProperty("method", out var methodEl) && methodEl.ValueKind == JsonValueKind.String;

        if (!hasMethod && hasId)
        {
            // A reply to one of OUR requests.
            var key = IdToKey(idEl);
            if (_pending.TryRemove(key, out var tcs))
            {
                if (root.TryGetProperty("error", out var errEl) && errEl.ValueKind == JsonValueKind.Object)
                {
                    var code = errEl.TryGetProperty("code", out var c) && c.ValueKind == JsonValueKind.Number
                        ? c.GetInt32() : 0;
                    var msg = errEl.TryGetProperty("message", out var m) && m.ValueKind == JsonValueKind.String
                        ? m.GetString()! : "";
                    tcs.TrySetException(new AcpRpcException(code, msg));
                }
                else
                {
                    var result = root.TryGetProperty("result", out var r) ? r.Clone() : default;
                    tcs.TrySetResult(result);
                }
            }
            return;
        }

        var method = hasMethod ? methodEl.GetString()! : "";

        if (method == "session/update")
        {
            if (root.TryGetProperty("params", out var p))
            {
                var raw = p.Clone();
                try { _onUpdate?.Invoke(raw); } catch { /* an observer must not stop the loop */ }
                var sink = _updateSink;
                sink?.Invoke(raw);
            }
            return;
        }

        if (method == "session/request_permission" && hasId)
        {
            HandlePermissionRequest(idEl, root.TryGetProperty("params", out var pp) ? pp : default);
            return;
        }

        // Any other agent->client request this thin client doesn't implement (fs/*, terminal/* —
        // declared unsupported in clientCapabilities): answer -32601 rather than leave the agent
        // hanging on a method we silently ignored.
        if (hasId)
            _ = ReplyErrorAsync(IdValue(idEl), -32601, "method not found: " + method);
    }

    private void HandlePermissionRequest(JsonElement idEl, JsonElement paramsEl)
    {
        var idValue = IdValue(idEl);
        if (!AutoAnswerPermission)
            return; // deliberately left unanswered — the hang case, test-only.

        if (_onPermission != null)
        {
            // The decider names the allow option to pick; null ⇒ reject (SPEC §8C).
            string? allow = null;
            try { allow = _onPermission(paramsEl.ValueKind == JsonValueKind.Undefined ? default : paramsEl.Clone()); }
            catch { allow = null; }
            var picked = allow ?? FirstOptionOfKind(paramsEl, "reject");
            _ = ReplyAsync(idValue, new Dictionary<string, object?>
            {
                ["outcome"] = picked is not null
                    ? new Dictionary<string, object?> { ["outcome"] = "selected", ["optionId"] = picked }
                    : new Dictionary<string, object?> { ["outcome"] = "cancelled" },
            });
            return;
        }

        string? chosen = null;
        if (paramsEl.ValueKind == JsonValueKind.Object &&
            paramsEl.TryGetProperty("options", out var optsEl) && optsEl.ValueKind == JsonValueKind.Array)
        {
            foreach (var opt in optsEl.EnumerateArray())
            {
                var kind = opt.TryGetProperty("kind", out var k) && k.ValueKind == JsonValueKind.String
                    ? k.GetString()! : "";
                if (kind.StartsWith("allow", StringComparison.Ordinal))
                {
                    chosen = opt.TryGetProperty("optionId", out var oid) && oid.ValueKind == JsonValueKind.String
                        ? oid.GetString() : null;
                    break;
                }
            }
        }

        var outcome = chosen is not null
            ? new Dictionary<string, object?> { ["outcome"] = "selected", ["optionId"] = chosen }
            : new Dictionary<string, object?> { ["outcome"] = "cancelled" };
        _ = ReplyAsync(idValue, new Dictionary<string, object?> { ["outcome"] = outcome });
    }

    private static string? FirstOptionOfKind(JsonElement paramsEl, string prefix)
    {
        if (paramsEl.ValueKind != JsonValueKind.Object ||
            !paramsEl.TryGetProperty("options", out var optsEl) || optsEl.ValueKind != JsonValueKind.Array)
            return null;
        foreach (var opt in optsEl.EnumerateArray())
        {
            var kind = opt.TryGetProperty("kind", out var k) && k.ValueKind == JsonValueKind.String ? k.GetString()! : "";
            if (kind.StartsWith(prefix, StringComparison.Ordinal) &&
                opt.TryGetProperty("optionId", out var oid) && oid.ValueKind == JsonValueKind.String)
                return oid.GetString();
        }
        return null;
    }

    private async Task ReplyErrorAsync(object idValue, int code, string message)
    {
        try
        {
            await WriteAsync(new Dictionary<string, object?>
            {
                ["jsonrpc"] = "2.0",
                ["id"] = idValue,
                ["error"] = new Dictionary<string, object?> { ["code"] = code, ["message"] = message },
            }, CancellationToken.None).ConfigureAwait(false);
        }
        catch
        {
            // Best effort: the process may already be closing.
        }
    }

    private async Task ReplyAsync(object idValue, object result)
    {
        try
        {
            await WriteAsync(new Dictionary<string, object?>
            {
                ["jsonrpc"] = "2.0",
                ["id"] = idValue,
                ["result"] = result,
            }, CancellationToken.None).ConfigureAwait(false);
        }
        catch
        {
            // Best effort: the process may already be closing.
        }
    }

    private static object IdValue(JsonElement idEl) => idEl.ValueKind switch
    {
        JsonValueKind.String => idEl.GetString() ?? "",
        JsonValueKind.Number => idEl.GetInt64(),
        _ => idEl.GetRawText(),
    };

    private static string IdToKey(JsonElement idEl) => idEl.ValueKind switch
    {
        JsonValueKind.String => idEl.GetString() ?? "",
        JsonValueKind.Number => idEl.GetInt64().ToString(),
        _ => idEl.GetRawText(),
    };

    /// <summary>
    /// Terminates the child process and unblocks any pending calls. Idempotent — safe to call
    /// more than once. The process's lifetime is independent of any single turn's cancellation:
    /// only Dispose ends it.
    /// </summary>
    public void Dispose()
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;

        try { _lifetimeCts.Cancel(); } catch { /* best effort */ }

        foreach (var kv in _pending)
            if (_pending.TryRemove(kv.Key, out var tcs))
                tcs.TrySetException(new ObjectDisposedException(nameof(AcpClient)));

        try { _process.StandardInput.Close(); } catch { /* best effort */ }
        try
        {
            if (!_process.HasExited && !_process.WaitForExit(2000))
                _process.Kill(true);
            else if (!_process.HasExited)
                _process.WaitForExit(2000);
        }
        catch { /* best effort */ }
        try { _process.Dispose(); } catch { /* best effort */ }

        _lifetimeCts.Dispose();
        _writeLock.Dispose();
        _turnLock.Dispose();
    }
}

/// <summary>A JSON-RPC error reply from the ACP agent.</summary>
public sealed class AcpRpcException : Exception
{
    public int Code { get; }
    public AcpRpcException(int code, string message) : base($"acp error {code}: {message}") => Code = code;
}

/// <summary>The agent process ended (its stdout closed) while a request waited for its reply.</summary>
public sealed class AcpExitedException : IOException
{
    public string Method { get; }
    public AcpExitedException(string method)
        : base($"toolnexus: acp: agent exited before replying to {method}") => Method = method;
}

/// <summary>The agent could not be started, or did not complete <c>initialize</c>.
/// <see cref="Stage"/> is <c>start</c> or <c>initialize</c>.</summary>
public sealed class AcpLaunchException : Exception
{
    public string Stage { get; }
    public AcpLaunchException(string stage, string message, Exception? inner = null)
        : base(message, inner) => Stage = stage;
}

/// <summary>
/// One entry of <c>session/new</c> <c>mcpServers</c> (SPEC §8C). Set <see cref="Url"/> for the
/// http shape (<c>{type:"http",name,url,headers[]}</c>), <see cref="Command"/> for the stdio shape
/// (<c>{name,command,args[],env[]}</c> — no type), or both to let the agent's advertised
/// <c>mcpCapabilities.http</c> pick. Headers and env go on the wire as <c>[{name,value}]</c>
/// arrays sorted by name; their values are never logged.
/// </summary>
public sealed class AcpMcpServer
{
    public string Name { get; init; } = "";
    public string? Url { get; init; }
    public IReadOnlyDictionary<string, string>? Headers { get; init; }
    public string? Command { get; init; }
    public IReadOnlyList<string>? Args { get; init; }
    public IReadOnlyDictionary<string, string>? Env { get; init; }

    internal Dictionary<string, object?> Wire(bool httpOk)
    {
        if (!string.IsNullOrEmpty(Url) && (httpOk || string.IsNullOrEmpty(Command)))
            return new Dictionary<string, object?>
            {
                ["type"] = "http", ["name"] = Name, ["url"] = Url, ["headers"] = NameValues(Headers),
            };
        return new Dictionary<string, object?>
        {
            ["name"] = Name,
            ["command"] = Command ?? "",
            ["args"] = (Args ?? Array.Empty<string>()).Cast<object?>().ToList(),
            ["env"] = NameValues(Env),
        };
    }

    private static List<object?> NameValues(IReadOnlyDictionary<string, string>? m) =>
        (m ?? new Dictionary<string, string>())
            .OrderBy(kv => kv.Key, StringComparer.Ordinal)
            .Select(kv => (object?)new Dictionary<string, object?> { ["name"] = kv.Key, ["value"] = kv.Value })
            .ToList();
}

/// <summary>Options for <see cref="AcpClient.StartAsync(AcpStartOptions, CancellationToken)"/>.</summary>
public sealed class AcpStartOptions
{
    public string Command { get; init; } = "";
    public IReadOnlyList<string>? Arguments { get; init; }
    /// <summary>Absolute session cwd; also the agent process's working directory.</summary>
    public string? Cwd { get; init; }
    /// <summary>Added to the agent's environment.</summary>
    public IReadOnlyDictionary<string, string>? Env { get; init; }
    /// <summary>Sent in <c>session/new</c> <c>mcpServers</c> (none ⇒ <c>[]</c>).</summary>
    public IReadOnlyList<AcpMcpServer>? McpServers { get; init; }
    /// <summary>Sent as initialize <c>clientCapabilities._meta</c>. Opaque to toolnexus.</summary>
    public IDictionary<string, object?>? ClientCapsMeta { get; init; }
    /// <summary>Passed through unchanged: <c>session/set_config_option {configId:"model"}</c>,
    /// then <c>session/set_model</c>. Both failing fails the start.</summary>
    public string? Model { get; init; }
    /// <summary>Receives each line of the agent's stderr (default: discarded).</summary>
    public Action<string>? Stderr { get; init; }
    /// <summary>Decides each <c>session/request_permission</c> (its raw params): return the
    /// optionId to select, or null to answer with the first <c>reject*</c> option. Unset ⇒ the
    /// first <c>allow*</c> option, as before.</summary>
    public Func<JsonElement, string?>? OnPermission { get; init; }
    /// <summary>Observes every raw <c>session/update</c> params (runs on the read loop: must not block).</summary>
    public Action<JsonElement>? OnUpdate { get; init; }
    public TimeSpan? CallTimeout { get; init; }
    public TimeSpan? PromptTimeout { get; init; }
}
