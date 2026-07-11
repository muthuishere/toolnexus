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

    /// <summary>A2A profile read from a top-level <c>a2a</c> config block (ServeAsync falls back to this).</summary>
    private readonly A2AConfig? _a2aConfig;

    /// <summary>MCP serve profile read from a top-level <c>mcpServer</c> config block (ServeAsync falls back to this).</summary>
    private readonly MCPServeConfig? _mcpServerConfig;

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

        /// <summary>
        /// Built-in tools (§4A). On by default. <c>false</c> | <c>{disabled:true}</c> |
        /// <c>{enabled:false}</c> ⇒ off; a <c>{tools:{name:bool,...}}</c> map disables named
        /// tools on the all-on baseline. Accepts a <see cref="bool"/> or an
        /// <c>IDictionary&lt;string, object?&gt;</c>. <c>null</c> = not set (defaults on, or falls
        /// back to a top-level <c>builtins</c> key on a parsed <see cref="McpConfig"/> object).
        /// </summary>
        public object? Builtins { get; set; }

        /// <summary>Remote A2A agents — each advertised skill becomes a tool (source <c>"a2a"</c>).</summary>
        public List<Agent>? Agents { get; set; }

        /// <summary>
        /// Host resolver for out-of-band input (§10). When set, connected MCP servers may elicit
        /// input from the human mid-<c>tools/call</c> and it is bridged onto this <c>WaitFor</c>
        /// (form→kind:"input", URL→kind:"authorization"). Typically the same function passed to the
        /// client. Omit ⇒ MCP elicitation is not advertised.
        /// </summary>
        public Func<Request, Task<Answer>>? WaitFor { get; set; }

        public Options WithMcpConfig(object? v) { McpConfig = v; return this; }
        public Options WithSkillsDir(params string[] v) { SkillsDir = v.ToList(); return this; }
        public Options WithExtraTools(params ITool[] v) { ExtraTools = v.ToList(); return this; }
        public Options WithAnnotatedObjects(params object[] v) { AnnotatedObjects = v.ToList(); return this; }
        public Options WithBuiltins(object? v) { Builtins = v; return this; }
        public Options WithAgents(params Agent[] v) { Agents = v.ToList(); return this; }
        public Options WithWaitFor(Func<Request, Task<Answer>> v) { WaitFor = v; return this; }
    }

    private Toolkit(McpSource? mcp, SkillSource? skill, List<ITool> builtins, List<ITool> agents, List<ITool> extraTools, A2AConfig? a2aConfig, MCPServeConfig? mcpServerConfig)
    {
        _mcp = mcp;
        _skill = skill;
        _a2aConfig = a2aConfig;
        _mcpServerConfig = mcpServerConfig;
        // Builtins are the lowest-precedence source: a host extraTools entry with the same name
        // shadows a builtin (SPEC §4). Drop shadowed builtins up front, then apply the normal
        // first-wins dedupe for MCP/skill/agents/extras. Order: MCP → skill → builtin → agents → extraTools.
        var extraNames = new HashSet<string>(extraTools.Select(t => t.Name));
        var activeBuiltins = builtins.Where(b => !extraNames.Contains(b.Name)).ToList();
        var all = new List<ITool>();
        if (mcp != null) all.AddRange(mcp.Tools);
        if (skill != null) all.Add(skill.Tool);
        all.AddRange(activeBuiltins);
        all.AddRange(agents);
        all.AddRange(extraTools);
        foreach (var t in all) Add(t);
    }

    public static async Task<Toolkit> CreateAsync(Options opts)
    {
        var mcp = opts.McpConfig != null ? await McpSource.LoadAsync(opts.McpConfig, opts.WaitFor).ConfigureAwait(false) : null;
        var skill = opts.SkillsDir != null ? SkillSource.Load(opts.SkillsDir) : null;

        var extras = new List<ITool>();
        if (opts.ExtraTools != null) extras.AddRange(opts.ExtraTools);
        if (opts.AnnotatedObjects != null)
            foreach (var o in opts.AnnotatedObjects)
                extras.AddRange(global::Toolnexus.Tools.FromObject(o));

        // The toggle comes from the `Builtins` option, or a top-level `builtins` key on a parsed
        // config object (same precedence as MCP's isEnabled).
        var builtinsCfg = opts.Builtins;
        if (builtinsCfg == null && opts.McpConfig is IDictionary<string, object?> mc && mc.ContainsKey("builtins"))
            builtinsCfg = mc.Get("builtins");
        var builtins = BuiltinTools.Select(builtinsCfg);

        // Remote A2A agents come from the `Agents` option plus a top-level `agents` block on a
        // parsed config object (mirrors mcpServers). Each is resolved to its skill tools; a
        // failing agent never breaks the toolkit (isolated + logged, like MCP).
        var agentDescriptors = new List<Agent>();
        if (opts.Agents != null) agentDescriptors.AddRange(opts.Agents);
        if (opts.McpConfig is IDictionary<string, object?> agmc && agmc.ContainsKey("agents"))
            agentDescriptors.AddRange(A2A.ParseAgentsConfig(agmc.Get("agents")));

        var agentToolLists = await Task.WhenAll(agentDescriptors.Select(async a =>
        {
            try { return await A2A.AgentTools(a).ConfigureAwait(false); }
            catch (Exception e)
            {
                Console.Error.WriteLine($"[toolnexus] A2A agent \"{a.Card}\" failed: {e.Message}");
                return new List<ITool>();
            }
        })).ConfigureAwait(false);
        var agentTools = agentToolLists.SelectMany(x => x).ToList();

        // A2A inbound profile: a top-level `a2a` block on a parsed config object (mirrors
        // builtins/agents). ServeAsync prefers its inline `a2a` over this.
        A2AConfig? a2aConfig = null;
        if (opts.McpConfig is IDictionary<string, object?> a2amc && a2amc.ContainsKey("a2a"))
            a2aConfig = ParseA2AConfig(a2amc.Get("a2a"));

        // MCP inbound profile: a top-level `mcpServer` (singular) block — distinct from the
        // client-side `mcpServers` block. ServeAsync prefers its inline `mcp` over this.
        MCPServeConfig? mcpServerConfig = null;
        if (opts.McpConfig is IDictionary<string, object?> mcpmc && mcpmc.ContainsKey("mcpServer"))
            mcpServerConfig = ParseMcpServeConfig(mcpmc.Get("mcpServer"));

        return new Toolkit(mcp, skill, builtins, agentTools, extras, a2aConfig, mcpServerConfig);
    }

    /// <summary>Coerce a top-level <c>mcpServer</c> config value (an <see cref="MCPServeConfig"/> or a parsed dict) into an <see cref="MCPServeConfig"/>.</summary>
    private static MCPServeConfig? ParseMcpServeConfig(object? value)
    {
        if (value is MCPServeConfig cfg) return cfg;
        if (value is not IDictionary<string, object?> map) return null;
        var result = new MCPServeConfig
        {
            Name = map.Get("name") as string,
            Version = map.Get("version") as string,
        };
        if (map.Get("tools") is IEnumerable<object?> tools)
            result.Tools = tools.Select(t => t?.ToString() ?? "").ToList();
        return result;
    }

    /// <summary>Coerce a top-level <c>a2a</c> config value (an <see cref="A2AConfig"/> or a parsed dict) into an <see cref="A2AConfig"/>.</summary>
    private static A2AConfig? ParseA2AConfig(object? value)
    {
        if (value is A2AConfig cfg) return cfg;
        if (value is not IDictionary<string, object?> map) return null;
        var result = new A2AConfig
        {
            Name = map.Get("name") as string,
            Description = map.Get("description") as string,
            Version = map.Get("version") as string,
            Store = map.Get("store"),
        };
        if (map.Get("skills") is IEnumerable<object?> skills)
            result.Skills = skills.Select(s => s?.ToString() ?? "").ToList();
        if (map.Get("provider") is IDictionary<string, object?> prov)
            result.Provider = new A2AProvider
            {
                Organization = prov.Get("organization")?.ToString() ?? "",
                Url = prov.Get("url")?.ToString() ?? "",
            };
        return result;
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

    /// <summary>
    /// Fetch a remote A2A agent's card at runtime and register its skills as tools.
    /// First-name-wins dedupe. Chainable.
    /// </summary>
    public async Task<Toolkit> AddAgentAsync(Agent ag)
    {
        var tools = await A2A.AgentTools(ag).ConfigureAwait(false);
        return Register(tools.ToArray());
    }

    /// <summary>Add a remote A2A agent by bare card URL (with optional headers/timeout/pollEvery).</summary>
    public Task<Toolkit> AddAgentAsync(string cardUrl, Agent? opts = null)
    {
        var ag = opts == null
            ? new Agent { Card = cardUrl }
            : new Agent { Card = cardUrl, Headers = opts.Headers, Timeout = opts.Timeout, PollEvery = opts.PollEvery };
        return AddAgentAsync(ag);
    }

    /// <summary>Options for <see cref="ServeAsync"/> — the host loop client plus the optional A2A profile.</summary>
    public sealed class ServeOptions
    {
        /// <summary>The unified LLM client that fulfils inbound tasks (<c>client.Run(text, toolkit)</c>).</summary>
        public LlmClient Client { get; set; } = null!;

        /// <summary>Opt-in A2A profile; falls back to a top-level <c>a2a</c> config block when null.</summary>
        public A2AConfig? A2A { get; set; }

        /// <summary>Fires on each served Task's terminal state with the <see cref="LlmClient.RunResult"/> telemetry.</summary>
        public OnTask? OnTask { get; set; }

        /// <summary>Opt-in MCP inbound profile; mounts a streamable-HTTP MCP server at <c>POST /mcp</c>. Falls back to a top-level <c>mcpServer</c> config block when null.</summary>
        public MCPServeConfig? Mcp { get; set; }

        /// <summary>Fires on each inbound MCP <c>tools/call</c>.</summary>
        public OnCall? OnCall { get; set; }
    }

    /// <summary>
    /// Serve this toolkit as an agent over HTTP. When the <c>a2a</c> profile is present
    /// (inline, or a top-level <c>a2a</c> config block the toolkit was built from), it mounts
    /// the A2A Agent Card (<c>/.well-known/agent-card.json</c>, built from skills) and a
    /// JSON-RPC endpoint (<c>SendMessage</c> + <c>GetTask</c>) fulfilled by the client. A message's
    /// A2A <c>contextId</c> keys the conversation via <c>client.Ask</c>, so a peer's turns are
    /// remembered through the client's <see cref="IConversationStore"/>.
    /// When <c>a2a</c> is absent, no A2A routes are mounted. Returns a stoppable handle.
    /// </summary>
    public Task<ServeHandle> ServeAsync(string addr, ServeOptions opts)
    {
        var a2a = opts.A2A ?? _a2aConfig;
        var mcp = opts.Mcp ?? _mcpServerConfig;
        var skills = _skill != null ? _skill.Skills.Values.ToList() : new List<SkillSource.SkillInfo>();
        return A2AServer.StartAsync(
            addr,
            a2a,
            skills,
            // A message's A2A contextId keys the conversation via client.Ask, so a peer's turns
            // are remembered through the client's IConversationStore; no contextId ⇒ stateless run.
            (text, contextId) => contextId != null
                ? opts.Client.AskAsync(text, this, contextId)
                : opts.Client.RunAsync(text, this),
            opts.OnTask,
            mcp,
            mcp != null ? Tools() : null,
            opts.OnCall);
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
