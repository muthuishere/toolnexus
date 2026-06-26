namespace Toolnexus;

/// <summary>
/// The uniform result of executing an <see cref="ITool"/>.
/// <list type="bullet">
///   <item><c>Output</c> — text handed back to the model.</item>
///   <item><c>IsError</c> — whether the call failed.</item>
///   <item><c>Metadata</c> — free-form (title, server, skill name, ...); may be null.</item>
/// </list>
/// </summary>
public sealed record ToolResult(string Output, bool IsError, IDictionary<string, object?>? Metadata = null)
{
    public string Output { get; init; } = Output ?? "";

    public static ToolResult Ok(string output, IDictionary<string, object?>? metadata = null)
        => new(output, false, metadata);

    public static ToolResult Error(string output, IDictionary<string, object?>? metadata = null)
        => new(output, true, metadata);
}
