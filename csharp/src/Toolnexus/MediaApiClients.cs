using System.Net.Http.Headers;
using System.Text;

namespace Toolnexus;

/// <summary>
/// §7G.9 — the shared base of the built-in HTTP media clients (ElevenLabs, OpenAI, OpenRouter, fal):
/// key from config or env at call time (missing ⇒ <see cref="MediaUnavailableException"/>, no
/// request), the §7G.8 HTTP rules, polling and gather. Not subclassable outside the library —
/// implement <see cref="IMediaClient"/> for anything not built in.
/// </summary>
public abstract class ApiMediaClient : IMediaClient
{
    private protected readonly MediaClientConfig Config;

    private protected ApiMediaClient(MediaClientConfig? config) => Config = config ?? new MediaClientConfig();

    public abstract string Name { get; }
    private protected abstract string KeyEnv { get; }
    private protected abstract string DefaultUrl { get; }
    private protected abstract string PrimaryVerb { get; }
    private protected virtual long DefaultIntervalMs => 5000;
    private protected abstract IReadOnlyDictionary<string, MediaArg[]> VerbArgs { get; }
    private protected abstract KeyValuePair<string, string> AuthHeader(string key);
    private protected abstract Task<MediaResult> RunAsync(ApiCall call);

    public bool Supports(string verb) => VerbArgs.ContainsKey(verb);

    public IReadOnlyList<MediaArg>? Args(string verb) => VerbArgs.TryGetValue(verb, out var a) ? a : null;

    public Task<MediaResult> ImageAsync(IDictionary<string, object?> args, MediaContext ctx) => GoAsync("image", args, ctx);
    public Task<MediaResult> VideoAsync(IDictionary<string, object?> args, MediaContext ctx) => GoAsync("video", args, ctx);
    public Task<MediaResult> SpeechAsync(IDictionary<string, object?> args, MediaContext ctx) => GoAsync("speech", args, ctx);
    public Task<MediaResult> MusicAsync(IDictionary<string, object?> args, MediaContext ctx) => GoAsync("music", args, ctx);
    public Task<MediaResult> TranscribeAsync(IDictionary<string, object?> args, MediaContext ctx) => GoAsync("transcribe", args, ctx);
    public Task<MediaResult> CloneVoiceAsync(IDictionary<string, object?> args, MediaContext ctx) => GoAsync("cloneVoice", args, ctx);
    public Task<MediaResult> TalkAsync(IDictionary<string, object?> args, MediaContext ctx) => GoAsync("talk", args, ctx);

    private Task<MediaResult> GoAsync(string verb, IDictionary<string, object?> args, MediaContext ctx)
    {
        if (!Supports(verb)) throw new MediaException($"{verb}: not supported by {Name}");
        var key = Config.Key is { Length: > 0 } k ? k : Environment.GetEnvironmentVariable(KeyEnv);
        if (string.IsNullOrEmpty(key)) throw new MediaUnavailableException($"{Name}: no API key (set {KeyEnv})");
        var baseUrl = (Config.Url is { Length: > 0 } u ? u : DefaultUrl).TrimEnd('/');
        var job = new MediaJob(verb, Name, MediaUtil.Positive(Config.Loop?.TimeoutMs, 600000), ctx, Config.Loop?.OnProgress);
        var http = Config.HttpClient ?? MediaHttp.Default;
        var auth = AuthHeader(key);
        var call = new ApiCall(verb, args, ctx, job, http, baseUrl, new Dictionary<string, string> { [auth.Key] = auth.Value },
            new MediaGatherer(Config.Gather, "download", http, job, MediaVerbs.KindOf(verb)),
            MediaUtil.Positive(Config.Loop?.IntervalMs, DefaultIntervalMs));
        return RunAsync(call);
    }

