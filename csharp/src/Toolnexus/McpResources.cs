using ModelContextProtocol.Protocol;

namespace Toolnexus;

/// <summary>(SPEC §4C) One server's load outcome, reported by <see cref="McpLoadOptions.OnServer"/>
/// the moment that server finishes.</summary>
/// <param name="Name">The server name.</param>
/// <param name="Status"><c>"connected"</c> | <c>"failed"</c> | <c>"disabled"</c>.</param>
/// <param name="Tools">This server's converted tools (empty unless connected). Usable until the
/// returned <see cref="McpSource"/> is disposed.</param>
/// <param name="Error">Why the server failed (null when connected or disabled).</param>
public sealed record McpServerResult(string Name, string Status, IReadOnlyList<ITool> Tools, Exception? Error);

/// <summary>(SPEC §4C) Options for <see cref="McpSource.LoadAsync(object, McpLoadOptions, CancellationToken)"/>.</summary>
public sealed class McpLoadOptions
{
    /// <summary>Bridges MCP elicitation onto §10, as in the <c>waitFor</c> overload.</summary>
    public Func<Request, Task<Answer>>? WaitFor { get; set; }

    /// <summary>Called once per server as soon as it is connected, failed or found disabled. Calls
    /// are serialized (never concurrent with each other) but come from load tasks; keep them short.</summary>
    public Action<McpServerResult>? OnServer { get; set; }

    /// <summary>(SPEC §2A.1a) Where MCP OAuth tokens are kept, keyed by resource. Default: in memory, per load.</summary>
    public ITokenStore? TokenStore { get; set; }

    /// <summary>(SPEC §2A.1a) How <c>auto</c>-mode OAuth opens the authorization URL. Default: the OS opener.
    /// Tests inject an HTTP GET.</summary>
    public Func<string, Task>? OpenUrl { get; set; }
}

public sealed partial class McpSource
{
    /// <summary>
    /// (SPEC §4C) Map MCP tool annotations to <see cref="ToolHints"/>. No hint fields at all ⇒ null
    /// (unknown). Defaults follow the MCP spec: readOnly false, destructive true and openWorld true
    /// when absent; a read-only tool is never destructive.
    /// </summary>
    internal static ToolHints? McpHints(bool? readOnly, bool? destructive, bool? openWorld)
    {
        if (readOnly == null && destructive == null && openWorld == null) return null;
        var ro = readOnly ?? false;
        return new ToolHints(ro, !ro && (destructive ?? true), openWorld ?? true);
    }

    private List<string> ServerNames() => _servers.Keys.OrderBy(n => n, StringComparer.Ordinal).ToList();

