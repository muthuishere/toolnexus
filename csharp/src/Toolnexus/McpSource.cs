using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;

namespace Toolnexus;

/// <summary>
/// Dynamic MCP source. Mirrors opencode's mcp/index.ts: connect to every configured
/// server (stdio for local, streamable-HTTP for remote), list tools, and convert
/// each into a uniform <see cref="ITool"/>. Failures are isolated — one bad server
/// never breaks the toolkit. Built on the official <c>ModelContextProtocol</c> SDK.
/// </summary>
public sealed partial class McpSource : IAsyncDisposable
{
    private const long DefaultTimeout = 30_000L;

    [GeneratedRegex(@"\$\{([A-Za-z0-9_]+)\}")]
    private static partial Regex EnvVar();

    // -----------------------------------------------------------------------
    // MCP elicitation bridge (§10). A server can ask US for input mid-`tools/call`
    // (a reverse-request). We map it onto the one `waitFor`: form mode → kind:"input",
    // URL mode → kind:"authorization". The two mappings are pure + byte-parity-tested.
    // -----------------------------------------------------------------------

    private static long _elcSeq;

    /// <summary>Map an MCP <c>elicitation/create</c> request onto a §10 <see cref="Request"/>.</summary>
    public static Request ElicitationToRequest(ElicitRequestParams p)
    {
        var isUrl = p.Mode == "url";
        var req = new Request
        {
            Id = $"elc-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds():x}-{Interlocked.Increment(ref _elcSeq)}",
            Kind = isUrl ? "authorization" : "input",
            Prompt = p.Message ?? "",
            Url = isUrl && p.Url != null ? p.Url : null,
            Data = !isUrl && p.RequestedSchema != null
                ? new Dictionary<string, object?> { ["schema"] = p.RequestedSchema }
                : null,
        };
        return req;
    }

    /// <summary>
    /// Map a resolved §10 <see cref="Answer"/> back onto an MCP <see cref="ElicitResult"/>.
    /// <c>ok</c>→accept; <c>reason:"declined"</c>→decline; else→cancel.
    /// </summary>
    public static ElicitResult AnswerToElicitResult(Answer answer)
    {
        if (answer.Ok)
        {
            var content = new Dictionary<string, JsonElement>();
            if (answer.Data != null)
                foreach (var (k, v) in answer.Data)
                    content[k] = JsonSerializer.SerializeToElement(v);
            return new ElicitResult { Action = "accept", Content = content };
        }
        return new ElicitResult { Action = answer.Reason == "declined" ? "decline" : "cancel" };
    }

    private readonly List<ITool> _tools;
    private readonly Dictionary<string, string> _status;
    private readonly List<McpClient> _clients;

    /// <summary>The connected clients by server name, with each server's timeout (for <see cref="ResourceTools"/>).</summary>
    private readonly Dictionary<string, (McpClient Client, long TimeoutMs)> _servers;

    /// <summary>Servers on the MCP 2026-07-28 path (SPEC §2.2), disposed with the source.</summary>
    private readonly List<Mcp2026.Server26> _servers26;

    private McpSource(List<ITool> tools, Dictionary<string, string> status, List<McpClient> clients,
        Dictionary<string, (McpClient Client, long TimeoutMs)> servers,
        List<Mcp2026.Server26>? servers26 = null, List<SkillSource.SkillDef>? remoteSkills = null)
    {
        _tools = tools;
        _status = status;
        _clients = clients;
        _servers = servers;
        _servers26 = servers26 ?? new();
        RemoteSkills = remoteSkills ?? new();
    }

    /// <summary>
    /// (SPEC §2.7) Skills published by 2026-07-28 servers through the skills extension, as data skills
    /// (<c>skill://&lt;name&gt;/</c> base). <see cref="Toolkit"/> merges them into the one <c>skill</c> tool.
    /// </summary>
    public IReadOnlyList<SkillSource.SkillDef> RemoteSkills { get; }

    public IReadOnlyList<ITool> Tools => _tools;

    /// <summary>server -&gt; "connected" | "disabled" | "failed".</summary>
    public IReadOnlyDictionary<string, string> Status => _status;

    public async ValueTask DisposeAsync()
    {
        foreach (var c in _clients)
        {
            try { await c.DisposeAsync().ConfigureAwait(false); }
            catch { /* best-effort teardown */ }
        }
        foreach (var s in _servers26)
        {
            try { await s.DisposeAsync().ConfigureAwait(false); }
            catch { /* best-effort teardown */ }
        }
    }

