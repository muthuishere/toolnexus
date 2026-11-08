using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace Toolnexus;

/// <summary>
/// §7G.10 — runs a local subscription CLI once, headless, reads its JSON-lines stdout and collects
/// the files it made. Cancellation or the deadline kills the process tree. A CLI that cannot be
/// started is <see cref="MediaUnavailableException"/>. Built-ins: <see cref="GrokMediaClient"/>,
/// <see cref="CodexMediaClient"/>, and the <c>google-agy-cli</c> preset. CLI clients accept <c>prompt</c> plus the
/// size hints (image: width, height, aspectRatio; video: seconds, aspectRatio), folded into the prompt
/// by <see cref="FoldMediaPrompt"/>.
/// </summary>
public abstract partial class CliMediaClient : IMediaClient
{
    private static readonly MediaArg[] ImageArgs = { new("prompt", true), new("width", false), new("height", false), new("aspectRatio", false) };
    private static readonly MediaArg[] VideoArgs = { new("prompt", true), new("seconds", false), new("aspectRatio", false) };

    private protected readonly MediaClientConfig Config;

    private protected CliMediaClient(MediaClientConfig? config) => Config = config ?? new MediaClientConfig();

    public abstract string Name { get; }
    private protected abstract string DefaultCommand { get; }
    private protected abstract IReadOnlyCollection<string> Verbs { get; }
    /// <summary>The prompt template of a verb; <c>{prompt}</c> is replaced by the folded prompt.</summary>
    private protected abstract string Template(string verb);
    /// <summary>The argv after the command; <paramref name="prompt"/> is the full templated prompt.</summary>
    private protected abstract List<string> Argv(string verb, string prompt, string cwd);
    /// <summary>When true the CLI is never started: a fixed file is returned (§7G CLI mock presets).</summary>
    internal bool Mock { get; init; }
    private protected virtual IReadOnlyDictionary<string, string> Env => new Dictionary<string, string>();
    private protected abstract CliEvent Parse(IDictionary<string, object?> obj, string line);
    private protected virtual string? Folder(string session) => null;
    /// <summary>The unavailable message for a run's last error, or null when it is an ordinary failure.</summary>
    private protected abstract string? Unavailable(string verb, string error);

    /// <summary>One parsed stdout object.</summary>
    private protected sealed record CliEvent(string? Session = null, string? Text = null, IReadOnlyList<string>? Paths = null,
        string? Error = null, string? Status = null, MediaUsage? Usage = null);

    public bool Supports(string verb) => Verbs.Contains(verb);

    public IReadOnlyList<MediaArg>? Args(string verb) => !Supports(verb) ? null : verb == "video" ? VideoArgs : ImageArgs;

    private static readonly (string Name, string Pre, string Post)[] Hints =
    {
        ("width", "width ", " px"), ("height", "height ", " px"),
        ("seconds", "about ", " seconds long"), ("aspectRatio", "aspect ratio ", ""),
    };

    /// <summary>The prompt a CLI receives: <c>prompt</c>, then the present size hints in canonical
    /// verb order as <c>" (h1, h2)"</c> (§7G CLI hint folding).</summary>
    public static string FoldMediaPrompt(string verb, IDictionary<string, object?> args)
    {
        var prompt = ArgText(args.Get("prompt"));
        var hs = new List<string>();
        foreach (var a in MediaVerbs.Find(verb)?.Args ?? (IReadOnlyList<MediaVerbs.ArgSpec>)Array.Empty<MediaVerbs.ArgSpec>())
            foreach (var h in Hints)
                if (h.Name == a.Name && args.Get(a.Name) is { } v)
                    hs.Add(h.Pre + ArgText(v) + h.Post);
        return hs.Count == 0 ? prompt : prompt + " (" + string.Join(", ", hs) + ")";
    }

    private static string ArgText(object? v) => v switch
    {
        null => "",
        string s => s,
        _ => MediaUtil.ScalarText(v, false) ?? Convert.ToString(v, System.Globalization.CultureInfo.InvariantCulture) ?? "",
    };