    /// <summary><c>Models[verb]</c>, else <c>Model</c> for the primary verb, else <paramref name="dflt"/>.</summary>
    private protected string ModelFor(string verb, string dflt)
        => Config.Models != null && Config.Models.TryGetValue(verb, out var m) && !string.IsNullOrEmpty(m) ? m
            : verb == PrimaryVerb && !string.IsNullOrEmpty(Config.Model) ? Config.Model! : dflt;

    private protected static MediaArg[] A(params string[] names)
        => names.Select(n => new MediaArg(n.TrimEnd('*'), n.EndsWith('*'))).ToArray();

    /// <summary>One verb call of an API client.</summary>
    private protected sealed class ApiCall
    {
        public string Verb { get; }
        public IDictionary<string, object?> Args { get; }
        public MediaContext Ctx { get; }
        public MediaJob Job { get; }
        public HttpClient Http { get; }
        public string BaseUrl { get; }
        public Dictionary<string, string> Headers { get; }
        public MediaGatherer Gather { get; }
        public long IntervalMs { get; }
        private readonly Uri _origin;

        public ApiCall(string verb, IDictionary<string, object?> args, MediaContext ctx, MediaJob job, HttpClient http,
            string baseUrl, Dictionary<string, string> headers, MediaGatherer gather, long intervalMs)
        {
            (Verb, Args, Ctx, Job, Http, BaseUrl, Headers, Gather, IntervalMs) = (verb, args, ctx, job, http, baseUrl, headers, gather, intervalMs);
            _origin = new Uri(baseUrl + "/");
        }

        public string? Str(string k) => Args.Get(k) switch { null => null, string s => s, var o => MediaUtil.ScalarText(o, false) ?? o.ToString() };
        public long? Long(string k) => MediaUtil.ToLong(Args.Get(k));
        public bool Bool(string k) => Args.Get(k) is true;

        /// <summary>Path relative to the base URL, or an absolute URL as handed back by the provider.</summary>
        private string Url(string pathOrUrl) => MediaUtil.IsHttp(pathOrUrl) ? pathOrUrl : BaseUrl + pathOrUrl;

        public async Task<MediaHttpResponse> SendAsync(string method, string pathOrUrl, Func<HttpContent>? body)
        {
            var r = await MediaHttp.SendAsync(Http, Job, method, Url(pathOrUrl), body, Headers, _origin).ConfigureAwait(false);
            MediaHttp.Check(r, Job);
            return r;
        }

        public async Task<object?> JsonAsync(string method, string pathOrUrl, Dictionary<string, object?>? body = null)
        {
            if (body != null && Ctx.ProviderOptions != null)
                foreach (var (k, v) in Ctx.ProviderOptions) body[k] = MediaUtil.Clone(v);
            var r = await SendAsync(method, pathOrUrl, body == null ? null : MediaHttp.JsonBody(body)).ConfigureAwait(false);
            return MediaHttp.ParseJson(r, Job);
        }

        public async Task<MediaHttpResponse> BytesAsync(string method, string pathOrUrl, Dictionary<string, object?>? body = null)
        {
            if (body != null && Ctx.ProviderOptions != null)
                foreach (var (k, v) in Ctx.ProviderOptions) body[k] = MediaUtil.Clone(v);
            return await SendAsync(method, pathOrUrl, body == null ? null : MediaHttp.JsonBody(body)).ConfigureAwait(false);
        }

        /// <summary>POST multipart: <paramref name="fields"/> in order (<c>Value</c> text or a file).</summary>
        public async Task<object?> MultipartAsync(string path, List<MultipartField> fields)
        {
            if (Ctx.ProviderOptions != null)
                foreach (var (k, v) in Ctx.ProviderOptions)
                {
                    var text = v as string ?? Json.Stringify(v);
                    var i = fields.FindIndex(f => f.Name == k && f.FileName == null);
                    if (i >= 0) fields[i] = new MultipartField(k, text);
                    else fields.Add(new MultipartField(k, text));
                }
            Func<HttpContent> body = () =>
            {
                var mp = new MultipartFormDataContent();
                foreach (var f in fields)
                {
                    if (f.FileName == null)
                    {
                        var c = new ByteArrayContent(Encoding.UTF8.GetBytes(f.Value ?? ""));
                        mp.Add(c, f.Name);
                    }
                    else
                    {
                        var c = new ByteArrayContent(f.Bytes!);
                        c.Headers.ContentType = new MediaTypeHeaderValue(f.ContentType!);
                        mp.Add(c, f.Name, f.FileName);
                    }
                }
                return mp;
            };
            var r = await SendAsync("POST", path, body).ConfigureAwait(false);
            return MediaHttp.ParseJson(r, Job);
        }

