using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Toolnexus.Acp;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// CLI client, ACP transport (SPEC §8C, ADR 0037 "ACP primary"). Mirrors
// golang/cli_acp.go.
//
// On the first model call the user's toolkit is served as a streamable-HTTP MCP
// server on 127.0.0.1:<ephemeral>/mcp, guarded by a per-session bearer token,
// and handed to the agent in session/new mcpServers (http when the agent
// advertises mcpCapabilities.http, else a stdio bridge; a project MCP file in a
// temp cwd for agents that ignore session servers). The agent calls the tools
// natively; toolnexus hooks run at the server boundary. Every permission
// request is decided per toolCallId: tools served by our server are allowed,
// everything else (the agent's own shell, edit, fetch …) is rejected.
// ---------------------------------------------------------------------------

/// <summary>One answered <c>session/request_permission</c>.</summary>
public sealed record CliPermissionDecision(string ToolCallId, string Title, bool Allowed);

/// <summary>A read-only view of the live ACP session, for tests and diagnostics. The bearer token
/// is never exposed: header and env values in <see cref="McpServersSent"/> are redacted.</summary>
public sealed record AcpSessionInfo(
    string Url,
    IReadOnlyList<object?> McpServersSent,
    IReadOnlyList<CliPermissionDecision> Permissions,
    IReadOnlyList<string> ServedCalls,
    string? SessionCwd);

/// <summary>A failure to start the agent or to complete initialize — the only failures on which
/// Auto falls back to the one-shot CLI.</summary>
internal sealed class CliAcpLaunchException : Exception
{
    public CliAcpLaunchException(string message, Exception? inner = null) : base(message, inner) { }
}

public sealed partial class CliClient
{
    /// <summary>The name the toolkit's <c>skill</c> tool is served under to an ACP agent (copilot and
    /// devin ship their own tool called <c>skill</c>).</summary>
    public const string ServedSkillToolName = "load_skill";

    /// <summary>Carries the bearer token to a stdio bridge.</summary>
    public const string McpTokenEnv = "TOOLNEXUS_MCP_TOKEN";

    internal const string AcpServerName = "toolnexus";

    /// <summary>The live session's info (null when the one-shot transport is in use or no call has
    /// opened a session yet).</summary>
    public AcpSessionInfo? AcpSession()
    {
        CliAcpSession? s;
        lock (_mu) s = _acp;
        return s?.Info();
    }

    /// <summary>
    /// Matches a permission title against the exact spellings agents use for a tool of OUR server:
    /// <c>mcp__toolnexus__&lt;tool&gt;</c> (claude), <c>toolnexus/&lt;tool&gt;</c>,
    /// <c>&lt;tool&gt; (toolnexus MCP Server)</c> (gemini), <c>Calling &lt;tool&gt; from toolnexus</c>
    /// (devin), <c>&lt;tool&gt;(…)</c> (gemini skill), or the bare tool name (copilot). No substring
    /// matching.
    /// </summary>
    internal static bool IsServedCall(string? title, IReadOnlySet<string> served)
    {
        if (string.IsNullOrEmpty(title)) return false;
        foreach (var n in served)
        {
            if (title == n || title == $"mcp__{AcpServerName}__{n}" || title == $"{AcpServerName}/{n}" ||
                title == $"{n} ({AcpServerName} MCP Server)" || title == $"Calling {n} from {AcpServerName}")
                return true;
            if (title.StartsWith(n + "(", StringComparison.Ordinal) && title.EndsWith(')')) return true;
        }
        return false;
    }

    /// <summary>
    /// Appends a style's ACP system hint to the first system message (a copy; the caller's messages
    /// are not mutated), or prepends a system message holding only the hint when there is none.
    /// </summary>
    internal static List<object?> WithSystemHint(IReadOnlyList<object?> messages, string? hint)
    {
        var msgs = messages.Select(m => m is JsonElement je ? Json.FromElement(je) : m).ToList();
        if (string.IsNullOrEmpty(hint)) return msgs;
        if (msgs.Count > 0 && msgs[0] is Dictionary<string, object?> m0 &&
            m0.TryGetValue("role", out var r) && r is "system" &&
            m0.TryGetValue("content", out var c) && c is string cs)
        {
            var cp = new Dictionary<string, object?>(m0) { ["content"] = cs.Length == 0 ? hint : cs + "\n\n" + hint };
            msgs[0] = cp;
            return msgs;
        }
        msgs.Insert(0, new Dictionary<string, object?> { ["role"] = "system", ["content"] = hint });
        return msgs;
    }