    public Task<MediaResult> ImageAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("image", args, ctx);
    public Task<MediaResult> VideoAsync(IDictionary<string, object?> args, MediaContext ctx) => RunAsync("video", args, ctx);

    private protected static string CapError(string s) => MediaUtil.Cap(s, 400);

    [GeneratedRegex("""/[^\s"'()\[\]]+\.(png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav)""", RegexOptions.IgnoreCase)]
    private static partial Regex PathInText();

    private async Task<MediaResult> RunAsync(string verb, IDictionary<string, object?> args, MediaContext ctx)
    {
        if (!Supports(verb)) throw new MediaException($"{verb}: not supported by {Name}");
        var prompt = Template(verb).Replace("{prompt}", FoldMediaPrompt(verb, args));
        var job = new MediaJob(verb, Name, MediaUtil.Positive(Config.Loop?.TimeoutMs, 600000), ctx, Config.Loop?.OnProgress);
        var command = Config.Command is { Count: > 0 } cmd ? cmd : new[] { DefaultCommand };
        if (Mock) return MockRun(verb, prompt, job);
        var cwd = MediaUtil.RealPath(Directory.CreateTempSubdirectory("toolnexus-cli-").FullName);
        try
        {
            var psi = new ProcessStartInfo(command[0])
            {
                WorkingDirectory = cwd,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                StandardOutputEncoding = Encoding.UTF8,
            };
            foreach (var a in command.Skip(1)) psi.ArgumentList.Add(a);
            foreach (var a in Argv(verb, prompt, cwd)) psi.ArgumentList.Add(a);
            foreach (var (k, v) in Env) psi.Environment[k] = v;

            Process proc;
            try
            {
                proc = Process.Start(psi) ?? throw new Win32Exception("not started");
            }
            catch (Exception e) when (e is Win32Exception or FileNotFoundException or DirectoryNotFoundException or InvalidOperationException)
            {
                throw new MediaUnavailableException($"{Name}: cannot start \"{command[0]}\"");
            }

            using var procScope = proc;
            proc.StandardInput.Close();
            _ = proc.StandardError.BaseStream.CopyToAsync(Stream.Null);

            string? session = null, error = null;
            MediaUsage? usage = null;
            var text = new StringBuilder();
            var paths = new List<string>();
            using var timer = CancellationTokenSource.CreateLinkedTokenSource(job.Ct);
            timer.CancelAfter(TimeSpan.FromMilliseconds(Math.Max(1, job.RemainingMs)));
            // Killing the tree ends the pipe, so a read that ignores the token still returns.
            using var kill = timer.Token.Register(() => { try { proc.Kill(entireProcessTree: true); } catch { /* gone */ } });
            try
            {
                while (await proc.StandardOutput.ReadLineAsync(timer.Token).ConfigureAwait(false) is { } line)
                {
                    if (Json.ParseLoose(line) is not IDictionary<string, object?> obj) continue;
                    var ev = Parse(obj, line);
                    if (ev.Session != null) { session = ev.Session; job.JobId = session; }
                    if (ev.Text != null) text.Append(ev.Text);
                    if (ev.Paths != null) paths.AddRange(ev.Paths);
                    if (ev.Error != null) error = ev.Error;
                    if (ev.Usage != null) usage = ev.Usage;
                    if (ev.Status != null) job.Report(ev.Status);
                }
                await proc.WaitForExitAsync(timer.Token).ConfigureAwait(false);
                timer.Token.ThrowIfCancellationRequested();
            }
            catch (OperationCanceledException)
            {
                try { proc.Kill(entireProcessTree: true); } catch { /* already gone */ }
                if (job.Ct.IsCancellationRequested) throw;
                throw new MediaException($"{Name}: timed out after {job.TimeoutMs}ms", "timeout", job.JobId);
            }

            // candidates: parsed paths, then absolute paths in the text, then the client's folder
            var all = new List<string>(paths);
            var collected = text.ToString();
            all.AddRange(PathInText().Matches(collected).Select(m => m.Value));
            if (session != null && Folder(session) is { } folder && Directory.Exists(folder))
                all.AddRange(Directory.GetFiles(folder).OrderBy(f => Path.GetFileName(f), StringComparer.Ordinal));
            var seen = new HashSet<string>(StringComparer.Ordinal);
            var want = verb == "video" ? "video/" : "image/";
            var gather = new MediaGatherer(Config.Gather, "download", MediaHttp.Default, job, MediaVerbs.KindOf(verb));
            var files = new List<MediaFile>();
            foreach (var p in all)
            {
                if (!seen.Add(p) || !File.Exists(p)) continue;
                var mime = MediaUtil.MimeOf(p);
                if (mime == null || !mime.StartsWith(want, StringComparison.Ordinal)) continue;
                files.Add(gather.FromLocal(p, mime, MediaUtil.TypeOfKind(MediaVerbs.KindOf(verb))));
            }
            if (files.Count > 0)
                return new MediaResult(files, "completed", collected.Length > 0 ? collected : null, session) { Usage = usage };

            if (error != null)
            {
                if (Unavailable(verb, error) is { } msg) throw new MediaUnavailableException(msg, session);
                throw new MediaException($"{Name}: no {verb}: {CapError(error)}", "failed", session);
            }
            var tail = collected.Length > 0 ? ": " + MediaUtil.Cap(collected, 300) : "";
            throw new MediaException($"{Name}: no {verb} file in the run (exit code {proc.ExitCode}){tail}", "failed", session);
        }
        finally
        {
            try { Directory.Delete(cwd, recursive: true); } catch { /* best effort */ }
        }
    }

