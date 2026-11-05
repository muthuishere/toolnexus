using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// CLI client styles (SPEC §8C, ADR 0037): an agent CLI the user already has
// installed — and pays for — becomes the model behind an ordinary in-process
// client, selected by a closed enum, never by a string.
//
// Two transports, one constructor:
//   - ACP (primary, CliAcp.cs): the user's whole toolkit is served as a local
//     MCP server and handed to the agent in session/new mcpServers. The agent
//     calls the tools natively.
//   - one-shot CLI (fallback, this file): the CLI is run once per model turn
//     with the conversation rendered under a fixed preamble; its reply is parsed
//     as an OpenAI-shaped envelope and tool calls go back to the toolnexus loop.
//
// A style is data (CliStyles/*.json, embedded, byte-identical to
// conformance/cli-styles/) and holds no binary: Command is always the caller's.
// Mirrors golang/cli.go.
// ---------------------------------------------------------------------------

/// <summary>The closed set of CLI families a CLI client can drive. Each is a shape (argv, prompt
/// mode, answer source, ACP launch), never a binary. The zero value means "no style chosen".</summary>
public enum CliStyles
{
    Claude = 1,
    Codex,
    Copilot,
    Opencode,
    Devin,
    Gemini,
    Goose,
    Custom,
}

/// <summary>How a CLI client reaches the agent. <see cref="Auto"/> (default) uses ACP when the style
/// has an ACP launch and the binary answers <c>initialize</c>, else the one-shot CLI.</summary>
public enum CliTransports
{
    Auto = 0,
    Acp,
    Cli,
}

/// <summary>A style's ACP launch: the args appended to <c>Command</c> to start it as an ACP agent,
/// and, for agents that ignore <c>session/new</c> mcpServers, the project MCP file written into a
/// fresh temp session cwd.</summary>
public sealed class CliAcpLaunch
{
    public List<string> Args { get; set; } = new();
    public string? ProjectMcpFile { get; set; }
    /// <summary>Appended to the system prompt of every ACP turn (a new system message when there is
    /// none). Data, not code: devin only finds MCP tools when the prompt names the server.</summary>
    public string? SystemHint { get; set; }
    public string? Verified { get; set; }
    public string? Notes { get; set; }
}

/// <summary>One CLI's invocation recipe — data shared by every port. <see cref="Args"/> elements are
/// strings or optional groups (lists of strings, kept only when a model is set).
/// Prompt: <c>stdin | arg | file</c>. Answer: <c>stdout | out</c>.</summary>
public sealed class CliStyleDef
{
    public string Name { get; set; } = "";
    public List<object?>? Args { get; set; }
    public string Prompt { get; set; } = "";
    public string Answer { get; set; } = "";
    public int TimeoutMs { get; set; }
    public Dictionary<string, string>? Env { get; set; }
    public string? Verified { get; set; }
    public string? Notes { get; set; }
    public CliAcpLaunch? Acp { get; set; }

    internal static CliStyleDef FromJson(string json)
    {
        var o = Json.ParseObjectLoose(json);
        string? Str(Dictionary<string, object?> m, string k) => m.TryGetValue(k, out var v) ? v as string : null;
        var d = new CliStyleDef
        {
            Name = Str(o, "name") ?? "",
            Args = o.TryGetValue("args", out var a) ? a as List<object?> : null,
            Prompt = Str(o, "prompt") ?? "",
            Answer = Str(o, "answer") ?? "",
            TimeoutMs = o.TryGetValue("timeoutMs", out var t) && t is long l ? (int)l : 0,
            Verified = Str(o, "verified"),
            Notes = Str(o, "notes"),
        };
        if (o.TryGetValue("env", out var e) && e is Dictionary<string, object?> env)
            d.Env = env.ToDictionary(kv => kv.Key, kv => kv.Value?.ToString() ?? "");
        if (o.TryGetValue("acp", out var ac) && ac is Dictionary<string, object?> acp)
            d.Acp = new CliAcpLaunch
            {
                Args = (acp.TryGetValue("args", out var aa) && aa is List<object?> al ? al : new List<object?>())
                    .Select(x => x?.ToString() ?? "").ToList(),
                ProjectMcpFile = Str(acp, "projectMcpFile"),
                SystemHint = Str(acp, "systemHint"),
                Verified = Str(acp, "verified"),
                Notes = Str(acp, "notes"),
            };
        return d;
    }
}