        public async Task<MultipartField> FileFieldAsync(string name, string value)
        {
            var (bytes, file, mime) = await Gather.ReadInputAsync(value).ConfigureAwait(false);
            return new MultipartField(name, null, file, mime, bytes);
        }

        public Task WaitAsync() => Task.Delay(TimeSpan.FromMilliseconds(IntervalMs), Job.Ct);

        /// <summary>The text at <paramref name="key"/> of a JSON object, else <c>&lt;verb&gt;: no result at &lt;key&gt;</c>.</summary>
        public string TextAt(object? resp, string key)
            => MediaUtil.ReadPath(resp, key) is string s ? s : throw Job.Fail($"{Verb}: no result at {key}");

        public MediaResult Done(List<MediaFile> files, string? text = null, MediaUsage? usage = null)
            => new(files, "completed", text, Job.JobId) { Usage = usage?.ToMap() == null ? null : usage };
    }

    private protected sealed record MultipartField(string Name, string? Value, string? FileName = null, string? ContentType = null, byte[]? Bytes = null);
}

/// <summary>§7G.9 ElevenLabs — speech (live-verified, incl. a cloned voice by name), music,
/// transcribe, cloneVoice (unverified). Key <c>ELEVENLABS_API_KEY</c>, header <c>xi-api-key</c>.</summary>
public sealed class ElevenLabsMediaClient : ApiMediaClient
{
    private static readonly IReadOnlyDictionary<string, MediaArg[]> Table = new Dictionary<string, MediaArg[]>
    {
        ["speech"] = A("text*", "voice"),
        ["music"] = A("prompt*", "seconds", "instrumental"),
        ["transcribe"] = A("audio*", "language"),
        ["cloneVoice"] = A("name*", "samples*"),
    };

    public ElevenLabsMediaClient(MediaClientConfig? config = null) : base(config) { }

    public override string Name => "elevenlabs";
    private protected override string KeyEnv => "ELEVENLABS_API_KEY";
    private protected override string DefaultUrl => "https://api.elevenlabs.io";
    private protected override string PrimaryVerb => "speech";
    private protected override IReadOnlyDictionary<string, MediaArg[]> VerbArgs => Table;
    private protected override KeyValuePair<string, string> AuthHeader(string key) => new("xi-api-key", key);

