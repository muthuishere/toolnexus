using System.Collections.Concurrent;
using System.Net;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using ModelContextProtocol;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// MCP — INBOUND, embeddable: a plain streamable-HTTP MCP server as a mountable
// handler with generic per-request hooks (SPEC §7C.1, ADR 0039).
//
// ServeAsync(addr, {Mcp}) (McpServe.cs) owns a listener and exposes one static
// tool list. McpHttpHandler is what a host mounts on its own server, behind its
// own TLS, CORS and OAuth. Every policy is a hook; toolnexus has no notion of
// tenant, user, role or storage — the host decides those inside the hooks.
//
// Hosting: the handler speaks to a tiny McpHttpExchange abstraction (method,
// headers, body in; status, headers, body out). HandleAsync(HttpListenerContext)
// adapts the HttpListener the port already uses for ServeAsync; an ASP.NET Core
// host subclasses McpHttpExchange over its HttpContext in ~20 lines. No new
// framework dependency is added.
//
// The JSON-RPC transport is implemented here by hand, like the Go reference
// (golang/mcphandler.go): a confirm elicitation rides the SAME POST's SSE
// response, and tools are resolved per request.
// ---------------------------------------------------------------------------

/// <summary>Outcome kind of a <see cref="McpHandlerConfig.BeforeCall"/> gate.</summary>
public enum McpDecisionKind
{
    Allow,
    Deny,
    Confirm,
}

/// <summary>
/// What <see cref="McpHandlerConfig.BeforeCall"/> returns. The default allows.
/// <list type="bullet">
///   <item>Allow: the tool runs.</item>
///   <item>Deny: the tool does not run; the caller gets <see cref="Result"/> when set, else an
///   isError result carrying <see cref="Message"/> ("denied" when empty).</item>
///   <item>Confirm: the client is asked (elicitation/create, <see cref="Message"/> as the prompt)
///   when it declared the elicitation capability; accept ⇒ the tool runs, decline/cancel ⇒ isError
///   "declined". When the client cannot elicit, the caller gets <see cref="Result"/> when set, else
///   an isError "confirmation required: &lt;Message&gt;".</item>
/// </list>
/// </summary>
public sealed record McpDecision(McpDecisionKind Kind = McpDecisionKind.Allow, string? Message = null, ToolResult? Result = null)
{
    public static McpDecision Allow() => new();
    public static McpDecision Deny(string? message = null, ToolResult? result = null) => new(McpDecisionKind.Deny, message, result);
    public static McpDecision Confirm(string? message = null, ToolResult? result = null) => new(McpDecisionKind.Confirm, message, result);
}

/// <summary>Advertised at initialize. An empty <see cref="Title"/> is omitted.</summary>
public sealed record McpServerInfo(string? Name = null, string? Version = null, string? Instructions = null, string? Title = null);

/// <summary>One entry of resources/list.</summary>
public sealed record McpResource(string Uri, string Name, string? Title = null, string? Description = null, string? MimeType = null);

/// <summary>One entry of resources/read. Set <see cref="Text"/>, or <see cref="Blob"/> (base64).</summary>
public sealed record McpResourceContent(string Uri, string? MimeType = null, string? Text = null, string? Blob = null);

/// <summary>One prompt argument.</summary>
public sealed record McpPromptArgument(string Name, string? Description = null, bool Required = false);

/// <summary>One entry of prompts/list.</summary>
public sealed record McpPrompt(string Name, string? Title = null, string? Description = null, IReadOnlyList<McpPromptArgument>? Arguments = null);

/// <summary>One text message of a prompts/get result. Role is "user" or "assistant".</summary>
public sealed record McpPromptMessage(string Role, string Text);

/// <summary>The prompts/get result.</summary>
public sealed record McpPromptResult(IReadOnlyList<McpPromptMessage> Messages, string? Description = null);

/// <summary>Surfaced once per inbound tools/call.</summary>
public sealed record McpCallEvent(
    string Name,
    string Source,
    long Ms,
    bool IsError,
    Exception? Err,
    int ResultBytes,
    McpDecisionKind Decision,
    object? Identity,
    string SessionId);

/// <summary>
/// Issues and checks <c>Mcp-Session-Id</c> values. The default is an in-memory set. A host plugs its
/// own (signed ids, a DB lookup) so any replica can validate any session.
/// </summary>
public interface IMcpSessionIds
{
    Task<string> GenerateAsync(McpContext ctx);

    /// <summary>Whether <paramref name="id"/> is a live session (false ⇒ HTTP 404).</summary>
    Task<bool> ValidateAsync(McpContext ctx, string id);

    Task TerminateAsync(McpContext ctx, string id);
}

/// <summary>
/// The per-request context of an <see cref="McpHttpHandler"/> request: the identity
/// <see cref="McpHandlerConfig.Authenticate"/> produced, the inbound request, the session id and the
/// JSON-RPC <c>params._meta</c>. Every hook receives it; a tool reads it from <see cref="Current"/>
/// (an <see cref="AsyncLocal{T}"/> that flows into <see cref="ITool.ExecuteAsync"/>).
/// </summary>
public sealed class McpContext
{
    private static readonly AsyncLocal<McpContext?> CurrentSlot = new();

    /// <summary>The context of the MCP request being served on this async flow (null outside one).</summary>
    public static McpContext? Current => CurrentSlot.Value;

    internal McpContext(McpHttpExchange request) => Request = request;

    /// <summary>The value Authenticate produced (null when no Authenticate is configured).</summary>
    public object? Identity { get; internal set; }

