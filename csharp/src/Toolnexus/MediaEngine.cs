using System.Diagnostics;
using System.Globalization;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text;

namespace Toolnexus;

/// <summary>§7G shared helpers: mime table, dotPath, number rendering, percent-encoding, caps.</summary>
internal static class MediaUtil
{
    /// <summary>§7G.12 D15: a JSON value with object keys sorted by code point at every depth (arrays keep order).</summary>
    internal static object? SortKeys(object? value) => Json.Normalize(value) switch
    {
        Dictionary<string, object?> d => d.OrderBy(kv => kv.Key, CodePointComparer.Instance)
            .Aggregate(new Dictionary<string, object?>(), (acc, kv) => { acc[kv.Key] = SortKeys(kv.Value); return acc; }),
        List<object?> l => l.Select(SortKeys).ToList(),
        var v => v,
    };

    /// <summary>§7G.12 D17: the realpath of an existing directory (every symlinked component resolved).</summary>
    internal static string RealPath(string path)
    {
        var full = Path.GetFullPath(path);
        var root = Path.GetPathRoot(full) ?? "/";
        var cur = root;
        foreach (var part in full[root.Length..].Split(Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries))
        {
            var next = Path.Combine(cur, part);
            for (var hops = 0; hops < 40; hops++)
            {
                var info = new DirectoryInfo(next);
                if (info.LinkTarget is not { } target) break;
                next = Path.GetFullPath(Path.IsPathRooted(target) ? target : Path.Combine(cur, target));
            }
            cur = next;
        }
        return cur;
    }

    internal static string Resource(string name)
    {
        using var s = typeof(MediaUtil).Assembly.GetManifestResourceStream(name)
            ?? throw new InvalidOperationException("missing embedded resource " + name);
        using var r = new StreamReader(s, Encoding.UTF8);
        return r.ReadToEnd();
    }

    internal static bool IsHttp(string url)
        => url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || url.StartsWith("https://", StringComparison.OrdinalIgnoreCase);

    internal static readonly Dictionary<string, string> MimeByExt = new(StringComparer.OrdinalIgnoreCase)
    {
        ["png"] = "image/png", ["jpg"] = "image/jpeg", ["jpeg"] = "image/jpeg", ["webp"] = "image/webp",
        ["gif"] = "image/gif", ["mp4"] = "video/mp4", ["webm"] = "video/webm", ["mov"] = "video/quicktime",
        ["mp3"] = "audio/mpeg", ["wav"] = "audio/wav", ["ogg"] = "audio/ogg", ["m4a"] = "audio/mp4",
        ["flac"] = "audio/flac",
    };

    private static readonly Dictionary<string, string> ExtByMime = new(StringComparer.OrdinalIgnoreCase)
    {
        ["image/png"] = ".png", ["image/jpeg"] = ".jpg", ["image/webp"] = ".webp", ["image/gif"] = ".gif",
        ["video/mp4"] = ".mp4", ["video/webm"] = ".webm", ["video/quicktime"] = ".mov", ["audio/mpeg"] = ".mp3",
        ["audio/wav"] = ".wav", ["audio/x-wav"] = ".wav", ["audio/wave"] = ".wav", ["audio/ogg"] = ".ogg",
        ["audio/mp4"] = ".m4a", ["audio/flac"] = ".flac", ["audio/aac"] = ".aac", ["text/plain"] = ".txt",
    };

    internal static string ExtFor(string mime) => ExtByMime.TryGetValue(mime, out var e) ? e : ".bin";

    /// <summary>§7G.12 D22: the URL path's own extension (last segment, "." + 1–5 ASCII letters/digits,
    /// lowercased), or null.</summary>
    internal static string? UrlExt(string url)
    {
        var p = url;
        var cut = p.IndexOfAny(new[] { '?', '#' });
        if (cut >= 0) p = p[..cut];
        var seg = p[(p.LastIndexOf('/') + 1)..];
        var dot = seg.LastIndexOf('.');
        if (dot < 0) return null;
        var ext = seg[(dot + 1)..];
        return ext.Length is >= 1 and <= 5 && ext.All(char.IsAsciiLetterOrDigit) ? "." + ext.ToLowerInvariant() : null;
    }

