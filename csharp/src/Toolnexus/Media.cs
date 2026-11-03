using System.Diagnostics;
using System.Globalization;
using System.Text;

namespace Toolnexus;

/// <summary>§7G one generated file: <c>{type, mimeType, url}</c>. <c>type</c> is a §1B part type.</summary>
public sealed record MediaFile(string Type, string MimeType, string Url)
{
    /// <summary>The same file as a §1B URL <see cref="ContentPart"/>.</summary>
    public ContentPart ToPart() => new() { Type = Type, MimeType = MimeType, Url = Url };
}

/// <summary>§7G result of every verb: <c>{files, text?, jobId?, status}</c>. <see cref="Files"/> is never null.</summary>
public sealed record MediaResult(IReadOnlyList<MediaFile> Files, string Status, string? Text = null, string? JobId = null);

/// <summary>§7G progress event, raised after every poll of an async job.</summary>
public sealed record MediaProgress(string Verb, string JobId, string Status);

/// <summary>A §7G engine error. <see cref="Exception.Message"/> is the byte-fixed conformance string.</summary>
public sealed class MediaException : Exception
{
    public MediaException(string message) : base(message) { }
}

/// <summary>
/// §7G provider style — JSON data, never code. Load one with <see cref="Load"/> (a file path),
/// <see cref="Parse"/> (JSON text) or <see cref="FromDictionary"/> (an already-parsed object).
/// </summary>
public sealed class MediaStyle
{
    internal Dictionary<string, object?> Raw { get; }

    private MediaStyle(Dictionary<string, object?> raw) => Raw = raw;

    /// <summary>Read one style file (SPEC <c>loadMediaStyle(path)</c>).</summary>
    public static MediaStyle Load(string path) => Parse(File.ReadAllText(path));

    /// <summary>Parse a style from JSON text.</summary>
    public static MediaStyle Parse(string json) => new(Json.ToMap(json));

    /// <summary>Wrap an already-parsed style object (deep-copied).</summary>
    public static MediaStyle FromDictionary(IDictionary<string, object?> style)
        => Parse(Json.Stringify(style));

    internal string BaseUrl => (Raw.Get("baseUrl") as string ?? "").TrimEnd('/');

    internal IDictionary<string, object?>? Verb(string verb)
        => Raw.Get("verbs") is IDictionary<string, object?> vs ? vs.Get(verb) as IDictionary<string, object?> : null;
}

/// <summary>§7G options for <see cref="Media.Create(MediaStyle, MediaOptions?)"/>.</summary>
public sealed class MediaOptions
{
    /// <summary>Called synchronously after every poll (<c>onProgress({verb, jobId, status})</c>).</summary>
    public Action<MediaProgress>? OnProgress { get; set; }

    /// <summary>Alternative progress sink, reported after <see cref="OnProgress"/>.</summary>
    public IProgress<MediaProgress>? Progress { get; set; }

    /// <summary>HTTP client for style requests — the same seam as <see cref="LlmClient.Options.HttpClient"/>.
    /// Wins over <see cref="HttpHandler"/>.</summary>
    public HttpClient? HttpClient { get; set; }

    /// <summary>Message handler to build the client from (a fake, a recorder, a proxy). Ignored when
    /// <see cref="HttpClient"/> is set. Null ⇒ default.</summary>
    public HttpMessageHandler? HttpHandler { get; set; }
}

/// <summary>Typed input of <see cref="Media.ImageAsync"/>.</summary>
public sealed record ImageInput(string Prompt)
{
    public long? Width { get; init; }
    public long? Height { get; init; }
    public string? AspectRatio { get; init; }
    public long? Seed { get; init; }
    /// <summary>Top-level keys overwrite the request body last. Function face only.</summary>
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>Typed input of <see cref="Media.VideoAsync"/>.</summary>
public sealed record VideoInput(string Prompt)
{
    public string? Image { get; init; }
    public long? Seconds { get; init; }
    public string? AspectRatio { get; init; }
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>Typed input of <see cref="Media.SpeechAsync"/>.</summary>
public sealed record SpeechInput(string Text)
{
    public string? Voice { get; init; }
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>Typed input of <see cref="Media.TranscribeAsync"/>.</summary>
public sealed record TranscribeInput(string Audio)
{
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>Typed input of <see cref="Media.CloneVoiceAsync"/>.</summary>
public sealed record CloneVoiceInput(string Name, IReadOnlyList<string> Samples)
{
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>Typed input of <see cref="Media.TalkingAsync"/>.</summary>
public sealed record TalkingInput(string Image, string Audio)
{
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>
/// SPEC §7G — media generation. One engine, two faces: typed verbs a host calls
/// (<see cref="ImageAsync"/> …, <see cref="GenerateAsync"/>) and tools an LLM calls
/// (<see cref="Tools"/>, source <c>"media"</c>). Providers are <see cref="MediaStyle"/> data;
/// the library names no vendor.
/// </summary>
public sealed class Media
{
    private static readonly HttpClient DefaultHttp = new() { Timeout = Timeout.InfiniteTimeSpan };