/// <summary>Options for <see cref="CliClient.Create"/>. <see cref="Command"/> is REQUIRED: the
/// library never picks or defaults the binary. <see cref="Model"/> is passed to the CLI unchanged
/// (empty ⇒ the CLI's own default model). Every in-process client option is here too.</summary>
public sealed class CliOptions
{
    /// <summary>A built-in recipe. Required unless <see cref="StyleDef"/> is set.</summary>
    public CliStyles Style { get; set; }
    /// <summary>A user style (<see cref="CliClient.LoadStyle"/>); wins over <see cref="Style"/>.</summary>
    public CliStyleDef? StyleDef { get; set; }
    /// <summary>The CLI binary, a path or a name on PATH. Required.</summary>
    public string? Command { get; set; }

    /// <summary>Args, Prompt and Answer describe the CLI for <see cref="CliStyles.Custom"/>.</summary>
    public List<object?>? Args { get; set; }
    public string? Prompt { get; set; }
    public string? Answer { get; set; }

    /// <summary>Added to the child's environment; values expand <c>${VAR}</c> from this process's
    /// environment at call time and are never logged.</summary>
    public Dictionary<string, string>? Env { get; set; }
    /// <summary>Bounds one CLI invocation or one ACP prompt. Default 300000.</summary>
    public int? CallTimeoutMs { get; set; }
    /// <summary>The child's working directory (default: this process's).</summary>
    public string? Cwd { get; set; }

    public CliTransports Transport { get; set; }
    /// <summary>Served to an ACP agent as a local MCP server. Pass the same toolkit (or null) to RunAsync.</summary>
    public Toolkit? Toolkit { get; set; }
    /// <summary>The full argv that starts the agent in ACP mode (e.g. an adapter). Replaces
    /// Command + the style's acp.args for the ACP launch only.</summary>
    public List<string>? AcpCommand { get; set; }
    /// <summary>argv of a stdio⇄HTTP MCP bridge, for an agent that accepts neither http MCP servers
    /// nor a project MCP file. The URL is appended; the token goes in env TOOLNEXUS_MCP_TOKEN.
    /// Default: <c>toolnexus mcp-bridge</c> when the toolnexus CLI is on PATH.</summary>
    public List<string>? BridgeCommand { get; set; }
    /// <summary>Observes every raw ACP session/update (must not block).</summary>
    public Action<JsonElement>? OnAcpUpdate { get; set; }

    // --- every in-process client option ---
    public string Model { get; set; } = "";
    public string? SystemPrompt { get; set; }
    public int? MaxTurns { get; set; }
    public int? MaxToolConcurrency { get; set; }
    public LlmClient.Hooks? Hooks { get; set; }
    public long? TimeoutMs { get; set; }
    public IConversationStore? Store { get; set; }
    public Action<MetricEvent>? OnMetric { get; set; }
    public Func<Request, Task<Answer>>? WaitFor { get; set; }
    public Func<Request, CancellationToken, Task<Answer>>? WaitForCtx { get; set; }
    public IDictionary<string, object?>? RequestParams { get; set; }
    public int? Retries { get; set; }
}

/// <summary>A CLI client failure (SPEC §8C pinned messages).</summary>
public sealed class CliException : Exception
{
    public CliException(string message, Exception? inner = null) : base(message, inner) { }
}

/// <summary>
/// An ordinary <see cref="LlmClient"/> whose model is an installed agent CLI (SPEC §8C), plus
/// <see cref="Close"/> (ends the ACP agent and the served toolkit) and <see cref="Transport"/>.
/// Use <see cref="Client"/> (or the implicit conversion) wherever an <see cref="LlmClient"/> goes.
/// </summary>
public sealed partial class CliClient : IDisposable, IAsyncDisposable
{
    /// <summary>The fixed preamble of the one-shot prompt (conformance/cli-styles/preamble.txt).</summary>
    public static readonly string Preamble = ReadResource("preamble.txt");

    /// <summary>Default bound for one CLI invocation / one ACP prompt.</summary>
    public const int DefaultCallTimeoutMs = 300000;

