using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace Toolnexus.Spike;

/// <summary>
/// Canonical JSON: keys sorted recursively (ordinal), arrays left alone, compact separators,
/// UTF-8, no trailing newline. System.Text.Json does NOT sort object keys on write, so the
/// sort is ours; the writer below feeds <see cref="Utf8JsonWriter"/> keys in order.
/// </summary>
public static class CanonicalJson
{
    private static readonly JsonWriterOptions WriterOpts = new()
    {
        Indented = false,
        // Default encoder escapes ' as ' — the fixture has "user's own request" raw.
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static byte[] Bytes(object? value)
    {
        var buf = new MemoryStream();
        using (var w = new Utf8JsonWriter(buf, WriterOpts)) Write(w, value);
        return buf.ToArray();
    }

    public static string String(object? value) => Encoding.UTF8.GetString(Bytes(value));

    private static void Write(Utf8JsonWriter w, object? v)
    {
        switch (v)
        {
            case null: w.WriteNullValue(); break;
            case string s: w.WriteStringValue(s); break;
            case bool b: w.WriteBooleanValue(b); break;
            case int i: w.WriteNumberValue(i); break;
            case long l: w.WriteNumberValue(l); break;
            case double d: w.WriteNumberValue(d); break;
            case IReadOnlyDictionary<string, object?> map:
                w.WriteStartObject();
                foreach (var k in map.Keys.OrderBy(k => k, StringComparer.Ordinal))
                {
                    w.WritePropertyName(k);
                    Write(w, map[k]);
                }
                w.WriteEndObject();
                break;
            case System.Collections.IEnumerable list:
                w.WriteStartArray();
                foreach (var item in list) Write(w, item);
                w.WriteEndArray();
                break;
            default: throw new InvalidOperationException($"not canonicalisable: {v.GetType()}");
        }
    }
}