    private protected override async Task<MediaResult> RunAsync(ApiCall c)
    {
        switch (c.Verb)
        {
            case "speech":
            {
                var voice = c.Str("voice");
                var id = "JBFqnCBsd6RMkjVDRZzb";
                if (voice != null)
                {
                    var list = await c.JsonAsync("GET", $"/v2/voices?page_size=100&search={MediaUtil.PercentEncode(voice)}").ConfigureAwait(false);
                    var hit = (MediaUtil.ReadPath(list, "voices") as List<object?> ?? new List<object?>())
                        .OfType<IDictionary<string, object?>>()
                        .FirstOrDefault(v => string.Equals(v.Get("name") as string, voice, StringComparison.OrdinalIgnoreCase)
                                             || (v.Get("voice_id") as string) == voice);
                    if (hit?.Get("voice_id") is string vid) id = vid;
                    else if (voice.Length == 20 && !voice.Contains(' ')) id = voice;
                    else throw c.Job.Fail($"speech: voice \"{voice}\" not found");
                }
                var r = await c.BytesAsync("POST", $"/v1/text-to-speech/{MediaUtil.PercentEncode(id)}?output_format=mp3_22050_32",
                    new Dictionary<string, object?> { ["text"] = c.Str("text"), ["model_id"] = ModelFor("speech", "eleven_flash_v2_5") }).ConfigureAwait(false);
                if (r.Headers.TryGetValue("request-id", out var rid) && rid.Length > 0) c.Job.JobId = rid;
                return c.Done(new List<MediaFile> { c.Gather.FromBytes(r.Body, "audio/mpeg") });
            }
            case "music":
            {
                var body = new Dictionary<string, object?>
                {
                    ["prompt"] = c.Str("prompt"),
                    ["music_length_ms"] = (c.Long("seconds") ?? 10) * 1000,
                };
                if (c.Bool("instrumental")) body["force_instrumental"] = true;
                var r = await c.BytesAsync("POST", "/v1/music?output_format=mp3_44100_128", body).ConfigureAwait(false);
                return c.Done(new List<MediaFile> { c.Gather.FromBytes(r.Body, "audio/mpeg") });
            }
            case "transcribe":
            {
                var fields = new List<MultipartField> { new("model_id", ModelFor("transcribe", "scribe_v1")) };
                if (c.Str("language") is { } lang) fields.Add(new MultipartField("language_code", lang));
                fields.Add(await c.FileFieldAsync("file", c.Str("audio")!).ConfigureAwait(false));
                var resp = await c.MultipartAsync("/v1/speech-to-text", fields).ConfigureAwait(false);
                return c.Done(new List<MediaFile>(), c.TextAt(resp, "text"));
            }
            default: // cloneVoice
            {
                var fields = new List<MultipartField> { new("name", c.Str("name")) };
                foreach (var s in c.Args.Get("samples") as IEnumerable<object?> ?? Array.Empty<object?>())
                    fields.Add(await c.FileFieldAsync("files", s?.ToString() ?? "").ConfigureAwait(false));
                var resp = await c.MultipartAsync("/v1/voices/add", fields).ConfigureAwait(false);
                return c.Done(new List<MediaFile>(), c.TextAt(resp, "voice_id"));
            }
        }
    }
}

/// <summary>§7G.9 OpenAI (or any compatible server via <c>Url</c>) — image (live-verified), speech,
/// transcribe, video (unverified). Key <c>OPENAI_API_KEY</c>, <c>Authorization: Bearer</c>.</summary>
public sealed class OpenAIMediaClient : ApiMediaClient
{
    private static readonly IReadOnlyDictionary<string, MediaArg[]> Table = new Dictionary<string, MediaArg[]>
    {
        ["image"] = A("prompt*", "width", "height"),
        ["video"] = A("prompt*", "seconds"),
        ["speech"] = A("text*", "voice"),
        ["transcribe"] = A("audio*", "language"),
    };

    public OpenAIMediaClient(MediaClientConfig? config = null) : base(config) { }

    public override string Name => "openai";
    private protected override string KeyEnv => "OPENAI_API_KEY";
    private protected override string DefaultUrl => "https://api.openai.com/v1";
    private protected override string PrimaryVerb => "image";
    private protected override IReadOnlyDictionary<string, MediaArg[]> VerbArgs => Table;
    private protected override KeyValuePair<string, string> AuthHeader(string key) => new("Authorization", "Bearer " + key);

