namespace Toolnexus;

/// <summary>
/// §7G.7 provider style — JSON data, never code. Load one with <see cref="Load"/> (SPEC
/// <c>loadMediaStyle(pathOrPreset, config?)</c>), <see cref="Parse"/> (JSON text) or
/// <see cref="FromDictionary"/>. A loaded style is itself an <see cref="IMediaClient"/>, so
/// <c>Media.Create(style)</c> works; <see cref="Load"/> also accepts a built-in CLI preset name
/// (<see cref="XaiGrokCli"/>, <see cref="OpenAICodexCli"/>, <see cref="GoogleAgyCli"/> and their mock twins).
/// </summary>
public sealed class MediaStyle : IMediaClient
{
    /// <summary>Preset: the <c>grok</c> CLI (image, video) on the user's own subscription.</summary>
    public const string XaiGrokCli = "xai-grok-cli";
    /// <summary>Preset: the <c>codex</c> CLI (image).</summary>
    public const string OpenAICodexCli = "openai-codex-cli";
    /// <summary>Preset: Google's Antigravity CLI <c>agy</c> (image).</summary>
    public const string GoogleAgyCli = "google-agy-cli";
    /// <summary>Mock twin of <see cref="XaiGrokCli"/>: same name, verbs, args, folding and errors; never starts a process.</summary>
    public const string XaiGrokCliMock = "xai-grok-cli-mock";
    /// <summary>Mock twin of <see cref="OpenAICodexCli"/>.</summary>
    public const string OpenAICodexCliMock = "openai-codex-cli-mock";
    /// <summary>Mock twin of <see cref="GoogleAgyCli"/>.</summary>
    public const string GoogleAgyCliMock = "google-agy-cli-mock";

    private static readonly Dictionary<string, string> CliPresets = new()
    {
        [XaiGrokCli] = "grok", [OpenAICodexCli] = "codex", [GoogleAgyCli] = "agy",
        [XaiGrokCliMock] = "grok", [OpenAICodexCliMock] = "codex", [GoogleAgyCliMock] = "agy",
    };

    /// <summary>The built-in preset this style is, or null for a JSON style.</summary>
    public string? Preset { get; }

    private readonly MediaClientConfig? _config;
    internal MediaClientConfig? Config => _config;

    /// <summary>Set to <c>0</c>, every mock preset reports unavailable (§7G.10b).</summary>
    public const string MockAvailableEnv = "TOOLNEXUS_MEDIA_MOCK_AVAILABLE";