    /// <summary>The CLI mock: no process. Writes <c>&lt;gather.dir&gt;/&lt;client&gt;-mock-&lt;verb&gt;.&lt;png|mp4&gt;</c>
    /// holding <c>toolnexus mock &lt;verb&gt;</c> and gathers it like a CLI file; text = the exact prompt
    /// the CLI would have received; jobId <c>mock</c>.</summary>
    private MediaResult MockRun(string verb, string prompt, MediaJob job)
    {
        var (ext, mime) = verb == "video" ? (".mp4", "video/mp4") : (".png", "image/png");
        var dir = Config.Gather?.Dir is { Length: > 0 } d ? d : Path.Combine(Path.GetTempPath(), "toolnexus-media");
        try
        {
            Directory.CreateDirectory(dir);
            var p = Path.GetFullPath(Path.Combine(dir, $"{Name}-mock-{verb}{ext}"));
            File.WriteAllBytes(p, Encoding.UTF8.GetBytes("toolnexus mock " + verb));
            job.JobId = "mock";
            var gather = new MediaGatherer(Config.Gather, "download", MediaHttp.Default, job, MediaVerbs.KindOf(verb));
            var f = gather.FromLocal(p, mime, MediaUtil.TypeOfKind(MediaVerbs.KindOf(verb)));
            return new MediaResult(new[] { f }, "completed", prompt, "mock");
        }
        catch (IOException e)
        {
            throw job.Fail($"{Name}: {e.Message}");
        }
    }
}

/// <summary>§7G.10 Grok Build CLI (<c>grok -p … --output-format streaming-json</c>, SuperGrok
/// subscription): image (live-verified), video (live-verified after opting in under <c>/privacy</c>).
/// Never edits the user's Grok settings; under Zero Data Retention video is <c>unavailable</c>.</summary>
public sealed class GrokMediaClient : CliMediaClient
{
    private static readonly string[] VerbList = { "image", "video" };

    public GrokMediaClient(MediaClientConfig? config = null) : base(config) { }

    public override string Name => "grok";
    private protected override string DefaultCommand => "grok";
    private protected override IReadOnlyCollection<string> Verbs => VerbList;

    private protected override IReadOnlyDictionary<string, string> Env => new Dictionary<string, string>
    {
        ["GROK_CONFIG"] = "{\"features\":{\"image_gen\":true,\"video_gen\":true}}",
    };

    private protected override string Template(string verb) => verb == "video"
        ? "Generate exactly one short video with your video generation tool, shortest duration available: {prompt}. Do not write code. Reply only with the file path."
        : "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the file path.";