    /// <summary>Accept a path, a raw JSON string, or a parsed config dictionary.</summary>
    public static Dictionary<string, object?> ParseConfig(object input)
    {
        Dictionary<string, object?> raw;
        if (input is IDictionary<string, object?> map)
        {
            raw = new Dictionary<string, object?>(map);
        }
        else
        {
            var s = input.ToString() ?? "";
            string text = File.Exists(s) ? File.ReadAllText(s) : s;
            raw = Json.ToMap(text);
        }

        var servers = raw.Get("mcpServers")
                      ?? raw.Get("servers")
                      ?? raw.Get("mcp");
        Dictionary<string, object?> result;
        if (servers is IDictionary<string, object?> sd)
        {
            result = new Dictionary<string, object?>(sd);
        }
        else
        {
            // Bare map of servers — but strip sibling top-level config keys (builtins/agents/a2a/
            // mcpServer) so they are not mistaken for MCP servers when no wrapper key is present (SPEC §2).
            result = new Dictionary<string, object?>(raw);
            result.Remove("builtins");
            result.Remove("agents");
            result.Remove("a2a");
            result.Remove("mcpServer");
        }
        // SPEC §2 `command` + `args`: a malformed local command fails the parse, naming the server.
        foreach (var (name, value) in result)
        {
            if (value is IDictionary<string, object?> cfg && !IsRemote(cfg) && cfg.ContainsKey("command"))
                ResolveCommand(name, cfg);
        }
        return result;
    }

    /// <summary>
    /// SPEC §2 <c>command</c> + <c>args</c>: <c>command</c> is a non-empty string or a non-empty
    /// array of strings; an optional <c>args</c> array of strings is appended. Returns the argv
    /// (spawned with no shell). Throws <see cref="ArgumentException"/> naming the server otherwise.
    /// </summary>
    public static List<string> ResolveCommand(string name, IDictionary<string, object?> cfg)
    {
        var argv = new List<string>();
        switch (cfg.Get("command"))
        {
            case string s when s.Length > 0:
                argv.Add(s);
                break;
            case IEnumerable<object?> list:
                foreach (var o in list)
                {
                    if (o is not string part)
                        throw new ArgumentException($"mcp server \"{name}\": invalid command (non-string element)");
                    argv.Add(part);
                }
                if (argv.Count == 0)
                    throw new ArgumentException($"mcp server \"{name}\": invalid command (empty)");
                break;
            default:
                throw new ArgumentException($"mcp server \"{name}\": invalid command");
        }
        if (cfg.TryGetValue("args", out var args) && args != null)
        {
            if (args is string || args is not IEnumerable<object?> argList)
                throw new ArgumentException($"mcp server \"{name}\": invalid args (must be an array of strings)");
            foreach (var o in argList)
            {
                if (o is not string a)
                    throw new ArgumentException($"mcp server \"{name}\": invalid args (non-string element)");
                argv.Add(a);
            }
        }
        return argv;
    }


    /// <summary>
    /// Expand <c>${ENV_VAR}</c> in each header VALUE from the environment so tokens
    /// live in the environment, not the committed config. Returns a new dict;
    /// <c>null</c> in → <c>null</c> out. Missing vars expand to <c>""</c>. Values are
    /// never logged.
    /// </summary>
    public static Dictionary<string, string>? ExpandEnvHeaders(IDictionary<string, string>? headers)
        => ExpandEnvHeaders(headers, Environment.GetEnvironmentVariable);

    /// <summary>Same as <see cref="ExpandEnvHeaders(IDictionary{string,string})"/> but with a pluggable lookup.</summary>
    public static Dictionary<string, string>? ExpandEnvHeaders(
        IDictionary<string, string>? headers, Func<string, string?> lookup)
    {
        if (headers == null) return null;
        var output = new Dictionary<string, string>();
        foreach (var (key, value) in headers)
        {
            if (value == null)
            {
                output[key] = null!;
                continue;
            }
            output[key] = EnvVar().Replace(value, m => lookup(m.Groups[1].Value) ?? "");
        }
        return output;
    }

    private static bool IsRemote(IDictionary<string, object?> cfg)
    {
        var type = cfg.Get("type") as string;
        if (type == "remote") return true;
        if (type == "local") return false;
        return cfg.Get("url") is string;
    }

    private static bool IsEnabled(IDictionary<string, object?> cfg)
    {
        if (cfg.Get("disabled") is true) return false;
        if (cfg.Get("enabled") is false) return false;
        return true;
    }

