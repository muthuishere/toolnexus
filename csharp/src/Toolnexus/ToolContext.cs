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

    public ToolContext(long? timeoutMs = null, CancellationToken cancellationToken = default)
    {
        TimeoutMs = timeoutMs;
        CancellationToken = cancellationToken;
    }

    public bool IsCancelled => CancellationToken.IsCancellationRequested;
}