    private protected override List<string> Argv(string verb, string prompt, string cwd)
    {
        var argv = new List<string> { "-p", prompt, "--cwd", cwd, "--output-format", "streaming-json", "--yolo", "--no-auto-update" };
        if (!string.IsNullOrEmpty(Config.Model)) argv.AddRange(new[] { "-m", Config.Model! });
        return argv;
    }

    private protected override CliEvent Parse(IDictionary<string, object?> obj, string line)
    {
        switch (obj.Get("type") as string)
        {
            case "text":
                return new CliEvent(Text: obj.Get("data") as string);
            case "tool_call":
                return new CliEvent(Status: "tool " + (obj.Get("toolName")?.ToString() ?? ""));
            case "tool_call_update":
            {
                if (obj.Get("status") is not string { Length: > 0 } st) return new CliEvent();
                var raw = obj.Get("rawOutput");
                if (st == "completed")
                    return new CliEvent(Status: "tool " + st,
                        Paths: MediaUtil.ReadPath(raw, "path") is string { Length: > 0 } p ? new[] { p } : null);
                if (st == "failed")
                    return new CliEvent(Status: "tool " + st,
                        Error: MediaUtil.ReadPath(raw, "message") as string ?? Json.Stringify(MediaUtil.SortKeys(raw)));
                return new CliEvent(Status: "tool " + st);
            }
            case "end":
            {
                // §7G.10/§7G.12 D21: tokens + total_cost_usd -> costUsd; ticks pass through raw, never converted
                var it = MediaUtil.ReadPath(obj, "usage.input_tokens");
                var ot = MediaUtil.ReadPath(obj, "usage.output_tokens");
                var cost = obj.Get("total_cost_usd") switch { double d => d, long l => l, int i => i, _ => (double?)null };
                var ticks = obj.Get("total_cost_usd_ticks");
                MediaUsage? usage = it != null || ot != null || cost != null || ticks != null
                    ? new MediaUsage
                    {
                        InputTokens = MediaUtil.ToLong(it),
                        OutputTokens = MediaUtil.ToLong(ot),
                        CostUsd = cost,
                        Raw = ticks != null ? new Dictionary<string, object?> { ["total_cost_usd_ticks"] = ticks } : null,
                    }
                    : null;
                return new CliEvent(Session: obj.Get("sessionId") as string, Status: "end " + (obj.Get("stopReason")?.ToString() ?? ""), Usage: usage);
            }
            case "error":
                return new CliEvent(Error: obj.Get("message")?.ToString() ?? line);
            default:
                return new CliEvent();
        }
    }

    private protected override string? Unavailable(string verb, string error)
    {
        if (error.Contains("zero data retention", StringComparison.OrdinalIgnoreCase))
            return $"grok: {verb} blocked by Zero Data Retention — run /privacy and opt in, or configure ZDR video storage (docs/media#grok-video-zdr)";
        if (error.Contains("usage limit", StringComparison.OrdinalIgnoreCase)) return "grok: " + CapError(error);
        return null;
    }
}

/// <summary>§7G.10 Codex CLI (<c>codex exec --json</c>, ChatGPT plan): image (live-verified). The
/// file comes from the reply text or <c>$CODEX_HOME/generated_images/&lt;thread&gt;</c>.</summary>
public sealed class CodexMediaClient : CliMediaClient
{
    private static readonly string[] VerbList = { "image" };

    public CodexMediaClient(MediaClientConfig? config = null) : base(config) { }

    public override string Name => "codex";
    private protected override string DefaultCommand => "codex";
    private protected override IReadOnlyCollection<string> Verbs => VerbList;

    private protected override string Template(string verb)
        => "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write code or use scripts. Reply only with the absolute path where the image was saved.";

    private protected override List<string> Argv(string verb, string prompt, string cwd)
    {
        var argv = new List<string> { "exec", "--json", "--skip-git-repo-check", "-s", "workspace-write" };
        if (!string.IsNullOrEmpty(Config.Model)) argv.AddRange(new[] { "-m", Config.Model! });
        argv.Add(prompt);
        return argv;
    }

