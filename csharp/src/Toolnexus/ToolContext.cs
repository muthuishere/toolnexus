namespace Toolnexus;

/// <summary>
/// Optional context passed to <see cref="ITool.ExecuteAsync"/>. Carries an optional
/// timeout (milliseconds) and a cooperative cancellation token.
/// </summary>
public sealed class ToolContext
{
    /// <summary>Timeout in milliseconds, or null for "use the tool default".</summary>
    public long? TimeoutMs { get; }

    public CancellationToken CancellationToken { get; }

    /// <summary>
    /// §10 Suspension. Present ONLY on a post-<c>WaitFor</c> retry: the resolution of a prior
    /// suspension. A tool uses <c>ctx.Answer.Data</c> when the resolution <em>is</em> the payload
    /// (<c>kind:"input"</c>) and ignores it when the world changed out-of-band
    /// (<c>kind:"authorization"</c> — the session is now valid).
    /// </summary>
    public Answer? Answer { get; }

    /// <summary>
    /// (SPEC §4C) The model's id for this tool call when the client loop runs the tool. A
    /// message's calls run concurrently, so this is how a tool tells its sibling calls apart.
    /// <c>null</c> when the tool is executed outside the client loop.
    /// </summary>
    public string? CallId { get; }

    public ToolContext(long? timeoutMs = null, CancellationToken cancellationToken = default, Answer? answer = null, string? callId = null)
    {
        TimeoutMs = timeoutMs;
        CancellationToken = cancellationToken;
        Answer = answer;
        CallId = callId;
    }

    public bool IsCancelled => CancellationToken.IsCancellationRequested;
}