    /// <summary>§7G.10b — whether this style can run, in well under a second, generating nothing and
    /// reading no secret. CLI preset: the command is found and the CLI's own login file exists (never
    /// read). HTTP style: config <c>Key</c> set, or every <c>${VAR}</c> in its headers set.</summary>
    public MediaAvailability Available()
    {
        if (Preset == null)
        {
            if (!string.IsNullOrEmpty(_config?.Key)) return new(true, true, Name + ": key from config");
            var vars = new SortedSet<string>(StringComparer.Ordinal);
            if (Raw.Get("headers") is IDictionary<string, object?> hs)
                foreach (var v in hs.Values)
                    foreach (System.Text.RegularExpressions.Match m in System.Text.RegularExpressions.Regex.Matches(v?.ToString() ?? "", @"\$\{([A-Za-z_][A-Za-z0-9_]*)\}"))
                        vars.Add(m.Groups[1].Value);
            foreach (var name in vars)
                if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable(name))) return new(true, false, $"{Name}: {name} is not set");
            return new(true, true, Name + ": env present");
        }
        var label = Name;
        if (Preset.EndsWith("-mock", StringComparison.Ordinal))
            return Environment.GetEnvironmentVariable(MockAvailableEnv) == "0"
                ? new(false, false, $"{label}: mock set unavailable by {MockAvailableEnv}=0")
                : new(true, true, label + ": mock");
        var bin = _config?.Command is { Count: > 0 } cmd ? cmd[0] : label;
        if (!OnPath(bin)) return new(false, false, $"{label}: \"{bin}\" not found on PATH");
        var auth = AuthFile(label);
        return File.Exists(auth)
            ? new(true, true, $"{label}: installed, logged in ({auth} present)")
            : new(true, false, $"{label}: installed, not logged in ({auth} missing)");
    }

    private static string Home => Environment.GetEnvironmentVariable("HOME") is { Length: > 0 } h
        ? h : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

    private static string AuthFile(string label) => label switch
    {
        "grok" => Path.Combine(Home, ".grok", "auth.json"),
        "codex" => Path.Combine(Environment.GetEnvironmentVariable("CODEX_HOME") is { Length: > 0 } ch ? ch : Path.Combine(Home, ".codex"), "auth.json"),
        _ => Path.Combine(Home, ".gemini", "antigravity-cli", "antigravity-oauth-token"),
    };

    private static bool OnPath(string bin)
    {
        if (bin.Contains('/') || bin.Contains(Path.DirectorySeparatorChar)) return IsExecutable(bin);
        foreach (var d in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            if (d.Length == 0) continue;
            if (IsExecutable(Path.Combine(d, bin))) return true;
            if (OperatingSystem.IsWindows())
                foreach (var ext in (Environment.GetEnvironmentVariable("PATHEXT") ?? ".EXE;.CMD;.BAT").Split(';'))
                    if (ext.Length > 0 && File.Exists(Path.Combine(d, bin + ext))) return true;
        }
        return false;
    }

    private static bool IsExecutable(string p)
    {
        if (!File.Exists(p)) return false;
        if (OperatingSystem.IsWindows()) return true;
        const UnixFileMode x = UnixFileMode.UserExecute | UnixFileMode.GroupExecute | UnixFileMode.OtherExecute;
        return (File.GetUnixFileMode(p) & x) != 0;
    }
    private IMediaClient? _client;

    private static readonly HashSet<string> Verbs = new() { "image", "video", "speech", "music", "transcribe", "cloneVoice", "talk" };

    internal Dictionary<string, object?> Raw { get; }

    private MediaStyle(string preset, MediaClientConfig? config)
    {
        Raw = new Dictionary<string, object?>();
        Preset = preset;
        _config = config;
    }

    private MediaStyle(Dictionary<string, object?> raw, MediaClientConfig? config = null)
    {
        Raw = raw;
        _config = config;
        if (raw.Get("verbs") is IDictionary<string, object?> vs)
            foreach (var (name, vv) in vs)
            {
                if (!Verbs.Contains(name)) throw new MediaException($"style: unknown verb \"{name}\"");
                if (vv is IDictionary<string, object?> verb && verb.Get("args") is IDictionary<string, object?> am)
                    foreach (var (k, path) in am)
                        if (path is string ps && ps.Length == 0)
                            throw new MediaException($"style: empty path for argument \"{k}\"");
            }
    }

    /// <summary>A built-in CLI preset name (<see cref="XaiGrokCli"/> …), or else a style file to read
    /// (SPEC <c>loadMediaStyle(pathOrPreset, config?)</c>). <paramref name="config"/> configures the
    /// client behind the style (model, command, loop, gather, …).</summary>
    public static MediaStyle Load(string pathOrPreset, MediaClientConfig? config = null)
        => CliPresets.ContainsKey(pathOrPreset)
            ? new MediaStyle(pathOrPreset, config)
            : new MediaStyle(Json.ToMap(File.ReadAllText(pathOrPreset)), config);

    /// <summary>Parse a style from JSON text.</summary>
    public static MediaStyle Parse(string json) => new(Json.ToMap(json));

    /// <summary>Wrap an already-parsed style object (deep-copied).</summary>
    public static MediaStyle FromDictionary(IDictionary<string, object?> style) => Parse(Json.Stringify(style));

    /// <summary>The preset's client label (<c>grok</c>, <c>codex</c>, <c>agy</c>), else <c>style.name</c>, else <c>"style"</c>.</summary>
    public string Name => Preset != null ? CliPresets[Preset] : Raw.Get("name") as string is { Length: > 0 } n ? n : "style";

    /// <summary>The client behind this style.</summary>
    private IMediaClient Client => _client ??= Build();

    private IMediaClient Build()
    {
        if (Preset == null) return new StyleMediaClient(this, _config);
        var mock = Preset.EndsWith("-mock", StringComparison.Ordinal);
        return CliPresets[Preset] switch
        {
            "grok" => new GrokMediaClient(_config) { Mock = mock },
            "codex" => new CodexMediaClient(_config) { Mock = mock },
            _ => new AgyMediaClient(_config) { Mock = mock },
        };
    }

    /// <summary>Whether the style declares <paramref name="verb"/> — the one verb-support mechanism, HTTP or CLI.</summary>
    public bool Supports(string verb) => Client.Supports(verb);
    public IReadOnlyList<MediaArg>? Args(string verb) => Client.Args(verb);
    public Task<MediaResult> ImageAsync(IDictionary<string, object?> args, MediaContext ctx) => Client.ImageAsync(args, ctx);
    public Task<MediaResult> VideoAsync(IDictionary<string, object?> args, MediaContext ctx) => Client.VideoAsync(args, ctx);
    public Task<MediaResult> SpeechAsync(IDictionary<string, object?> args, MediaContext ctx) => Client.SpeechAsync(args, ctx);
    public Task<MediaResult> MusicAsync(IDictionary<string, object?> args, MediaContext ctx) => Client.MusicAsync(args, ctx);
    public Task<MediaResult> TranscribeAsync(IDictionary<string, object?> args, MediaContext ctx) => Client.TranscribeAsync(args, ctx);
    public Task<MediaResult> CloneVoiceAsync(IDictionary<string, object?> args, MediaContext ctx) => Client.CloneVoiceAsync(args, ctx);
    public Task<MediaResult> TalkAsync(IDictionary<string, object?> args, MediaContext ctx) => Client.TalkAsync(args, ctx);

    internal string BaseUrl => (Raw.Get("baseUrl") as string ?? "").TrimEnd('/');

    internal IDictionary<string, object?>? Verb(string verb)
        => Raw.Get("verbs") is IDictionary<string, object?> vs ? vs.Get(verb) as IDictionary<string, object?> : null;
}