    /// <summary>Mime from the extension of a URL path or file path (query/fragment stripped); null when unknown.</summary>
    internal static string? MimeOf(string urlOrPath)
    {
        var p = urlOrPath;
        var cut = p.IndexOfAny(new[] { '?', '#' });
        if (cut >= 0) p = p[..cut];
        var seg = p[(p.LastIndexOf('/') + 1)..];
        var dot = seg.LastIndexOf('.');
        return dot >= 0 && MimeByExt.TryGetValue(seg[(dot + 1)..], out var mime) ? mime : null;
    }

    internal static string DefaultMime(string kind) => kind switch { "video" => "video/mp4", "audio" => "audio/mpeg", _ => "image/png" };

    internal static string TypeOfKind(string kind) => kind switch { "audio" => "audio", "video" => "file", _ => "image" };

    /// <summary>The last path segment of a URL or path (query stripped), or <c>"input"</c>.</summary>
    internal static string FileName(string value)
    {
        var p = value;
        var cut = p.IndexOfAny(new[] { '?', '#' });
        if (cut >= 0) p = p[..cut];
        var seg = p[(p.LastIndexOfAny(new[] { '/', '\\' }) + 1)..];
        return seg.Length == 0 ? "input" : seg;
    }

    /// <summary>First <paramref name="n"/> code points, plus "…" when truncated.</summary>
    internal static string Cap(string text, int n)
    {
        var count = 0;
        for (var i = 0; i < text.Length; i++)
        {
            if (count == n) return text[..i] + "…";
            if (char.IsHighSurrogate(text[i]) && i + 1 < text.Length && char.IsLowSurrogate(text[i + 1])) i++;
            count++;
        }
        return text;
    }

    /// <summary>§7G.8: echoed bodies are capped at 1000 code points, then "…".</summary>
    internal static string CapBody(string text) => Cap(text, 1000);

    /// <summary>§7G.8: RFC 3986 unreserved kept, everything else %XX of its UTF-8 bytes.</summary>
    internal static string PercentEncode(string s)
    {
        var sb = new StringBuilder();
        foreach (var b in Encoding.UTF8.GetBytes(s))
        {
            var c = (char)b;
            if (c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '.' or '_' or '~') sb.Append(c);
            else sb.Append('%').Append(b.ToString("X2", CultureInfo.InvariantCulture));
        }
        return sb.ToString();
    }

    internal static long Positive(long? v, long dflt) => v is > 0 ? v.Value : dflt;

    internal static long? ToLong(object? v) => v switch
    {
        long l => l,
        int i => i,
        double d when d >= long.MinValue && d < 9223372036854775808d => (long)d,
        double d when !double.IsNaN(d) => d > 0 ? long.MaxValue : long.MinValue,
        _ => null,
    };

    /// <summary>String as-is; an integral number as integer text; a non-integral number (unless
    /// <paramref name="integralOnly"/>) in invariant round-trip form; anything else null.</summary>
    internal static string? ScalarText(object? v, bool integralOnly) => v switch
    {
        string s => s,
        long l => l.ToString(CultureInfo.InvariantCulture),
        int i => i.ToString(CultureInfo.InvariantCulture),
        double d when d == Math.Floor(d) && !double.IsInfinity(d) => new System.Numerics.BigInteger(d).ToString(CultureInfo.InvariantCulture),
        double d when !integralOnly => d.ToString("R", CultureInfo.InvariantCulture),
        _ => null,
    };

    internal static bool JsonEqual(object? a, object? b)
    {
        if (a is long or int or double && b is long or int or double)
            return Convert.ToDouble(a, CultureInfo.InvariantCulture) == Convert.ToDouble(b, CultureInfo.InvariantCulture);
        return Json.Stringify(a) == Json.Stringify(b);
    }

    private static bool IsIndex(string seg, out int idx)
    {
        idx = 0;
        return seg.Length > 0 && seg.All(char.IsAsciiDigit) && int.TryParse(seg, NumberStyles.None, CultureInfo.InvariantCulture, out idx);
    }

    /// <summary>§7G.7 dotPath read: a missing segment ⇒ null (absent); "" ⇒ the whole value.</summary>
    internal static object? ReadPath(object? root, string path)
    {
        if (string.IsNullOrEmpty(path)) return root;
        var cur = root;
        foreach (var seg in path.Split('.'))
        {
            if (cur is IDictionary<string, object?> d)
            {
                if (!d.TryGetValue(seg, out cur)) return null;
            }
            else if (cur is List<object?> l && IsIndex(seg, out var i))
            {
                if (i >= l.Count) return null;
                cur = l[i];
            }
            else return null;
        }
        return cur;
    }