    /// <summary>
    /// (SPEC §4C) <c>list_mcp_resources</c> and <c>read_mcp_resource</c> bound to this source's
    /// connected servers. Both read-only. Opt-in: a host registers them. They stop working once the
    /// source is disposed. A server-less source still returns the tools; they report that no server
    /// is connected.
    /// </summary>
    public List<ITool> ResourceTools()
    {
        var ro = new ToolHints(ReadOnly: true);
        (McpClientConn? Conn, string? Error) Conn(string server)
            => _servers.TryGetValue(server, out var c)
                ? (new McpClientConn(c.Client, c.TimeoutMs), null)
                : (null, $"no connected MCP server \"{server}\" (connected: {string.Join(", ", ServerNames())})");

        var list = new ResourceTool("list_mcp_resources",
            "List the resources MCP servers offer (uri, name, description, mimeType). Optionally for one server.",
            new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["server"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "Only this server (default: all)" },
                },
                ["additionalProperties"] = false,
            }, ro,
            async (args, ctx) =>
            {
                var names = ServerNames();
                if (args.Get("server") is string s && s.Length > 0)
                {
                    var (_, err) = Conn(s);
                    if (err != null) return ToolResult.Error("list_mcp_resources: " + err);
                    names = new List<string> { s };
                }
                var lines = new List<string>();
                var items = new List<object?>();
                foreach (var name in names)
                {
                    var c = Conn(name).Conn!;
                    using var cts = CancellationTokenSource.CreateLinkedTokenSource(ctx?.CancellationToken ?? default);
                    cts.CancelAfter(TimeSpan.FromMilliseconds(c.TimeoutMs));
                    IList<ModelContextProtocol.Client.McpClientResource> res;
                    try { res = await c.Client.ListResourcesAsync(cancellationToken: cts.Token).ConfigureAwait(false); }
                    catch (Exception e)
                    {
                        lines.Add($"{name}: error: {e.Message}");
                        continue;
                    }
                    foreach (var r in res)
                    {
                        var line = $"{name} {r.Uri}";
                        if (!string.IsNullOrEmpty(r.Name)) line += " — " + r.Name;
                        if (!string.IsNullOrEmpty(r.MimeType)) line += " (" + r.MimeType + ")";
                        if (!string.IsNullOrEmpty(r.Description)) line += ": " + r.Description;
                        lines.Add(line);
                        items.Add(new Dictionary<string, object?>
                        {
                            ["server"] = name, ["uri"] = r.Uri, ["name"] = r.Name ?? "",
                            ["mimeType"] = r.MimeType ?? "", ["description"] = r.Description ?? "",
                        });
                    }
                }
                if (lines.Count == 0) return ToolResult.Ok("No resources.", new Dictionary<string, object?> { ["count"] = 0L });
                return ToolResult.Ok(string.Join("\n", lines),
                    new Dictionary<string, object?> { ["count"] = (long)items.Count, ["resources"] = items });
            });

        var read = new ResourceTool("read_mcp_resource",
            "Read one MCP resource by server and uri. Text comes back as text; binary content is described, not inlined.",
            new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = new Dictionary<string, object?>
                {
                    ["server"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "The MCP server name" },
                    ["uri"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "The resource uri" },
                },
                ["required"] = new List<object?> { "server", "uri" },
                ["additionalProperties"] = false,
            }, ro,
            async (args, ctx) =>
            {
                var server = args.Get("server") as string ?? "";
                var uri = args.Get("uri") as string ?? "";
                var (c, err) = Conn(server);
                if (err != null) return ToolResult.Error("read_mcp_resource: " + err);
                if (uri.Length == 0) return ToolResult.Error("read_mcp_resource: uri is required");
                using var cts = CancellationTokenSource.CreateLinkedTokenSource(ctx?.CancellationToken ?? default);
                cts.CancelAfter(TimeSpan.FromMilliseconds(c!.TimeoutMs));
                ReadResourceResult res;
                try { res = await c.Client.ReadResourceAsync(uri, cancellationToken: cts.Token).ConfigureAwait(false); }
                catch (Exception e) { return ToolResult.Error($"read_mcp_resource: {e.Message}"); }
                var parts = new List<string>();
                foreach (var rc in res.Contents)
                {
                    switch (rc)
                    {
                        case TextResourceContents t:
                            parts.Add(t.Text ?? "");
                            break;
                        case BlobResourceContents b:
                            parts.Add($"[binary {b.MimeType}, {B64(b.Blob).Length} base64 chars]");
                            break;
                    }
                }
                return ToolResult.Ok(string.Join("\n", parts), new Dictionary<string, object?> { ["server"] = server, ["uri"] = uri });
            });
        return new List<ITool> { list, read };
    }

    private sealed record McpClientConn(ModelContextProtocol.Client.McpClient Client, long TimeoutMs);

    private sealed class ResourceTool(string name, string description, IDictionary<string, object?> schema, ToolHints hints,
        Func<IDictionary<string, object?>, ToolContext?, Task<ToolResult>> run) : ITool
    {
        public string Name => name;
        public string Description => description;
        public IDictionary<string, object?> InputSchema => schema;
        public string Source => "mcp";
        public ToolHints? Hints => hints;

        public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
        {
            try { return await run(args ?? new Dictionary<string, object?>(), ctx).ConfigureAwait(false); }
            catch (Exception e) { return ToolResult.Error($"{name}: {e.Message}"); }
        }
    }
}