    private static long TimeoutOf(IDictionary<string, object?> cfg)
        => cfg.Get("timeout") switch
        {
            long l => l,
            int i => i,
            double d => (long)d,
            _ => DefaultTimeout,
        };

    /// <summary>Connect to every enabled server, list + convert tools. Failures are isolated.</summary>
    /// <param name="input">Path string, raw JSON string, or parsed config dictionary.</param>
    /// <param name="waitFor">
    /// Host resolver for out-of-band input (§10). When set, connected MCP servers may elicit input
    /// from the human mid-<c>tools/call</c> and it is bridged onto this <c>waitFor</c> (form→kind:"input",
    /// URL→kind:"authorization"). Omit ⇒ the elicitation capability is not advertised (clean degrade).
    /// </param>
    /// <param name="cancellationToken">
    /// (§2 Gap 3) The caller's token propagates through connect, list, and the SSE fallback start;
    /// each server's work is additionally bounded by its own <c>timeout</c>. A per-server timeout
    /// within budget marks only that server <c>failed</c> and the build continues, but parent-token
    /// cancellation/deadline aborts the WHOLE load and throws <see cref="OperationCanceledException"/>.
    /// </param>
    public static Task<McpSource> LoadAsync(object input, Func<Request, Task<Answer>>? waitFor = null,
        CancellationToken cancellationToken = default)
        => LoadAsync(input, new McpLoadOptions { WaitFor = waitFor }, cancellationToken);

    /// <summary>
    /// (SPEC §4C) <see cref="LoadAsync(object, Func{Request, Task{Answer}}?, CancellationToken)"/> plus
    /// per-server readiness: <see cref="McpLoadOptions.OnServer"/> fires once per server the moment it is
    /// connected, failed or found disabled — never waiting for a slower server — so a host can
    /// <see cref="Toolkit.Register"/> a fast server's tools while a slow one is still connecting. The
    /// load still returns only after every server finishes; parent cancellation still aborts the load
    /// and disposes every client, including ones whose tools were already handed out.
    /// </summary>
    public static async Task<McpSource> LoadAsync(object input, McpLoadOptions options,
        CancellationToken cancellationToken = default)
    {
        var waitFor = options.WaitFor;
        var config = ParseConfig(input);
        var tools = new List<ITool>();
        var status = new Dictionary<string, string>();
        var clients = new List<McpClient>();
        var servers = new Dictionary<string, (McpClient Client, long TimeoutMs)>();
        var servers26 = new List<Mcp2026.Server26>();
        var remoteSkills = new List<SkillSource.SkillDef>();
        var store = options.TokenStore ?? new MemoryTokenStore();
        var gate = new object();
        var cbGate = new object();
        void Report(McpServerResult r)
        {
            if (options.OnServer == null) return;
            lock (cbGate) options.OnServer(r); // serialized: never concurrent with itself
        }

        var tasks = new List<Task>();
        foreach (var (name, value) in config)
        {
            if (value is not IDictionary<string, object?> cfg) continue;

            tasks.Add(Task.Run(async () =>
            {
                if (!IsEnabled(cfg))
                {
                    lock (gate) status[name] = "disabled";
                    Report(new McpServerResult(name, "disabled", Array.Empty<ITool>(), null));
                    return;
                }
                var timeout = TimeoutOf(cfg);
                McpClient? client = null;
                Mcp2026.Server26? s26 = null;
                try
                {
                    // SPEC §2.2: discover first; a 2026-07-28 server never reaches the SDK path below.
                    var auth = IsRemote(cfg) ? new Mcp2026.OAuthClient(Mcp2026.AuthConfig.From(cfg.Get("auth")), store, options.OpenUrl) : null;
                    s26 = await Open26Async(name, cfg, timeout, waitFor, auth, cancellationToken).ConfigureAwait(false);
                    if (s26 != null)
                    {
                        var converted26 = await Tools26Async(name, cfg, s26, cancellationToken).ConfigureAwait(false);
                        var skills26 = s26.HasSkills ? await s26.LoadSkillsAsync(cancellationToken).ConfigureAwait(false) : new();
                        lock (gate)
                        {
                            tools.AddRange(converted26);
                            remoteSkills.AddRange(skills26);
                            servers26.Add(s26);
                            status[name] = "connected";
                        }
                        Report(new McpServerResult(name, "connected", converted26, null));
                        return;
                    }

                    // Frozen fallback (SPEC §2.F), unchanged except OAuth (§2A) on its HTTP transport.
                    // Bound this server's connect+list by its own timeout, but LINKED to the caller's
                    // token so a parent cancel/deadline propagates through (§2 Gap 3).
                    using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    cts.CancelAfter(TimeSpan.FromMilliseconds(timeout));
                    IClientTransport transport = IsRemote(cfg) ? BuildRemoteTransport(cfg, auth, waitFor) : BuildLocalTransport(name, cfg);
                    client = await McpClient.CreateAsync(transport, BuildClientOptions(waitFor), cancellationToken: cts.Token).ConfigureAwait(false);
                    var defs = await client.ListToolsAsync(cancellationToken: cts.Token).ConfigureAwait(false);

                    // §2 Gap 7: per-server allowlist keyed on ORIGINAL tool name, applied before convert.
                    var kept = ApplyToolsFilter(name, defs, d => d.ProtocolTool.Name, ToolsFilterOf(cfg));
                    var converted = new List<ITool>();
                    foreach (var def in kept)
                        converted.Add(new McpTool(name, def, timeout));

                    lock (gate)
                    {
                        tools.AddRange(converted);
                        clients.Add(client);
                        servers[name] = (client, timeout);
                        status[name] = "connected";
                    }
                    Report(new McpServerResult(name, "connected", converted, null));
                }
                catch (Exception e)
                {
                    lock (gate) status[name] = "failed";
                    if (client != null)
                    {
                        try { await client.DisposeAsync().ConfigureAwait(false); } catch { }
                    }
                    if (s26 != null)
                    {
                        try { await s26.DisposeAsync().ConfigureAwait(false); } catch { }
                    }
                    Console.Error.WriteLine($"[toolnexus] MCP server \"{name}\" failed: {e.Message}");
                    Report(new McpServerResult(name, "failed", Array.Empty<ITool>(), e));
                }
            }));
        }

        await Task.WhenAll(tasks).ConfigureAwait(false);

        // Parent cancellation/deadline aborts the whole build (§2 Gap 3, A1): release any clients
        // that did connect and surface the cancellation.
        if (cancellationToken.IsCancellationRequested)
        {
            foreach (var c in clients)
            {
                try { await c.DisposeAsync().ConfigureAwait(false); } catch { }
            }
            foreach (var s in servers26)
            {
                try { await s.DisposeAsync().ConfigureAwait(false); } catch { }
            }
            cancellationToken.ThrowIfCancellationRequested();
        }
        return new McpSource(tools, status, clients, servers, servers26, remoteSkills);
    }

