namespace Toolnexus;

/// <summary>
/// The uniform result of executing an <see cref="ITool"/>.
/// <list type="bullet">
///   <item><c>Output</c> — text handed back to the model.</item>
///   <item><c>IsError</c> — whether the call failed.</item>
///   <item><c>Metadata</c> — free-form (title, server, skill name, ...); may be null.</item>
///   <item><c>Parts</c> — §1B non-text output (image/file/audio); absent ⇒ byte-identical to
///   pre-multimodal behaviour.</item>
/// </list>
/// <para><c>Output</c> stays required even when <c>Parts</c> is present: it is what the
/// transcript, compaction, token estimation and any text-only provider see. A tool returning an
/// image sets <c>Output</c> to a description ("screenshot, 1280x720 png") and <c>Parts</c> to the
/// image.</para>
/// <para><c>Parts</c> is the <b>fourth</b> positional parameter, after <c>Metadata</c> — a
/// deliberate append, so every existing <c>new ToolResult(output, isError, meta)</c> site is
/// untouched.</para>
/// </summary>
public sealed record ToolResult(
    string Output,
    bool IsError,
    IDictionary<string, object?>? Metadata = null,
    IReadOnlyList<ContentPart>? Parts = null)
{
    public string Output { get; init; } = Output ?? "";

    public static ToolResult Ok(string output, IDictionary<string, object?>? metadata = null)
        => new(output, false, metadata);

    public static ToolResult Error(string output, IDictionary<string, object?>? metadata = null)
        => new(output, true, metadata);

    /// <summary>A success carrying §1B non-text content alongside its describing text.</summary>
    public static ToolResult OkWithParts(string output, IReadOnlyList<ContentPart> parts,
        IDictionary<string, object?>? metadata = null)
        => new(output, false, metadata, parts);

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