    /// <summary>§7G.7 dotPath write: creates objects, and arrays when the next segment is numeric (null-padded).
    /// A scalar in the way is replaced; so is an array met with a non-numeric segment.</summary>
    internal static void WritePath(Dictionary<string, object?> root, string path, object? value)
    {
        var segs = path.Split('.');
        root[segs[0]] = WriteInto(root.Get(segs[0]), segs, 1, value);
    }

    private static object? WriteInto(object? cur, string[] segs, int n, object? value)
    {
        if (n == segs.Length) return value;
        var seg = segs[n];
        if (cur is not Dictionary<string, object?> && IsIndex(seg, out var i))
        {
            var list = cur as List<object?> ?? new List<object?>();
            while (list.Count <= i) list.Add(null);
            list[i] = WriteInto(list[i], segs, n + 1, value);
            return list;
        }
        var d = cur as Dictionary<string, object?> ?? new Dictionary<string, object?>();
        d[seg] = WriteInto(d.Get(seg), segs, n + 1, value);
        return d;
    }

    /// <summary>Deep copy through JSON into the plain object model.</summary>
    internal static object? Clone(object? v) => Json.ParseLoose(Json.Stringify(v));

    internal static bool SameOrigin(Uri a, Uri b)
        => string.Equals(a.Scheme, b.Scheme, StringComparison.OrdinalIgnoreCase)
           && string.Equals(a.Host, b.Host, StringComparison.OrdinalIgnoreCase) && a.Port == b.Port;
}

/// <summary>One verb call in flight: deadline, progress, cancellation (§7G.2, §7G.8).</summary>
internal sealed class MediaJob
{
    public string Verb { get; }
    public string Client { get; }
    public long TimeoutMs { get; }
    public CancellationToken Ct { get; }
    public string? JobId { get; set; }
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly Action<MediaProgress>? _progress;

    public MediaJob(string verb, string client, long loopTimeoutMs, MediaContext ctx, Action<MediaProgress>? onProgress)
    {
        Verb = verb;
        Client = client;
        var t = loopTimeoutMs;
        if (ctx.Deadline is { } dl)
        {
            var left = (long)Math.Max(0, (dl - DateTimeOffset.UtcNow).TotalMilliseconds);
            if (left < t) t = left;
        }
        TimeoutMs = t;
        Ct = ctx.CancellationToken;
        _progress = ctx.Progress ?? onProgress;
    }

    public long ElapsedMs => _clock.ElapsedMilliseconds;
    public long RemainingMs => TimeoutMs - _clock.ElapsedMilliseconds;

    public void Report(string status) => _progress?.Invoke(new MediaProgress(Verb, Client, JobId, status, ElapsedMs));

    public MediaException TimedOut() => JobId == null
        ? new MediaException($"{Verb}: timed out after {TimeoutMs}ms", "timeout")
        : new MediaException($"job {JobId} timed out after {TimeoutMs}ms", "timeout", JobId);

    public MediaException Fail(string message) => new(message, "failed", JobId);
}

internal sealed record MediaHttpResponse(int Status, byte[] Body, string? ContentType, IReadOnlyDictionary<string, string> Headers)
{
    public string Text => Encoding.UTF8.GetString(Body);
}

/// <summary>The §7G.8 HTTP engine shared by every API client: redirects followed by hand, headers
/// never leave the base origin, every request bounded by the job deadline.</summary>
internal static class MediaHttp
{
    /// <summary>§7G.12 D23: <c>toolnexus/&lt;version&gt; (csharp)</c>, sent unless the client/style headers set one.</summary>
    internal static readonly string UserAgent = "toolnexus/" + PackageVersion() + " (csharp)";

    private static string PackageVersion()
    {
        var v = typeof(MediaHttp).Assembly.GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
            .OfType<System.Reflection.AssemblyInformationalVersionAttribute>().FirstOrDefault()?.InformationalVersion
            ?? typeof(MediaHttp).Assembly.GetName().Version?.ToString(3) ?? "0.0.0";
        var plus = v.IndexOf('+');
        return plus >= 0 ? v[..plus] : v;
    }

    internal static readonly HttpClient Default = new(new SocketsHttpHandler { AllowAutoRedirect = false }) { Timeout = Timeout.InfiniteTimeSpan };