    private static readonly string[] StyleFiles =
        { "claude", "codex", "copilot", "opencode", "devin", "gemini", "goose" };

    /// <summary>The underlying client: run it, stream it, hand it anywhere an LlmClient goes.</summary>
    public LlmClient Client { get; }

    public static implicit operator LlmClient(CliClient c) => c.Client;

    private readonly CliStyleDef _style;
    private readonly CliOptions _opts;
    private readonly List<CliElem> _elems = new();
    private readonly bool _hasCli;
    private readonly Dictionary<string, string> _env = new();
    private readonly int _timeout;
    private readonly List<string>? _acpArgv;
    private readonly object _mu = new();
    private readonly SemaphoreSlim _open = new(1, 1);
    private CliTransports _chosen;
    private CliAcpSession? _acp;
    private bool _closed;
    private string _lastNote = "";

    internal CliStyleDef Style => _style;

    /// <summary>"acp", "cli", or "auto" before the first model call has decided.</summary>
    public string Transport
    {
        get { lock (_mu) return _chosen switch { CliTransports.Acp => "acp", CliTransports.Cli => "cli", _ => "auto" }; }
    }

    /// <summary>Why Auto fell back from ACP to the one-shot CLI ("" when it did not).</summary>
    public string FallbackReason
    {
        get { lock (_mu) return _lastNote; }
    }

    // ---------------------------------------------------------------------
    // Styles.
    // ---------------------------------------------------------------------

    private static string ReadResource(string file)
    {
        using var s = typeof(CliClient).Assembly.GetManifestResourceStream("Toolnexus.CliStyles." + file)
                      ?? throw new InvalidOperationException("toolnexus: missing embedded CLI style " + file);
        using var r = new StreamReader(s, new UTF8Encoding(false));
        return r.ReadToEnd();
    }

    /// <summary>The embedded bytes of one shared style file (drift-tested against conformance/).</summary>
    internal static string EmbeddedFile(string file) => ReadResource(file);

    /// <summary>The built-in definition of a style (null for Custom and the zero value).</summary>
    public static CliStyleDef? StyleDefFor(CliStyles style)
    {
        var i = (int)style - 1;
        if (i < 0 || i >= StyleFiles.Length) return null;
        return CliStyleDef.FromJson(ReadResource(StyleFiles[i] + ".json"));
    }

    /// <summary>The shared file name of a style ("claude", …; "custom"). Never accepted as input.</summary>
    public static string StyleName(CliStyles style) =>
        style == CliStyles.Custom ? "custom" : (int)style is >= 1 and <= 7 ? StyleFiles[(int)style - 1] : "";

    /// <summary>Reads a user style from a file path, or from JSON text that starts with "{".
    /// Pass the result as <see cref="CliOptions.StyleDef"/>.</summary>
    public static CliStyleDef LoadStyle(string pathOrJson)
    {
        try
        {
            var text = pathOrJson.TrimStart().StartsWith('{') ? pathOrJson : File.ReadAllText(pathOrJson);
            return CliStyleDef.FromJson(text);
        }
        catch (Exception e)
        {
            throw new CliException("cli style: " + e.Message, e);
        }
    }

    // ---------------------------------------------------------------------
    // Construction.
    // ---------------------------------------------------------------------

    private static CliStyleDef ResolveStyle(CliOptions o)
    {
        if (string.IsNullOrEmpty(o.Command))
            throw new CliException("cli: Command is required (path or name of the CLI binary)");
        if (o.StyleDef != null) return o.StyleDef;
        if (o.Style == CliStyles.Custom)
            return new CliStyleDef { Name = "custom", Args = o.Args, Prompt = o.Prompt ?? "", Answer = o.Answer ?? "" };
        return StyleDefFor(o.Style)
               ?? throw new CliException("cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)");
    }

    /// <summary>
    /// Builds a client whose model is an installed agent CLI. Validates everything it can here;
    /// the transport (ACP or one-shot) is decided on the first model call. Throws
    /// <see cref="CliException"/> with the SPEC §8C pinned messages.
    /// </summary>
    public static CliClient Create(CliOptions options) => new(options ?? throw new ArgumentNullException(nameof(options)));

