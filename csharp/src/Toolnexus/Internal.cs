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
