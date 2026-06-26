namespace Toolnexus;

/// <summary>
/// Toolkit — aggregates dynamic MCP tools + the skill tool + custom/native/http
/// tools into a single uniform list, with provider adapters and a single
/// <see cref="ExecuteAsync"/> router.
/// </summary>
public sealed class Toolkit : IAsyncDisposable
{
    private readonly McpSource? _mcp;
    private readonly SkillSource? _skill;
    private readonly Dictionary<string, ITool> _byName = new();

    public sealed class Options
    {
        /// <summary>Path string, raw JSON string, or parsed config dictionary.</summary>
        public object? McpConfig { get; set; }

        /// <summary>One or more skill roots.</summary>
        public List<string>? SkillsDir { get; set; }

        /// <summary>Custom/native/http tools.</summary>
        public List<ITool>? ExtraTools { get; set; }

        /// <summary>Objects scanned via <see cref="Tools.FromObject"/>.</summary>
        public List<object>? AnnotatedObjects { get; set; }

        public Options WithMcpConfig(object? v) { McpConfig = v; return this; }
        public Options WithSkillsDir(params string[] v) { SkillsDir = v.ToList(); return this; }
        public Options WithExtraTools(params ITool[] v) { ExtraTools = v.ToList(); return this; }
        public Options WithAnnotatedObjects(params object[] v) { AnnotatedObjects = v.ToList(); return this; }
    }

    private Toolkit(McpSource? mcp, SkillSource? skill, List<ITool> extraTools)
    {
        _mcp = mcp;
        _skill = skill;
        var all = new List<ITool>();
        if (mcp != null) all.AddRange(mcp.Tools);
        if (skill != null) all.Add(skill.Tool);
        all.AddRange(extraTools);
        foreach (var t in all) Add(t);
    }

    public static async Task<Toolkit> CreateAsync(Options opts)
    {
        var mcp = opts.McpConfig != null ? await McpSource.LoadAsync(opts.McpConfig).ConfigureAwait(false) : null;
        var skill = opts.SkillsDir != null ? SkillSource.Load(opts.SkillsDir) : null;

        var extras = new List<ITool>();
        if (opts.ExtraTools != null) extras.AddRange(opts.ExtraTools);
        if (opts.AnnotatedObjects != null)
            foreach (var o in opts.AnnotatedObjects)
                extras.AddRange(global::Toolnexus.Tools.FromObject(o));

        return new Toolkit(mcp, skill, extras);
    }

    private void Add(ITool t)
    {
        if (_byName.ContainsKey(t.Name))
        {
            Console.Error.WriteLine($"[toolnexus] duplicate tool name \"{t.Name}\" — keeping first");
            return;
        }
        _byName[t.Name] = t;
    }

    public List<ITool> Tools() => _byName.Values.ToList();

    public ITool? Get(string name) => _byName.GetValueOrDefault(name);

    /// <summary>Add native/http/custom tools at runtime. First-name-wins; warn on dup. Chainable.</summary>
    public Toolkit Register(params ITool[] tools)
    {
        foreach (var t in tools) Add(t);
        return this;
    }

    public async Task<ToolResult> ExecuteAsync(string name, IDictionary<string, object?>? args, ToolContext? ctx = null)
    {
        if (!_byName.TryGetValue(name, out var tool))
            return ToolResult.Error($"Unknown tool: {name}");
        return await tool.ExecuteAsync(args ?? new Dictionary<string, object?>(), ctx).ConfigureAwait(false);
    }

    /// <summary>Markdown skill catalog for the system prompt.</summary>
    public string SkillsPrompt() => _skill?.Prompt() ?? "";

    /// <summary>server -&gt; "connected" | "failed" | "disabled".</summary>
    public IReadOnlyDictionary<string, string> McpStatus()
        => _mcp?.Status ?? new Dictionary<string, string>();

    public List<Dictionary<string, object?>> ToOpenAI() => Adapters.ToOpenAI(Tools());
    public List<Dictionary<string, object?>> ToAnthropic() => Adapters.ToAnthropic(Tools());
    public List<Dictionary<string, object?>> ToGemini() => Adapters.ToGemini(Tools());

    public async ValueTask DisposeAsync()
    {
        if (_mcp != null) await _mcp.DisposeAsync().ConfigureAwait(false);
    }
}
