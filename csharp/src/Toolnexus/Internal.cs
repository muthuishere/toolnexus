namespace Toolnexus;

/// <summary>Internal helpers for working with the plain JSON object model.</summary>
internal static class DictExtensions
{
    /// <summary>
    /// Null-safe lookup on an <see cref="IDictionary{TKey,TValue}"/> of JSON values.
    /// (The BCL <c>GetValueOrDefault</c> only exists for <c>IReadOnlyDictionary</c>, which
    /// <c>IDictionary</c> does not implement.)
    /// </summary>
    public static object? Get(this IDictionary<string, object?> dict, string key)
        => dict.TryGetValue(key, out var v) ? v : null;
}

/// <summary>
/// Wraps the process's default <see cref="System.Net.IWebProxy"/> and remembers its answer per
/// scheme+host+port. The default proxy re-queries the OS (CFNetwork on macOS) on every request; the
/// decision for a given endpoint does not change between LLM calls, so the lookup is made once —
/// the same rule the Python port applies (proxy decision cached per host).
/// </summary>
internal sealed class CachedProxy(Func<System.Net.IWebProxy> source) : System.Net.IWebProxy
{
    // Resolved on first use, as the stock handler does, so a host that sets HttpClient.DefaultProxy
    // before its first LLM call is still honoured.
    private readonly Lazy<System.Net.IWebProxy> _inner = new(source);
    private System.Net.IWebProxy inner => _inner.Value;
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, (Uri? Proxy, bool Bypassed)> _cache = new();

    public System.Net.ICredentials? Credentials
    {
        get => inner.Credentials;
        set => inner.Credentials = value;
    }

    private (Uri? Proxy, bool Bypassed) Lookup(Uri uri) =>
        _cache.GetOrAdd(uri.Scheme + "://" + uri.Authority, static (_, st) =>
        {
            var bypassed = st.inner.IsBypassed(st.uri);
            return (bypassed ? null : st.inner.GetProxy(st.uri), bypassed);
        }, (inner, uri));

    public Uri? GetProxy(Uri destination) => Lookup(destination).Proxy;

    public bool IsBypassed(Uri host) => Lookup(host).Bypassed;
}