    private CliClient(CliOptions o)
    {
        var d = ResolveStyle(o);
        _style = d;
        _opts = o;

        if (o.AcpCommand is { Count: > 0 }) _acpArgv = new List<string>(o.AcpCommand);
        else if (d.Acp != null) _acpArgv = new List<string> { o.Command! }.Concat(d.Acp.Args).ToList();

        if (o.Transport == CliTransports.Acp && _acpArgv == null)
            throw new CliException($"cli: style \"{d.Name}\" has no ACP launch; set ACPCommand or use CliTransports.CLI");
        if (o.Transport != CliTransports.Acp)
        {
            var acpOnly = d.Args == null && d.Prompt == "" && d.Answer == "" && _acpArgv != null && o.Transport == CliTransports.Auto;
            if (!acpOnly)
            {
                _elems = NormalizeArgs(d.Args);
                Validate(d, _elems, o.Model);
                _hasCli = true;
            }
        }

        if (d.Env != null) foreach (var (k, v) in d.Env) _env[k] = v;
        if (o.Env != null) foreach (var (k, v) in o.Env) _env[k] = v;
        _timeout = o.CallTimeoutMs is > 0 ? o.CallTimeoutMs.Value : d.TimeoutMs > 0 ? d.TimeoutMs : DefaultCallTimeoutMs;
        if (o.Transport == CliTransports.Cli || _acpArgv == null) _chosen = CliTransports.Cli;
        else if (o.Transport == CliTransports.Acp) _chosen = CliTransports.Acp;

        var ip = new InProcess.Options
        {
            Model = string.IsNullOrEmpty(o.Model) ? d.Name : o.Model,
            Generate = Generate,
            GenerateStream = GenerateStreamAsync,
            SystemPrompt = o.SystemPrompt,
            MaxTurns = o.MaxTurns,
            MaxToolConcurrency = o.MaxToolConcurrency,
            Hooks = o.Hooks,
            TimeoutMs = o.TimeoutMs,
            Store = o.Store,
            OnMetric = o.OnMetric,
            WaitFor = o.WaitFor,
            WaitForCtx = o.WaitForCtx,
            RequestParams = o.RequestParams,
            Retries = o.Retries,
        };
        Client = InProcess.CreateClient(ip);
    }

    // ---------------------------------------------------------------------
    // The LlmClient surface, delegated.
    // ---------------------------------------------------------------------

    public Task<LlmClient.RunResult> RunAsync(string prompt, Toolkit? toolkit, List<object?>? history = null,
        CancellationToken cancellationToken = default) =>
        Client.RunAsync(prompt, toolkit, history, cancellationToken);

    public Task<LlmClient.RunResult> StreamAsync(string prompt, Toolkit? toolkit, Action<LlmClient.StreamEvent> onEvent,
        string? id = null, CancellationToken cancellationToken = default) =>
        Client.StreamAsync(prompt, toolkit, onEvent, id, cancellationToken);

    /// <summary>Ends the ACP agent's process tree, stops the served toolkit and removes any temp
    /// session dir. Idempotent; a no-op for the one-shot CLI.</summary>
    public void Close()
    {
        CliAcpSession? s;
        lock (_mu)
        {
            s = _acp;
            _acp = null;
            _closed = true;
        }
        s?.Dispose();
    }

    public void Dispose() => Close();

    public ValueTask DisposeAsync()
    {
        Close();
        return ValueTask.CompletedTask;
    }

    // ---------------------------------------------------------------------
    // The in-process model.
    // ---------------------------------------------------------------------

    private InProcess.Response Generate(InProcess.Request req)
    {
        var s = Task.Run(SessionAsync).GetAwaiter().GetResult();
        if (s != null) return s.Generate(req);
        var prompt = RenderPrompt(req.Messages, req.Tools);
        return ParseReply(Invoke(prompt));
    }