    private protected override async Task<MediaResult> RunAsync(ApiCall c)
    {
        switch (c.Verb)
        {
            case "image":
            {
                var model = ModelFor("image", "gpt-image-1-mini");
                var w = c.Long("width");
                var h = c.Long("height");
                var size = w == null && h == null ? "1024x1024" : $"{w ?? h}x{h ?? w}";
                var body = new Dictionary<string, object?> { ["model"] = model, ["prompt"] = c.Str("prompt"), ["n"] = 1L, ["size"] = size };
                if (model.StartsWith("gpt-image", StringComparison.Ordinal)) body["quality"] = "low";
                var resp = await c.JsonAsync("POST", "/images/generations", body).ConfigureAwait(false);
                var data = MediaUtil.ReadPath(resp, "data") as List<object?>;
                if (data == null || data.Count == 0) throw c.Job.Fail("image: no result at data");
                var mime = (MediaUtil.ReadPath(resp, "output_format") as string) switch
                {
                    "jpeg" => "image/jpeg",
                    "webp" => "image/webp",
                    _ => "image/png",
                };
                var files = new List<MediaFile>();
                foreach (var e in data.OfType<IDictionary<string, object?>>())
                {
                    if (e.Get("b64_json") is string b64 && b64.Length > 0) files.Add(c.Gather.FromBase64(b64, mime));
                    else if (e.Get("url") is string u && u.Length > 0) files.Add(await c.Gather.FromUrlAsync(u).ConfigureAwait(false));
                }
                if (files.Count == 0) throw c.Job.Fail("image: no result at data");
                return c.Done(files, usage: new MediaUsage // §7G.12 D21
                {
                    InputTokens = MediaUtil.ToLong(MediaUtil.ReadPath(resp, "usage.input_tokens")),
                    OutputTokens = MediaUtil.ToLong(MediaUtil.ReadPath(resp, "usage.output_tokens")),
                });
            }
            case "speech":
            {
                var r = await c.BytesAsync("POST", "/audio/speech", new Dictionary<string, object?>
                {
                    ["model"] = ModelFor("speech", "gpt-4o-mini-tts"),
                    ["input"] = c.Str("text"),
                    ["voice"] = c.Str("voice") ?? "alloy",
                }).ConfigureAwait(false);
                return c.Done(new List<MediaFile> { c.Gather.FromBytes(r.Body, "audio/mpeg") });
            }
            case "transcribe":
            {
                var fields = new List<MultipartField> { new("model", ModelFor("transcribe", "gpt-4o-mini-transcribe")) };
                if (c.Str("language") is { } lang) fields.Add(new MultipartField("language", lang));
                fields.Add(await c.FileFieldAsync("file", c.Str("audio")!).ConfigureAwait(false));
                var resp = await c.MultipartAsync("/audio/transcriptions", fields).ConfigureAwait(false);
                return c.Done(new List<MediaFile>(), c.TextAt(resp, "text"));
            }
            default: // video
            {
                var start = await c.JsonAsync("POST", "/videos", new Dictionary<string, object?>
                {
                    ["model"] = ModelFor("video", "sora-2"),
                    ["prompt"] = c.Str("prompt"),
                    ["seconds"] = (c.Long("seconds") ?? 4).ToString(System.Globalization.CultureInfo.InvariantCulture),
                }).ConfigureAwait(false);
                c.Job.JobId = MediaUtil.ScalarText(MediaUtil.ReadPath(start, "id"), true) is { Length: > 0 } id ? id
                    : throw c.Job.Fail("video: no job id at id");
                var path = "/videos/" + MediaUtil.PercentEncode(c.Job.JobId);
                var clock = System.Diagnostics.Stopwatch.StartNew();
                while (true)
                {
                    await c.WaitAsync().ConfigureAwait(false);
                    var poll = await c.JsonAsync("GET", path).ConfigureAwait(false);
                    var st = MediaUtil.ScalarText(MediaUtil.ReadPath(poll, "status"), false) ?? "";
                    c.Job.Report(st);
                    if (st == "completed") break;
                    if (st == "failed")
                    {
                        var msg = MediaUtil.ReadPath(poll, "error.message") as string;
                        throw c.Job.Fail(string.IsNullOrEmpty(msg) ? $"job {c.Job.JobId} failed" : $"job {c.Job.JobId} failed: {msg}");
                    }
                    if (clock.ElapsedMilliseconds >= c.Job.TimeoutMs) throw c.Job.TimedOut();
                }
                var r = await c.BytesAsync("GET", path + "/content").ConfigureAwait(false);
                return c.Done(new List<MediaFile> { c.Gather.FromBytes(r.Body, "video/mp4") });
            }
        }
    }
}

