using System.Reflection;

namespace Toolnexus;

/// <summary>§7G one generated file: <c>{type, mimeType, url?, path?, data?}</c>. <c>type</c> is a §1B
/// part type (image ⇒ <c>image</c>, audio ⇒ <c>audio</c>, video ⇒ <c>file</c>). Which of
/// <see cref="Url"/> / <see cref="Path"/> / <see cref="Data"/> is set is decided by gather (§7G.5).</summary>
public sealed record MediaFile(string Type, string MimeType, string? Url = null, string? Path = null, string? Data = null)
{
    /// <summary>The same file as a §1B <see cref="ContentPart"/> (§7G.6): <c>data</c> when the file
    /// has data, a <c>url</c> part for an http(s) URL, else the local file's bytes read now. A part
    /// never holds a path.</summary>
    public ContentPart ToPart()
    {
        if (Data != null) return new ContentPart { Type = Type, MimeType = MimeType, Data = Data, LocalPath = Path };
        if (Url != null && MediaUtil.IsHttp(Url)) return new ContentPart { Type = Type, MimeType = MimeType, Url = Url, LocalPath = Path };
        var local = Path ?? (Url != null && Url.StartsWith("file://", StringComparison.Ordinal) ? Url["file://".Length..] : null);
        if (local == null) return new ContentPart { Type = Type, MimeType = MimeType, Url = Url };
        return new ContentPart { Type = Type, MimeType = MimeType, Data = Convert.ToBase64String(File.ReadAllBytes(local)), LocalPath = Path };
    }
}

/// <summary>§7G result of every verb: <c>{files, text?, jobId?, status}</c>. <see cref="Files"/> is never null.</summary>
public sealed record MediaResult(IReadOnlyList<MediaFile> Files, string Status, string? Text = null, string? JobId = null)
{
    /// <summary>Provider-reported usage (§7G.12 D21); null when the provider reports none. Never estimated.</summary>
    public MediaUsage? Usage { get; init; }
}

/// <summary>§7G.12 D21 provider-reported media usage. A field the provider omits stays null.</summary>
public sealed record MediaUsage
{
    public long? InputTokens { get; init; }
    public long? OutputTokens { get; init; }
    public double? CostUsd { get; init; }
    public IDictionary<string, object?>? Raw { get; init; }

    /// <summary>The JSON shape <c>{inputTokens?, outputTokens?, costUsd?, raw?}</c>, or null when empty.</summary>
    public Dictionary<string, object?>? ToMap()
    {
        var d = new Dictionary<string, object?>();
        if (InputTokens != null) d["inputTokens"] = InputTokens;
        if (OutputTokens != null) d["outputTokens"] = OutputTokens;
        if (CostUsd != null) d["costUsd"] = CostUsd;
        if (Raw != null) d["raw"] = Raw;
        return d.Count == 0 ? null : d;
    }
}

/// <summary>§7G.2 progress event: <c>{verb, client, jobId?, status, elapsedMs}</c>.</summary>
public sealed record MediaProgress(string Verb, string Client, string? JobId, string Status, long ElapsedMs);

/// <summary>§7G.2 media error. <see cref="Exception.Message"/> is the byte-fixed conformance string;
/// <see cref="Status"/> is <c>failed | timeout | cancelled | unavailable</c>. Cancellation surfaces
/// as the native <see cref="OperationCanceledException"/> instead.</summary>
public class MediaException : Exception
{
    public string Status { get; }
    public string? JobId { get; }

    public MediaException(string message, string status = "failed", string? jobId = null) : base(message)
    {
        Status = status;
        JobId = jobId;
    }
}

/// <summary>§7G.2 status <c>unavailable</c>: the call was valid but this client cannot serve it now
/// (no credits, quota, no key, CLI missing, ZDR). The only error <see cref="Media"/> falls through on.</summary>
public sealed class MediaUnavailableException : MediaException
{
    public MediaUnavailableException(string message, string? jobId = null) : base(message, "unavailable", jobId) { }
}