    private readonly IReadOnlyList<MediaStyle> _styles;
    private readonly MediaOptions _opts;
    private readonly HttpClient _http;

    private Media(IReadOnlyList<MediaStyle> styles, MediaOptions? opts)
    {
        _styles = styles;
        _opts = opts ?? new MediaOptions();
        _http = _opts.HttpClient
            ?? (_opts.HttpHandler != null
                ? new HttpClient(_opts.HttpHandler, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan }
                : DefaultHttp);
    }

    /// <summary>A media engine over one style.</summary>
    public static Media Create(MediaStyle style, MediaOptions? options = null)
        => new(new[] { style ?? throw new ArgumentNullException(nameof(style)) }, options);

    /// <summary>A media engine over several styles; a verb resolves to the FIRST style declaring it.</summary>
    public static Media Create(IEnumerable<MediaStyle> styles, MediaOptions? options = null)
        => new(styles.ToList(), options);

    // ---------------------------------------------------------------- typed verbs

    public Task<MediaResult> ImageAsync(ImageInput input, CancellationToken cancellationToken = default)
        => GenerateAsync("image", Args(("prompt", input.Prompt), ("width", input.Width), ("height", input.Height),
            ("aspectRatio", input.AspectRatio), ("seed", input.Seed)), input.ProviderOptions, cancellationToken);

    public Task<MediaResult> VideoAsync(VideoInput input, CancellationToken cancellationToken = default)
        => GenerateAsync("video", Args(("prompt", input.Prompt), ("image", input.Image), ("seconds", input.Seconds),
            ("aspectRatio", input.AspectRatio)), input.ProviderOptions, cancellationToken);

    public Task<MediaResult> SpeechAsync(SpeechInput input, CancellationToken cancellationToken = default)
        => GenerateAsync("speech", Args(("text", input.Text), ("voice", input.Voice)), input.ProviderOptions, cancellationToken);

    public Task<MediaResult> TranscribeAsync(TranscribeInput input, CancellationToken cancellationToken = default)
        => GenerateAsync("transcribe", Args(("audio", input.Audio)), input.ProviderOptions, cancellationToken);

    public Task<MediaResult> CloneVoiceAsync(CloneVoiceInput input, CancellationToken cancellationToken = default)
        => GenerateAsync("cloneVoice", Args(("name", input.Name),
            ("samples", input.Samples?.Select(s => (object?)s).ToList())), input.ProviderOptions, cancellationToken);

    public Task<MediaResult> TalkingAsync(TalkingInput input, CancellationToken cancellationToken = default)
        => GenerateAsync("talking", Args(("image", input.Image), ("audio", input.Audio)), input.ProviderOptions, cancellationToken);

    private static Dictionary<string, object?> Args(params (string Key, object? Value)[] pairs)
    {
        var d = new Dictionary<string, object?>();
        foreach (var (k, v) in pairs)
            if (v != null) d[k] = v;
        return d;
    }

    // ---------------------------------------------------------------- engine