    /// <summary>
    /// (§2 Gap 7) Read a server config's <c>tools</c> allowlist into a name→bool map, or <c>null</c>
    /// when absent. Keyed on ORIGINAL (unprefixed) tool names.
    /// </summary>
    private static IReadOnlyDictionary<string, bool>? ToolsFilterOf(IDictionary<string, object?> cfg)
    {
        if (cfg.Get("tools") is not IDictionary<string, object?> raw) return null;
        var filter = new Dictionary<string, bool>();
        foreach (var (k, v) in raw) filter[k] = v is true || (v is string s && s == "true");
        return filter;
    }

    /// <summary>
    /// (§2 Gap 7) Apply a per-server tool allowlist to listed defs — SAME semantics as the
    /// builtins/skills filter: <c>null</c>/empty ⇒ all; ≥1 <c>true</c> entry ⇒ allowlist (only
    /// true-mapped names); only-<c>false</c> entries ⇒ drop-list over the all-on baseline; unknown
    /// names are ignored but warned once. Keyed on the ORIGINAL tool name via <paramref name="nameOf"/>.
    /// </summary>
    internal static List<T> ApplyToolsFilter<T>(string server, IEnumerable<T> source, Func<T, string> nameOf,
        IReadOnlyDictionary<string, bool>? filter)
    {
        var defs = source.ToList();
        if (filter == null || filter.Count == 0) return defs;
        var hasTrue = filter.Values.Any(v => v);
        var present = new HashSet<string>(defs.Select(nameOf));
        foreach (var k in filter.Keys)
            if (!present.Contains(k))
                Console.Error.WriteLine($"[toolnexus] server \"{server}\" tools filter name \"{k}\" matched no tool");
        var output = new List<T>();
        foreach (var d in defs)
        {
            var found = filter.TryGetValue(nameOf(d), out var v);
            var keep = hasTrue ? (found && v) : !(found && !v);
            if (keep) output.Add(d);
        }
        return output;
    }

