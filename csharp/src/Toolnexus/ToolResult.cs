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

    // ---------------------------------------------------------------- §10 Suspension

    private static int _pendingSeq;

    /// <summary>
    /// §10 Producer helper: return a suspension. A <see cref="ToolResult"/> (<c>IsError = true</c>)
    /// whose <c>Metadata["pending"]</c> is a <see cref="Request"/>. The uniform
    /// <c>execute(args, ctx) -&gt; ToolResult</c> contract is untouched — suspension is data on the
    /// existing result, not a new return type. Generates an <c>id</c> when the request lacks one.
    /// </summary>
    public static ToolResult Pending(Request request)
    {
        var id = string.IsNullOrEmpty(request.Id)
            ? $"pnd-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds():x}-{System.Threading.Interlocked.Increment(ref _pendingSeq)}"
            : request.Id;
        var req = request with { Id = id };
        var output = req.Prompt + (string.IsNullOrEmpty(req.Url) ? "" : "\n" + req.Url);
        return new ToolResult(output, true, new Dictionary<string, object?> { ["pending"] = req });
    }

    /// <summary>Sugar for the common case: <c>kind:"authorization"</c> at a login URL (OAuth2/OIDC).</summary>
    public static ToolResult AuthRequired(string url, string prompt = "Authorization required to continue")
        => Pending(new Request { Kind = "authorization", Prompt = prompt, Url = url });

    /// <summary>Read the suspension off a result, if any (§10).</summary>
    public static Request? PendingOf(ToolResult? result)
        => result?.Metadata != null && result.Metadata.TryGetValue("pending", out var p) ? p as Request : null;
}
