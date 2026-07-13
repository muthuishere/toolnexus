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

    private McpSource(List<ITool> tools, Dictionary<string, string> status, List<McpClient> clients)
    {
        _tools = tools;
        _status = status;
        _clients = clients;
    }

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
        if (servers is IDictionary<string, object?> sd)
            return new Dictionary<string, object?>(sd);
        // Bare map of servers — but strip sibling top-level config keys (builtins/agents/a2a/
        // mcpServer) so they are not mistaken for MCP servers when no wrapper key is present (SPEC §2).
        var stripped = new Dictionary<string, object?>(raw);
        stripped.Remove("builtins");
        stripped.Remove("agents");
        stripped.Remove("a2a");
        stripped.Remove("mcpServer");
        return stripped;
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
    public static async Task<McpSource> LoadAsync(object input, Func<Request, Task<Answer>>? waitFor = null,
        CancellationToken cancellationToken = default)
    {
        var config = ParseConfig(input);
        var tools = new List<ITool>();
        var status = new Dictionary<string, string>();
        var clients = new List<McpClient>();
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
                try
                {
                    // Bound this server's connect+list by its own timeout, but LINKED to the caller's
                    // token so a parent cancel/deadline propagates through (§2 Gap 3).
                    using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    cts.CancelAfter(TimeSpan.FromMilliseconds(timeout));
                    IClientTransport transport = IsRemote(cfg) ? BuildRemoteTransport(cfg) : BuildLocalTransport(cfg);
                    client = await McpClient.CreateAsync(transport, BuildClientOptions(waitFor), cancellationToken: cts.Token).ConfigureAwait(false);
                    var defs = await client.ListToolsAsync(cancellationToken: cts.Token).ConfigureAwait(false);

                    // §2 Gap 7: per-server allowlist keyed on ORIGINAL tool name, applied before convert.
                    var kept = ApplyToolsFilter(name, defs, d => d.ProtocolTool.Name, ToolsFilterOf(cfg));
                    var converted = new List<ITool>();
                    foreach (var def in kept)
                        converted.Add(new McpTool(name, def));

                    lock (gate)
                    {
                        tools.AddRange(converted);
                        clients.Add(client);
                        status[name] = "connected";
                    }
                }
                catch (Exception e)
                {
                    lock (gate) status[name] = "failed";
                    if (client != null)
                    {
                        try { await client.DisposeAsync().ConfigureAwait(false); } catch { }
                    }
                    Console.Error.WriteLine($"[toolnexus] MCP server \"{name}\" failed: {e.Message}");
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
            cancellationToken.ThrowIfCancellationRequested();
        }
        return new McpSource(tools, status, clients);
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
                try
                {
                    using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    cts.CancelAfter(TimeSpan.FromMilliseconds(timeout));
                    IClientTransport transport = IsRemote(cfg) ? BuildRemoteTransport(cfg) : BuildLocalTransport(cfg);
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

    private static IClientTransport BuildLocalTransport(IDictionary<string, object?> cfg)
    {
        if (cfg.Get("command") is not IEnumerable<object?> commandList)
            throw new ArgumentException("local server has no command");
        var command = commandList.Select(o => o?.ToString() ?? "").ToList();
        if (command.Count == 0) throw new ArgumentException("local server has no command");

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

    private static IClientTransport BuildRemoteTransport(IDictionary<string, object?> cfg)
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

        return new HttpClientTransport(opts);
    }

    /// <summary>A single MCP tool wrapped as a uniform <see cref="ITool"/>.</summary>
    private sealed class McpTool : ITool
    {
        private readonly string _server;
        private readonly McpClientTool _def;
        private readonly string _mcpName;

        public string Name { get; }
        public string Description { get; }
        public IDictionary<string, object?> InputSchema { get; }
        public string Source => "mcp";

        public McpTool(string server, McpClientTool def)
        {
            _server = server;
            _def = def;
            _mcpName = def.ProtocolTool.Name;
            Name = global::Toolnexus.Tools.Sanitize(server) + "_" + global::Toolnexus.Tools.Sanitize(_mcpName);
            Description = def.Description ?? "";

            InputSchema = BuildInputSchema(def);
        }

        public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            var meta = new Dictionary<string, object?> { ["server"] = _server };
            try
            {
                var argMap = (IReadOnlyDictionary<string, object?>)(args ?? new Dictionary<string, object?>());
                var result = await _def.CallAsync(argMap, cancellationToken: ctx?.CancellationToken ?? default)
                    .ConfigureAwait(false);

                if (result.IsError == true)
                    return new ToolResult(FormatToolError(result.Content), true, meta);

                if (result.StructuredContent is { ValueKind: not JsonValueKind.Null and not JsonValueKind.Undefined } sc)
                    return new ToolResult(Json.Stringify(Json.FromElement(sc)), false, meta);

                return new ToolResult(JoinTextContent(result.Content), false, meta);
            }
            catch (Exception e)
            {
                return new ToolResult(e.Message ?? e.ToString(), true, meta);
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