/// <summary>§7G.2 per-call context: native cancellation, an absolute deadline, a progress sink
/// (wins over the client's <c>Loop.OnProgress</c>) and function-face provider options.</summary>
public sealed class MediaContext
{
    public CancellationToken CancellationToken { get; init; }
    public DateTimeOffset? Deadline { get; init; }
    public Action<MediaProgress>? Progress { get; init; }
    /// <summary>Top-level keys overwrite the request body last (function face only, never the tool face).</summary>
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>§7G.2 one accepted canonical arg of a verb.</summary>
public sealed record MediaArg(string Name, bool Required);

/// <summary>§7G.4 <c>loop</c>: poll interval, job deadline, progress callback. Absent or <c>&lt;= 0</c> ⇒ the default.</summary>
public sealed class MediaLoop
{
    public long? IntervalMs { get; set; }
    public long? TimeoutMs { get; set; }
    public Action<MediaProgress>? OnProgress { get; set; }
}

/// <summary>§7G.4/§7G.5 <c>gather</c>: what the host gets back.</summary>
public sealed class MediaGather
{
    /// <summary><c>url | download | inline</c>; default <c>download</c> (style clients: <c>url</c>).</summary>
    public string? Mode { get; set; }
    /// <summary>Where saved files go; default <c>&lt;os temp&gt;/toolnexus-media</c>, created when missing.</summary>
    public string? Dir { get; set; }
    /// <summary>Largest file saved, inlined or downloaded; default 100 MiB.</summary>
    public long? MaxBytes { get; set; }
    /// <summary>CLI clients: keep the CLI's original file after copying it out; default true.</summary>
    public bool? Keep { get; set; }
}

/// <summary>§7G.4 — the one config shape of every built-in media client. Fields a client does not use are ignored.</summary>
public sealed class MediaClientConfig
{
    /// <summary>API clients: the model of the primary verb. CLI clients: passed as <c>-m &lt;model&gt;</c>.</summary>
    public string? Model { get; set; }
    /// <summary>Per-verb model override (<c>{"video": "sora-2"}</c>); wins over <see cref="Model"/>.</summary>
    public IDictionary<string, string>? Models { get; set; }
    /// <summary>API key; default the client's env var, read at call time. Never logged.</summary>
    public string? Key { get; set; }
    /// <summary>Base URL override (trailing <c>/</c> trimmed) — any compatible server.</summary>
    public string? Url { get; set; }
    /// <summary>CLI clients: argv prefix (one executable per entry, never shell-split); default the CLI's name.</summary>
    public IReadOnlyList<string>? Command { get; set; }
    public MediaLoop? Loop { get; set; }
    public MediaGather? Gather { get; set; }
    /// <summary>The same HTTP-client injection seam as <see cref="LlmClient.Options.HttpClient"/>.
    /// Redirects are followed by the client itself, so a handler should not auto-redirect.</summary>
    public HttpClient? HttpClient { get; set; }
}

/// <summary>
/// §7G.2 — a media source. Every verb is optional: implement what the provider supports and say so
/// in <see cref="Supports"/>. Unimplemented verbs fail with a <see cref="MediaException"/>.
/// <see cref="Args"/> returns null to accept every canonical arg of the verb.
/// </summary>
public interface IMediaClient
{
    string Name { get; }
    bool Supports(string verb);
    IReadOnlyList<MediaArg>? Args(string verb) => null;

    Task<MediaResult> ImageAsync(IDictionary<string, object?> args, MediaContext ctx) => throw Unsupported("image");
    Task<MediaResult> VideoAsync(IDictionary<string, object?> args, MediaContext ctx) => throw Unsupported("video");
    Task<MediaResult> SpeechAsync(IDictionary<string, object?> args, MediaContext ctx) => throw Unsupported("speech");
    Task<MediaResult> MusicAsync(IDictionary<string, object?> args, MediaContext ctx) => throw Unsupported("music");
    Task<MediaResult> TranscribeAsync(IDictionary<string, object?> args, MediaContext ctx) => throw Unsupported("transcribe");
    Task<MediaResult> CloneVoiceAsync(IDictionary<string, object?> args, MediaContext ctx) => throw Unsupported("cloneVoice");
    Task<MediaResult> TalkAsync(IDictionary<string, object?> args, MediaContext ctx) => throw Unsupported("talk");

    private MediaException Unsupported(string verb) => new($"{verb}: not supported by {Name}");
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

/// <summary>Typed input of <see cref="Media.MusicAsync"/>.</summary>
public sealed record MusicInput(string Prompt)
{
    public long? Seconds { get; init; }
    public bool? Instrumental { get; init; }
    public string? Lyrics { get; init; }
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>Typed input of <see cref="Media.TranscribeAsync"/>.</summary>
public sealed record TranscribeInput(string Audio)
{
    public string? Language { get; init; }
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>Typed input of <see cref="Media.CloneVoiceAsync"/>.</summary>
public sealed record CloneVoiceInput(string Name, IReadOnlyList<string> Samples)
{
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>Typed input of <see cref="Media.TalkAsync"/>.</summary>
public sealed record TalkInput(string Image)
{
    public string? Audio { get; init; }
    public string? Text { get; init; }
    public string? Voice { get; init; }
    public IDictionary<string, object?>? ProviderOptions { get; init; }
}

/// <summary>
/// SPEC §7G — media generation (<c>createMedia</c>). One router over one or more
/// <see cref="IMediaClient"/>s, two faces: typed verbs a host calls (<see cref="ImageAsync"/> …,
/// <see cref="GenerateAsync(string, IDictionary{string, object?}?, MediaContext?)"/>) and tools an
/// LLM calls (<see cref="Tools"/>, source <c>"media"</c>). A verb goes to the first client that
/// supports it and falls through to the next <b>only</b> on <see cref="MediaUnavailableException"/>.
/// </summary>
public sealed class Media
{
    private readonly IReadOnlyList<IMediaClient> _clients;