/// <summary>§7G.9 OpenRouter — image (live-verified) through chat completions with
/// <c>modalities: ["image","text"]</c>. Key <c>OPENROUTER_API_KEY</c>.</summary>
public sealed class OpenRouterMediaClient : ApiMediaClient
{
    private static readonly IReadOnlyDictionary<string, MediaArg[]> Table = new Dictionary<string, MediaArg[]>
    {
        ["image"] = A("prompt*"),
    };

    public OpenRouterMediaClient(MediaClientConfig? config = null) : base(config) { }

    public override string Name => "openrouter";
    private protected override string KeyEnv => "OPENROUTER_API_KEY";
    private protected override string DefaultUrl => "https://openrouter.ai/api/v1";
    private protected override string PrimaryVerb => "image";
    private protected override IReadOnlyDictionary<string, MediaArg[]> VerbArgs => Table;
    private protected override KeyValuePair<string, string> AuthHeader(string key) => new("Authorization", "Bearer " + key);

    private protected override async Task<MediaResult> RunAsync(ApiCall c)
    {
        var resp = await c.JsonAsync("POST", "/chat/completions", new Dictionary<string, object?>
        {
            ["model"] = ModelFor("image", "google/gemini-2.5-flash-image"),
            ["modalities"] = new List<object?> { "image", "text" },
            ["messages"] = new List<object?> { new Dictionary<string, object?> { ["role"] = "user", ["content"] = c.Str("prompt") } },
        }).ConfigureAwait(false);
        if (MediaUtil.ScalarText(MediaUtil.ReadPath(resp, "id"), true) is { Length: > 0 } id) c.Job.JobId = id;
        var text = new StringBuilder();
        var files = new List<MediaFile>();
        foreach (var choice in (MediaUtil.ReadPath(resp, "choices") as List<object?> ?? new List<object?>()).OfType<IDictionary<string, object?>>())
        {
            if (MediaUtil.ReadPath(choice, "message.content") is string s) text.Append(s);
            foreach (var img in MediaUtil.ReadPath(choice, "message.images") as List<object?> ?? new List<object?>())
                if (MediaUtil.ReadPath(img, "image_url.url") is string u && u.Length > 0)
                    files.Add(await c.Gather.FromUrlAsync(u).ConfigureAwait(false));
        }
        var t = text.ToString();
        if (files.Count == 0)
            throw c.Job.Fail(t.Length > 0 ? $"image: no image in response: {MediaUtil.Cap(t, 200)}" : "image: no image in response");
        return c.Done(files, t.Length > 0 ? t : null, new MediaUsage // §7G.12 D21
        {
            InputTokens = MediaUtil.ToLong(MediaUtil.ReadPath(resp, "usage.prompt_tokens")),
            OutputTokens = MediaUtil.ToLong(MediaUtil.ReadPath(resp, "usage.completion_tokens")),
            CostUsd = MediaUtil.ReadPath(resp, "usage.cost") switch { double d => d, long l => l, int i => i, _ => null },
        });
    }
}

/// <summary>§7G.9 fal — the queue API for any hosted model id: image (live-verified on
/// <c>fal-ai/flux/schnell</c>), video (unverified). Key <c>FAL_API_KEY</c>, <c>Authorization: Key</c>.</summary>
public sealed class FalMediaClient : ApiMediaClient
{
    private static readonly IReadOnlyDictionary<string, MediaArg[]> Table = new Dictionary<string, MediaArg[]>
    {
        ["image"] = A("prompt*", "width", "height", "seed"),
        ["video"] = A("prompt*", "image", "seconds", "aspectRatio"),
    };

    public FalMediaClient(MediaClientConfig? config = null) : base(config) { }