    /// <summary>The inbound HTTP request (e.g. to read the Host header).</summary>
    public McpHttpExchange Request { get; }

    /// <summary>The Mcp-Session-Id of the request ("" if none).</summary>
    public string SessionId { get; internal set; } = "";

    /// <summary>The <c>params._meta</c> of the JSON-RPC request (null when absent).</summary>
    public IReadOnlyDictionary<string, object?>? Meta { get; internal set; }

    /// <summary>Cancelled when the HTTP request is aborted (when the host can tell).</summary>
    public CancellationToken CancellationToken => Request.Aborted;

    internal void MakeCurrent() => CurrentSlot.Value = this;
}

/// <summary>
/// The HTTP exchange an <see cref="McpHttpHandler"/> serves. <see cref="FromListener"/> adapts an
/// <see cref="HttpListenerContext"/>; a host on another server (ASP.NET Core, …) subclasses it.
/// Headers and status must be set before the first write to <see cref="ResponseBody"/>.
/// </summary>
public abstract class McpHttpExchange
{
    public abstract string Method { get; }

    /// <summary>A request header value, or null (case-insensitive name).</summary>
    public abstract string? Header(string name);

    public abstract Stream RequestBody { get; }

    public virtual CancellationToken Aborted => default;

    /// <summary>The native request object (e.g. the <see cref="HttpListenerContext"/>), when any.</summary>
    public virtual object? Native => null;

    public abstract void SetStatus(int status);

    public abstract void SetHeader(string name, string value);

    public abstract Stream ResponseBody { get; }

    /// <summary>The request path (e.g. "/mcp"); <see cref="McpServerAuth.Metadata"/> routes on it.
    /// Default "/"; the HttpListener adapter returns the real path.</summary>
    public virtual string Path => "/";

    /// <summary>"http" or "https" — used to derive a default audience / metadata URL. Default "http".</summary>
    public virtual string Scheme => "http";

    /// <summary>Adapt an <see cref="HttpListenerContext"/>.</summary>
    public static McpHttpExchange FromListener(HttpListenerContext ctx) => new ListenerExchange(ctx);

    private sealed class ListenerExchange(HttpListenerContext ctx) : McpHttpExchange
    {
        private bool _started;

        public override string Method => ctx.Request.HttpMethod;
        public override string? Header(string name) => ctx.Request.Headers[name];
        public override Stream RequestBody => ctx.Request.InputStream;
        public override object? Native => ctx;
        public override string Path => ctx.Request.Url?.AbsolutePath ?? "/";
        public override string Scheme => ctx.Request.Url?.Scheme ?? "http";
        public override void SetStatus(int status) => ctx.Response.StatusCode = status;

        public override void SetHeader(string name, string value)
        {
            if (name.Equals("Content-Type", StringComparison.OrdinalIgnoreCase)) ctx.Response.ContentType = value;
            else ctx.Response.AddHeader(name, value);
        }

        public override Stream ResponseBody
        {
            get
            {
                if (!_started)
                {
                    ctx.Response.SendChunked = true; // headers are sent on the first write
                    _started = true;
                }
                return ctx.Response.OutputStream;
            }
        }
    }
}

/// <summary>Configures <see cref="Toolkit.McpHandler"/>. Every member is optional.</summary>
public sealed class McpHandlerConfig
{
    /// <summary>Static serverInfo; <see cref="ServerInfo"/>, when set, wins. Defaults "toolnexus" / "0.1.0".</summary>
    public string? Name { get; set; }
    public string? Title { get; set; }
    public string? Version { get; set; }
    public string? Instructions { get; set; }
    public Func<McpContext, McpServerInfo>? ServerInfo { get; set; }

    /// <summary>Protocol versions accepted, newest first. Entries outside
    /// <see cref="McpHttpHandler.ImplementedVersions"/> are dropped. Default ["2025-06-18"].</summary>
    public IReadOnlyList<string>? ProtocolVersions { get; set; }

    /// <summary>Runs on every HTTP request before JSON-RPC parsing; its result is
    /// <see cref="McpContext.Identity"/>. A throw ⇒ HTTP 401.</summary>
    public Func<McpHttpExchange, Task<object?>>? Authenticate { get; set; }

    /// <summary>The 401's WWW-Authenticate header value (omitted when null/empty).</summary>
    public Func<McpHttpExchange, Exception, string?>? WwwAuthenticate { get; set; }

    /// <summary>Resolves the tool list per request, for tools/list AND the tools/call lookup (a name
    /// outside it is never executed). null ⇒ the toolkit's tools.</summary>
    public Func<McpContext, Task<IReadOnlyList<ITool>>>? ListTools { get; set; }

    /// <summary>Gates each tools/call. The JsonElement is the raw arguments object.</summary>
    public Func<McpContext, ITool, JsonElement, Task<McpDecision>>? BeforeCall { get; set; }

    /// <summary>Bounds an elicitation round-trip. Default 5 minutes.</summary>
    public TimeSpan ConfirmTimeout { get; set; }

    /// <summary>Fires once per tools/call; its throws are isolated.</summary>
    public Action<McpCallEvent>? OnCall { get; set; }

    /// <summary>Resources / prompts. A null List hook ⇒ capability not advertised (-32601).</summary>
    public Func<McpContext, Task<IReadOnlyList<McpResource>>>? ListResources { get; set; }
    public Func<McpContext, string, Task<IReadOnlyList<McpResourceContent>>>? ReadResource { get; set; }
    public Func<McpContext, Task<IReadOnlyList<McpPrompt>>>? ListPrompts { get; set; }
    public Func<McpContext, string, IReadOnlyDictionary<string, string>, Task<McpPromptResult>>? GetPrompt { get; set; }