    /// <summary>
    /// The generic verb call (SPEC <c>generate(verb, args)</c>). Throws <see cref="MediaException"/>
    /// with the conformance message on any engine error, and <see cref="OperationCanceledException"/>
    /// when <paramref name="cancellationToken"/> fires.
    /// </summary>
    public async Task<MediaResult> GenerateAsync(string verb, IDictionary<string, object?>? args,
        IDictionary<string, object?>? providerOptions = null, CancellationToken cancellationToken = default)
    {
        // 1. resolve
        MediaStyle? style = null;
        IDictionary<string, object?>? v = null;
        foreach (var s in _styles)
        {
            v = s.Verb(verb);
            if (v != null) { style = s; break; }
        }
        if (style == null || v == null) throw new MediaException($"media: no style supports \"{verb}\"");

        // 2. validate args
        var argMap = v.Get("args") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
        args ??= new Dictionary<string, object?>();
        // A null value is absent; with several unsupported keys, the first in code-point order is named.
        var bad = args.Where(kv => kv.Value != null && !argMap.ContainsKey(kv.Key)).Select(kv => kv.Key)
            .OrderBy(k => k, CodePointComparer.Instance).FirstOrDefault();
        if (bad != null) throw new MediaException($"{verb}: argument \"{bad}\" not supported by this style");

        // 3. body
        var body = v.Get("defaults") is IDictionary<string, object?> defs
            ? Json.ToMap(Json.Stringify(defs))
            : new Dictionary<string, object?>();
        foreach (var (key, value) in args)
        {
            if (value == null) continue;
            WritePath(body, argMap.Get(key)?.ToString() ?? key, Json.ParseLoose(Json.Stringify(value)));
        }
        if (providerOptions != null)
            foreach (var (k, pv) in providerOptions) body[k] = Json.ParseLoose(Json.Stringify(pv));

        // 4. start
        var start = v.Get("start") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
        var method = start.Get("method") as string ?? "POST";
        var final = await SendAsync(style, verb, method, start.Get("path") as string ?? "", body, cancellationToken).ConfigureAwait(false);

        string status;
        string? jobId = null;
        if ((v.Get("mode") as string) == "async")
        {
            var idPath = v.Get("idPath") as string ?? "";
            jobId = ScalarText(ReadPath(final, idPath), integralOnly: true) is { Length: > 0 } jid ? jid
                : throw new MediaException($"{verb}: no job id at {idPath}");
            (final, status) = await PollAsync(style, verb, v.Get("poll") as IDictionary<string, object?> ?? new Dictionary<string, object?>(),
                jobId, cancellationToken).ConfigureAwait(false);
        }
        else
        {
            status = "completed";
        }

        // 8. result
        var kind = v.Get("kind") as string ?? "image";
        var resultPath = v.Get("resultPath") as string ?? "";
        var raw = ReadPath(final, resultPath);
        if (kind == "text")
        {
            var text = raw is string str ? str : raw is long or double ? ScalarText(raw, false) : null;
            if (text == null) throw new MediaException($"{verb}: no result at {resultPath}");
            return new MediaResult(Array.Empty<MediaFile>(), status, text, jobId);
        }
        var urls = new List<string>();
        Flatten(raw, urls);
        if (urls.Count == 0) throw new MediaException($"{verb}: no result at {resultPath}");
        return new MediaResult(urls.Select(u => FileFor(kind, u)).ToList(), status, null, jobId);
    }

    private async Task<(object? Final, string Status)> PollAsync(MediaStyle style, string verb,
        IDictionary<string, object?> poll, string jobId, CancellationToken ct)
    {
        var interval = ToLong(poll.Get("intervalMs")) ?? 5000;
        var timeout = ToLong(poll.Get("timeoutMs")) ?? 600000;
        var method = poll.Get("method") as string ?? "GET";
        var path = (poll.Get("path") as string ?? "").Replace("{id}", jobId);
        var statusPath = poll.Get("statusPath") as string ?? "";
        var done = StringSet(poll.Get("done"));
        var failed = StringSet(poll.Get("failed"));
        var errorPath = poll.Get("errorPath") as string;
        var clock = Stopwatch.StartNew();
        while (true)
        {
            await Task.Delay(TimeSpan.FromMilliseconds(interval), ct).ConfigureAwait(false);
            var resp = await SendAsync(style, verb, method, path, null, ct).ConfigureAwait(false);
            var status = ScalarText(ReadPath(resp, statusPath), false) ?? "";
            var ev = new MediaProgress(verb, jobId, status);
            _opts.OnProgress?.Invoke(ev);
            _opts.Progress?.Report(ev);
            if (done.Contains(status)) return (resp, status);
            if (failed.Contains(status))
            {
                var msg = errorPath != null ? ReadPath(resp, errorPath) as string : null;
                throw new MediaException(string.IsNullOrEmpty(msg) ? $"job {jobId} {status}" : $"job {jobId} {status}: {msg}");
            }
            if (clock.ElapsedMilliseconds >= timeout)
                throw new MediaException($"job {jobId} timed out after {timeout}ms");
        }
    }

