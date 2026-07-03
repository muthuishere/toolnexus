using System.Text.Json;
using System.Text.RegularExpressions;
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
    public static async Task<McpSource> LoadAsync(object input)
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
                    using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(timeout));
                    IClientTransport transport = IsRemote(cfg) ? BuildRemoteTransport(cfg) : BuildLocalTransport(cfg);
                    client = await McpClient.CreateAsync(transport, cancellationToken: cts.Token).ConfigureAwait(false);
                    var defs = await client.ListToolsAsync(cancellationToken: cts.Token).ConfigureAwait(false);

                    var converted = new List<ITool>();
                    foreach (var def in defs)
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
        return new McpSource(tools, status, clients);
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

            var schema = def.JsonSchema.ValueKind == JsonValueKind.Object
                ? (Dictionary<string, object?>)Json.FromElement(def.JsonSchema)!
                : new Dictionary<string, object?>();
            schema["type"] = "object";
            schema["properties"] = schema.Get("properties") ?? new Dictionary<string, object?>();
            schema["additionalProperties"] = false;
            InputSchema = schema;
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