    /// <summary>Publishes skills through the SEP-2640 skills extension on the 2026-07-28 wire
    /// (skills/list, skills/get, resources/read of skill:// URIs; SPEC §7C.2). null ⇒ not advertised.
    /// <see cref="McpHttpHandler.LoadMcpSkills"/> builds the list from a skills directory.</summary>
    public Func<McpContext, Task<IReadOnlyList<McpSkill>>>? ListSkills { get; set; }

    /// <summary>Replaces the default in-memory session set.</summary>
    public IMcpSessionIds? SessionIds { get; set; }

    /// <summary>Issue no Mcp-Session-Id and check none; elicitation is then never used.</summary>
    public bool Stateless { get; set; }

    /// <summary>In Stateless mode, returned as Mcp-Session-Id on initialize; never validated.</summary>
    public string? StatelessSessionId { get; set; }

    /// <summary>Caps a POST body. Default 4 MiB.</summary>
    public long MaxBodyBytes { get; set; }
}

/// <summary>
/// The embeddable streamable-HTTP MCP server returned by <see cref="Toolkit.McpHandler"/> (SPEC §7C.1).
/// Mount <see cref="HandleAsync(McpHttpExchange)"/> (or the HttpListener overload) on the host's server.
/// </summary>
public sealed partial class McpHttpHandler
{
    /// <summary>The MCP protocol versions whose wire this handler implements (ADR 0039). 2026-07-28 is
    /// opt-in only (never a default): the stateless wire of SPEC §7C.2, served by McpHandler2026.cs.</summary>
    public static readonly IReadOnlyList<string> ImplementedVersions = new[] { "2026-07-28", "2025-11-25", "2025-06-18" };

    private static readonly string[] DefaultVersions = { "2025-06-18" };

    private static readonly JsonSerializerOptions WireJson = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private readonly Toolkit _tk;
    private readonly McpHandlerConfig _cfg;
    private readonly IMcpSessionIds _ids;
    private readonly List<string> _versions = new();
    private readonly List<string> _legacy = new(); // _versions minus 2026-07-28: the initialize/session wire
    private readonly TimeSpan _confirmTimeout;
    private readonly long _maxBody;
    private readonly ConcurrentDictionary<string, bool> _caps = new();
    private readonly ConcurrentDictionary<Channel<string>, byte> _streams = new();
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonElement>> _pending = new();
    private long _seq;

    internal McpHttpHandler(Toolkit tk, McpHandlerConfig? cfg)
    {
        _tk = tk;
        _cfg = cfg ?? new McpHandlerConfig();
        foreach (var v in _cfg.ProtocolVersions ?? Array.Empty<string>())
            if (ImplementedVersions.Contains(v) && !_versions.Contains(v)) _versions.Add(v);
        if (_versions.Count == 0) _versions.AddRange(DefaultVersions);
        _legacy.AddRange(_versions.Where(v => v != Protocol2026));
        _ids = _cfg.SessionIds ?? new MemorySessionIds();
        _confirmTimeout = _cfg.ConfirmTimeout > TimeSpan.Zero ? _cfg.ConfirmTimeout : TimeSpan.FromMinutes(5);
        _maxBody = _cfg.MaxBodyBytes > 0 ? _cfg.MaxBodyBytes : 4 << 20;
    }