    private async Task<object?> SendAsync(MediaStyle style, string verb, string method, string path,
        Dictionary<string, object?>? body, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(new HttpMethod(method.ToUpperInvariant()), style.BaseUrl + path);
        if (body != null)
            req.Content = new StringContent(Json.Stringify(body), Encoding.UTF8, "application/json");
        if (style.Raw.Get("headers") is IDictionary<string, object?> hs)
        {
            var expanded = McpSource.ExpandEnvHeaders(hs.ToDictionary(kv => kv.Key, kv => kv.Value?.ToString() ?? ""))!;
            foreach (var (k, hv) in expanded)
            {
                if (string.Equals(k, "Content-Type", StringComparison.OrdinalIgnoreCase)) continue;
                req.Headers.TryAddWithoutValidation(k, hv);
            }
        }
        HttpResponseMessage resp;
        string text;
        try
        {
            resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
            text = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        }
        catch (HttpRequestException e)
        {
            throw new MediaException($"{verb}: {e.Message}"); // transport error: no HTTP status
        }
        using var _ = resp;
        var code = (int)resp.StatusCode;
        if (code is < 200 or > 299) throw new MediaException($"HTTP {code}: {text}");
        object? parsed;
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(text);
            parsed = Json.FromElement(doc.RootElement);
        }
        catch (System.Text.Json.JsonException)
        {
            throw new MediaException($"{verb}: response is not JSON");
        }
        if (style.Raw.Get("errorEnvelope") is IDictionary<string, object?> env)
        {
            var codePath = env.Get("codePath") as string ?? "";
            var c = ReadPath(parsed, codePath);
            var oks = env.Get("okValues") as IEnumerable<object?> ?? Array.Empty<object?>();
            if (c != null && !oks.Any(ok => JsonEqual(ok, c)))
            {
                var msg = env.Get("messagePath") is string mp ? ReadPath(parsed, mp) as string ?? "" : "";
                var codeText = ScalarText(c, true) ?? Json.Stringify(c);
                throw new MediaException($"API error {codeText}: {msg}");
            }
        }
        return parsed;
    }

    // ---------------------------------------------------------------- tool face

    /// <summary>One <see cref="ITool"/> (source <c>"media"</c>) per supported verb, in canonical order.</summary>
    public IReadOnlyList<ITool> Tools()
    {
        var output = new List<ITool>();
        foreach (var spec in MediaVerbs.All)
        {
            var v = _styles.Select(s => s.Verb(spec.Verb)).FirstOrDefault(x => x != null);
            if (v == null) continue;
            var mapped = v.Get("args") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
            var props = new Dictionary<string, object?>();
            var required = new List<object?>();
            foreach (var a in spec.Args)
            {
                if (!mapped.ContainsKey(a.Name)) continue;
                var p = new Dictionary<string, object?> { ["type"] = a.Type };
                if (a.Items != null) p["items"] = new Dictionary<string, object?> { ["type"] = a.Items };
                p["description"] = a.Description;
                props[a.Name] = p;
                if (a.Required) required.Add(a.Name);
            }
            var schema = new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = props,
                ["required"] = required,
            };
            output.Add(new MediaTool(this, spec, schema, v.Get("kind") as string ?? "image"));
        }
        return output;
    }

    private sealed class MediaTool : ITool
    {
        private readonly Media _media;
        private readonly MediaVerbs.VerbSpec _spec;
        private readonly string _kind;

        public MediaTool(Media media, MediaVerbs.VerbSpec spec, IDictionary<string, object?> schema, string kind)
        {
            (_media, _spec, InputSchema, _kind) = (media, spec, schema, kind);
        }

        public string Name => _spec.Tool;
        public string Description => _spec.Description;
        public IDictionary<string, object?> InputSchema { get; }
        public string Source => "media";

        /// <summary>Each call is an independent remote job: always concurrency-safe (§7G, §8 Tool scheduling).</summary>
        public bool IsConcurrencySafe(IDictionary<string, object?> args) => true;

