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
/// <para>The agent is a real tool-calling model (openspec/changes/add-acp-tool-calling, SPEC §8
/// "ACP model source"): each prompt carries the OpenAI-shaped request — messages plus tool
/// schemas — and the agent's JSON reply is parsed back into content or tool calls, which the
/// loop executes. The agent's own tools are refused unless <see cref="AllowAgentTools"/>.</para>
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
    /// immediately — see <see cref="AllowAgentTools"/> for which option. An unanswered permission
    /// request hangs the turn forever — even in a supposed "bypass" mode — so this must stay on
    /// for any real use; it exists as a knob only so a test can reproduce the hang deliberately.
    /// </summary>
    public bool AutoAnswerPermission { get; set; } = true;

    /// <summary>
    /// Lets the agent run tools of its OWN: a <c>session/request_permission</c> is then answered
    /// with the first option whose <c>kind</c> starts with <c>allow</c>. Default false — the first
    /// <c>reject</c>-kind option (no such option ⇒ <c>cancelled</c>) — because toolnexus is the
    /// tool executor (SPEC §8, openspec/changes/add-acp-tool-calling): an agent that runs
    /// <c>bash</c> itself has escaped every hook and any builtin execution seam (ADR 0033).
    /// </summary>
    public bool AllowAgentTools { get; set; }

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
    private volatile Action<JsonElement>? _updateSink;
    private int _closed;

    /// <summary>The <c>sessionId</c> negotiated by <c>session/new</c>.</summary>
    public string SessionId { get; private set; } = "";

    private AcpClient(Process process)
    {
        _process = process;
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
    public static async Task<AcpClient> StartAsync(
        string command,
        IReadOnlyList<string>? arguments = null,
        string? cwd = null,
        CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo
        {
            FileName = command,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        if (arguments != null)
            foreach (var a in arguments) psi.ArgumentList.Add(a);

        var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        if (!process.Start())
            throw new InvalidOperationException($"toolnexus: failed to start ACP agent '{command}'");

        var client = new AcpClient(process);
        try
        {
            await client.CallAsync("initialize", new Dictionary<string, object?>
            {
                ["protocolVersion"] = 1,
                ["clientCapabilities"] = new Dictionary<string, object?>
                {
                    // A model host serves no files.
                    ["fs"] = new Dictionary<string, object?> { ["readTextFile"] = false, ["writeTextFile"] = false },
                },
            }, ct).ConfigureAwait(false);

            var sessionCwd = cwd ?? Directory.GetCurrentDirectory();
            if (!Path.IsPathRooted(sessionCwd))
                throw new ArgumentException("toolnexus: ACP session/new requires an absolute cwd", nameof(cwd));

            var sessionResult = await client.CallAsync("session/new", new Dictionary<string, object?>
            {
                ["cwd"] = sessionCwd,
                ["mcpServers"] = new List<object?>(),
            }, ct).ConfigureAwait(false);

            if (sessionResult.ValueKind == JsonValueKind.Object &&
                sessionResult.TryGetProperty("sessionId", out var sid) &&
                sid.ValueKind == JsonValueKind.String)
            {
                client.SessionId = sid.GetString() ?? "";
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
    /// The model source — a real tool-calling model (SPEC §8 "ACP model source"): sends the
    /// assembled OpenAI-shaped request (messages, including earlier tool calls and their results,
    /// plus the tool schemas) as one <c>session/prompt</c> on the warm session, and parses the
    /// accumulated <c>agent_message_chunk</c> text into one assistant message — tool calls the
    /// loop executes through the toolkit, or content (<see cref="ParseReply"/>). Blocking, so it plugs directly into
    /// <see cref="InProcess.Options.Generate"/> / <c>RuntimeOptions.InProcess</c>, both of which
    /// are synchronous seams; the work itself runs on the thread pool so a caller with a
    /// synchronization context (e.g. ASP.NET classic) cannot deadlock on it.
    /// </summary>
    public InProcess.Response Generate(InProcess.Request request)
    {
        var text = Task.Run(() => PromptAsync(request)).GetAwaiter().GetResult();
        return ParseReply(text);
    }

    /// <summary>Assembles <paramref name="request"/> into a full prompt
    /// (<see cref="BuildPromptText"/>) and runs it as one turn on the warm session, returning the
    /// raw accumulated reply text (unparsed — <see cref="Generate"/> parses it).</summary>
    public Task<string> PromptAsync(InProcess.Request request, CancellationToken ct = default) =>
        PromptAsync(BuildPromptText(request), ct);

    /// <summary>
    /// Runs one turn on the warm session with <paramref name="fullText"/> as the complete prompt
    /// (the caller is responsible for the supersedes marker if bypassing
    /// <see cref="PromptAsync(InProcess.Request,CancellationToken)"/>). Turns are serialised:
    /// concurrent callers queue rather than interleave into the one ACP session.
    /// </summary>
    public async Task<string> PromptAsync(string fullText, CancellationToken ct = default)
    {
        await _turnLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            return await SendPromptAsync(fullText, ct).ConfigureAwait(false);
        }
        finally
        {
            _turnLock.Release();
        }
    }

    /// <summary>
    /// The preamble every assembled prompt opens with, byte-pinned by SPEC.md §8 "ACP model
    /// source" — identical in all seven ports. It turns the agent into the model behind this
    /// tool-calling client: reply with one JSON object, content or tool calls, never run tools.
    /// </summary>
    internal const string Preamble =
        "You are the language model behind a tool-calling client. The client executes tools; you never do.\n" +
        "Do not run commands, read or edit files, or use any tool of your own.\n" +
        "The REQUEST below is the complete conversation in OpenAI chat-completions format: \"messages\" holds every message so far, including earlier tool calls and their results; \"tools\" lists the only tools you may call.\n" +
        "Reply with exactly one JSON object and nothing else: no prose, no markdown fences.\n" +
        "To give the final answer: {\"content\": \"<answer>\"}\n" +
        "To call tools: {\"tool_calls\": [{\"id\": \"<unique id>\", \"type\": \"function\", \"function\": {\"name\": \"<tool name>\", \"arguments\": \"<JSON-encoded arguments>\"}}]}\n" +
        "Never both. Use tool results already in \"messages\" instead of calling the same tool again.\n";

    /// <summary>
    /// Builds the full-request prompt text (SPEC §8 "ACP model source", ADR 0031):
    /// <c>PREAMBLE + "\nREQUEST:\n" + {"messages":[…],"tools":[…]} + "\n\n" + marker + " " +
    /// latest user text</c>. The FULL request goes every turn (an ACP session is stateful; a delta
    /// would make the client a shadow copy of conversation state), and the supersedes marker keeps
    /// a stateful agent off an earlier near-duplicate in its own history.
    /// </summary>
    public static string BuildPromptText(InProcess.Request request)
    {
        // Dictionary insertion order keeps "messages" first; Json.Stringify never HTML-escapes.
        var payload = Json.Stringify(new Dictionary<string, object?>
        {
            ["messages"] = request.Messages ?? Array.Empty<object?>(),
            ["tools"] = request.Tools ?? Array.Empty<object?>(),
        });
        // Messages arrive as JsonElements (the in-process seam) or caller-built dictionaries;
        // reading them back from the payload gives one plain object model for the latest-user scan.
        var messages = Json.ParseLoose(payload) is Dictionary<string, object?> parsed &&
                       parsed["messages"] is List<object?> list ? list : new List<object?>();

        var latest = "";
        var found = false;
        for (var i = messages.Count - 1; i >= 0; i--)
        {
            if (messages[i] is Dictionary<string, object?> d && d.TryGetValue("role", out var r) && r is "user")
            {
                latest = ContentText(d.TryGetValue("content", out var c) ? c : null);
                found = true;
                break;
            }
        }
        if (!found && messages.Count > 0 && messages[^1] is Dictionary<string, object?> last)
            latest = ContentText(last.TryGetValue("content", out var c) ? c : null);

        return Preamble + "\nREQUEST:\n" + payload + "\n\n" + SupersedesMarker + " " + latest;
    }

    /// <summary>A message's content for the supersedes line: a string as is; an array of parts ⇒
    /// the text of its <c>type:"text"</c> parts joined by one space; anything else ⇒ "".</summary>
    private static string ContentText(object? content) => content switch
    {
        string s => s,
        List<object?> parts => string.Join(" ", parts
            .OfType<Dictionary<string, object?>>()
            .Where(p => p.TryGetValue("type", out var t) && t is "text" && p.TryGetValue("text", out var x) && x is string)
            .Select(p => (string)p["text"]!)),
        _ => "",
    };

    /// <summary>
    /// Parses the agent's accumulated reply text into one assistant message, by the algorithm
    /// SPEC §8 pins: strip a fence, parse (or the first-<c>{</c>..last-<c>}</c> slice), unwrap
    /// <c>choices[0].message</c> / <c>message</c>, then <c>tool_calls</c> ⇒ tool calls,
    /// <c>content</c> ⇒ content, anything else ⇒ the ORIGINAL text untouched (e.g. structured
    /// output the host asked for).
    /// </summary>
    public static InProcess.Response ParseReply(string text)
    {
        var s = text.Trim();
        if (s.StartsWith("```", StringComparison.Ordinal))
        {
            var nl = s.IndexOf('\n');
            s = nl >= 0 ? s[(nl + 1)..].Trim() : "";
            if (s.EndsWith("```", StringComparison.Ordinal)) s = s[..^3];
            s = s.Trim();
        }

        var obj = ParseObject(s);
        if (obj is null)
        {
            int i = s.IndexOf('{'), j = s.LastIndexOf('}');
            if (i >= 0 && j > i) obj = ParseObject(s[i..(j + 1)]);
        }
        if (obj is null) return InProcess.Response.FromContent(text);

        if (obj.TryGetValue("choices", out var ch) && ch is List<object?> { Count: > 0 } choices)
        {
            if (choices[0] is Dictionary<string, object?> first &&
                first.TryGetValue("message", out var fm) && fm is Dictionary<string, object?> firstMsg)
                obj = firstMsg;
        }
        else if (obj.TryGetValue("message", out var m) && m is Dictionary<string, object?> msg)
        {
            obj = msg;
        }

        if (obj.TryGetValue("tool_calls", out var tc) && tc is List<object?> raw)
        {
            var calls = new List<InProcess.ToolCall>();
            foreach (var el in raw)
            {
                if (el is not Dictionary<string, object?> em) continue;
                var fn = em.TryGetValue("function", out var f) && f is Dictionary<string, object?> fd ? fd : em;
                if (!fn.TryGetValue("name", out var n) || n is not string name || name.Length == 0) continue;
                // A string passes through as pre-encoded; absent/null ⇒ {}; else structured.
                var args = fn.TryGetValue("arguments", out var a) && a is not null ? a : new Dictionary<string, object?>();
                var id = em.TryGetValue("id", out var idv) && idv is string ids && ids.Length > 0 ? ids : null;
                calls.Add(new InProcess.ToolCall { Id = id, Name = name, Arguments = args });
            }
            if (calls.Count > 0) return new InProcess.Response { ToolCalls = calls };
        }

        if (obj.TryGetValue("content", out var content))
        {
            return InProcess.Response.FromContent(content switch
            {
                string cs => cs,
                null => "",
                _ => Json.Stringify(content),
            });
        }
        return InProcess.Response.FromContent(text);
    }

    private static Dictionary<string, object?>? ParseObject(string s)
    {
        try
        {
            using var doc = JsonDocument.Parse(s);
            return doc.RootElement.ValueKind == JsonValueKind.Object
                ? (Dictionary<string, object?>)Json.FromElement(doc.RootElement)!
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private async Task<string> SendPromptAsync(string text, CancellationToken ct)
    {
        var id = NextId();
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[id] = tcs;

        var sb = new StringBuilder();
        _updateSink = el =>
        {
            if (el.ValueKind != JsonValueKind.Object) return;
            if (!el.TryGetProperty("update", out var upd) || upd.ValueKind != JsonValueKind.Object) return;
            if (!upd.TryGetProperty("sessionUpdate", out var kindEl) || kindEl.ValueKind != JsonValueKind.String) return;
            // ONLY agent_message_chunk forms the reply — agent_thought_chunk and any
            // tool_call/tool_call_update notification are dropped, or they wrap prose around
            // structured output (ADR 0031 gate item 3 / spec scenario "Only agent message
            // chunks form the reply").
            if (kindEl.GetString() != "agent_message_chunk") return;
            if (!upd.TryGetProperty("content", out var contentEl)) return;
            if (!contentEl.TryGetProperty("text", out var textEl) || textEl.ValueKind != JsonValueKind.String) return;
            lock (sb) sb.Append(textEl.GetString());
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
            _updateSink = null;
        }
    }

    private async Task<JsonElement> CallAsync(string method, object? @params, CancellationToken ct)
    {
        var id = NextId();
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
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
        foreach (var kv in _pending)
            if (_pending.TryRemove(kv.Key, out var tcs))
                tcs.TrySetException(new IOException("toolnexus: ACP agent's stdout closed"));
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
            // Discarded: a fake/real agent's stderr is not part of the ACP contract. Draining it
            // (rather than leaving it unread) keeps the child from blocking on a full pipe buffer.
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
                var sink = _updateSink;
                sink?.Invoke(p.Clone());
            }
            return;
        }

        if (method == "session/request_permission" && hasId)
        {
            HandlePermissionRequest(idEl, root.TryGetProperty("params", out var pp) ? pp : default);
            return;
        }

        // Any other server-initiated request this thin client doesn't implement: reply empty
        // rather than leave the agent hanging on a method we silently ignored.
        if (hasId)
            _ = ReplyAsync(IdValue(idEl), new Dictionary<string, object?>());
    }

    private void HandlePermissionRequest(JsonElement idEl, JsonElement paramsEl)
    {
        var idValue = IdValue(idEl);
        if (!AutoAnswerPermission)
            return; // deliberately left unanswered — the hang case, test-only.

        // The client executes tools, the agent must not: reject by default, allow only on opt-in.
        var want = AllowAgentTools ? "allow" : "reject";
        string? chosen = null;
        if (paramsEl.ValueKind == JsonValueKind.Object &&
            paramsEl.TryGetProperty("options", out var optsEl) && optsEl.ValueKind == JsonValueKind.Array)
        {
            foreach (var opt in optsEl.EnumerateArray())
            {
                var kind = opt.TryGetProperty("kind", out var k) && k.ValueKind == JsonValueKind.String
                    ? k.GetString()! : "";
                if (kind.StartsWith(want, StringComparison.Ordinal))
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
