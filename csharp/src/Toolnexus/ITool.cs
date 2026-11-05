namespace Toolnexus;

/// <summary>
/// The single uniform tool abstraction. Mirrors opencode's <c>dynamicTool</c>.
///
/// <para><see cref="InputSchema"/> is a JSON-Schema object as a plain dictionary
/// (e.g. <c>{type:"object", properties:{...}, required:[...]}</c>). <see cref="Source"/>
/// is one of <c>"mcp" | "skill" | "native" | "http" | "a2a" | "custom"</c>.</para>
/// </summary>
public interface ITool
{
    string Name { get; }

    string Description { get; }

    IDictionary<string, object?> InputSchema { get; }

    string Source { get; }

    Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null);

    /// <summary>
    /// (SPEC §4C) Advisory behaviour hints for a host permission gate (<c>Hooks.BeforeTool</c>):
    /// e.g. let read-only tools run in a plan mode. <c>null</c> = unknown; treat as mutating.
    /// Builtins always carry hints; MCP tools carry them when the server sends annotations.
    /// Never serialized into any adapter or wire output. Default: <c>null</c>.
    /// </summary>
    ToolHints? Hints => null;

    /// <summary>
    /// (SPEC §8 "Tool scheduling") Whether a call with <paramref name="args"/> may run alongside
    /// other safe calls of the same model response. Default: safe only when <see cref="Hints"/>
    /// says read-only; a tool without hints is unsafe.
    /// </summary>
    bool IsConcurrencySafe(IDictionary<string, object?> args) => Hints?.ReadOnly == true;

    /// <summary>
    /// (SPEC §1) Optional JSON Schema of the tool's structured result (MCP <c>outputSchema</c>).
    /// Emitted by the MCP serve paths (§7C / §7C.1) when set; absent ⇒ omitted. Default: <c>null</c>.
    /// </summary>
    IDictionary<string, object?>? OutputSchema => null;

    /// <summary>
    /// (SPEC §1) Optional MCP tool annotations, emitted verbatim on <c>tools/list</c> by the
    /// embeddable MCP handler (§7C.1). <c>null</c> ⇒ derived from <see cref="Hints"/>, else omitted.
    /// </summary>
    ToolAnnotations? Annotations => null;
}

/// <summary>
/// (SPEC §1) MCP tool annotations. Every field is optional; a <c>null</c> hint is omitted from the wire.
/// </summary>
public sealed record ToolAnnotations
{
    public string? Title { get; init; }
    public bool? ReadOnlyHint { get; init; }
    public bool? DestructiveHint { get; init; }
    public bool? IdempotentHint { get; init; }
    public bool? OpenWorldHint { get; init; }
}

/// <summary>
/// (SPEC §4C) Advisory tool behaviour hints, named after MCP tool annotations. A server can
/// mislabel its tools, so a gate must treat these as hints, not guarantees.
/// </summary>
/// <param name="ReadOnly">The tool does not change its environment.</param>
/// <param name="Destructive">The tool may overwrite or delete things (meaningful when not read-only).</param>
/// <param name="OpenWorld">The tool reaches outside the host (network, external systems).</param>
public sealed record ToolHints(bool ReadOnly = false, bool Destructive = false, bool OpenWorld = false);