    /// <summary>
    /// Build a tool's uniform input schema from a listed MCP def: spread the server schema, then
    /// force <c>type:"object"</c>, default <c>properties</c> to <c>{}</c>, and
    /// <c>additionalProperties:false</c>. Shared by the converted <c>McpTool</c> and the Gap 6 inventory.
    /// </summary>
    internal static Dictionary<string, object?> BuildInputSchema(McpClientTool def)
    {
        var schema = def.JsonSchema.ValueKind == JsonValueKind.Object
            ? (Dictionary<string, object?>)Json.FromElement(def.JsonSchema)!
            : new Dictionary<string, object?>();
        schema["type"] = "object";
        schema["properties"] = schema.Get("properties") ?? new Dictionary<string, object?>();
        schema["additionalProperties"] = false;
        return schema;
    }

    /// <summary>
    /// (§2 Gap 6) One listed tool definition — the ORIGINAL, unprefixed name plus its description
    /// and built input schema.
    /// </summary>
    public sealed record ToolInfo(string Name, string Description, IDictionary<string, object?> InputSchema);

    /// <summary>(§2 Gap 6) The result of a list-only pass over an MCP config.</summary>
    public sealed class McpInventory
    {
        /// <summary>server → its full listed tool defs, UNFILTERED by the per-server <c>tools</c>
        /// allowlist (it exists to author/validate those allowlists).</summary>
        public required IReadOnlyDictionary<string, IReadOnlyList<ToolInfo>> Tools { get; init; }

        /// <summary>server → "connected" | "disabled" | "failed".</summary>
        public required IReadOnlyDictionary<string, string> Status { get; init; }
    }

    /// <summary>
    /// (§2 Gap 6) Connect to every enabled server, list tool definitions, and DISCONNECT before
    /// returning — no toolkit, no <c>Execute</c> wiring, nothing left running. ctx-aware per Gap 3.
    /// The returned inventory is UNFILTERED by any per-server <c>tools</c> allowlist. Failure
    /// isolation as in <see cref="LoadAsync"/>: a bad server is <c>failed</c>, never an error for
    /// the whole call.
    /// </summary>
    public static async Task<McpInventory> ListMcpToolsAsync(object input, CancellationToken cancellationToken = default)
    {
        var config = ParseConfig(input);
        var byServer = new Dictionary<string, IReadOnlyList<ToolInfo>>();
        var status = new Dictionary<string, string>();
        var gate = new object();

        var tasks = new List<Task>();
        foreach (var (name, value) in config)
        {
            if (value is not IDictionary<string, object?> cfg) continue;

            tasks.Add(Task.Run(async () =>
            {
                if (!IsEnabled(cfg))
                {
                    lock (gate) status[name] = "disabled";
                    return;
                }
                var timeout = TimeoutOf(cfg);
                McpClient? client = null;
                Mcp2026.Server26? s26 = null;
                try
                {
                    var auth = IsRemote(cfg) ? new Mcp2026.OAuthClient(Mcp2026.AuthConfig.From(cfg.Get("auth")), new MemoryTokenStore(), null) : null;
                    s26 = await Open26Async(name, cfg, timeout, null, auth, cancellationToken).ConfigureAwait(false);
                    if (s26 != null)
                    {
                        var defs26 = await s26.ListToolsAsync(cancellationToken).ConfigureAwait(false);
                        var infos26 = defs26.Select(d => new ToolInfo(Mcp2026.J.Str(d["name"], ""), Mcp2026.J.Str(d["description"], ""),
                            Mcp2026.McpTool26.BuildInputSchema(d["inputSchema"]))).ToList();
                        lock (gate)
                        {
                            byServer[name] = infos26;
                            status[name] = "connected";
                        }
                        return;
                    }
                    using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    cts.CancelAfter(TimeSpan.FromMilliseconds(timeout));
                    IClientTransport transport = IsRemote(cfg) ? BuildRemoteTransport(cfg, auth, null) : BuildLocalTransport(name, cfg);
                    client = await McpClient.CreateAsync(transport, null, cancellationToken: cts.Token).ConfigureAwait(false);
                    var defs = await client.ListToolsAsync(cancellationToken: cts.Token).ConfigureAwait(false);
                    var infos = defs
                        .Select(d => new ToolInfo(d.ProtocolTool.Name, d.Description ?? "", BuildInputSchema(d)))
                        .ToList();
                    lock (gate)
                    {
                        byServer[name] = infos;
                        status[name] = "connected";
                    }
                }
                catch (Exception e)
                {
                    lock (gate) status[name] = "failed";
                    Console.Error.WriteLine($"[toolnexus] MCP server \"{name}\" failed: {e.Message}");
                }
                finally
                {
                    // Disconnect before returning — nothing left running (§2 Gap 6).
                    if (client != null)
                    {
                        try { await client.DisposeAsync().ConfigureAwait(false); } catch { }
                    }
                    if (s26 != null)
                    {
                        try { await s26.DisposeAsync().ConfigureAwait(false); } catch { }
                    }
                }
            }));
        }

        await Task.WhenAll(tasks).ConfigureAwait(false);
        if (cancellationToken.IsCancellationRequested)
            cancellationToken.ThrowIfCancellationRequested();
        return new McpInventory { Tools = byServer, Status = status };
    }