    /// <summary>
    /// The toolkit as served to the agent: <c>skill</c> is renamed to <see cref="ServedSkillToolName"/>
    /// with the skills list in its description, and every call runs through
    /// <c>Hooks.BeforeTool</c> / <c>AfterTool</c>.
    /// </summary>
    internal static List<ITool> ServedTools(Toolkit? tk, LlmClient.Hooks? hooks, Action<string>? ran)
    {
        if (tk == null) return new List<ITool>();
        var sp = tk.SkillsPrompt();
        return tk.Tools().Select(t =>
        {
            var isSkill = t.Source == "skill" && t.Name == "skill";
            return (ITool)new ServedTool(t,
                isSkill ? ServedSkillToolName : t.Name,
                isSkill && sp.Length > 0 ? t.Description + "\n\n" + sp : t.Description,
                hooks, ran);
        }).ToList();
    }

    private sealed class ServedTool : ITool
    {
        private readonly ITool _inner;
        private readonly LlmClient.Hooks? _hooks;
        private readonly Action<string>? _ran;

        public ServedTool(ITool inner, string name, string description, LlmClient.Hooks? hooks, Action<string>? ran)
        {
            _inner = inner;
            Name = name;
            Description = description;
            _hooks = hooks;
            _ran = ran;
        }

        public string Name { get; }
        public string Description { get; }
        public IDictionary<string, object?> InputSchema => _inner.InputSchema;
        public string Source => _inner.Source;
        public ToolHints? Hints => _inner.Hints;

        public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            // Hooks see the toolkit's own name (e.g. "skill"), as in the in-loop path.
            if (_hooks?.BeforeTool != null)
            {
                LlmClient.ToolOverride? ov;
                try { ov = _hooks.BeforeTool(new LlmClient.BeforeToolEvent(_inner.Name, args, null, 0, _inner.Hints)); }
                catch (Exception e) { return new ToolResult(e.Message, true); }
                if (ov?.Result != null) return ov.Result;
                if (ov?.Args != null) args = ov.Args;
            }
            _ran?.Invoke(Name);
            ToolResult res;
            try { res = await _inner.ExecuteAsync(args, ctx).ConfigureAwait(false); }
            catch (Exception e) { res = new ToolResult(e.Message, true); }
            if (_hooks?.AfterTool != null)
            {
                try
                {
                    var ov = _hooks.AfterTool(new LlmClient.AfterToolEvent(_inner.Name, args, res, null, 0));
                    if (ov?.Result != null) res = ov.Result;
                }
                catch (Exception e) { return new ToolResult(e.Message, true); }
            }
            return res;
        }
    }

    /// <summary>
    /// The stdio side of a stdio McpServer entry: each JSON-RPC line read from <paramref name="input"/>
    /// is POSTed to the toolkit's streamable-HTTP endpoint (with the bearer token when set), and each
    /// reply — plain JSON or SSE <c>data:</c> frames — is written to <paramref name="output"/> as one
    /// line. The equivalent of Go's <c>toolnexus mcp-bridge &lt;url&gt;</c>.
    /// </summary>
    public static async Task RunMcpBridgeAsync(string url, string? token, TextReader input, TextWriter output,
        CancellationToken ct = default)
    {
        using var http = new HttpClient();
        string? session = null;
        while (await input.ReadLineAsync(ct).ConfigureAwait(false) is { } raw)
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            using var req = new HttpRequestMessage(HttpMethod.Post, url)
            {
                Content = new StringContent(line, Encoding.UTF8, "application/json"),
            };
            req.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream");
            if (!string.IsNullOrEmpty(token)) req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
            if (session != null) req.Headers.TryAddWithoutValidation("Mcp-Session-Id", session);
            using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
            if (resp.Headers.TryGetValues("Mcp-Session-Id", out var sv)) session = sv.FirstOrDefault() ?? session;
            var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            if (resp.StatusCode == HttpStatusCode.Unauthorized)
                throw new InvalidOperationException($"mcp-bridge: unauthorized (missing or wrong {McpTokenEnv})");
            var ctype = resp.Content.Headers.ContentType?.MediaType ?? "";
            if (ctype.StartsWith("text/event-stream", StringComparison.Ordinal))
            {
                foreach (var l in body.Split('\n'))
                {
                    var t = l.TrimEnd('\r');
                    if (t.StartsWith("data:", StringComparison.Ordinal))
                        await output.WriteLineAsync(t["data:".Length..].Trim()).ConfigureAwait(false);
                }
            }
            else if (body.Trim() is { Length: > 0 } b && b[0] == '{')
            {
                await output.WriteLineAsync(b).ConfigureAwait(false);
            }
            await output.FlushAsync(ct).ConfigureAwait(false);
        }
    }
}

