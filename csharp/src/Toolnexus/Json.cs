using System.Text.Json;
using System.Text.Json.Nodes;

namespace Toolnexus;

/// <summary>
/// Small shared System.Text.Json helper for our own JSON (config, adapters, LLM client).
/// JSON values are represented with plain CLR objects (the JS/Java "Map&lt;String,Object&gt;"
/// model): objects =&gt; <c>Dictionary&lt;string, object?&gt;</c>, arrays =&gt; <c>List&lt;object?&gt;</c>,
/// numbers =&gt; <c>long</c>/<c>double</c>, plus <c>string</c>/<c>bool</c>/<c>null</c>.
/// </summary>
public static class Json
{
    private static readonly JsonSerializerOptions Compact = new()
    {
        // Match the other ports: no extra escaping of non-ASCII, compact output.
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private static readonly JsonSerializerOptions Indented = new()
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static string Stringify(object? value)
    {
        try
        {
            return JsonSerializer.Serialize(Normalize(value), Compact);
        }
        catch
        {
            return value?.ToString() ?? "null";
        }
    }

    public static string Pretty(object? value)
    {
        try
        {
            return JsonSerializer.Serialize(Normalize(value), Indented);
        }
        catch
        {
            return value?.ToString() ?? "null";
        }
    }

    /// <summary>
    /// The bytes of Go's <c>json.MarshalIndent(v, "", "  ")</c>: object keys sorted (ordinal) at
    /// every level, two-space indent, and <c>&lt;</c>, <c>&gt;</c>, <c>&amp;</c>, U+2028, U+2029
    /// escaped as <c>\u003c</c> etc. Used where a port must print the same bytes as Go (tool_search).
    /// </summary>
    public static string GoIndent(object? value)
    {
        var sb = new System.Text.StringBuilder();
        WriteGo(sb, Normalize(value), "");
        return sb.ToString();
    }

    private static void WriteGo(System.Text.StringBuilder sb, object? v, string pad)
    {
        var inner = pad + "  ";
        switch (v)
        {
            case null: sb.Append("null"); break;
            case string s: WriteGoString(sb, s); break;
            case bool b: sb.Append(b ? "true" : "false"); break;
            case Dictionary<string, object?> d:
                if (d.Count == 0) { sb.Append("{}"); break; }
                sb.Append("{\n");
                var first = true;
                foreach (var k in d.Keys.OrderBy(k => k, StringComparer.Ordinal))
                {
                    if (!first) sb.Append(",\n");
                    first = false;
                    sb.Append(inner);
                    WriteGoString(sb, k);
                    sb.Append(": ");
                    WriteGo(sb, d[k], inner);
                }
                sb.Append('\n').Append(pad).Append('}');
                break;
            case List<object?> l:
                if (l.Count == 0) { sb.Append("[]"); break; }
                sb.Append("[\n");
                for (var i = 0; i < l.Count; i++)
                {
                    if (i > 0) sb.Append(",\n");
                    sb.Append(inner);
                    WriteGo(sb, l[i], inner);
                }
                sb.Append('\n').Append(pad).Append(']');
                break;
            default: sb.Append(JsonSerializer.Serialize(v, Compact)); break;
        }
    }

    private static void WriteGoString(System.Text.StringBuilder sb, string s)
    {
        sb.Append('"');
        foreach (var c in s)
        {
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                case '\b': sb.Append("\\b"); break;
                case '\f': sb.Append("\\f"); break;
                case '<' or '>' or '&' or '\u2028' or '\u2029' or < (char)0x20:
                    sb.Append("\\u").Append(((int)c).ToString("x4")); break;
                default: sb.Append(c); break;
            }
        }
        sb.Append('"');
    }

    /// <summary>Parse a JSON string into the plain CLR object model. Returns the raw text on failure.</summary>
    public static object? ParseLoose(string? text)
    {
        if (text == null) return null;
        try
        {
            using var doc = JsonDocument.Parse(text);
            return FromElement(doc.RootElement);
        }
        catch
        {
            return text;
        }
    }

    /// <summary>Parse a JSON object string into a dictionary (empty dict on blank/invalid).</summary>
    public static Dictionary<string, object?> ParseObjectLoose(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return new Dictionary<string, object?>();
        try
        {
            using var doc = JsonDocument.Parse(text);
            if (doc.RootElement.ValueKind == JsonValueKind.Object)
                return (Dictionary<string, object?>)FromElement(doc.RootElement)!;
        }
        catch
        {
            // fall through
        }
        return new Dictionary<string, object?>();
    }

    /// <summary>Parse a JSON object string into a dictionary, throwing on invalid input.</summary>
    public static Dictionary<string, object?> ToMap(string text)
    {
        using var doc = JsonDocument.Parse(text);
        if (doc.RootElement.ValueKind != JsonValueKind.Object)
            throw new InvalidOperationException("Expected a JSON object");
        return (Dictionary<string, object?>)FromElement(doc.RootElement)!;
    }

    /// <summary>Recursively convert a <see cref="JsonElement"/> into the plain CLR object model.</summary>
    public static object? FromElement(JsonElement el)
    {
        switch (el.ValueKind)
        {
            case JsonValueKind.Object:
                var obj = new Dictionary<string, object?>();
                foreach (var prop in el.EnumerateObject())
                    obj[prop.Name] = FromElement(prop.Value);
                return obj;
            case JsonValueKind.Array:
                var arr = new List<object?>();
                foreach (var item in el.EnumerateArray())
                    arr.Add(FromElement(item));
                return arr;
            case JsonValueKind.String:
                return el.GetString();
            case JsonValueKind.Number:
                if (el.TryGetInt64(out var l)) return l;
                return el.GetDouble();
            case JsonValueKind.True:
                return true;
            case JsonValueKind.False:
                return false;
            case JsonValueKind.Null:
            case JsonValueKind.Undefined:
            default:
                return null;
        }
    }

    /// <summary>
    /// Normalize CLR values for serialization: pass <see cref="JsonElement"/>/<see cref="JsonNode"/>
    /// through, recurse into dictionaries/enumerables. Anything else serializes natively.
    /// </summary>
    private static object? Normalize(object? value)
    {
        switch (value)
        {
            case null:
                return null;
            case string s:
                return s;
            case bool or int or long or double or float or decimal:
                return value;
            case JsonElement je:
                return FromElement(je);
            case JsonNode node:
                return FromElement(JsonSerializer.SerializeToElement(node));
            case System.Collections.IDictionary dict:
                var outObj = new Dictionary<string, object?>();
                foreach (System.Collections.DictionaryEntry e in dict)
                    outObj[e.Key?.ToString() ?? ""] = Normalize(e.Value);
                return outObj;
            case System.Collections.IEnumerable en:
                var outArr = new List<object?>();
                foreach (var item in en) outArr.Add(Normalize(item));
                return outArr;
            default:
                return value;
        }
    }
}