    private Media(IReadOnlyList<IMediaClient> clients)
    {
        if (clients.Count == 0) throw new MediaException("media: no clients");
        _clients = clients;
    }

    /// <summary>A router over one client.</summary>
    public static Media Create(IMediaClient client) => new(new[] { client ?? throw new ArgumentNullException(nameof(client)) });

    /// <summary>A router over several clients, tried in order.</summary>
    public static Media Create(IEnumerable<IMediaClient> clients) => new(clients.ToList());

    /// <summary>A router over several clients, tried in order.</summary>
    public static Media Create(params IMediaClient[] clients) => new(clients.ToList());

    /// <summary>True when any client supports <paramref name="verb"/>.</summary>
    public bool Supports(string verb) => _clients.Any(c => c.Supports(verb));

    // ---------------------------------------------------------------- typed verbs

    public Task<MediaResult> ImageAsync(ImageInput input, CancellationToken cancellationToken = default)
        => Typed("image", input.ProviderOptions, cancellationToken, ("prompt", input.Prompt), ("width", input.Width),
            ("height", input.Height), ("aspectRatio", input.AspectRatio), ("seed", input.Seed));

    public Task<MediaResult> VideoAsync(VideoInput input, CancellationToken cancellationToken = default)
        => Typed("video", input.ProviderOptions, cancellationToken, ("prompt", input.Prompt), ("image", input.Image),
            ("seconds", input.Seconds), ("aspectRatio", input.AspectRatio));

    public Task<MediaResult> SpeechAsync(SpeechInput input, CancellationToken cancellationToken = default)
        => Typed("speech", input.ProviderOptions, cancellationToken, ("text", input.Text), ("voice", input.Voice));

    public Task<MediaResult> MusicAsync(MusicInput input, CancellationToken cancellationToken = default)
        => Typed("music", input.ProviderOptions, cancellationToken, ("prompt", input.Prompt), ("seconds", input.Seconds),
            ("instrumental", input.Instrumental), ("lyrics", input.Lyrics));

    public Task<MediaResult> TranscribeAsync(TranscribeInput input, CancellationToken cancellationToken = default)
        => Typed("transcribe", input.ProviderOptions, cancellationToken, ("audio", input.Audio), ("language", input.Language));

    public Task<MediaResult> CloneVoiceAsync(CloneVoiceInput input, CancellationToken cancellationToken = default)
        => Typed("cloneVoice", input.ProviderOptions, cancellationToken, ("name", input.Name),
            ("samples", input.Samples?.Select(s => (object?)s).ToList()));

    public Task<MediaResult> TalkAsync(TalkInput input, CancellationToken cancellationToken = default)
        => Typed("talk", input.ProviderOptions, cancellationToken, ("image", input.Image), ("audio", input.Audio),
            ("text", input.Text), ("voice", input.Voice));

    private Task<MediaResult> Typed(string verb, IDictionary<string, object?>? providerOptions, CancellationToken ct,
        params (string Key, object? Value)[] pairs)
    {
        var d = new Dictionary<string, object?>();
        foreach (var (k, v) in pairs)
            if (v != null) d[k] = v;
        return GenerateAsync(verb, d, new MediaContext { CancellationToken = ct, ProviderOptions = providerOptions });
    }

    // ---------------------------------------------------------------- routing (§7G.3)

    /// <summary>The generic verb call (SPEC <c>generate(verb, args, ctx?)</c>). Throws
    /// <see cref="MediaException"/> (typed status) or <see cref="OperationCanceledException"/>.</summary>
    public async Task<MediaResult> GenerateAsync(string verb, IDictionary<string, object?>? args, MediaContext? ctx = null)
        => (await RouteAsync(verb, args, ctx ?? new MediaContext()).ConfigureAwait(false)).Result;