/// <summary>One live ACP session of a CLI client: the agent, the served toolkit, the temp cwd.</summary>
internal sealed class CliAcpSession : IDisposable
{
    private AcpClient? _client;
    private HttpListener? _listener;
    private CancellationTokenSource? _serveCts;
    private string _url = "";
    private string _token = "";
    private string? _tempDir;
    private readonly HashSet<string> _served = new();
    private int _timeout;
    private string? _hint;

    private readonly object _mu = new();
    private readonly Dictionary<string, string> _calls = new();      // toolCallId -> first non-empty title
    private readonly Dictionary<string, bool> _decisions = new();    // toolCallId -> allowed
    private readonly List<CliPermissionDecision> _log = new();
    private readonly List<string> _ran = new();
    private int _disposed;

    public static async Task<CliAcpSession> OpenAsync(CliClient c, CliOptions opts, List<string> argv,
        Dictionary<string, string> env, int timeoutMs)
    {
        var bin = CliClient.LookPath(argv[0])
                  ?? throw new CliAcpLaunchException($"cli: command not found: {argv[0]}");
        var s = new CliAcpSession
        {
            _token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant(),
            _timeout = timeoutMs,
            _hint = c.Style.Acp?.SystemHint,
        };
        var tools = CliClient.ServedTools(opts.Toolkit, opts.Hooks, name => { lock (s._mu) s._ran.Add(name); });
        foreach (var t in tools) s._served.Add(t.Name);
        s.Serve(tools);

        try
        {
            var cwd = Path.GetFullPath(string.IsNullOrEmpty(opts.Cwd) ? Directory.GetCurrentDirectory() : opts.Cwd);
            var servers = new List<AcpMcpServer>();
            var bearer = new Dictionary<string, string> { ["Authorization"] = "Bearer " + s._token };
            var projectFile = c.Style.Acp?.ProjectMcpFile;
            if (!string.IsNullOrEmpty(projectFile) && opts.AcpCommand is not { Count: > 0 })
            {
                s._tempDir = Directory.CreateTempSubdirectory("toolnexus-acp-").FullName;
                cwd = s._tempDir;
                var f = Path.Combine(s._tempDir, projectFile);
                Directory.CreateDirectory(Path.GetDirectoryName(f)!);
                var body = Json.Stringify(new Dictionary<string, object?>
                {
                    ["mcpServers"] = new Dictionary<string, object?>
                    {
                        [CliClient.AcpServerName] = new Dictionary<string, object?>
                        {
                            ["type"] = "http", ["url"] = s._url, ["headers"] = bearer,
                        },
                    },
                });
                File.WriteAllText(f, "");
                if (!OperatingSystem.IsWindows())
                    File.SetUnixFileMode(f, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                File.WriteAllText(f, body);
            }
            else
            {
                var bridge = opts.BridgeCommand;
                if (bridge is not { Count: > 0 } && CliClient.LookPath("toolnexus") is { } tn)
                    bridge = new List<string> { tn, "mcp-bridge" };
                servers.Add(bridge is { Count: > 0 }
                    ? new AcpMcpServer
                    {
                        Name = CliClient.AcpServerName, Url = s._url, Headers = bearer,
                        Command = bridge[0], Args = bridge.Skip(1).Append(s._url).ToList(),
                        Env = new Dictionary<string, string> { [CliClient.McpTokenEnv] = s._token },
                    }
                    : new AcpMcpServer { Name = CliClient.AcpServerName, Url = s._url, Headers = bearer });
            }

            var stderr = new StringBuilder();
            var timeout = TimeSpan.FromMilliseconds(timeoutMs);
            try
            {
                s._client = await AcpClient.StartAsync(new AcpStartOptions
                {
                    Command = bin,
                    Arguments = argv.Skip(1).ToList(),
                    Env = CliClient.ExpandEnv(env),
                    Cwd = cwd,
                    CallTimeout = timeout,
                    PromptTimeout = timeout,
                    McpServers = servers,
                    Model = string.IsNullOrEmpty(opts.Model) ? null : opts.Model,
                    OnPermission = s.Permission,
                    OnUpdate = raw =>
                    {
                        s.Update(raw);
                        opts.OnAcpUpdate?.Invoke(raw);
                    },
                    Stderr = line =>
                    {
                        lock (stderr)
                        {
                            stderr.Append(line).Append('\n');
                            if (stderr.Length > 4096) stderr.Remove(0, stderr.Length - 4096);
                        }
                    },
                }).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                // Give the stderr drain a moment to catch the agent's last words.
                await Task.Delay(50).ConfigureAwait(false);
                string tail;
                lock (stderr) tail = stderr.ToString().Trim();
                var msg = e.Message;
                if (msg.StartsWith("toolnexus: acp:", StringComparison.Ordinal)) msg = "cli: acp:" + msg["toolnexus: acp:".Length..];
                else msg = "cli: acp: " + msg;
                if (tail.Length > 0) msg += ": " + tail;
                if (e is AcpLaunchException) throw new CliAcpLaunchException(msg, e);
                throw new CliException(msg, e);
            }
        }
        catch
        {
            s.Dispose();
            throw;
        }
        return s;
    }

    private void Serve(List<ITool> tools)
    {
        var port = A2AServer.FreePort();
        _url = $"http://127.0.0.1:{port}/mcp";
        var listener = new HttpListener();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        try
        {
            listener.Start();
        }
        catch (Exception e)
        {
            throw new CliException("cli: acp: serve toolkit: " + e.Message, e);
        }
        _listener = listener;
        var cts = _serveCts = new CancellationTokenSource();
        var cfg = new MCPServeConfig { Name = CliClient.AcpServerName };
        var want = Encoding.UTF8.GetBytes("Bearer " + _token);
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync().ConfigureAwait(false); }
                catch { break; }
                _ = Task.Run(async () =>
                {
                    try
                    {
                        if (ctx.Request.Url?.AbsolutePath != "/mcp")
                        {
                            Status(ctx, 404, "not found");
                            return;
                        }
                        var got = Encoding.UTF8.GetBytes(ctx.Request.Headers["Authorization"] ?? "");
                        if (got.Length != want.Length || !CryptographicOperations.FixedTimeEquals(got, want))
                        {
                            ctx.Response.Headers["WWW-Authenticate"] = "Bearer";
                            Status(ctx, 401, "unauthorized");
                            return;
                        }
                        await A2AServer.HandleMcpAsync(ctx, tools, cfg, null).ConfigureAwait(false);
                    }
                    catch { /* isolated — the server keeps serving */ }
                });
            }
        });
    }

    private static void Status(HttpListenerContext ctx, int code, string text)
    {
        try
        {
            var b = Encoding.UTF8.GetBytes(text);
            ctx.Response.StatusCode = code;
            ctx.Response.ContentType = "text/plain";
            ctx.Response.ContentLength64 = b.Length;
            ctx.Response.OutputStream.Write(b);
            ctx.Response.OutputStream.Close();
        }
        catch { /* client hung up */ }
    }

    public AcpSessionInfo Info()
    {
        lock (_mu)
            return new AcpSessionInfo(_url, Redact(_client?.McpServersSent ?? Array.Empty<object?>()),
                _log.ToList(), _ran.ToList(), _tempDir);
    }

    private static IReadOnlyList<object?> Redact(IReadOnlyList<object?> servers)
    {
        var copy = Json.ParseLoose(Json.Stringify(servers)) as List<object?> ?? new List<object?>();
        foreach (var e in copy.OfType<Dictionary<string, object?>>())
            foreach (var k in new[] { "headers", "env" })
                if (e.TryGetValue(k, out var arr) && arr is List<object?> list)
                    foreach (var nv in list.OfType<Dictionary<string, object?>>())
                        nv["value"] = "[redacted]";
        return copy;
    }

    public InProcess.Response Generate(InProcess.Request req) =>
        Task.Run(() => GenerateStreamAsync(req, null, CancellationToken.None)).GetAwaiter().GetResult();

    /// <summary>One turn on the warm session, reporting each session/update to <paramref name="emit"/>
    /// as the ACP read loop processes it (text, thought, the agent's own tool calls/results).</summary>
    public async Task<InProcess.Response> GenerateStreamAsync(InProcess.Request req,
        Action<LlmClient.StreamEvent>? emit, CancellationToken ct)
    {
        var turn = new InProcess.Request
        {
            Messages = CliClient.WithSystemHint(req.Messages, _hint),
            Tools = req.Tools,
            Model = req.Model,
            Body = req.Body,
        };
        try
        {
            return await _client!.GenerateStreamAsync(turn, emit, ct).ConfigureAwait(false);
        }
        catch (TimeoutException e)
        {
            throw new CliException($"cli: timed out after {_timeout}ms", e);
        }
    }

    /// <summary>Remembers the first non-empty title of each toolCallId (devin puts the name only on
    /// the tool_call update and sends an empty permission title).</summary>
    private void Update(JsonElement raw)
    {
        if (raw.ValueKind != JsonValueKind.Object || !raw.TryGetProperty("update", out var u) ||
            u.ValueKind != JsonValueKind.Object) return;
        var kind = Str(u, "sessionUpdate");
        var id = Str(u, "toolCallId");
        var title = Str(u, "title");
        if (id.Length == 0 || title.Length == 0 || kind is not ("tool_call" or "tool_call_update")) return;
        lock (_mu) _calls.TryAdd(id, title);
    }

    /// <summary>Decides one permission by its toolCallId, once per id, never re-made. Allowed ⇒
    /// an allow option; otherwise null so the ACP client answers with a reject option.</summary>
    private string? Permission(JsonElement p)
    {
        var tc = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("toolCall", out var t) &&
                 t.ValueKind == JsonValueKind.Object ? t : default;
        var id = tc.ValueKind == JsonValueKind.Object ? Str(tc, "toolCallId") : "";
        var title = tc.ValueKind == JsonValueKind.Object ? Str(tc, "title") : "";
        bool allowed;
        lock (_mu)
        {
            if (title.Length == 0) _calls.TryGetValue(id, out title!);
            title ??= "";
            if (!_decisions.TryGetValue(id, out allowed))
            {
                allowed = id.Length > 0 && CliClient.IsServedCall(title, _served);
                if (id.Length > 0) _decisions[id] = allowed;
            }
            _log.Add(new CliPermissionDecision(id, title, allowed));
        }
        if (!allowed) return null;
        var options = p.TryGetProperty("options", out var o) && o.ValueKind == JsonValueKind.Array
            ? o.EnumerateArray().Select(x => (Id: Str(x, "optionId"), Kind: Str(x, "kind"))).ToList()
            : new List<(string Id, string Kind)>();
        foreach (var x in options) if (x.Kind == "allow_once") return x.Id;
        foreach (var x in options) if (x.Kind.StartsWith("allow", StringComparison.Ordinal)) return x.Id;
        return null;
    }

    private static string Str(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";

    /// <summary>Ends the agent, stops the server and removes the temp session dir.</summary>
    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        try { _client?.Dispose(); } catch { /* best effort */ }
        try { _serveCts?.Cancel(); } catch { }
        try { _listener?.Stop(); } catch { }
        try { _listener?.Close(); } catch { }
        if (_tempDir != null)
            try { Directory.Delete(_tempDir, recursive: true); } catch { /* best effort */ }
    }
}