    /// <summary>
    /// The streaming seam (SPEC §8C). Over ACP the session reports every update to
    /// <paramref name="emit"/> as it arrives, so a stream is genuinely incremental. The one-shot CLI
    /// transport emits nothing: its reply is the §8C envelope — content OR tool_calls, known only
    /// once the whole reply parsed — so the in-process seam replays it as one text chunk (or the
    /// tool calls) after the call returns, for every style.
    /// </summary>
    private async Task<InProcess.Response> GenerateStreamAsync(InProcess.Request req,
        Action<LlmClient.StreamEvent> emit, CancellationToken ct)
    {
        var s = await Task.Run(SessionAsync, ct).ConfigureAwait(false);
        if (s != null) return await s.GenerateStreamAsync(req, emit, ct).ConfigureAwait(false);
        return await Task.Run(() => ParseReply(Invoke(RenderPrompt(req.Messages, req.Tools))), ct).ConfigureAwait(false);
    }

    /// <summary>The live ACP session, opening it on first use; null ⇒ the one-shot transport.</summary>
    private async Task<CliAcpSession?> SessionAsync()
    {
        await _open.WaitAsync().ConfigureAwait(false);
        try
        {
            lock (_mu)
            {
                if (_closed) throw new CliException("cli: client is closed");
                if (_chosen == CliTransports.Cli) return null;
                if (_acp != null) return _acp;
            }
            CliAcpSession s;
            try
            {
                s = await CliAcpSession.OpenAsync(this, _opts, _acpArgv!, _env, _timeout).ConfigureAwait(false);
            }
            catch (CliAcpLaunchException e)
            {
                lock (_mu)
                {
                    if (_chosen == CliTransports.Auto && _hasCli)
                    {
                        _chosen = CliTransports.Cli;
                        _lastNote = e.Message;
                        return null;
                    }
                }
                throw new CliException(e.Message, e);
            }
            lock (_mu)
            {
                if (_closed)
                {
                    s.Dispose();
                    throw new CliException("cli: client is closed");
                }
                _acp = s;
                _chosen = CliTransports.Acp;
            }
            return s;
        }
        finally
        {
            _open.Release();
        }
    }

    // ---------------------------------------------------------------------
    // Argv templating and validation.
    // ---------------------------------------------------------------------

    private static readonly Regex PlaceholderRe = new(@"\{\{([^{}]*)\}\}", RegexOptions.Compiled);

    internal sealed record CliElem(bool Group, List<string> Parts);

    private static List<CliElem> NormalizeArgs(List<object?>? args)
    {
        var out_ = new List<CliElem>();
        foreach (var e in args ?? new List<object?>())
        {
            switch (e)
            {
                case string s:
                    out_.Add(new CliElem(false, new List<string> { s }));
                    break;
                case JsonElement { ValueKind: JsonValueKind.String } js:
                    out_.Add(new CliElem(false, new List<string> { js.GetString()! }));
                    break;
                case System.Collections.IEnumerable list:
                    var g = new List<string>();
                    foreach (var x in list)
                    {
                        if (x is string xs) g.Add(xs);
                        else if (x is JsonElement { ValueKind: JsonValueKind.String } xj) g.Add(xj.GetString()!);
                        else throw new CliException("cli: a group may only hold strings");
                    }
                    out_.Add(new CliElem(true, g));
                    break;
                default:
                    throw new CliException("cli: an arg must be a string or a group of strings");
            }
        }
        return out_;
    }

    private static void Validate(CliStyleDef d, List<CliElem> elems, string? model)
    {
        if (d.Prompt is not ("stdin" or "arg" or "file"))
            throw new CliException($"cli: prompt must be stdin, arg or file, not \"{d.Prompt}\"");
        if (d.Answer is not ("stdout" or "out"))
            throw new CliException($"cli: answer must be stdout or out, not \"{d.Answer}\"");
        var seen = new HashSet<string>();
        foreach (var e in elems)
            foreach (var p in e.Parts)
                foreach (Match m in PlaceholderRe.Matches(p))
                {
                    var name = m.Groups[1].Value;
                    switch (name)
                    {
                        case "prompt" or "file" or "out":
                            break;
                        case "model":
                            if (!e.Group && string.IsNullOrEmpty(model))
                                throw new CliException("cli: the style needs a model: {{model}} is outside an optional group");
                            break;
                        default:
                            throw new CliException($"cli: unknown placeholder {{{{{name}}}}}");
                    }
                    seen.Add(name);
                }
        if (!string.IsNullOrEmpty(model) && !seen.Contains("model"))
            throw new CliException($"cli: style \"{d.Name}\" takes no model");
        if (d.Prompt == "arg" && !seen.Contains("prompt"))
            throw new CliException("cli: prompt \"arg\" needs {{prompt}} in args");
        if (d.Prompt == "file" && !seen.Contains("file"))
            throw new CliException("cli: prompt \"file\" needs {{file}} in args");
        if (d.Answer == "out" && !seen.Contains("out"))
            throw new CliException("cli: answer \"out\" needs {{out}} in args");
    }