    /// <summary><see cref="GenerateAsync(string, IDictionary{string, object?}?, MediaContext?)"/> with only a cancellation token.</summary>
    public Task<MediaResult> GenerateAsync(string verb, IDictionary<string, object?>? args, CancellationToken cancellationToken)
        => GenerateAsync(verb, args, new MediaContext { CancellationToken = cancellationToken });

    internal async Task<(MediaResult Result, string Client)> RouteAsync(string verb, IDictionary<string, object?>? args, MediaContext ctx)
    {
        var candidates = _clients.Where(c => c.Supports(verb)).ToList();
        if (candidates.Count == 0) throw new MediaException($"media: no client supports \"{verb}\"");
        var clean = new Dictionary<string, object?>();
        if (args != null)
            foreach (var (k, v) in args)
                if (v != null) clean[k] = v;

        var spec = MediaVerbs.Find(verb);
        var nonCanonical = clean.Keys.Where(k => spec == null || spec.Args.All(a => a.Name != k))
            .OrderBy(k => k, CodePointComparer.Instance).FirstOrDefault();
        if (nonCanonical != null) throw new MediaException($"{verb}: argument \"{nonCanonical}\" not supported"); // §7G.12 D7

        var errors = new List<string>();
        foreach (var c in candidates)
        {
            // §7G.12 D19: every candidate, the first included, is skipped when it cannot take the args.
            var ok = AcceptedArgs(c, verb);
            var bad = clean.Keys.Where(k => ok.All(a => a.Name != k)).OrderBy(k => k, CodePointComparer.Instance).FirstOrDefault();
            if (bad != null)
            {
                errors.Add($"{c.Name}: argument \"{bad}\" not supported");
                continue;
            }
            if (ok.FirstOrDefault(a => a.Required && !clean.ContainsKey(a.Name)) is { } missing)
            {
                errors.Add($"{c.Name}: missing required argument \"{missing.Name}\"");
                continue;
            }
            try
            {
                return (await Dispatch(c, verb, clean, ctx).ConfigureAwait(false), c.Name);
            }
            catch (MediaUnavailableException e)
            {
                errors.Add(e.Message);
            }
        }
        throw new MediaUnavailableException($"media: no client available for \"{verb}\": " + string.Join("; ", errors));
    }

    /// <summary>A client's accepted args in canonical order (non-canonical names are dropped):
    /// its <see cref="IMediaClient.Args"/>, or every canonical arg with canonical required.</summary>
    internal static List<MediaArg> AcceptedArgs(IMediaClient client, string verb)
    {
        var spec = MediaVerbs.Find(verb);
        var declared = client.Args(verb);
        if (declared == null)
            return spec?.Args.Select(a => new MediaArg(a.Name, a.Required)).ToList() ?? new List<MediaArg>();
        var ordered = new List<MediaArg>();
        if (spec != null)
            foreach (var a in spec.Args)
                if (declared.FirstOrDefault(d => d.Name == a.Name) is { } hit) ordered.Add(hit);
        return ordered; // §7G.12 D7: only canonical args are ever accepted
    }

    private static Task<MediaResult> Dispatch(IMediaClient c, string verb, IDictionary<string, object?> args, MediaContext ctx) => verb switch
    {
        "image" => c.ImageAsync(args, ctx),
        "video" => c.VideoAsync(args, ctx),
        "speech" => c.SpeechAsync(args, ctx),
        "music" => c.MusicAsync(args, ctx),
        "transcribe" => c.TranscribeAsync(args, ctx),
        "cloneVoice" => c.CloneVoiceAsync(args, ctx),
        "talk" => c.TalkAsync(args, ctx),
        _ => throw new MediaException($"media: no client supports \"{verb}\""),
    };

    // ---------------------------------------------------------------- tool face (§7G.6)

    /// <summary>One <see cref="ITool"/> (source <c>"media"</c>) per verb any client supports, in
    /// canonical order; the schema comes from the first supporting client.</summary>
    public IReadOnlyList<ITool> Tools()
    {
        var output = new List<ITool>();
        foreach (var spec in MediaVerbs.All)
        {
            var first = _clients.FirstOrDefault(c => c.Supports(spec.Verb));
            if (first == null) continue;
            var accepted = AcceptedArgs(first, spec.Verb);
            var props = new Dictionary<string, object?>();
            var required = new List<object?>();
            foreach (var a in spec.Args)
            {
                var hit = accepted.FirstOrDefault(x => x.Name == a.Name);
                if (hit == null) continue;
                var p = new Dictionary<string, object?> { ["type"] = a.Type };
                if (a.Items != null) p["items"] = new Dictionary<string, object?> { ["type"] = a.Items };
                p["description"] = a.Description;
                props[a.Name] = p;
                if (hit.Required) required.Add(a.Name);
            }
            var schema = new Dictionary<string, object?> { ["type"] = "object", ["properties"] = props, ["required"] = required };
            output.Add(new MediaTool(this, spec, schema));
        }
        return output;
    }