        public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            MediaResult r;
            try
            {
                r = await _media.GenerateAsync(_spec.Verb, args, null, ctx?.CancellationToken ?? default).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                return ToolResult.Error(e.Message);
            }
            var meta = new Dictionary<string, object?> { ["source"] = "media", ["verb"] = _spec.Verb };
            if (r.JobId != null) meta["jobId"] = r.JobId;
            meta["status"] = r.Status;
            if (_kind == "text") return ToolResult.Ok(r.Text ?? "", meta);
            var word = _kind switch { "video" => "video", "audio" => "audio", _ => "image" };
            var output = word + " ready: " + string.Join("\n", r.Files.Select(f => f.Url));
            return ToolResult.OkWithParts(output, r.Files.Select(f => f.ToPart()).ToList(), meta);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static readonly Dictionary<string, string> MimeByExt = new(StringComparer.OrdinalIgnoreCase)
    {
        ["png"] = "image/png", ["jpg"] = "image/jpeg", ["jpeg"] = "image/jpeg", ["webp"] = "image/webp",
        ["gif"] = "image/gif", ["mp4"] = "video/mp4", ["webm"] = "video/webm", ["mov"] = "video/quicktime",
        ["mp3"] = "audio/mpeg", ["wav"] = "audio/wav", ["ogg"] = "audio/ogg", ["m4a"] = "audio/mp4",
        ["flac"] = "audio/flac",
    };

    internal static MediaFile FileFor(string kind, string url)
    {
        var type = kind switch { "audio" => "audio", "video" => "file", _ => "image" };
        var p = url;
        var cut = p.IndexOfAny(new[] { '?', '#' });
        if (cut >= 0) p = p[..cut];
        var seg = p[(p.LastIndexOf('/') + 1)..];
        var dot = seg.LastIndexOf('.');
        string? mime = null;
        if (dot >= 0) MimeByExt.TryGetValue(seg[(dot + 1)..], out mime);
        mime ??= kind switch { "video" => "video/mp4", "audio" => "audio/mpeg", _ => "image/png" };
        return new MediaFile(type, mime, url);
    }

    private static void Flatten(object? v, List<string> into)
    {
        switch (v)
        {
            case string s when s.Length > 0: into.Add(s); break;
            case List<object?> list: foreach (var x in list) Flatten(x, into); break;
        }
    }

    private static HashSet<string> StringSet(object? v)
        => v is IEnumerable<object?> e ? e.Select(x => x?.ToString() ?? "").ToHashSet(StringComparer.Ordinal) : new HashSet<string>();

    private static long? ToLong(object? v) => v switch
    {
        long l => l,
        int i => i,
        double d => (long)d,
        _ => null,
    };

    /// <summary>String as-is; an integral number as integer text; a non-integral number (unless
    /// <paramref name="integralOnly"/>) in invariant round-trip form; anything else null.</summary>
    private static string? ScalarText(object? v, bool integralOnly) => v switch
    {
        string s => s,
        long l => l.ToString(CultureInfo.InvariantCulture),
        int i => i.ToString(CultureInfo.InvariantCulture),
        double d when d == Math.Floor(d) && !double.IsInfinity(d) => ((long)d).ToString(CultureInfo.InvariantCulture),
        double d when !integralOnly => d.ToString("R", CultureInfo.InvariantCulture),
        _ => null,
    };

    private static bool JsonEqual(object? a, object? b)
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

    /// <summary>§7G dotPath read: a missing segment ⇒ null (absent).</summary>
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

