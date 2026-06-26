namespace Toolnexus;

/// <summary>
/// The single uniform tool abstraction. Mirrors opencode's <c>dynamicTool</c>.
///
/// <para><see cref="InputSchema"/> is a JSON-Schema object as a plain dictionary
/// (e.g. <c>{type:"object", properties:{...}, required:[...]}</c>). <see cref="Source"/>
/// is one of <c>"mcp" | "skill" | "native" | "http" | "custom"</c>.</para>
/// </summary>
public interface ITool
{
    string Name { get; }

    string Description { get; }

    IDictionary<string, object?> InputSchema { get; }

    string Source { get; }

    Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null);
}
