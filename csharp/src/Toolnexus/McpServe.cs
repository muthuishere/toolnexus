using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using ModelContextProtocol;
using ModelContextProtocol.Protocol;
using ModelContextProtocol.Server;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// MCP — INBOUND: serve a toolkit as an MCP server (SPEC §7C).
//
// The inbound mirror of the A2A `serve` (§7B). Where A2A advertises the toolkit's
// SKILLS and fulfils a Task through the whole client loop, MCP advertises the
// toolkit's UNIFIED TOOLS (every source — mcp · skill · native · http · builtin ·
// a2a) and dispatches each `tools/call` straight to <see cref="ITool.ExecuteAsync"/>.
// The calling MCP client *is* the LLM host, so there is no client, no Task, and no
// TaskStore — just `tools/list` + `tools/call` over the toolkit registry.
//
// Transport: streamable-HTTP, built on the same official <c>ModelContextProtocol</c>
// SDK the client side already uses — mounted at POST /mcp on the
// <see cref="Toolkit.ServeAsync"/> server, alongside the A2A routes (see A2AServer.cs).
//
// Mirrors the JS reference (js/src/mcpserve.ts). See ../../SPEC.md §7C and
// openspec/changes/add-mcp-inbound.
// ---------------------------------------------------------------------------

/// <summary>Opt-in MCP serve profile — the toolkit is exposed as an MCP server.</summary>
public sealed class MCPServeConfig
{
    /// <summary>Advertised server name (initialize <c>serverInfo.name</c>). Default "toolnexus".</summary>
    public string? Name { get; set; }

    /// <summary>Advertised server version. Default "0.1.0".</summary>
    public string? Version { get; set; }

    /// <summary>Subset of tool names to expose; omit ⇒ all. Unknown names are ignored.</summary>
    public IReadOnlyList<string>? Tools { get; set; }
}

/// <summary>Surfaced on each inbound <c>tools/call</c>.</summary>
public sealed record OnCallEvent(string Name, string Source, long Ms, bool IsError);

/// <summary>Callback fired per inbound <c>tools/call</c>. Host callback errors are isolated.</summary>
public delegate Task OnCall(OnCallEvent ev);

/// <summary>
/// Builds the toolkit's tools as an MCP server. The streamable-HTTP path
/// (<c>/mcp</c>) is driven by <see cref="A2AServer"/>.
/// </summary>
public static class McpServe
{
    private const string DefaultName = "toolnexus";
    private const string DefaultVersion = "0.1.0";

    /// <summary>
    /// The tools this profile exposes: every toolkit tool, filtered to <c>cfg.Tools</c>
    /// when set (unknown names in the filter are ignored, never an error).
    /// </summary>
    public static IReadOnlyList<ITool> ExposedMcpTools(IReadOnlyList<ITool> tools, MCPServeConfig? cfg)
    {
        if (cfg?.Tools == null) return tools;
        var wanted = new HashSet<string>(cfg.Tools);
        return tools.Where(t => wanted.Contains(t.Name)).ToList();
    }

    /// <summary>
    /// Build a low-level MCP <see cref="McpServer"/> over <paramref name="transport"/> exposing
    /// <paramref name="tools"/> as MCP tools. <c>tools/list</c> maps each tool's name (verbatim —
    /// already sanitized at registration), description, and inputSchema; <c>tools/call</c>
    /// dispatches to <see cref="ITool.ExecuteAsync"/> and maps the <see cref="ToolResult"/> to a
    /// <see cref="CallToolResult"/> (<c>Output</c> → text, <c>IsError</c> propagates).
    /// </summary>
    public static McpServer BuildMcpServer(
        ITransport transport, IReadOnlyList<ITool> tools, MCPServeConfig? cfg = null, OnCall? onCall = null)
        => McpServer.Create(transport, BuildOptions(tools, cfg, onCall), NullLoggerFactory.Instance, null);

    /// <summary>Assemble the <see cref="McpServerOptions"/> (serverInfo + tools capability + handlers).</summary>
    internal static McpServerOptions BuildOptions(IReadOnlyList<ITool> tools, MCPServeConfig? cfg, OnCall? onCall)
    {
        // First-name-wins index for dispatch (toolkit lists are already deduped).
        var byName = new Dictionary<string, ITool>();
        foreach (var t in tools)
            if (!byName.ContainsKey(t.Name)) byName[t.Name] = t;

        var options = new McpServerOptions
        {
            ServerInfo = new Implementation
            {
                Name = cfg?.Name ?? DefaultName,
                Version = cfg?.Version ?? DefaultVersion,
            },
            Capabilities = new ServerCapabilities { Tools = new ToolsCapability() },
        };

        options.Handlers.ListToolsHandler = (_, _) =>
            new ValueTask<ListToolsResult>(new ListToolsResult { Tools = tools.Select(ToMcpTool).ToList() });

        options.Handlers.CallToolHandler = async (ctx, ct) =>
        {
            var name = ctx.Params?.Name ?? "";
            if (!byName.TryGetValue(name, out var tool))
                throw new McpException($"Unknown tool: {name}");

            var started = System.Diagnostics.Stopwatch.GetTimestamp();
            // The call is a single tool invocation honoring the MCP request's cancellation.
            // An ExecuteAsync throw becomes an isError result — never a crash.
            ToolResult result;
            try
            {
                var args = ToArgs(ctx.Params?.Arguments);
                result = await tool.ExecuteAsync(args, new ToolContext(cancellationToken: ct)).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                result = new ToolResult(e.Message ?? e.ToString(), true);
            }

            if (onCall != null)
            {
                var ms = (long)System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds;
                try { await onCall(new OnCallEvent(tool.Name, tool.Source, ms, result.IsError)).ConfigureAwait(false); }
                catch { /* Host callback errors are isolated. */ }
            }

            return new CallToolResult
            {
                Content = new List<ContentBlock> { new TextContentBlock { Text = result.Output } },
                IsError = result.IsError,
            };
        };

        return options;
    }

    // -----------------------------------------------------------------------
    // Mapping helpers.
    // -----------------------------------------------------------------------

    private static Tool ToMcpTool(ITool t) => new()
    {
        Name = t.Name,
        Description = t.Description,
        InputSchema = SchemaElement(t.InputSchema),
    };

    /// <summary>Convert a tool's plain-dictionary JSON Schema into the SDK's <see cref="JsonElement"/> form.</summary>
    private static JsonElement SchemaElement(IDictionary<string, object?> schema)
    {
        using var doc = JsonDocument.Parse(Json.Stringify(schema));
        return doc.RootElement.Clone();
    }

    /// <summary>Convert MCP call arguments (<see cref="JsonElement"/> values) into the plain CLR model.</summary>
    private static IDictionary<string, object?> ToArgs(IDictionary<string, JsonElement>? arguments)
    {
        var args = new Dictionary<string, object?>();
        if (arguments == null) return args;
        foreach (var (k, v) in arguments) args[k] = Json.FromElement(v);
        return args;
    }
}