/// <summary>
/// §7G.7 — a <see cref="IMediaClient"/> over a JSON <see cref="MediaStyle"/> (SPEC
/// <c>styleMediaClient(style, config?)</c>). Uses <c>Url</c>, <c>Loop</c>, <c>Gather</c> (default mode
/// <c>url</c>) and <c>HttpClient</c> from the config; <c>Key</c>, when set, is what every <c>${…}</c>
/// in the style's headers expands to.
/// </summary>
public class StyleMediaClient : IMediaClient
{
    private readonly MediaStyle _style;
    private protected readonly MediaClientConfig Config;
    private readonly string _defaultGather;

    public StyleMediaClient(MediaStyle style, MediaClientConfig? config = null) : this(style, config, "url") { }

    private protected StyleMediaClient(MediaStyle style, MediaClientConfig? config, string defaultGather)
    {
        _style = style ?? throw new ArgumentNullException(nameof(style));
        Config = config ?? new MediaClientConfig();
        _defaultGather = defaultGather;
    }

    public virtual string Name => _style.Name;

    public bool Supports(string verb) => _style.Verb(verb) != null;

    /// <summary>The mapped canonical args; required = (canonical required ∩ mapped) ∪ the verb's <c>required</c>.</summary>
    public IReadOnlyList<MediaArg>? Args(string verb)
    {
        var v = _style.Verb(verb);
        if (v == null) return null;
        var mapped = v.Get("args") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
        var extra = (v.Get("required") as IEnumerable<object?> ?? Array.Empty<object?>()).Select(x => x?.ToString()).ToHashSet();
        var spec = MediaVerbs.Find(verb);
        var list = new List<MediaArg>();
        foreach (var key in mapped.Keys)
        {
            if (spec != null && spec.Args.All(a => a.Name != key)) continue; // §7G.12 D6: canonical names only
            var canonicalRequired = spec?.Args.FirstOrDefault(a => a.Name == key)?.Required ?? false;
            list.Add(new MediaArg(key, canonicalRequired || extra.Contains(key)));
        }
        return list;
    }

    public Task<MediaResult> ImageAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("image", args, ctx);
    public Task<MediaResult> VideoAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("video", args, ctx);
    public Task<MediaResult> SpeechAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("speech", args, ctx);
    public Task<MediaResult> MusicAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("music", args, ctx);
    public Task<MediaResult> TranscribeAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("transcribe", args, ctx);
    public Task<MediaResult> CloneVoiceAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("cloneVoice", args, ctx);
    public Task<MediaResult> TalkAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("talk", args, ctx);