    private protected override CliEvent Parse(IDictionary<string, object?> obj, string line)
    {
        var type = obj.Get("type")?.ToString();
        switch (type)
        {
            case "thread.started":
                return new CliEvent(Session: obj.Get("thread_id") as string, Status: type);
            case "item.completed":
                return MediaUtil.ReadPath(obj, "item.type") as string == "agent_message"
                    ? new CliEvent(Text: MediaUtil.ReadPath(obj, "item.text") as string, Status: type)
                    : new CliEvent(Status: type);
            case "error" or "turn.failed":
                return new CliEvent(Status: type,
                    Error: obj.Get("message") as string ?? MediaUtil.ReadPath(obj, "error.message") as string ?? line);
            default:
                return new CliEvent(Status: type);
        }
    }

    private protected override string? Folder(string session)
    {
        var home = Environment.GetEnvironmentVariable("CODEX_HOME") is { Length: > 0 } h
            ? h : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".codex");
        return Path.Combine(home, "generated_images", session);
    }

    private protected override string? Unavailable(string verb, string error)
        => MediaHttp.ContainsAny(error, "usage limit", "quota") ? "codex: " + CapError(error) : null;
}

/// <summary>§7G.10 Google Antigravity CLI (<c>agy -p … --output-format stream-json
/// --dangerously-skip-permissions</c>): image. The path is in the final result's response text,
/// else the files of the conversation's brain folder. Reached through the <c>google-agy-cli</c> preset.</summary>
internal sealed class AgyMediaClient : CliMediaClient
{
    private static readonly string[] VerbList = { "image" };

    public AgyMediaClient(MediaClientConfig? config = null) : base(config) { }

    public override string Name => "agy";
    private protected override string DefaultCommand => "agy";
    private protected override IReadOnlyCollection<string> Verbs => VerbList;

    private protected override string Template(string verb)
        => "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the absolute path where the image was saved.";

    private protected override List<string> Argv(string verb, string prompt, string cwd)
    {
        var argv = new List<string> { "-p", prompt, "--output-format", "stream-json", "--dangerously-skip-permissions" };
        if (!string.IsNullOrEmpty(Config.Model)) argv.AddRange(new[] { "--model", Config.Model! });
        return argv;
    }

    private protected override CliEvent Parse(IDictionary<string, object?> obj, string line)
    {
        switch (obj.Get("event") as string)
        {
            case "init":
                return new CliEvent(Session: obj.Get("conversation_id") as string, Status: "init");
            case "step_update":
                return MediaUtil.ReadPath(obj, "step_update.tool_name") is string { Length: > 0 } t
                       && MediaUtil.ReadPath(obj, "step_update.state") is string { Length: > 0 } st
                    ? new CliEvent(Status: "tool " + t + " " + st)
                    : new CliEvent();
            case "result":
            {
                var text = MediaUtil.ReadPath(obj, "result.response") as string;
                var status = MediaUtil.ReadPath(obj, "result.status") as string ?? "";
                string? error = null;
                if (status != "SUCCESS")
                    error = MediaUtil.ReadPath(obj, "result.error") is string { Length: > 0 } m1 ? m1
                        : MediaUtil.ReadPath(obj, "result.error.message") is string { Length: > 0 } m2 ? m2
                        : !string.IsNullOrEmpty(text) ? text
                        : Json.Stringify(MediaUtil.SortKeys(obj.Get("result")));
                var it = MediaUtil.ReadPath(obj, "result.usage.input_tokens");
                var ot = MediaUtil.ReadPath(obj, "result.usage.output_tokens");
                MediaUsage? usage = it != null || ot != null
                    ? new MediaUsage { InputTokens = MediaUtil.ToLong(it), OutputTokens = MediaUtil.ToLong(ot) }
                    : null;
                return new CliEvent(Session: MediaUtil.ReadPath(obj, "result.conversation_id") as string, Text: text,
                    Error: error, Status: "result " + status, Usage: usage);
            }
            case "error":
                return new CliEvent(Error: obj.Get("message") as string);
            default:
                return new CliEvent();
        }
    }

    private protected override string? Folder(string session)
        => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".gemini", "antigravity-cli", "brain", session);

    private protected override string? Unavailable(string verb, string error)
        => MediaHttp.ContainsAny(error, "quota", "usage limit") ? "agy: " + CapError(error) : null;
}
