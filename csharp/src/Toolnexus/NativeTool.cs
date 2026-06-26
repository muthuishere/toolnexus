namespace Toolnexus;

/// <summary>
/// Programmatic native tool (source <c>"native"</c>). Wrap a plain function as a
/// uniform <see cref="ITool"/>.
///
/// <para>The function may return a <see cref="string"/> (wrapped as output) or a full
/// <see cref="ToolResult"/>. A thrown exception becomes an error result.</para>
/// </summary>
public sealed class NativeTool : ITool
{
    private readonly Func<IDictionary<string, object?>, ToolContext?, Task<object?>> _fn;

    public string Name { get; }
    public string Description { get; }
    public IDictionary<string, object?> InputSchema { get; }
    public string Source { get; }

    private NativeTool(string name, string description, IDictionary<string, object?>? inputSchema,
        Func<IDictionary<string, object?>, ToolContext?, Task<object?>> fn)
    {
        Name = name;
        Description = description;
        InputSchema = inputSchema ?? EmptySchema();
        Source = "native";
        _fn = fn;
    }

    /// <summary>Build from a synchronous function that ignores the context.</summary>
    public static NativeTool Of(string name, string description, IDictionary<string, object?>? inputSchema,
        Func<IDictionary<string, object?>, object?> fn)
        => new(name, description, inputSchema, (args, _) => Task.FromResult(fn(args)));

    /// <summary>Build from a synchronous function that receives the context.</summary>
    public static NativeTool Of(string name, string description, IDictionary<string, object?>? inputSchema,
        Func<IDictionary<string, object?>, ToolContext?, object?> fn)
        => new(name, description, inputSchema, (args, ctx) => Task.FromResult(fn(args, ctx)));

    /// <summary>Build from an asynchronous function.</summary>
    public static NativeTool OfAsync(string name, string description, IDictionary<string, object?>? inputSchema,
        Func<IDictionary<string, object?>, ToolContext?, Task<object?>> fn)
        => new(name, description, inputSchema, fn);

    public async Task<ToolResult> ExecuteAsync(IDictionary<string, object?> args, ToolContext? ctx = null)
    {
        try
        {
            var output = await _fn(args ?? new Dictionary<string, object?>(), ctx).ConfigureAwait(false);
            return output switch
            {
                ToolResult tr => tr,
                string s => ToolResult.Ok(s),
                null => ToolResult.Ok(""),
                _ => ToolResult.Ok(Json.Stringify(output)),
            };
        }
        catch (Exception e)
        {
            var cause = e.InnerException ?? e;
            return ToolResult.Error(cause.Message ?? cause.ToString());
        }
    }

    internal static Dictionary<string, object?> EmptySchema() => new()
    {
        ["type"] = "object",
        ["properties"] = new Dictionary<string, object?>(),
        ["additionalProperties"] = false,
    };
}