    private sealed class MediaTool : ITool
    {
        private readonly Media _media;
        private readonly MediaVerbs.VerbSpec _spec;

        public MediaTool(Media media, MediaVerbs.VerbSpec spec, IDictionary<string, object?> schema)
        {
            (_media, _spec, InputSchema) = (media, spec, schema);
        }

        public string Name => _spec.Tool;
        public string Description => _spec.Description;
        public IDictionary<string, object?> InputSchema { get; }
        public string Source => "media";

        /// <summary>Each call is an independent job: always concurrency-safe (§7G.6, §8 Tool scheduling).</summary>
        public bool IsConcurrencySafe(IDictionary<string, object?> args) => true;

        public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            try
            {
                var (r, client) = await _media.RouteAsync(_spec.Verb, args,
                    new MediaContext { CancellationToken = ctx?.CancellationToken ?? default }).ConfigureAwait(false);
                var meta = new Dictionary<string, object?> { ["source"] = "media", ["verb"] = _spec.Verb, ["client"] = client };
                if (r.JobId != null) meta["jobId"] = r.JobId;
                meta["status"] = r.Status;
                if (r.Usage?.ToMap() is { } usage) meta["usage"] = usage; // §7G.12 D21
                if (MediaVerbs.KindOf(_spec.Verb) == "text") return ToolResult.Ok(r.Text ?? "", meta);
                var firstType = r.Files.Count > 0 ? r.Files[0].Type : MediaUtil.TypeOfKind(MediaVerbs.KindOf(_spec.Verb));
                var word = firstType switch { "file" => "video", "audio" => "audio", _ => "image" };
                var lines = r.Files.Select(f => f.Path ?? f.Url
                    ?? $"inline {f.MimeType} {(f.Data == null ? 0 : Convert.FromBase64String(f.Data).Length)} bytes");
                var parts = r.Files.Select(f => f.ToPart()).ToList();
                return ToolResult.OkWithParts(word + " ready: " + string.Join("\n", lines), parts, meta);
            }
            catch (Exception e)
            {
                return ToolResult.Error(e.Message);
            }
        }
    }

    /// <summary>§7G.6 model visibility: the parts of a tool result that reach the model. For a
    /// <c>source:"media"</c> result only image parts (§7G.12 D8, text parts included); every other result unchanged.</summary>
    internal static IReadOnlyList<ContentPart>? ModelParts(ToolResult result)
    {
        if (result.Parts == null || result.Metadata?.Get("source") as string != "media") return result.Parts;
        return result.Parts.Where(p => p.Type == "image").ToList();
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
/// The canonical verb table (SPEC §7G.1) — <c>conformance/media/verbs.json</c> embedded verbatim as
/// a resource: order, tool names, descriptions, args, types and required flags.
/// </summary>
public static class MediaVerbs
{
    public sealed record ArgSpec(string Name, string Type, string Description, bool Required, string? Items);
    public sealed record VerbSpec(string Verb, string Tool, string Description, IReadOnlyList<ArgSpec> Args);

    /// <summary>The verbatim text of <c>conformance/media/verbs.json</c>.</summary>
    public static readonly string VerbsJson = MediaUtil.Resource("Toolnexus.media.verbs.json");

    /// <summary>The parsed table, in canonical order.</summary>
    public static readonly IReadOnlyList<VerbSpec> All = Parse();

    internal static VerbSpec? Find(string verb) => All.FirstOrDefault(v => v.Verb == verb);

    /// <summary>The result kind of a verb: image | video | audio | text.</summary>
    internal static string KindOf(string verb) => verb switch
    {
        "image" => "image",
        "video" or "talk" => "video",
        "speech" or "music" => "audio",
        _ => "text",
    };

    private static IReadOnlyList<VerbSpec> Parse()
    {
        var list = (List<object?>)Json.ToMap(VerbsJson)["verbs"]!;
        return list.Cast<Dictionary<string, object?>>().Select(v => new VerbSpec(
            (string)v["verb"]!, (string)v["tool"]!, (string)v["description"]!,
            ((List<object?>)v["args"]!).Cast<Dictionary<string, object?>>().Select(a => new ArgSpec(
                (string)a["name"]!, (string)a["type"]!, (string)a["description"]!,
                a.Get("required") is true, a.Get("items") as string)).ToList())).ToList();
    }
}