    /// <summary>
    /// Advertise elicitation + register the bridge ONLY when a <c>waitFor</c> exists to satisfy it —
    /// a waitFor-less host degrades cleanly (the server won't elicit). (§10 elicitation bridge)
    /// </summary>
    private static McpClientOptions? BuildClientOptions(Func<Request, Task<Answer>>? waitFor)
    {
        if (waitFor == null) return null;
        return new McpClientOptions
        {
            ClientInfo = new Implementation { Name = "toolnexus", Version = "0.1.0" },
            Capabilities = new ClientCapabilities
            {
                Elicitation = new ElicitationCapability { Form = new(), Url = new() },
            },
            Handlers = new McpClientHandlers
            {
                ElicitationHandler = async (p, ct) =>
                    AnswerToElicitResult(await waitFor(ElicitationToRequest(p!)).ConfigureAwait(false)),
            },
        };
    }

    private static IClientTransport BuildLocalTransport(string name, IDictionary<string, object?> cfg)
    {
        if (!cfg.ContainsKey("command"))
            throw new ArgumentException("local server has no command");
        var command = ResolveCommand(name, cfg);

        var opts = new StdioClientTransportOptions
        {
            Command = command[0],
            Arguments = command.Skip(1).ToList(),
            // Merge process env (InheritEnvironmentVariables) with configured environment/env.
            InheritEnvironmentVariables = true,
        };

        var extra = cfg.Get("environment") as IDictionary<string, object?>
                    ?? cfg.Get("env") as IDictionary<string, object?>;
        if (extra != null)
        {
            var env = new Dictionary<string, string?>();
            foreach (var (k, v) in extra) env[k] = v?.ToString() ?? "";
            opts.EnvironmentVariables = env;
        }

        if (cfg.Get("cwd") is string cwd)
            opts.WorkingDirectory = cwd;

        return new StdioClientTransport(opts);
    }

