using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Toolnexus.Mcp2026;

// MCP 2026-07-28 client wire layer (SPEC §2.1–§2.6). In-house, no MCP SDK: BCL only.
// Stateless Streamable HTTP (JSON or SSE replies) and line-delimited stdio, same API.

/// <summary>Wire constants (SPEC §2.1, byte-exact).</summary>
internal static class Wire
{
    public const string ProtocolVersion = "2026-07-28";
    public const string MetaVersion = "io.modelcontextprotocol/protocolVersion";
    public const string MetaClientCaps = "io.modelcontextprotocol/clientCapabilities";
    public const string MetaClientInfo = "io.modelcontextprotocol/clientInfo";
    public const string MetaServerInfo = "io.modelcontextprotocol/serverInfo";
    public const string SkillsExt = "io.modelcontextprotocol/skills";

    public const int CodeMethodNotFound = -32601;
    public const int CodeInvalidParams = -32602;
    public const int CodeHeaderMismatch = -32020;
    public const int CodeUnsupportedProtocol = -32022;

    /// <summary>SPEC §2.3: a header value that is not plain printable ASCII (or looks like the sentinel) is base64-wrapped.</summary>
    public static string EncodeHeader(string v)
    {
        var safe = v == v.Trim() && !(v.StartsWith("=?base64?") && v.EndsWith("?=")) && v.All(c => c >= 0x20 && c <= 0x7e);
        return safe ? v : "=?base64?" + Convert.ToBase64String(Encoding.UTF8.GetBytes(v)) + "?=";
    }
}

/// <summary>A JSON-RPC error returned by the server.</summary>
internal sealed class RpcException(int code, string message, JsonNode? data = null) : Exception(message)
{
    public int Code { get; } = code;
    public new JsonNode? Data { get; } = data;
}

/// <summary>Transport / protocol failure that is not a JSON-RPC error. Never retried (SPEC §2.6).</summary>
internal sealed class WireException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>HTTP reply with a non-2xx status that carried no JSON-RPC message.</summary>
internal sealed class HttpStatusException(int status, string message) : Exception(message)
{
    public int Status { get; } = status;
}

/// <summary>Small JSON helpers over System.Text.Json.Nodes.</summary>
internal static class J
{
    public static readonly JsonSerializerOptions Options = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    public static string? Str(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;
    public static string Str(JsonNode? n, string fallback) => Str(n) ?? fallback;

    public static double? Num(JsonNode? n)
    {
        if (n is not JsonValue v) return null;
        if (v.TryGetValue<double>(out var d)) return d;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<int>(out var i)) return i;
        return null;
    }

    public static bool True(JsonNode? n) => n is JsonValue v && v.TryGetValue<bool>(out var b) && b;

    public static string Serialize(JsonNode? n) => n?.ToJsonString(Options) ?? "null";

    public static JsonObject? ParseObject(string s)
    {
        try { return JsonNode.Parse(s) as JsonObject; } catch (JsonException) { return null; }
    }

    public static JsonArray Array(IEnumerable<string> xs) => new(xs.Select(x => (JsonNode?)JsonValue.Create(x)).ToArray());

    public static IEnumerable<string> Strings(JsonNode? n) =>
        n is JsonArray a ? a.Select(x => Str(x)).Where(x => x != null).Select(x => x!) : [];

    /// <summary>JsonNode → the plain CLR model the rest of toolnexus uses (Dictionary/List/long/double/...).</summary>
    public static object? ToClr(JsonNode? n) => n == null ? null : Json.FromElement(JsonSerializer.SerializeToElement(n));

    /// <summary>The plain CLR model → JsonNode.</summary>
    public static JsonNode? FromClr(object? v) => v == null ? null : JsonNode.Parse(Json.Stringify(v));

    /// <summary>Parse an <c>application/x-www-form-urlencoded</c> string / URL query (no leading '?').</summary>
    public static Dictionary<string, string> ParseQuery(string q)
    {
        var o = new Dictionary<string, string>();
        if (q.StartsWith('?')) q = q[1..];
        foreach (var pair in q.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var i = pair.IndexOf('=');
            var k = Decode(i < 0 ? pair : pair[..i]);
            var v = i < 0 ? "" : Decode(pair[(i + 1)..]);
            o.TryAdd(k, v);
        }
        return o;
        static string Decode(string s) => Uri.UnescapeDataString(s.Replace('+', ' '));
    }
}