    /// <summary>The key for every <c>${…}</c>, or null to expand from the environment. A built-in
    /// client may throw <see cref="MediaUnavailableException"/> here when it has none.</summary>
    private protected virtual string? ResolveKey() => Config.Key;

    /// <summary>Hook for built-ins to adjust the start body (after args, before providerOptions).</summary>
    private protected virtual void AdjustBody(string verb, Dictionary<string, object?> body) { }

    private async Task<MediaResult> RunAsync(string verb, IDictionary<string, object?> args, MediaContext ctx)
    {
        var v = _style.Verb(verb) ?? throw new MediaException($"media: no client supports \"{verb}\"");
        var key = ResolveKey();

        var argMap = v.Get("args") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
        var accepted = Args(verb) ?? Array.Empty<MediaArg>();
        var bad = args.Where(kv => kv.Value != null && accepted.All(a => a.Name != kv.Key)).Select(kv => kv.Key)
            .OrderBy(k => k, CodePointComparer.Instance).FirstOrDefault();
        if (bad != null) throw new MediaException($"{verb}: argument \"{bad}\" not supported by this style");

        // body
        var body = v.Get("defaults") is IDictionary<string, object?> defs
            ? (Dictionary<string, object?>)MediaUtil.Clone(defs)!
            : new Dictionary<string, object?>();
        foreach (var (k, value) in args)
        {
            if (value == null) continue;
            MediaUtil.WritePath(body, argMap.Get(k)?.ToString() ?? k, MediaUtil.Clone(value));
        }
        AdjustBody(verb, body);
        if (ctx.ProviderOptions != null)
            foreach (var (k, pv) in ctx.ProviderOptions) body[k] = MediaUtil.Clone(pv);

        // headers
        Dictionary<string, string>? headers = null;
        if (_style.Raw.Get("headers") is IDictionary<string, object?> hs)
        {
            var hraw = hs.ToDictionary(kv => kv.Key, kv => kv.Value?.ToString() ?? "");
            headers = key != null ? McpSource.ExpandEnvHeaders(hraw, _ => key)! : McpSource.ExpandEnvHeaders(hraw)!;
        }

        var baseUrl = Config.Url is { Length: > 0 } u ? u.TrimEnd('/') : _style.BaseUrl;
        var origin = new Uri(baseUrl + "/");
        var pollCfg = v.Get("poll") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
        var timeoutMs = MediaUtil.Positive(Config.Loop?.TimeoutMs, MediaUtil.Positive(MediaUtil.ToLong(pollCfg.Get("timeoutMs")), 600000));
        var interval = MediaUtil.Positive(Config.Loop?.IntervalMs, MediaUtil.Positive(MediaUtil.ToLong(pollCfg.Get("intervalMs")), 5000));
        var job = new MediaJob(verb, Name, timeoutMs, ctx, Config.Loop?.OnProgress);
        var http = Config.HttpClient ?? MediaHttp.Default;

        async Task<object?> Send(string method, string path, Dictionary<string, object?>? b)
        {
            var r = await MediaHttp.SendAsync(http, job, method, baseUrl + path, b == null ? null : MediaHttp.JsonBody(b), headers, origin).ConfigureAwait(false);
            MediaHttp.Check(r, job);
            var parsed = MediaHttp.ParseJson(r, job);
            if (_style.Raw.Get("errorEnvelope") is IDictionary<string, object?> env)
            {
                var c = MediaUtil.ReadPath(parsed, env.Get("codePath") as string ?? "");
                var oks = env.Get("okValues") as IEnumerable<object?> ?? Array.Empty<object?>();
                if (c != null && !oks.Any(ok => MediaUtil.JsonEqual(ok, c)))
                {
                    var msg = env.Get("messagePath") is string mp ? MediaUtil.ReadPath(parsed, mp) as string ?? "" : "";
                    throw job.Fail($"API error {MediaUtil.ScalarText(c, true) ?? Json.Stringify(c)}: {msg}");
                }
            }
            return parsed;
        }

        var start = v.Get("start") as IDictionary<string, object?> ?? new Dictionary<string, object?>();
        var final = await Send(start.Get("method") as string ?? "POST", start.Get("path") as string ?? "", body).ConfigureAwait(false);

        string status = "completed";
        if ((v.Get("mode") as string) == "async")
        {
            var idPath = v.Get("idPath") as string ?? "";
            job.JobId = MediaUtil.ScalarText(MediaUtil.ReadPath(final, idPath), integralOnly: true) is { Length: > 0 } jid ? jid
                : throw job.Fail($"{verb}: no job id at {idPath}");
            var method = pollCfg.Get("method") as string ?? "GET";
            var path = (pollCfg.Get("path") as string ?? "").Replace("{id}", MediaUtil.PercentEncode(job.JobId));
            var statusPath = pollCfg.Get("statusPath") as string ?? "";
            var done = StringSet(pollCfg.Get("done"));
            var failed = StringSet(pollCfg.Get("failed"));
            var errorPath = pollCfg.Get("errorPath") as string;
            var clock = System.Diagnostics.Stopwatch.StartNew();
            while (true)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(interval), job.Ct).ConfigureAwait(false);
                var resp = await Send(method, path, null).ConfigureAwait(false);
                var st = MediaUtil.ScalarText(MediaUtil.ReadPath(resp, statusPath), false) ?? "";
                job.Report(st);
                if (done.Contains(st)) { final = resp; status = st; break; }
                if (failed.Contains(st))
                {
                    var msg = errorPath != null ? MediaUtil.ReadPath(resp, errorPath) as string : null;
                    throw job.Fail(string.IsNullOrEmpty(msg) ? $"job {job.JobId} {st}" : $"job {job.JobId} {st}: {msg}");
                }
                if (clock.ElapsedMilliseconds >= timeoutMs) throw job.TimedOut();
            }
        }

        var kind = v.Get("kind") as string ?? "image";
        var resultPath = v.Get("resultPath") as string ?? "";
        var raw = MediaUtil.ReadPath(final, resultPath);
        if (kind == "text")
        {
            var text = raw is string str ? str : raw is long or double ? MediaUtil.ScalarText(raw, false) : null;
            if (text == null) throw job.Fail($"{verb}: no result at {resultPath}");
            return new MediaResult(Array.Empty<MediaFile>(), status, text, job.JobId);
        }
        var urls = new List<string>();
        Flatten(raw, urls);
        if (urls.Count == 0) throw job.Fail($"{verb}: no result at {resultPath}");
        var gather = new MediaGatherer(Config.Gather, _defaultGather, http, job, kind);
        var files = new List<MediaFile>();
        foreach (var u2 in urls) files.Add(await gather.FromUrlAsync(u2).ConfigureAwait(false));
        return new MediaResult(files, status, null, job.JobId);
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
        => v is IEnumerable<object?> e ? e.Select(x => MediaUtil.ScalarText(x, false) ?? x?.ToString() ?? "").ToHashSet(StringComparer.Ordinal) : new HashSet<string>();
}