    // ---------------------------------------------------------------------
    // The envelope: render and tolerant parse.
    // ---------------------------------------------------------------------

    /// <summary><c>PREAMBLE + "\nREQUEST:\n" + {"messages","tools"}</c>: the OpenAI-shaped request,
    /// unmodified, as compact JSON without HTML escaping.</summary>
    public static string RenderPrompt(IReadOnlyList<object?>? messages, IReadOnlyList<object?>? tools) =>
        Preamble + "\nREQUEST:\n" + Json.Stringify(new Dictionary<string, object?>
        {
            ["messages"] = messages ?? Array.Empty<object?>(),
            ["tools"] = tools ?? Array.Empty<object?>(),
        });

    /// <summary>
    /// The tolerant six-step envelope parser: (1) trim; (2) strip one markdown fence; (3) parse
    /// the whole text as one JSON object, else (4) the span from the first "{" to the last "}";
    /// (5) unwrap <c>choices[0].message</c> or <c>message</c>; (6) <c>tool_calls</c> (flat or
    /// <c>{function:{}}</c>, string or object arguments) win, else <c>content</c>. Anything that
    /// is not an envelope is returned verbatim as content.
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
                var args = fn.TryGetValue("arguments", out var a) && a is not null ? a : new Dictionary<string, object?>();
                var id = em.TryGetValue("id", out var idv) && idv is string ids && ids.Length > 0 ? ids : null;
                calls.Add(new InProcess.ToolCall { Id = id, Name = name, Arguments = args });
            }
            if (calls.Count > 0) return new InProcess.Response { ToolCalls = calls };
        }

        if (obj.TryGetValue("content", out var content))
            return content switch
            {
                string cs => InProcess.Response.FromContent(cs),
                null => new InProcess.Response(),
                _ => InProcess.Response.FromContent(Json.Stringify(content)),
            };
        return InProcess.Response.FromContent(text);
    }

    /// <summary>One whole JSON object, or null. Trailing text is a failure, never ignored.</summary>
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

    // ---------------------------------------------------------------------
    // One-shot invocation.
    // ---------------------------------------------------------------------

    private static readonly Regex EnvRefRe = new(@"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", RegexOptions.Compiled);

    /// <summary>Expands <c>${VAR}</c> in each value from this process's environment. Never logged.</summary>
    internal static Dictionary<string, string> ExpandEnv(IReadOnlyDictionary<string, string> env) =>
        env.ToDictionary(kv => kv.Key,
            kv => EnvRefRe.Replace(kv.Value, m => Environment.GetEnvironmentVariable(m.Groups[1].Value) ?? ""));

    private static string NewTempPath() =>
        Path.Combine(Path.GetTempPath(), "toolnexus-cli-" + Guid.NewGuid().ToString("N")[..12]);

    /// <summary>Renders the argv for one invocation. Every temp file it names is added to
    /// <paramref name="tmp"/> (in creation order) so the caller removes them on every path.</summary>
    internal List<string> CliArgv(string prompt, List<string> tmp, out string? outPath)
    {
        var model = _opts.Model ?? "";
        string? filePath = null, outP = null;
        string Sub(string p) => PlaceholderRe.Replace(p, m =>
        {
            switch (m.Groups[1].Value)
            {
                case "prompt": return prompt;
                case "model": return model;
                case "file":
                    if (filePath == null)
                    {
                        filePath = NewTempPath();
                        tmp.Add(filePath);
                        File.WriteAllText(filePath, prompt, new UTF8Encoding(false));
                    }
                    return filePath;
                case "out":
                    if (outP == null)
                    {
                        // The CLI creates it; absence ⇒ "no output file".
                        outP = NewTempPath();
                        tmp.Add(outP);
                    }
                    return outP;
            }
            return m.Value;
        });
        var argv = new List<string> { _opts.Command! };
        foreach (var e in _elems)
        {
            if (e.Group && model.Length == 0) continue;
            foreach (var p in e.Parts) argv.Add(Sub(p));
        }
        outPath = outP;
        return argv;
    }

    /// <summary>Resolves a command like <c>exec.LookPath</c>: a path is used as is when it exists,
    /// a bare name is searched on PATH. Null when not found.</summary>
    internal static string? LookPath(string cmd)
    {
        if (string.IsNullOrEmpty(cmd)) return null;
        if (cmd.Contains('/') || cmd.Contains(Path.DirectorySeparatorChar))
            return File.Exists(cmd) ? cmd : null;
        var exts = OperatingSystem.IsWindows()
            ? (Environment.GetEnvironmentVariable("PATHEXT") ?? ".EXE;.CMD;.BAT").Split(';').Prepend("").ToArray()
            : new[] { "" };
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            if (dir.Length == 0) continue;
            foreach (var ext in exts)
            {
                var p = Path.Combine(dir, cmd + ext);
                if (!File.Exists(p)) continue;
                if (!OperatingSystem.IsWindows() &&
                    (File.GetUnixFileMode(p) & (UnixFileMode.UserExecute | UnixFileMode.GroupExecute | UnixFileMode.OtherExecute)) == 0)
                    continue;
                return p;
            }
        }
        return null;
    }

    private string Invoke(string prompt)
    {
        var tmp = new List<string>();
        try
        {
            List<string> argv;
            string? outPath;
            try
            {
                argv = CliArgv(prompt, tmp, out outPath);
            }
            catch (IOException e)
            {
                throw new CliException("cli: " + e.Message, e);
            }
            var bin = LookPath(argv[0]) ?? throw new CliException($"cli: command not found: {argv[0]}");
            var psi = new ProcessStartInfo(bin)
            {
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                StandardOutputEncoding = new UTF8Encoding(false),
                StandardErrorEncoding = new UTF8Encoding(false),
            };
            foreach (var a in argv.Skip(1)) psi.ArgumentList.Add(a);
            if (!string.IsNullOrEmpty(_opts.Cwd)) psi.WorkingDirectory = _opts.Cwd;
            foreach (var (k, v) in ExpandEnv(_env)) psi.Environment[k] = v;

            Process p;
            try
            {
                p = Process.Start(psi) ?? throw new InvalidOperationException();
            }
            catch (Exception e)
            {
                throw new CliException($"cli: command not found: {argv[0]}", e);
            }
            using (p)
            {
                var stdout = p.StandardOutput.ReadToEndAsync();
                var stderr = p.StandardError.ReadToEndAsync();
                var input = _style.Prompt == "stdin" ? prompt : "";
                _ = Task.Run(async () =>
                {
                    try
                    {
                        await p.StandardInput.WriteAsync(input).ConfigureAwait(false);
                        p.StandardInput.Close();
                    }
                    catch { /* the CLI may exit without reading its stdin */ }
                });
                if (!p.WaitForExit(_timeout))
                {
                    try { p.Kill(entireProcessTree: true); } catch { /* already gone */ }
                    p.WaitForExit();
                    throw new CliException($"cli: timed out after {_timeout}ms");
                }
                p.WaitForExit(); // drain the redirected streams
                var outText = stdout.GetAwaiter().GetResult();
                var errText = stderr.GetAwaiter().GetResult();
                if (p.ExitCode != 0)
                {
                    var msg = errText.Trim();
                    if (msg.Length == 0) throw new CliException($"cli: exit {p.ExitCode}");
                    var runes = msg.EnumerateRunes().ToList();
                    if (runes.Count > 1000) msg = string.Concat(runes.Take(1000).Select(r => r.ToString())) + "…";
                    throw new CliException($"cli: exit {p.ExitCode}: {msg}");
                }
                var text = outText;
                if (_style.Answer == "out")
                {
                    if (outPath == null || !File.Exists(outPath)) throw new CliException("cli: no output file");
                    text = File.ReadAllText(outPath);
                }
                if (text.Trim().Length == 0) throw new CliException("cli: empty reply");
                return text;
            }
        }
        finally
        {
            foreach (var f in tmp)
                try { File.Delete(f); } catch { /* best effort */ }
        }
    }
}