    /// <summary>§7G dotPath write: creates objects, and arrays when the next segment is numeric (null-padded).</summary>
    internal static void WritePath(Dictionary<string, object?> root, string path, object? value)
    {
        var segs = path.Split('.');
        object cur = root;
        for (var n = 0; n < segs.Length; n++)
        {
            var seg = segs[n];
            var last = n == segs.Length - 1;
            object? Fresh() => n + 1 < segs.Length && IsIndex(segs[n + 1], out _) ? new List<object?>() : new Dictionary<string, object?>();
            if (cur is List<object?> list && IsIndex(seg, out var i))
            {
                while (list.Count <= i) list.Add(null);
                if (last) { list[i] = value; return; }
                if (list[i] is not (Dictionary<string, object?> or List<object?>)) list[i] = Fresh();
                cur = list[i]!;
            }
            else
            {
                if (cur is not Dictionary<string, object?> d)
                    return; // unreachable: containers are created to match the next segment
                if (last) { d[seg] = value; return; }
                if (!d.TryGetValue(seg, out var next) || next is not (Dictionary<string, object?> or List<object?>)
                    || (next is List<object?> && !IsIndex(segs[n + 1], out _)))
                    d[seg] = next = Fresh();
                cur = next!;
            }
        }
    }
}

/// <summary>Unicode code-point order (not UTF-16 code-unit order).</summary>
internal sealed class CodePointComparer : IComparer<string>
{
    public static readonly CodePointComparer Instance = new();
    public int Compare(string? x, string? y)
    {
        var a = (x ?? "").EnumerateRunes().GetEnumerator();
        var b = (y ?? "").EnumerateRunes().GetEnumerator();
        while (true)
        {
            var ha = a.MoveNext(); var hb = b.MoveNext();
            if (!ha || !hb) return ha == hb ? 0 : ha ? 1 : -1;
            var c = a.Current.Value.CompareTo(b.Current.Value);
            if (c != 0) return c;
        }
    }
}

/// <summary>
/// The canonical verb table (SPEC §7G) — <c>conformance/media/verbs.json</c> embedded verbatim
/// (its <c>verbs</c> array): order, tool names, descriptions, args, types and required flags.
/// </summary>
public static class MediaVerbs
{
    public sealed record ArgSpec(string Name, string Type, string Description, bool Required, string? Items);
    public sealed record VerbSpec(string Verb, string Tool, string Description, IReadOnlyList<ArgSpec> Args);

    /// <summary>The verbatim <c>verbs</c> array of <c>conformance/media/verbs.json</c>.</summary>
    public const string VerbsJson = """
[
    { "verb": "image", "tool": "generate_image", "description": "Generate an image from a text prompt.",
      "args": [
        { "name": "prompt", "type": "string", "description": "What to draw.", "required": true },
        { "name": "width", "type": "integer", "description": "Width in pixels." },
        { "name": "height", "type": "integer", "description": "Height in pixels." },
        { "name": "aspectRatio", "type": "string", "description": "Aspect ratio, e.g. 16:9." },
        { "name": "seed", "type": "integer", "description": "Seed for reproducible output." } ] },
    { "verb": "video", "tool": "generate_video", "description": "Generate a short video from a prompt, optionally starting from an image URL. Can take minutes.",
      "args": [
        { "name": "prompt", "type": "string", "description": "What should happen in the video.", "required": true },
        { "name": "image", "type": "string", "description": "URL of the starting image." },
        { "name": "seconds", "type": "integer", "description": "Video length in seconds." },
        { "name": "aspectRatio", "type": "string", "description": "Aspect ratio, e.g. 9:16." } ] },
    { "verb": "speech", "tool": "generate_speech", "description": "Speak text aloud and return the audio.",
      "args": [
        { "name": "text", "type": "string", "description": "The text to speak.", "required": true },
        { "name": "voice", "type": "string", "description": "Voice or speaker id." } ] },
    { "verb": "transcribe", "tool": "transcribe_audio", "description": "Transcribe speech in an audio URL to text.",
      "args": [
        { "name": "audio", "type": "string", "description": "URL of the audio.", "required": true } ] },
    { "verb": "cloneVoice", "tool": "clone_voice", "description": "Create a cloned voice from audio sample URLs and return its voice id.",
      "args": [
        { "name": "name", "type": "string", "description": "Name for the new voice.", "required": true },
        { "name": "samples", "type": "array", "items": "string", "description": "URLs of audio samples of the voice.", "required": true } ] },
    { "verb": "talking", "tool": "talking_video", "description": "Make the person in an image speak the given audio (lip-synced video).",
      "args": [
        { "name": "image", "type": "string", "description": "URL of the face image.", "required": true },
        { "name": "audio", "type": "string", "description": "URL of the speech audio.", "required": true } ] }
  ]
""";

    /// <summary>The parsed table, in canonical order.</summary>
    public static readonly IReadOnlyList<VerbSpec> All = Parse();

    private static IReadOnlyList<VerbSpec> Parse()
    {
        var list = (List<object?>)Json.ParseLoose(VerbsJson)!;
        return list.Cast<Dictionary<string, object?>>().Select(v => new VerbSpec(
            (string)v["verb"]!, (string)v["tool"]!, (string)v["description"]!,
            ((List<object?>)v["args"]!).Cast<Dictionary<string, object?>>().Select(a => new ArgSpec(
                (string)a["name"]!, (string)a["type"]!, (string)a["description"]!,
                a.Get("required") is true, a.Get("items") as string)).ToList())).ToList();
    }
}