    /// <summary>Send one request, following redirects. <paramref name="headers"/> go only to
    /// <paramref name="headerOrigin"/>'s origin. <paramref name="maxBytes"/> caps the body read.</summary>
    internal static async Task<MediaHttpResponse> SendAsync(HttpClient http, MediaJob job, string method, string url,
        Func<HttpContent>? body, IDictionary<string, string>? headers, Uri? headerOrigin, long? maxBytes = null)
    {
        if (job.RemainingMs <= 0) throw job.TimedOut();
        using var timer = CancellationTokenSource.CreateLinkedTokenSource(job.Ct);
        timer.CancelAfter(TimeSpan.FromMilliseconds(Math.Max(1, job.RemainingMs)));
        var uri = new Uri(url);
        var withHeaders = headers != null && headerOrigin != null && MediaUtil.SameOrigin(headerOrigin, uri);
        HttpResponseMessage? resp = null;
        try
        {
            for (var hops = 0; ; hops++)
            {
                using (var req = new HttpRequestMessage(new HttpMethod(method.ToUpperInvariant()), uri))
                {
                    if (body != null) req.Content = body();
                    if (withHeaders)
                        foreach (var (k, v) in headers!)
                        {
                            if (string.Equals(k, "Content-Type", StringComparison.OrdinalIgnoreCase)) continue;
                            req.Headers.TryAddWithoutValidation(k, v);
                        }
                    if (!req.Headers.Contains("User-Agent")) req.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
                    resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, timer.Token).ConfigureAwait(false);
                }
                var sc = (int)resp.StatusCode;
                if (sc is 301 or 302 or 303 or 307 or 308 && resp.Headers.Location is { } loc)
                {
                    if (hops >= 10) throw new MediaException($"{job.Verb}: too many redirects", "failed", job.JobId);
                    uri = loc.IsAbsoluteUri ? loc : new Uri(uri, loc);
                    withHeaders = withHeaders && MediaUtil.SameOrigin(headerOrigin!, uri);
                    if (sc is 301 or 302 or 303 && method.ToUpperInvariant() is not ("GET" or "HEAD")) { method = "GET"; body = null; }
                    resp.Dispose();
                    resp = null;
                    continue;
                }
                break;
            }
            var bytes = await ReadAsync(resp, job, maxBytes, timer.Token).ConfigureAwait(false);
            var ct = resp.Content.Headers.ContentType?.MediaType;
            var hs = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var h in resp.Headers) hs[h.Key] = string.Join(",", h.Value);
            return new MediaHttpResponse((int)resp.StatusCode, bytes, ct, hs);
        }
        catch (OperationCanceledException) when (!job.Ct.IsCancellationRequested)
        {
            throw job.TimedOut();
        }
        catch (HttpRequestException e)
        {
            throw new MediaException($"{job.Verb}: {e.Message}", "failed", job.JobId); // transport error: no HTTP status
        }
        finally
        {
            resp?.Dispose();
        }
    }

    private static async Task<byte[]> ReadAsync(HttpResponseMessage resp, MediaJob job, long? maxBytes, CancellationToken ct)
    {
        if (maxBytes is { } cap && resp.Content.Headers.ContentLength is { } cl && cl > cap)
            throw new MediaException($"{job.Verb}: file exceeds gather.maxBytes ({cap} bytes)", "failed", job.JobId);
        await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = new MemoryStream();
        var buf = new byte[81920];
        while (true)
        {
            var limit = maxBytes is { } m ? (int)Math.Min(buf.Length, m + 1 - ms.Length) : buf.Length;
            if (limit <= 0) break;
            var n = await s.ReadAsync(buf.AsMemory(0, limit), ct).ConfigureAwait(false);
            if (n == 0) break;
            ms.Write(buf, 0, n);
        }
        if (maxBytes is { } max && ms.Length > max)
            throw new MediaException($"{job.Verb}: file exceeds gather.maxBytes ({max} bytes)", "failed", job.JobId);
        return ms.ToArray();
    }

    /// <summary>§7G.2/§7G.7: 402, or 429 naming quota/credit/billing/balance ⇒ unavailable; other non-2xx ⇒ failed.</summary>
    internal static void Check(MediaHttpResponse r, MediaJob job)
    {
        if (r.Status is >= 200 and <= 299) return;
        var text = r.Text;
        if (r.Status == 402 || (r.Status == 429 && ContainsAny(text, "quota", "credit", "billing", "balance")))
            throw new MediaUnavailableException($"{job.Client}: HTTP {r.Status}: {MediaUtil.CapBody(text)}", job.JobId);
        throw job.Fail($"HTTP {r.Status}: {MediaUtil.CapBody(text)}");
    }

    internal static bool ContainsAny(string text, params string[] words)
        => words.Any(w => text.Contains(w, StringComparison.OrdinalIgnoreCase));

    internal static object? ParseJson(MediaHttpResponse r, MediaJob job)
    {
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(r.Body);
            return Json.FromElement(doc.RootElement);
        }
        catch (System.Text.Json.JsonException)
        {
            throw job.Fail($"{job.Verb}: response is not JSON");
        }
    }

    internal static Func<HttpContent> JsonBody(object? body)
    {
        var bytes = Encoding.UTF8.GetBytes(Json.Stringify(body));
        return () =>
        {
            var c = new ByteArrayContent(bytes);
            c.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            return c;
        };
    }
}