/// <summary>
/// One MCP 2026-07-28 connection: HTTP (<see cref="Url"/>) or stdio (<see cref="StartStdio"/>).
/// Every request carries the per-request <c>_meta</c>; HTTP adds the MCP headers.
/// </summary>
internal sealed class Conn : IAsyncDisposable
{
    /// <summary>
    /// One shared HttpClient; per-request deadlines come from cancellation tokens. Created on first
    /// remote use, so a stdio-only host never loads the networking stack for it.
    /// </summary>
    private static readonly Lazy<HttpClient> s_sharedHttp =
        new(() => new HttpClient { Timeout = Timeout.InfiniteTimeSpan });

    internal static HttpClient SharedHttp => s_sharedHttp.Value;

    private readonly HttpClient? _http;

    public string Url { get; }
    public HttpClient Http { get => _http ?? SharedHttp; init => _http = value; }
    /// <summary>Configured remote <c>headers</c>, already <c>${ENV}</c>-expanded. Never logged.</summary>
    public IReadOnlyDictionary<string, string> Headers { get; init; } = new Dictionary<string, string>();
    public OAuthClient? Auth { get; init; }
    public bool Elicitation { get; init; } = true;
    internal Dictionary<string, string>? HeaderOverride; // tests: forge headers (negative cases only)

    private StdioConn? _stdio;
    private long _nextId;

    public Conn(string url) => Url = url;

    public bool IsStdio => _stdio != null;

    private JsonObject Meta() => new()
    {
        [Wire.MetaVersion] = Wire.ProtocolVersion,
        [Wire.MetaClientCaps] = new JsonObject { ["elicitation"] = new JsonObject { ["form"] = new JsonObject() } },
        [Wire.MetaClientInfo] = new JsonObject { ["name"] = "toolnexus", ["version"] = typeof(Conn).Assembly.GetName().Version?.ToString(3) ?? "0.0.0" },
    };

    /// <summary>
    /// Sends one request and returns its result (resultType defaulted to "complete"). Re-sent ONLY after a
    /// pre-dispatch 401 / 403 insufficient_scope carrying WWW-Authenticate (SPEC §2.6); a lost connection,
    /// timeout, 5xx or unreadable response is thrown and never retried.
    /// </summary>
    public async Task<JsonObject> CallAsync(string method, JsonObject? @params, CancellationToken ct)
    {
        @params = @params == null ? new JsonObject() : (JsonObject)@params.DeepClone();
        @params["_meta"] = Meta();
        var id = Interlocked.Increment(ref _nextId);
        var body = J.Serialize(new JsonObject { ["jsonrpc"] = "2.0", ["id"] = id, ["method"] = method, ["params"] = @params.DeepClone() });
        var msg = _stdio != null ? await _stdio.RoundTripAsync(id, body, ct).ConfigureAwait(false)
            : await HttpRoundTripAsync(method, @params, body, ct).ConfigureAwait(false);
        if (msg["error"] is JsonObject e)
            throw new RpcException((int)(J.Num(e["code"]) ?? 0), J.Str(e["message"], ""), e["data"]?.DeepClone());
        if (msg["result"] is not JsonObject res) throw new WireException("response has neither result nor error");
        res = (JsonObject)res.DeepClone();
        res["resultType"] ??= "complete";
        return res;
    }