/// <summary>
/// §7G.9 a2e.ai — a style client over the embedded preset <c>examples/media-styles/a2e.json</c>
/// (verbatim). Key: config <c>Key</c> or <c>A2E_API_KEY</c> at call time; <c>Url</c> replaces the
/// base URL; <c>Model</c> is written to the image body's <c>model_type</c>; gather defaults to
/// <c>download</c>. Verbs: image, video, speech (live-verified), talk (unverified).
/// </summary>
public sealed class A2EMediaClient : StyleMediaClient
{
    /// <summary>The verbatim text of <c>examples/media-styles/a2e.json</c>.</summary>
    public static readonly string PresetJson = MediaUtil.Resource("Toolnexus.media-styles.a2e.json");

    public A2EMediaClient(MediaClientConfig? config = null) : base(MediaStyle.Parse(PresetJson), config, "download") { }

    public override string Name => "a2e";

    private protected override string? ResolveKey()
    {
        var key = Config.Key is { Length: > 0 } k ? k : Environment.GetEnvironmentVariable("A2E_API_KEY");
        if (string.IsNullOrEmpty(key)) throw new MediaUnavailableException("a2e: no API key (set A2E_API_KEY)");
        return key;
    }

    private protected override void AdjustBody(string verb, Dictionary<string, object?> body)
    {
        var model = Config.Models?.TryGetValue(verb, out var m) == true ? m : verb == "image" ? Config.Model : null;
        if (verb == "image" && !string.IsNullOrEmpty(model)) body["model_type"] = model;
    }
}