/// <summary>§7G.5 — turns a provider result (bytes, base64, data: URL, http(s) URL, local path) into a <see cref="MediaFile"/>.</summary>
internal sealed class MediaGatherer
{
    private static long _seq;

    private readonly string _mode;
    private readonly string _dir;
    private readonly long _maxBytes;
    private readonly bool _keep;
    private readonly string _slug;
    private static readonly object NameLock = new();

    /// <summary>§7G.10b gather.name sanitised: bytes outside [A-Za-z0-9._-] ⇒ '-', leading '.'/'-' dropped.</summary>
    internal static string Slug(string? name)
    {
        if (string.IsNullOrEmpty(name)) return "";
        var sb = new StringBuilder();
        foreach (var b in Encoding.UTF8.GetBytes(name))
            sb.Append(b is >= (byte)'a' and <= (byte)'z' or >= (byte)'A' and <= (byte)'Z' or >= (byte)'0' and <= (byte)'9'
                or (byte)'.' or (byte)'_' or (byte)'-' ? (char)b : '-');
        return sb.ToString().TrimStart('.', '-');
    }
    private readonly HttpClient _http;
    private readonly MediaJob _job;
    private readonly string _kind;

    public MediaGatherer(MediaGather? cfg, string defaultMode, HttpClient http, MediaJob job, string kind)
    {
        _mode = cfg?.Mode is "url" or "download" or "inline" ? cfg.Mode : defaultMode;
        _dir = cfg?.Dir is { Length: > 0 } d ? d : Path.Combine(Path.GetTempPath(), "toolnexus-media");
        _maxBytes = MediaUtil.Positive(cfg?.MaxBytes, 104857600);
        _keep = cfg?.Keep ?? true;
        _slug = Slug(cfg?.Name);
        _http = http;
        _job = job;
        _kind = kind;
    }

    public long MaxBytes => _maxBytes;

    private string Type => MediaUtil.TypeOfKind(_kind);

    private string TooBigReason => $"file exceeds gather.maxBytes ({_maxBytes} bytes)";
    private static bool IsTooBig(MediaException e) => e.Status == "failed" && e.Message.EndsWith("bytes)", StringComparison.Ordinal) && e.Message.Contains(": file exceeds gather.maxBytes (", StringComparison.Ordinal);
    private MediaException TooBig() => _job.Fail($"{_job.Verb}: file exceeds gather.maxBytes ({_maxBytes} bytes)");

    public MediaFile FromBytes(byte[] bytes, string mime, string? urlExt = null)
    {
        if (bytes.LongLength > _maxBytes) throw TooBig();
        if (_mode == "inline") return new MediaFile(Type, mime, Data: Convert.ToBase64String(bytes));
        var path = Save(bytes, mime, urlExt);
        return new MediaFile(Type, mime, "file://" + path, path);
    }

    public MediaFile FromBase64(string b64, string mime) => FromBytes(Convert.FromBase64String(b64), mime);

    public MediaFile FromDataUrl(string dataUrl)
    {
        var comma = dataUrl.IndexOf(',');
        var meta = dataUrl[5..Math.Max(5, comma)];
        var mime = meta.Split(';')[0];
        if (mime.Length == 0) mime = MediaUtil.DefaultMime(_kind);
        var payload = comma >= 0 ? dataUrl[(comma + 1)..] : "";
        var bytes = meta.EndsWith(";base64", StringComparison.OrdinalIgnoreCase)
            ? Convert.FromBase64String(payload)
            : Encoding.UTF8.GetBytes(Uri.UnescapeDataString(payload));
        return FromBytes(bytes, mime);
    }