    private async Task<JsonObject> HttpRoundTripAsync(string method, JsonObject @params, string body, CancellationToken ct)
    {
        HttpResponseMessage resp;
        for (var attempt = 0; ; attempt++)
        {
            var sent = Auth != null ? await Auth.BearerAsync(Url, ct).ConfigureAwait(false) : "";
            try { resp = await PostAsync(method, @params, body, sent, ct).ConfigureAwait(false); }
            catch (Exception ex) when (ex is HttpRequestException or IOException || (ex is TaskCanceledException && !ct.IsCancellationRequested))
            {
                throw new WireException($"{method}: {ex.Message}", ex); // never retried: the server may have executed it
            }
            var www = resp.Headers.NonValidated.TryGetValues("WWW-Authenticate", out var v) ? string.Join(", ", v) : "";
            var st = (int)resp.StatusCode;
            if (Auth == null || (st != 401 && st != 403) || www == "") break;
            resp.Dispose();
            if (attempt >= 3) throw new WireException($"authorization failed: HTTP {st} after {attempt + 1} attempts");
            await Auth.ChallengeAsync(Url, st, www, sent, ct).ConfigureAwait(false);
        }
        using (resp)
        {
            var status = (int)resp.StatusCode;
            if (status >= 500) throw new HttpStatusException(status, $"{method}: HTTP {status}");
            string text;
            try { text = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false); }
            catch (Exception ex) when (ex is HttpRequestException or IOException || (ex is TaskCanceledException && !ct.IsCancellationRequested))
            {
                throw new WireException($"HTTP {status}: {ex.Message}", ex);
            }
            JsonObject? msg = null;
            if (resp.Content.Headers.ContentType?.MediaType == "text/event-stream")
            {
                foreach (var line in text.Split('\n'))
                    if (line.StartsWith("data:") && J.ParseObject(line[5..].Trim()) is { } m && (m["result"] != null || m["error"] != null))
                        msg = m; // the final data: carrying a result or error is the response
            }
            else msg = J.ParseObject(text);
            if (msg == null || (msg["result"] is not JsonObject && msg["error"] is not JsonObject))
            {
                if (status is < 200 or >= 300) throw new HttpStatusException(status, $"{method}: HTTP {status}");
                throw new WireException($"HTTP {status}: response has neither result nor error");
            }
            if (status >= 500 || (status is < 200 or >= 300 && msg["error"] is not JsonObject))
                throw new HttpStatusException(status, $"{method}: HTTP {status}");
            return msg;
        }
    }

    private Task<HttpResponseMessage> PostAsync(string method, JsonObject @params, string body, string bearer, CancellationToken ct)
    {
        var req = new HttpRequestMessage(HttpMethod.Post, Url) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        req.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream");
        req.Headers.TryAddWithoutValidation("MCP-Protocol-Version", Wire.ProtocolVersion);
        req.Headers.TryAddWithoutValidation("Mcp-Method", method);
        if (method is "tools/call" or "prompts/get") req.Headers.TryAddWithoutValidation("Mcp-Name", Wire.EncodeHeader(J.Str(@params["name"], "")));
        if (method == "resources/read") req.Headers.TryAddWithoutValidation("Mcp-Name", Wire.EncodeHeader(J.Str(@params["uri"], "")));
        foreach (var (k, v) in Headers)
        {
            req.Headers.Remove(k);
            req.Headers.TryAddWithoutValidation(k, v);
        }
        foreach (var (k, v) in HeaderOverride ?? [])
        {
            req.Headers.Remove(k);
            req.Headers.TryAddWithoutValidation(k, v);
        }
        if (bearer != "")
        {
            req.Headers.Remove("Authorization");
            req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + bearer);
        }
        return Http.SendAsync(req, HttpCompletionOption.ResponseContentRead, ct);
    }

    /// <summary>Spawn a stdio server (no shell). The process env is inherited plus <paramref name="env"/>.</summary>
    public static Conn StartStdio(IReadOnlyList<string> argv, IDictionary<string, string>? env, string? cwd)
    {
        var psi = new ProcessStartInfo(argv[0])
        {
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false,
            StandardInputEncoding = new UTF8Encoding(false), StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var a in argv.Skip(1)) psi.ArgumentList.Add(a);
        if (env != null) foreach (var (k, v) in env) psi.Environment[k] = v;
        if (cwd != null) psi.WorkingDirectory = cwd;
        Process proc;
        try { proc = Process.Start(psi) ?? throw new WireException("could not start " + argv[0]); }
        catch (System.ComponentModel.Win32Exception e) { throw new WireException($"could not start {argv[0]}: {e.Message}", e); }
        return new Conn("") { _stdio = new StdioConn(proc) };
    }

    public ValueTask DisposeAsync() => _stdio?.DisposeAsync() ?? ValueTask.CompletedTask;

    /// <summary>SPEC §2.2a: discover succeeded on stdio — restart the reader the probe paused.</summary>
    public void EndProbe() => _stdio?.EndProbe();

    /// <summary>
    /// SPEC §2.2a: hand the stdio probe child (which answered discover) to the frozen SDK path. Returns the server's
    /// stdin and a stream over its stdout that drains our reader's buffer first; disposing this Conn still kills
    /// the child. Only valid on a stdio connection whose discover got a reply.
    /// </summary>
    public Task<(Stream Input, Stream Output)> HandOffAsync() =>
        _stdio?.HandOffAsync() ?? throw new InvalidOperationException("not a stdio connection");
}

/// <summary>
/// stdio transport: one JSON-RPC message per line, responses demultiplexed by id (concurrent calls allowed),
/// stderr passed through and never parsed, a dead child fails every pending call.
/// </summary>
internal sealed class StdioConn : IAsyncDisposable
{
    private readonly Process _proc;
    private readonly SemaphoreSlim _write = new(1, 1);
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonObject>> _pending = new();
    private readonly TaskCompletionSource _done = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Task _stderrPump;
    private volatile string? _err;
    /// <summary>SPEC §2.2a probe mode: the reader exits right after delivering the first response (the discover reply),
    /// so no pending read can steal the frozen path's <c>initialize</c> reply.</summary>
    private volatile bool _probe = true;
    private Task _reader = Task.CompletedTask;