    /// <summary>
    /// SPEC §2.2/§2.2a: open a server on the 2026-07-28 path. Returns <c>null</c> when it must fall back to the
    /// frozen SDK path (the stdio probe child is terminated first); throws on a load failure.
    /// </summary>
    private static async Task<Mcp2026.Server26?> Open26Async(string name, IDictionary<string, object?> cfg, long timeout,
        Func<Request, Task<Answer>>? waitFor, Mcp2026.OAuthClient? auth, CancellationToken ct)
    {
        Mcp2026.Conn conn;
        if (IsRemote(cfg))
        {
            var headers = new Dictionary<string, string>();
            if (cfg.Get("headers") is IDictionary<string, object?> raw)
            {
                var stringified = new Dictionary<string, string>();
                foreach (var (k, v) in raw) stringified[k] = v?.ToString() ?? "";
                headers = ExpandEnvHeaders(stringified)!;
            }
            conn = new Mcp2026.Conn(cfg.Get("url")?.ToString() ?? "") { Headers = headers, Auth = auth };
        }
        else
        {
            if (!cfg.ContainsKey("command")) throw new ArgumentException("local server has no command");
            var env = new Dictionary<string, string>();
            if ((cfg.Get("environment") ?? cfg.Get("env")) is IDictionary<string, object?> extra)
                foreach (var (k, v) in extra) env[k] = v?.ToString() ?? "";
            conn = Mcp2026.Conn.StartStdio(ResolveCommand(name, cfg), env, cfg.Get("cwd") as string);
        }
        var s = new Mcp2026.Server26(name, conn, timeout, waitFor)
        {
            ToolSearch = cfg.Get("toolSearch") is true,
            Description = cfg.Get("description") as string ?? "",
        };
        try
        {
            await s.DiscoverAsync(ct).ConfigureAwait(false);
            return s;
        }
        catch (Mcp2026.FallbackException)
        {
            await s.DisposeAsync().ConfigureAwait(false);
            return null;
        }
        catch
        {
            await s.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    /// <summary>The toolkit tools for a 2026-path server: every (filtered) server tool, or the two tool-search tools.</summary>
    private static async Task<List<ITool>> Tools26Async(string name, IDictionary<string, object?> cfg, Mcp2026.Server26 s, CancellationToken ct)
    {
        var defs = await s.ListToolsAsync(ct).ConfigureAwait(false);
        if (s.ToolSearch) return new List<ITool> { new Mcp2026.SearchTool26(s, false), new Mcp2026.SearchTool26(s, true) };
        var kept = ApplyToolsFilter(name, defs, d => Mcp2026.J.Str(d["name"], ""), ToolsFilterOf(cfg));
        return kept.Select(d => (ITool)new Mcp2026.McpTool26(s, d)).ToList();
    }

    private static IClientTransport BuildRemoteTransport(IDictionary<string, object?> cfg,
        Mcp2026.OAuthClient? auth = null, Func<Request, Task<Answer>>? waitFor = null)
    {
        var url = cfg.Get("url")?.ToString() ?? "";
        var opts = new HttpClientTransportOptions
        {
            Endpoint = new Uri(url),
            // AutoDetect = streamable-HTTP with SSE fallback.
            TransportMode = HttpTransportMode.AutoDetect,
        };

        if (cfg.Get("headers") is IDictionary<string, object?> rawHeaders)
        {
            var stringified = new Dictionary<string, string>();
            foreach (var (k, v) in rawHeaders)
                stringified[k] = v?.ToString() ?? "";
            // Expand ${ENV_VAR} before the values reach the transport (never logged).
            opts.AdditionalHeaders = ExpandEnvHeaders(stringified)!;
        }

        if (auth == null) return new HttpClientTransport(opts);
        // SPEC §2A.1a: the bearer rides the SDK's HTTP transport through a delegating handler (dynamic per request).
        var http = new HttpClient(new Mcp2026.BearerHandler(auth, url, waitFor) { InnerHandler = new HttpClientHandler() })
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };
        return new HttpClientTransport(opts, http, null, ownsHttpClient: true);
    }

    /// <summary>A single MCP tool wrapped as a uniform <see cref="ITool"/>.</summary>
    /// <summary>
    /// §0.4: map every non-text <c>content[]</c> entry to a <see cref="ContentPart"/> —
    /// image ⇒ <c>image</c>, audio ⇒ <c>audio</c>, <c>resource_link</c> ⇒ <c>file{url}</c>,
    /// an embedded resource carrying a blob ⇒ <c>file{data}</c>; an embedded resource
    /// carrying text is appended to <c>output</c> instead. Nothing is dropped silently.
    /// <para>Base64 is taken <b>verbatim</b> from the SDK: <c>.Data</c>/<c>.Blob</c> already
    /// hold the server's base64 (as UTF-8 bytes, in this SDK), so we only widen them back to a
    /// string. We never round-trip through <c>.DecodedData</c> — .NET's encoder must not drift
    /// the seven-port byte parity.</para>
    /// </summary>
    internal static IReadOnlyList<ContentPart>? MapParts(IList<ContentBlock>? content, out List<string> resourceText)
    {
        resourceText = new List<string>();
        if (content == null) return null;
        var parts = new List<ContentPart>();
        foreach (var block in content)
        {
            switch (block)
            {
                case TextContentBlock:
                    break;
                case ImageContentBlock img:
                    parts.Add(new ContentPart { Type = "image", MimeType = img.MimeType, Data = B64(img.Data) });
                    break;
                case AudioContentBlock aud:
                    parts.Add(new ContentPart { Type = "audio", MimeType = aud.MimeType, Data = B64(aud.Data) });
                    break;
                case ResourceLinkBlock link:
                    parts.Add(new ContentPart
                    {
                        Type = "file",
                        MimeType = link.MimeType ?? "application/octet-stream",
                        Url = link.Uri,
                        Name = link.Name,
                    });
                    break;
                case EmbeddedResourceBlock res:
                    switch (res.Resource)
                    {
                        case BlobResourceContents blob:
                            parts.Add(new ContentPart
                            {
                                Type = ContentPart.TypeForMime(blob.MimeType ?? ""),
                                MimeType = blob.MimeType ?? "application/octet-stream",
                                Data = B64(blob.Blob),
                                Name = blob.Uri,
                            });
                            break;
                        case TextResourceContents txt:
                            if (!string.IsNullOrEmpty(txt.Text)) resourceText.Add(txt.Text!);
                            break;
                    }
                    break;
            }
        }
        return parts.Count == 0 ? null : parts;
    }

    /// <summary>The SDK holds base64 as UTF-8 bytes; widen it to the string verbatim.</summary>
    internal static string B64(ReadOnlyMemory<byte> b64) => System.Text.Encoding.UTF8.GetString(b64.Span);

    private sealed class McpTool : ITool
    {
        private readonly string _server;
        private readonly McpClientTool _def;
        private readonly string _mcpName;

        public string Name { get; }
        public string Description { get; }
        public IDictionary<string, object?> InputSchema { get; }
        public string Source => "mcp";
        public ToolHints? Hints { get; }

        private readonly long _timeoutMs;

        public McpTool(string server, McpClientTool def, long timeoutMs)
        {
            _server = server;
            _timeoutMs = timeoutMs;
            _def = def;
            _mcpName = def.ProtocolTool.Name;
            Name = global::Toolnexus.Tools.Sanitize(server) + "_" + global::Toolnexus.Tools.Sanitize(_mcpName);
            Description = def.Description ?? "";

            InputSchema = BuildInputSchema(def);
            var a = def.ProtocolTool.Annotations;
            Hints = McpHints(a?.ReadOnlyHint, a?.DestructiveHint, a?.OpenWorldHint);
        }

        public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            var meta = new Dictionary<string, object?> { ["server"] = _server };
            try
            {
                var argMap = (IReadOnlyDictionary<string, object?>)(args ?? new Dictionary<string, object?>());
                // SPEC §2 idle timeout: the server `timeout` bounds SILENCE, not total duration.
                // Passing an IProgress makes the SDK attach a fresh per-call progressToken and route
                // only notifications matching THAT token to it; each one re-arms the budget.
                var caller = ctx?.CancellationToken ?? default;
                using var idle = CancellationTokenSource.CreateLinkedTokenSource(caller);
                var budget = TimeSpan.FromMilliseconds(_timeoutMs);
                idle.CancelAfter(budget);
                var progress = new IdleReset(idle, budget);
                CallToolResult result;
                try
                {
                    result = await _def.CallAsync(argMap, progress, cancellationToken: idle.Token)
                        .ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!caller.IsCancellationRequested && idle.IsCancellationRequested)
                {
                    // Caller cancellation wins: only an idle expiry with a live caller token is a timeout.
                    return new ToolResult(
                        $"MCP tool \"{_mcpName}\" on server \"{_server}\" timed out after {_timeoutMs}ms without progress",
                        true, meta);
                }

                // §0.4 / D5: collect the non-text content parts BEFORE any branch returns —
                // the structuredContent short-circuit and the error path would otherwise each
                // reintroduce a silent drop.
                var parts = McpSource.MapParts(result.Content, out var resourceText);

                if (result.IsError == true)
                    return new ToolResult(FormatToolError(result.Content), true, meta, parts);

                if (result.StructuredContent is { ValueKind: not JsonValueKind.Null and not JsonValueKind.Undefined } sc)
                    return new ToolResult(Json.Stringify(Json.FromElement(sc)), false, meta, parts);

                var text = JoinTextContent(result.Content);
                if (resourceText.Count > 0)
                    text = text.Length == 0 ? string.Join("\n", resourceText) : text + "\n" + string.Join("\n", resourceText);
                // An image-only result is not the empty string: name what came back (§0.4).
                if (text.Length == 0 && parts != null)
                    text = string.Join("\n", parts.Select(p => p.DescribeInText()));
                return new ToolResult(text, false, meta, parts);
            }
            catch (Exception e)
            {
                return new ToolResult(e.Message ?? e.ToString(), true, meta);
            }
        }

        /// <summary>Synchronous progress sink: re-arms the idle budget on each matching notification.</summary>
        private sealed class IdleReset(CancellationTokenSource cts, TimeSpan budget) : IProgress<ModelContextProtocol.ProgressNotificationValue>
        {
            public void Report(ModelContextProtocol.ProgressNotificationValue value)
            {
                try { cts.CancelAfter(budget); } catch (ObjectDisposedException) { }
            }
        }

        private static string JoinTextContent(IList<ContentBlock>? content)
        {
            if (content == null) return "";
            return string.Join("\n", content.OfType<TextContentBlock>().Select(c => c.Text));
        }

        private static string FormatToolError(IList<ContentBlock>? content)
        {
            if (content == null) return "MCP tool returned an error";
            var parts = content.OfType<TextContentBlock>()
                .Select(c => c.Text)
                .Where(t => !string.IsNullOrWhiteSpace(t))
                .ToList();
            var joined = string.Join("\n\n", parts);
            return joined.Length == 0 ? "MCP tool returned an error" : joined;
        }
    }
}