    /// <summary>A result that is a URL: http(s) per mode; a <c>data:</c> URL is decoded.</summary>
    public async Task<MediaFile> FromUrlAsync(string url)
    {
        if (url.StartsWith("data:", StringComparison.OrdinalIgnoreCase)) return FromDataUrl(url);
        var extMime = MediaUtil.MimeOf(url) ?? MediaUtil.DefaultMime(_kind);
        if (_mode == "url") return new MediaFile(Type, extMime, url);
        MediaHttpResponse r;
        try { r = await MediaHttp.SendAsync(_http, _job, "GET", url, null, null, null, _maxBytes).ConfigureAwait(false); }
        catch (MediaException e) when (IsTooBig(e)) { throw _job.Fail($"{_job.Verb}: download failed: {TooBigReason}"); }
        if (r.Status is < 200 or > 299) throw _job.Fail($"{_job.Verb}: download failed: HTTP {r.Status}");
        var mime = r.ContentType is { Length: > 0 } ct && !string.Equals(ct, "application/octet-stream", StringComparison.OrdinalIgnoreCase)
            ? ct : extMime;
        return FromBytes(r.Body, mime, MediaUtil.UrlExt(url));
    }

    /// <summary>A local file the CLI wrote: url mode keeps it; download copies it out (keep:false
    /// deletes the original); inline reads it.</summary>
    public MediaFile FromLocal(string path, string mime, string type)
    {
        var full = Path.GetFullPath(path);
        if (_mode == "url") return new MediaFile(type, mime, "file://" + full, full);
        var len = new FileInfo(full).Length;
        if (len > _maxBytes) throw TooBig();
        var bytes = File.ReadAllBytes(full);
        if (_mode == "inline") return new MediaFile(type, mime, Data: Convert.ToBase64String(bytes));
        var saved = Save(bytes, mime);
        if (!_keep) File.Delete(full);
        return new MediaFile(type, mime, "file://" + saved, saved);
    }

    private string Save(byte[] bytes, string mime, string? urlExt = null)
    {
        Directory.CreateDirectory(_dir);
        var ext = urlExt ?? MediaUtil.ExtFor(mime);
        string path;
        if (_slug.Length > 0)
        {
            lock (NameLock)
                for (var n = 1; ; n++)
                {
                    path = Path.GetFullPath(Path.Combine(_dir, n == 1 ? _slug + ext : $"{_slug}-{n}{ext}"));
                    try
                    {
                        using (new FileStream(path, FileMode.CreateNew, FileAccess.Write)) { }
                        break;
                    }
                    catch (IOException) when (File.Exists(path) || Directory.Exists(path)) { /* taken: next */ }
                }
            File.WriteAllBytes(path, bytes);
            return path;
        }
        var name = $"{_job.Client}-{_job.Verb}-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}-{Interlocked.Increment(ref _seq)}{ext}";
        path = Path.GetFullPath(Path.Combine(_dir, name));
        File.WriteAllBytes(path, bytes);
        return path;
    }

    /// <summary>§7G.1 input reference ⇒ bytes for an upload: http(s) GET without client headers,
    /// <c>file://</c> or a path read from disk. Failure ⇒ <c>&lt;verb&gt;: cannot read "&lt;value&gt;"</c>.</summary>
    public async Task<(byte[] Bytes, string FileName, string Mime)> ReadInputAsync(string value)
    {
        var name = MediaUtil.FileName(value);
        var mime = MediaUtil.MimeOf(name) ?? "application/octet-stream";
        try
        {
            if (MediaUtil.IsHttp(value))
            {
                var r = await MediaHttp.SendAsync(_http, _job, "GET", value, null, null, null, _maxBytes).ConfigureAwait(false);
                if (r.Status is < 200 or > 299) throw new IOException($"HTTP {r.Status}");
                return (r.Body, name, mime);
            }
            var path = value.StartsWith("file://", StringComparison.OrdinalIgnoreCase) ? new Uri(value).LocalPath : value;
            if (new FileInfo(path) is { Exists: true } fi && fi.Length > _maxBytes) throw TooBig();
            return (await File.ReadAllBytesAsync(path, _job.Ct).ConfigureAwait(false), name, mime);
        }
        catch (OperationCanceledException) { throw; }
        catch (MediaException e) when (IsTooBig(e)) { throw _job.Fail($"{_job.Verb}: cannot read \"{value}\": {TooBigReason}"); }
        catch (MediaException e) when (e.Status != "failed") { throw; }
        catch (Exception e)
        {
            throw _job.Fail($"{_job.Verb}: cannot read \"{value}\": {e.Message}");
        }
    }
}