    public StdioConn(Process proc)
    {
        _proc = proc;
        _reader = Task.Run(ReadLoopAsync);
        _stderrPump = Task.Run(async () =>
        {
            try
            {
                string? line;
                while ((line = await proc.StandardError.ReadLineAsync().ConfigureAwait(false)) != null)
                    Console.Error.WriteLine(line);
            }
            catch { /* child gone */ }
        });
    }

    private async Task ReadLoopAsync()
    {
        try
        {
            string? line;
            while ((line = await _proc.StandardOutput.ReadLineAsync().ConfigureAwait(false)) != null)
            {
                var m = J.ParseObject(line);
                if (m == null || m["method"] != null || m["id"] == null) continue; // notification / server request / noise
                if (_pending.TryRemove(J.Serialize(m["id"]), out var tcs))
                {
                    var probe = _probe;
                    tcs.TrySetResult(m);
                    if (probe) return; // paused: EndProbe resumes, HandOff gives stdout to the SDK
                }
            }
            _err = "stdio server exited";
        }
        catch (Exception e) { _err = "stdio read: " + e.Message; }
        _done.TrySetResult();
        foreach (var k in _pending.Keys)
            if (_pending.TryRemove(k, out var t)) t.TrySetException(new WireException(_err!));
    }

    public void EndProbe()
    {
        if (!_probe) return;
        _probe = false;
        // the probe reader returns right after delivering the discover reply; restart once it has
        _reader = _reader.ContinueWith(_ => _done.Task.IsCompleted ? Task.CompletedTask : ReadLoopAsync(),
            TaskScheduler.Default).Unwrap();
    }

    public async Task<(Stream Input, Stream Output)> HandOffAsync()
    {
        // the probe reader exits right after delivering the discover reply: wait for it so no read is in flight
        await _reader.WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        _probe = false;
        return (_proc.StandardInput.BaseStream, new ReaderStream(_proc.StandardOutput));
    }

    public async Task<JsonObject> RoundTripAsync(long id, string body, CancellationToken ct)
    {
        if (_err != null) throw new WireException(_err);
        var tcs = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        var key = id.ToString();
        _pending[key] = tcs;
        try
        {
            if (_err != null) throw new WireException(_err);
            await _write.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                await _proc.StandardInput.WriteAsync(body + "\n").ConfigureAwait(false);
                await _proc.StandardInput.FlushAsync(ct).ConfigureAwait(false);
            }
            catch (IOException e) { throw new WireException("stdio write: " + e.Message, e); }
            finally { _write.Release(); }
            return await tcs.Task.WaitAsync(ct).ConfigureAwait(false);
        }
        finally { _pending.TryRemove(key, out _); }
    }

    public async ValueTask DisposeAsync()
    {
        try { _proc.StandardInput.Close(); } catch { /* already gone */ }
        var exited = _proc.WaitForExitAsync();
        var first = await Task.WhenAny(_done.Task, exited, Task.Delay(2000)).ConfigureAwait(false);
        if (first != _done.Task && first != exited)
            try { _proc.Kill(true); } catch { /* exited */ }
        try { if (!_proc.HasExited) _proc.Kill(true); } catch { }
        try { await _proc.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false); } catch { }
        try { await _stderrPump.WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false); } catch { }
        _proc.Dispose();
    }
}

/// <summary>
/// SPEC §2.2a handoff: a read-only byte stream over the probe's <see cref="StreamReader"/>, so any bytes it already
/// buffered past the discover reply reach the SDK reader instead of being lost.
/// </summary>
internal sealed class ReaderStream(StreamReader reader) : Stream
{
    private readonly Encoder _enc = new UTF8Encoding(false).GetEncoder();
    private readonly char[] _chars = new char[4096];
    private byte[] _bytes = Array.Empty<byte>();
    private int _pos, _len;

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
        while (_pos >= _len)
        {
            var n = await reader.ReadAsync(_chars.AsMemory(), ct).ConfigureAwait(false);
            var flush = n == 0;
            var need = _enc.GetByteCount(_chars, 0, n, flush);
            if (_bytes.Length < need) _bytes = new byte[Math.Max(need, 8192)];
            _len = _enc.GetBytes(_chars, 0, n, _bytes, 0, flush);
            _pos = 0;
            if (n == 0) return 0;
        }
        var c = Math.Min(buffer.Length, _len - _pos);
        _bytes.AsMemory(_pos, c).CopyTo(buffer);
        _pos += c;
        return c;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) reader.Dispose();
        base.Dispose(disposing);
    }
}