    /// <summary>Sends notifications/tools/list_changed on every open GET stream.</summary>
    public void NotifyToolsChanged()
    {
        const string msg = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}";
        foreach (var ch in _streams.Keys) ch.Writer.TryWrite(msg); // full (slow) stream ⇒ dropped
    }

    private sealed class MemorySessionIds : IMcpSessionIds
    {
        private readonly ConcurrentDictionary<string, byte> _live = new();

        public Task<string> GenerateAsync(McpContext ctx)
        {
            var id = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
            _live[id] = 0;
            return Task.FromResult(id);
        }

        public Task<bool> ValidateAsync(McpContext ctx, string id) => Task.FromResult(_live.ContainsKey(id));

        public Task TerminateAsync(McpContext ctx, string id)
        {
            _live.TryRemove(id, out _);
            return Task.CompletedTask;
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    /// <summary>Serve one HttpListener request, closing its response when done.</summary>
    public async Task HandleAsync(HttpListenerContext ctx)
    {
        try { await HandleAsync(McpHttpExchange.FromListener(ctx)).ConfigureAwait(false); }
        finally { try { ctx.Response.OutputStream.Close(); } catch { } }
    }

    /// <summary>Serve one HTTP request (any method; the host routes the MCP path here).</summary>
    public async Task HandleAsync(McpHttpExchange ex)
    {
        if (ex.Method == "POST" && _versions.Contains(Protocol2026))
        {
            var (handled, buffered) = await HandlePost2026Async(ex).ConfigureAwait(false);
            if (handled) return;
            ex = buffered ?? ex;
        }
        if (Only2026 && ex.Method != "POST")
        {
            ex.SetHeader("Allow", "POST");
            await PlainAsync(ex, 405, "Method Not Allowed").ConfigureAwait(false);
            return;
        }
        var ctx = new McpContext(ex);
        ctx.MakeCurrent();
        if (_cfg.Authenticate != null)
        {
            try
            {
                ctx.Identity = await _cfg.Authenticate(ex).ConfigureAwait(false);
            }
            catch (Exception err)
            {
                await WriteAuthFailureAsync(ex, err).ConfigureAwait(false);
                return;
            }
        }
        switch (ex.Method)
        {
            case "POST": await PostAsync(ctx, ex).ConfigureAwait(false); break;
            case "GET": await GetAsync(ctx, ex).ConfigureAwait(false); break;
            case "DELETE": await DeleteAsync(ctx, ex).ConfigureAwait(false); break;
            default:
                ex.SetHeader("Allow", "GET, POST, DELETE");
                await PlainAsync(ex, 405, "Method Not Allowed").ConfigureAwait(false);
                break;
        }
    }

    /// <summary>Validates MCP-Protocol-Version and (stateful) Mcp-Session-Id; writes the error itself.</summary>
    private async Task<bool> CheckSessionAsync(McpContext ctx, McpHttpExchange ex)
    {
        var v = ex.Header("MCP-Protocol-Version");
        if (!string.IsNullOrEmpty(v) && !_versions.Contains(v))
        {
            await PlainAsync(ex, 400, "Bad Request: unsupported MCP-Protocol-Version").ConfigureAwait(false);
            return false;
        }
        if (_cfg.Stateless) return true;
        var sid = ex.Header("Mcp-Session-Id");
        if (string.IsNullOrEmpty(sid))
        {
            await PlainAsync(ex, 400, "Bad Request: missing Mcp-Session-Id").ConfigureAwait(false);
            return false;
        }
        bool ok;
        try { ok = await _ids.ValidateAsync(ctx, sid).ConfigureAwait(false); }
        catch { ok = false; }
        if (!ok)
        {
            await PlainAsync(ex, 404, "Session not found").ConfigureAwait(false);
            return false;
        }
        ctx.SessionId = sid;
        return true;
    }

    private sealed record RpcMessage(JsonNode? Id, bool HasId, string Method, JsonElement? Params, JsonElement Raw);

    private async Task PostAsync(McpContext ctx, McpHttpExchange ex)
    {
        var ct = (ex.Header("Content-Type") ?? "").Split(';')[0].Trim().ToLowerInvariant();
        if (ct != "application/json")
        {
            await PlainAsync(ex, 415, "Unsupported Media Type: must be application/json").ConfigureAwait(false);
            return;
        }
        byte[] body;
        try
        {
            using var ms = new MemoryStream();
            var buf = new byte[81920];
            int n;
            while ((n = await ex.RequestBody.ReadAsync(buf).ConfigureAwait(false)) > 0)
            {
                ms.Write(buf, 0, n);
                if (ms.Length > _maxBody) break;
            }
            body = ms.ToArray();
        }
        catch
        {
            await RpcErrorAsync(ex, null, -32700, "read error").ConfigureAwait(false);
            return;
        }
        if (body.LongLength > _maxBody)
        {
            await PlainAsync(ex, 413, "Request Entity Too Large").ConfigureAwait(false);
            return;
        }
        if (Encoding.UTF8.GetString(body).TrimStart().StartsWith('['))
        {
            // JSON-RPC batching was removed in protocol 2025-06-18.
            await RpcErrorAsync(ex, null, -32600, "batch requests are not supported").ConfigureAwait(false);
            return;
        }
        var msg = ParseMessage(body);
        if (msg == null)
        {
            await RpcErrorAsync(ex, null, -32700, "parse error").ConfigureAwait(false);
            return;
        }

        if (msg.Method == "initialize")
        {
            await InitializeAsync(ctx, ex, msg).ConfigureAwait(false);
            return;
        }
        if (!await CheckSessionAsync(ctx, ex).ConfigureAwait(false)) return;

        // A response from the client (the answer to our elicitation).
        if (msg.Method == "")
        {
            if (msg.HasId) Deliver(ctx.SessionId, msg);
            await EmptyAsync(ex, 202).ConfigureAwait(false);
            return;
        }
        // A notification (no id): acknowledge.
        if (!msg.HasId || msg.Id == null)
        {
            await EmptyAsync(ex, 202).ConfigureAwait(false);
            return;
        }
        if (msg.Params is { ValueKind: JsonValueKind.Object } p && p.TryGetProperty("_meta", out var meta)
            && meta.ValueKind == JsonValueKind.Object)
            ctx.Meta = (Dictionary<string, object?>)Json.FromElement(meta)!;

        var rw = new RpcWriter(ex);
        JsonNode? result = null;
        RpcError? err;
        try { (result, err) = await DispatchAsync(ctx, rw, msg).ConfigureAwait(false); }
        catch (Exception e) { err = new RpcError(-32603, e.Message); }
        await rw.RespondAsync(msg.Id, result, err).ConfigureAwait(false);
    }

    private static RpcMessage? ParseMessage(byte[] body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement.Clone();
            if (root.ValueKind != JsonValueKind.Object) return null;
            if (!root.TryGetProperty("jsonrpc", out var jr) || jr.ValueKind != JsonValueKind.String || jr.GetString() != "2.0")
                return null;
            var method = "";
            if (root.TryGetProperty("method", out var m) && m.ValueKind != JsonValueKind.Null)
            {
                if (m.ValueKind != JsonValueKind.String) return null;
                method = m.GetString() ?? "";
            }
            var hasId = root.TryGetProperty("id", out var id);
            JsonElement? prm = root.TryGetProperty("params", out var pe) ? pe : null;
            return new RpcMessage(hasId ? JsonNode.Parse(id.GetRawText()) : null, hasId, method, prm, root);
        }
        catch
        {
            return null;
        }
    }

    private async Task InitializeAsync(McpContext ctx, McpHttpExchange ex, RpcMessage msg)
    {
        string? clientVersion = null;
        var elicit = false;
        if (msg.Params is { ValueKind: JsonValueKind.Object } p)
        {
            if (p.TryGetProperty("protocolVersion", out var pv) && pv.ValueKind == JsonValueKind.String)
                clientVersion = pv.GetString();
            if (p.TryGetProperty("capabilities", out var caps) && caps.ValueKind == JsonValueKind.Object
                && caps.TryGetProperty("elicitation", out var el) && el.ValueKind != JsonValueKind.Null)
                elicit = true;
        }
        var version = clientVersion != null && _legacy.Contains(clientVersion) ? clientVersion : _legacy[0];

        string? sid;
        if (_cfg.Stateless)
        {
            sid = _cfg.StatelessSessionId;
        }
        else
        {
            try { sid = await _ids.GenerateAsync(ctx).ConfigureAwait(false); }
            catch
            {
                await RpcErrorAsync(ex, msg.Id, -32603, "cannot create session").ConfigureAwait(false);
                return;
            }
            ctx.SessionId = sid;
            _caps[sid] = elicit;
        }

        var info = _cfg.ServerInfo != null
            ? _cfg.ServerInfo(ctx)
            : new McpServerInfo(_cfg.Name, _cfg.Version, _cfg.Instructions, _cfg.Title);
        var capsNode = new JsonObject { ["tools"] = new JsonObject { ["listChanged"] = true } };
        if (_cfg.ListResources != null) capsNode["resources"] = new JsonObject();
        if (_cfg.ListPrompts != null) capsNode["prompts"] = new JsonObject();
        var serverInfo = new JsonObject { ["name"] = string.IsNullOrEmpty(info.Name) ? "toolnexus" : info.Name };
        if (!string.IsNullOrEmpty(info.Title)) serverInfo["title"] = info.Title;
        serverInfo["version"] = string.IsNullOrEmpty(info.Version) ? "0.1.0" : info.Version;
        var res = new JsonObject
        {
            ["protocolVersion"] = version,
            ["capabilities"] = capsNode,
            ["serverInfo"] = serverInfo,
        };
        if (!string.IsNullOrEmpty(info.Instructions)) res["instructions"] = info.Instructions;
        if (!string.IsNullOrEmpty(sid)) ex.SetHeader("Mcp-Session-Id", sid);
        await new RpcWriter(ex).RespondAsync(msg.Id, res, null).ConfigureAwait(false);
    }

    private async Task GetAsync(McpContext ctx, McpHttpExchange ex)
    {
        if (!(ex.Header("Accept") ?? "").Contains("text/event-stream"))
        {
            await PlainAsync(ex, 405, "Method Not Allowed").ConfigureAwait(false);
            return;
        }
        if (!await CheckSessionAsync(ctx, ex).ConfigureAwait(false)) return;
        ex.SetStatus(200);
        ex.SetHeader("Content-Type", "text/event-stream");
        ex.SetHeader("Cache-Control", "no-cache");
        var body = ex.ResponseBody;
        var ch = Channel.CreateBounded<string>(new BoundedChannelOptions(16) { FullMode = BoundedChannelFullMode.DropWrite });
        _streams[ch] = 0;
        try
        {
            await body.FlushAsync(ex.Aborted).ConfigureAwait(false);
            while (true)
            {
                var m = await ch.Reader.ReadAsync(ex.Aborted).ConfigureAwait(false);
                await body.WriteAsync(Encoding.UTF8.GetBytes($"event: message\ndata: {m}\n\n"), ex.Aborted).ConfigureAwait(false);
                await body.FlushAsync(ex.Aborted).ConfigureAwait(false);
            }
        }
        catch
        {
            // stream closed / request aborted
        }
        finally
        {
            _streams.TryRemove(ch, out _);
        }
    }

    private async Task DeleteAsync(McpContext ctx, McpHttpExchange ex)
    {
        if (_cfg.Stateless)
        {
            await PlainAsync(ex, 405, "Method Not Allowed").ConfigureAwait(false);
            return;
        }
        if (!await CheckSessionAsync(ctx, ex).ConfigureAwait(false)) return;
        try { await _ids.TerminateAsync(ctx, ctx.SessionId).ConfigureAwait(false); }
        catch
        {
            await PlainAsync(ex, 500, "cannot terminate session").ConfigureAwait(false);
            return;
        }
        _caps.TryRemove(ctx.SessionId, out _);
        await EmptyAsync(ex, 200).ConfigureAwait(false);
    }

    // ── JSON-RPC dispatch ─────────────────────────────────────────────────

    private sealed record RpcError(int Code, string Message);

    private async Task<(JsonNode?, RpcError?)> DispatchAsync(McpContext ctx, RpcWriter? rw, RpcMessage msg)
    {
        var p = msg.Params is { ValueKind: JsonValueKind.Object } pe ? pe : (JsonElement?)null;
        switch (msg.Method)
        {
            case "ping":
                return (new JsonObject(), null);
            case "tools/list":
            {
                IReadOnlyList<ITool> tools;
                try { tools = await ToolsAsync(ctx).ConfigureAwait(false); }
                catch (Exception e) { return (null, new RpcError(-32603, e.Message)); }
                var arr = new JsonArray();
                foreach (var t in tools) arr.Add(WireTool(t));
                return (new JsonObject { ["tools"] = arr }, null);
            }
            case "tools/call":
                return await CallToolAsync(ctx, rw!, msg.Params).ConfigureAwait(false);
            case "resources/list" when _cfg.ListResources != null:
            {
                IReadOnlyList<McpResource> rs;
                try { rs = await _cfg.ListResources(ctx).ConfigureAwait(false); }
                catch (Exception e) { return (null, new RpcError(-32603, e.Message)); }
                var arr = new JsonArray();
                foreach (var r in rs)
                    arr.Add(OmitEmpty(("uri", r.Uri), ("name", r.Name), ("title", r.Title), ("description", r.Description), ("mimeType", r.MimeType)));
                return (new JsonObject { ["resources"] = arr }, null);
            }
            case "resources/read" when _cfg.ListResources != null && _cfg.ReadResource != null:
            {
                var uri = p is { } pp && pp.TryGetProperty("uri", out var u) && u.ValueKind == JsonValueKind.String ? u.GetString() ?? "" : "";
                IReadOnlyList<McpResourceContent> cs;
                try { cs = await _cfg.ReadResource(ctx, uri).ConfigureAwait(false); }
                catch (Exception e) { return (null, new RpcError(-32002, e.Message)); }
                var arr = new JsonArray();
                foreach (var c in cs)
                {
                    var m = OmitEmpty(("uri", c.Uri), ("mimeType", c.MimeType), ("blob", c.Blob));
                    if (string.IsNullOrEmpty(c.Blob)) m["text"] = c.Text ?? "";
                    arr.Add(m);
                }
                return (new JsonObject { ["contents"] = arr }, null);
            }
            case "prompts/list" when _cfg.ListPrompts != null:
            {
                IReadOnlyList<McpPrompt> ps;
                try { ps = await _cfg.ListPrompts(ctx).ConfigureAwait(false); }
                catch (Exception e) { return (null, new RpcError(-32603, e.Message)); }
                var arr = new JsonArray();
                foreach (var pr in ps)
                {
                    var m = OmitEmpty(("name", pr.Name), ("title", pr.Title), ("description", pr.Description));
                    if (pr.Arguments is { Count: > 0 })
                    {
                        var args = new JsonArray();
                        foreach (var a in pr.Arguments)
                        {
                            var am = OmitEmpty(("name", a.Name), ("description", a.Description));
                            if (a.Required) am["required"] = true;
                            args.Add(am);
                        }
                        m["arguments"] = args;
                    }
                    arr.Add(m);
                }
                return (new JsonObject { ["prompts"] = arr }, null);
            }
            case "prompts/get" when _cfg.ListPrompts != null && _cfg.GetPrompt != null:
            {
                var name = "";
                var args = new Dictionary<string, string>();
                if (msg.Params is { } raw && raw.ValueKind != JsonValueKind.Null)
                {
                    if (raw.ValueKind != JsonValueKind.Object) return (null, new RpcError(-32602, "invalid params"));
                    if (raw.TryGetProperty("name", out var n))
                    {
                        if (n.ValueKind != JsonValueKind.String) return (null, new RpcError(-32602, "invalid params"));
                        name = n.GetString() ?? "";
                    }
                    if (raw.TryGetProperty("arguments", out var a) && a.ValueKind != JsonValueKind.Null)
                    {
                        if (a.ValueKind != JsonValueKind.Object) return (null, new RpcError(-32602, "invalid params"));
                        foreach (var prop in a.EnumerateObject())
                        {
                            if (prop.Value.ValueKind != JsonValueKind.String) return (null, new RpcError(-32602, "invalid params"));
                            args[prop.Name] = prop.Value.GetString() ?? "";
                        }
                    }
                }
                McpPromptResult pr;
                try { pr = await _cfg.GetPrompt(ctx, name, args).ConfigureAwait(false); }
                catch (Exception e) { return (null, new RpcError(-32602, e.Message)); }
                var msgs = new JsonArray();
                foreach (var m in pr.Messages)
                    msgs.Add(new JsonObject { ["role"] = m.Role, ["content"] = new JsonObject { ["type"] = "text", ["text"] = m.Text } });
                var outObj = OmitEmpty(("description", pr.Description));
                outObj["messages"] = msgs;
                return (outObj, null);
            }
        }
        return (null, new RpcError(-32601, "method not found: " + msg.Method));
    }

    private async Task<IReadOnlyList<ITool>> ToolsAsync(McpContext ctx)
        => _cfg.ListTools != null ? await _cfg.ListTools(ctx).ConfigureAwait(false) : _tk.Tools();

    private async Task<(JsonNode?, RpcError?)> CallToolAsync(McpContext ctx, RpcWriter rw, JsonElement? prm)
    {
        var name = "";
        JsonElement? rawArgs = null;
        if (prm is { } p && p.ValueKind != JsonValueKind.Null)
        {
            if (p.ValueKind != JsonValueKind.Object) return (null, new RpcError(-32602, "invalid params"));
            if (p.TryGetProperty("name", out var n))
            {
                if (n.ValueKind != JsonValueKind.String) return (null, new RpcError(-32602, "invalid params"));
                name = n.GetString() ?? "";
            }
            if (p.TryGetProperty("arguments", out var a)) rawArgs = a;
        }
        IReadOnlyList<ITool> tools;
        try { tools = await ToolsAsync(ctx).ConfigureAwait(false); }
        catch (Exception e) { return (null, new RpcError(-32603, e.Message)); }
        var tool = tools.FirstOrDefault(t => t.Name == name);
        if (tool == null) return (null, new RpcError(-32602, "tool not found: " + name));

        IDictionary<string, object?> args = new Dictionary<string, object?>();
        JsonElement argsElement;
        if (rawArgs is { } ra && ra.ValueKind != JsonValueKind.Null)
        {
            if (ra.ValueKind != JsonValueKind.Object)
            {
                if (ctx.Request.Header("MCP-Protocol-Version") == "2025-11-25")
                    // 2025-11-25: input validation errors are tool execution errors.
                    return (ResultNode(new ToolResult("invalid arguments: must be an object", true)).Node, null);
                return (null, new RpcError(-32602, "arguments must be an object"));
            }
            args = (Dictionary<string, object?>)Json.FromElement(ra)!;
            argsElement = ra;
        }
        else
        {
            argsElement = JsonDocument.Parse("{}").RootElement.Clone();
        }

        var started = System.Diagnostics.Stopwatch.GetTimestamp();
        var decision = McpDecisionKind.Allow;
        JsonNode Finish(ToolResult res, Exception? execErr)
        {
            var (node, bytes) = ResultNode(res);
            if (_cfg.OnCall != null)
            {
                var ms = (long)System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds;
                try
                {
                    _cfg.OnCall(new McpCallEvent(tool.Name, tool.Source, ms, res.IsError, execErr, bytes, decision,
                        ctx.Identity, ctx.SessionId));
                }
                catch { /* host callback errors are isolated */ }
            }
            return node;
        }

        if (_cfg.BeforeCall != null)
        {
            McpDecision d;
            try { d = await _cfg.BeforeCall(ctx, tool, argsElement).ConfigureAwait(false) ?? McpDecision.Allow(); }
            catch (Exception e) { d = McpDecision.Deny(e.Message); }
            decision = d.Kind;
            switch (d.Kind)
            {
                case McpDecisionKind.Deny:
                    if (d.Result != null) return (Finish(d.Result, null), null);
                    return (Finish(new ToolResult(string.IsNullOrEmpty(d.Message) ? "denied" : d.Message, true), null), null);
                case McpDecisionKind.Confirm:
                    if (!CanElicit(ctx.SessionId))
                    {
                        if (d.Result != null) return (Finish(d.Result, null), null);
                        return (Finish(new ToolResult("confirmation required: " + d.Message, true), null), null);
                    }
                    bool accepted;
                    try { accepted = await ElicitConfirmAsync(ctx, rw, d.Message).ConfigureAwait(false); }
                    catch (Exception e)
                    {
                        return (Finish(new ToolResult("confirmation failed: " + e.Message, true), null), null);
                    }
                    if (!accepted) return (Finish(new ToolResult("declined", true), null), null);
                    break;
            }
        }

        ToolResult result;
        Exception? err = null;
        try
        {
            result = await tool.ExecuteAsync(args, new ToolContext(cancellationToken: ctx.CancellationToken)).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            err = e;
            result = new ToolResult(e.Message, true);
        }
        return (Finish(result, err), null);
    }

    private bool CanElicit(string sid) => sid != "" && _caps.TryGetValue(sid, out var b) && b;

    /// <summary>Sends elicitation/create on this POST's SSE stream and waits for the client's answer
    /// (a separate POST, delivered via <see cref="Deliver"/>).</summary>
    private async Task<bool> ElicitConfirmAsync(McpContext ctx, RpcWriter rw, string? message)
    {
        var id = "toolnexus-confirm-" + Interlocked.Increment(ref _seq);
        var key = ctx.SessionId + "\0" + id;
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[key] = tcs;
        try
        {
            if (string.IsNullOrEmpty(message)) message = "Confirm this action?";
            var req = new JsonObject
            {
                ["jsonrpc"] = "2.0",
                ["id"] = id,
                ["method"] = "elicitation/create",
                ["params"] = new JsonObject
                {
                    ["message"] = message,
                    ["requestedSchema"] = new JsonObject
                    {
                        ["type"] = "object",
                        ["properties"] = new JsonObject
                        {
                            ["confirm"] = new JsonObject { ["type"] = "boolean", ["title"] = "Confirm", ["description"] = message },
                        },
                    },
                },
            };
            await rw.EventAsync(req).ConfigureAwait(false);

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ctx.CancellationToken);
            var delay = Task.Delay(_confirmTimeout, cts.Token);
            var done = await Task.WhenAny(tcs.Task, delay).ConfigureAwait(false);
            cts.Cancel();
            if (done != tcs.Task) throw new TimeoutException("context deadline exceeded");
            var m = tcs.Task.Result;
            if (m.TryGetProperty("error", out var e) && e.ValueKind != JsonValueKind.Null)
                throw new InvalidOperationException("client returned an error");
            if (!m.TryGetProperty("result", out var r) || r.ValueKind != JsonValueKind.Object)
                throw new InvalidOperationException("invalid elicitation result");
            var action = r.TryGetProperty("action", out var a) && a.ValueKind == JsonValueKind.String ? a.GetString() : null;
            if (action != "accept") return false;
            if (r.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.Object
                && c.TryGetProperty("confirm", out var cf) && cf.ValueKind == JsonValueKind.False)
                return false;
            return true;
        }
        finally
        {
            _pending.TryRemove(key, out _);
        }
    }

    private void Deliver(string sid, RpcMessage msg)
    {
        if (msg.Id is not JsonValue v || !v.TryGetValue<string>(out var id)) return;
        if (_pending.TryGetValue(sid + "\0" + id, out var tcs)) tcs.TrySetResult(msg.Raw);
    }

    // ── wire helpers ──────────────────────────────────────────────────────

    /// <summary>The text block of a result: <c>Output</c>, or the JSON of <c>Structured</c> when the
    /// output is empty (the spec asks a structured result to carry an equivalent text block).</summary>
    internal static string ResultText(ToolResult r)
        => r.Structured != null && string.IsNullOrEmpty(r.Output) ? SortedJson(r.Structured) : r.Output;

    /// <summary>Compact JSON with object keys sorted (ordinal) — the bytes Go's json.Marshal emits for a map.</summary>
    internal static string SortedJson(object? value)
    {
        static JsonNode? Sort(JsonNode? n) => n switch
        {
            JsonObject o => new JsonObject(o.OrderBy(kv => kv.Key, StringComparer.Ordinal)
                .Select(kv => KeyValuePair.Create(kv.Key, Sort(kv.Value?.DeepClone())))),
            JsonArray a => new JsonArray(a.Select(x => Sort(x?.DeepClone())).ToArray()),
            _ => n?.DeepClone(),
        };
        return Sort(JsonNode.Parse(Json.Stringify(value)))?.ToJsonString(WireJson) ?? "null";
    }

    /// <summary>The wire CallToolResult (shared mapping with §7C serve), isError omitted when false.</summary>
    private static (JsonNode Node, int Bytes) ResultNode(ToolResult res)
    {
        var json = JsonSerializer.Serialize(McpServe.ToCallToolResult(res), McpJsonUtilities.DefaultOptions);
        var node = JsonNode.Parse(json)!.AsObject();
        if (!res.IsError) node.Remove("isError");
        var text = node.ToJsonString(WireJson);
        return (node, Encoding.UTF8.GetByteCount(text));
    }

    /// <summary>The tools/list entry: name verbatim, description, inputSchema, outputSchema when set, and
    /// annotations (verbatim; else derived from Hints; else omitted).</summary>
    internal static JsonObject WireTool(ITool t)
    {
        var m = new JsonObject
        {
            ["name"] = t.Name,
            ["description"] = t.Description ?? "",
            ["inputSchema"] = t.InputSchema != null ? JsonNode.Parse(Json.Stringify(t.InputSchema)) : new JsonObject { ["type"] = "object" },
        };
        if (t.OutputSchema != null) m["outputSchema"] = JsonNode.Parse(Json.Stringify(t.OutputSchema));
        var ann = new JsonObject();
        if (t.Annotations is { } a)
        {
            if (!string.IsNullOrEmpty(a.Title)) ann["title"] = a.Title;
            if (a.ReadOnlyHint is { } ro) ann["readOnlyHint"] = ro;
            if (a.DestructiveHint is { } de) ann["destructiveHint"] = de;
            if (a.IdempotentHint is { } id) ann["idempotentHint"] = id;
            if (a.OpenWorldHint is { } ow) ann["openWorldHint"] = ow;
        }
        else if (t.Hints is { } h)
        {
            ann["readOnlyHint"] = h.ReadOnly;
            ann["destructiveHint"] = h.Destructive;
            ann["openWorldHint"] = h.OpenWorld;
        }
        if (ann.Count > 0) m["annotations"] = ann;
        return m;
    }

    private static JsonObject OmitEmpty(params (string Key, string? Value)[] kvs)
    {
        var o = new JsonObject();
        foreach (var (k, v) in kvs)
            if (!string.IsNullOrEmpty(v)) o[k] = v;
        return o;
    }

    private static async Task PlainAsync(McpHttpExchange ex, int status, string message)
    {
        ex.SetStatus(status);
        ex.SetHeader("Content-Type", "text/plain; charset=utf-8");
        ex.SetHeader("X-Content-Type-Options", "nosniff");
        await ex.ResponseBody.WriteAsync(Encoding.UTF8.GetBytes(message + "\n")).ConfigureAwait(false);
    }

    private static Task EmptyAsync(McpHttpExchange ex, int status)
    {
        ex.SetStatus(status);
        return Task.CompletedTask;
    }

    private static async Task RpcErrorAsync(McpHttpExchange ex, JsonNode? id, int code, string message)
    {
        var msg = new JsonObject
        {
            ["jsonrpc"] = "2.0",
            ["id"] = id?.DeepClone(),
            ["error"] = new JsonObject { ["code"] = code, ["message"] = message },
        };
        ex.SetStatus(400);
        ex.SetHeader("Content-Type", "application/json");
        await ex.ResponseBody.WriteAsync(Encoding.UTF8.GetBytes(msg.ToJsonString(WireJson) + "\n")).ConfigureAwait(false);
    }

    /// <summary>Answers one POST: plain JSON, unless a server→client request was sent first, in which
    /// case the response is upgraded to an SSE stream.</summary>
    private sealed class RpcWriter(McpHttpExchange ex)
    {
        private bool _sse;

        public async Task EventAsync(JsonNode msg)
        {
            if (!_sse)
            {
                ex.SetStatus(200);
                ex.SetHeader("Content-Type", "text/event-stream");
                ex.SetHeader("Cache-Control", "no-cache");
                _sse = true;
            }
            var body = ex.ResponseBody;
            await body.WriteAsync(Encoding.UTF8.GetBytes($"event: message\ndata: {msg.ToJsonString(WireJson)}\n\n")).ConfigureAwait(false);
            await body.FlushAsync().ConfigureAwait(false);
        }

        public async Task RespondAsync(JsonNode? id, JsonNode? result, RpcError? err)
        {
            var msg = new JsonObject { ["jsonrpc"] = "2.0", ["id"] = id?.DeepClone() };
            if (err != null) msg["error"] = new JsonObject { ["code"] = err.Code, ["message"] = err.Message };
            else msg["result"] = result;
            if (_sse)
            {
                try { await EventAsync(msg).ConfigureAwait(false); } catch { }
                return;
            }
            ex.SetStatus(200);
            ex.SetHeader("Content-Type", "application/json");
            await ex.ResponseBody.WriteAsync(Encoding.UTF8.GetBytes(msg.ToJsonString(WireJson) + "\n")).ConfigureAwait(false);
        }
    }
}