    public override string Name => "fal";
    private protected override string KeyEnv => "FAL_API_KEY";
    private protected override string DefaultUrl => "https://queue.fal.run";
    private protected override string PrimaryVerb => "image";
    private protected override long DefaultIntervalMs => 500;
    private protected override IReadOnlyDictionary<string, MediaArg[]> VerbArgs => Table;
    private protected override KeyValuePair<string, string> AuthHeader(string key) => new("Authorization", "Key " + key);

    private protected override async Task<MediaResult> RunAsync(ApiCall c)
    {
        string model;
        var input = new Dictionary<string, object?> { ["prompt"] = c.Str("prompt") };
        if (c.Verb == "image")
        {
            model = ModelFor("image", "fal-ai/flux/schnell");
            input["num_images"] = 1L;
            if (model.Contains("schnell", StringComparison.Ordinal))
            {
                input["image_size"] = "square";
                input["num_inference_steps"] = 4L;
            }
            var w = c.Long("width");
            var h = c.Long("height");
            if (w != null || h != null)
                input["image_size"] = new Dictionary<string, object?> { ["width"] = w ?? h, ["height"] = h ?? w };
            if (c.Long("seed") is { } seed) input["seed"] = seed;
        }
        else
        {
            var image = c.Str("image");
            model = Config.Models != null && Config.Models.TryGetValue("video", out var vm) && !string.IsNullOrEmpty(vm) ? vm
                : image != null ? "fal-ai/kling-video/v2.1/standard/image-to-video" : "fal-ai/kling-video/v2.1/standard/text-to-video";
            input["duration"] = (c.Long("seconds") ?? 5).ToString(System.Globalization.CultureInfo.InvariantCulture);
            if (image != null) input["image_url"] = image;
            if (c.Str("aspectRatio") is { } ar) input["aspect_ratio"] = ar;
        }

        var submit = await c.JsonAsync("POST", "/" + model, input).ConfigureAwait(false);
        c.Job.JobId = MediaUtil.ScalarText(MediaUtil.ReadPath(submit, "request_id"), true) is { Length: > 0 } id ? id
            : throw c.Job.Fail($"{c.Verb}: no job id at request_id");
        var statusUrl = MediaUtil.ReadPath(submit, "status_url") as string
            ?? $"/{model}/requests/{MediaUtil.PercentEncode(c.Job.JobId)}/status";
        var responseUrl = MediaUtil.ReadPath(submit, "response_url") as string
            ?? $"/{model}/requests/{MediaUtil.PercentEncode(c.Job.JobId)}";
        var clock = System.Diagnostics.Stopwatch.StartNew();
        while (true)
        {
            await c.WaitAsync().ConfigureAwait(false);
            var poll = await c.JsonAsync("GET", statusUrl).ConfigureAwait(false);
            var st = MediaUtil.ScalarText(MediaUtil.ReadPath(poll, "status"), false) ?? "";
            c.Job.Report(st);
            if (st == "COMPLETED") break;
            var err = MediaUtil.ReadPath(poll, "error");
            var errText = err as string ?? (err == null ? "" : Json.Stringify(err));
            if (errText.Length > 0) throw c.Job.Fail($"job {c.Job.JobId} failed: {errText}");
            if (clock.ElapsedMilliseconds >= c.Job.TimeoutMs) throw c.Job.TimedOut();
        }
        var result = await c.JsonAsync("GET", responseUrl).ConfigureAwait(false);
        var urls = new List<string>();
        foreach (var img in MediaUtil.ReadPath(result, "images") as List<object?> ?? new List<object?>())
            if (MediaUtil.ReadPath(img, "url") is string u && u.Length > 0) urls.Add(u);
        foreach (var p in new[] { "image.url", "video.url", "audio.url", "audio_file.url" })
            if (MediaUtil.ReadPath(result, p) is string u && u.Length > 0) urls.Add(u);
        if (urls.Count == 0) throw c.Job.Fail($"{c.Verb}: no result in response");
        var files = new List<MediaFile>();
        foreach (var u in urls) files.Add(await c.Gather.FromUrlAsync(u).ConfigureAwait(false));
        return c.Done(files);
    }
}
