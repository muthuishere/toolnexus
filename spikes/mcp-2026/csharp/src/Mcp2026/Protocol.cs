using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Mcp2026;

/// <summary>Shared wire constants for MCP 2026-07-28 (stateless Streamable HTTP).</summary>
public static class Mcp
{
    public const string ProtocolVersion = "2026-07-28";
    public const string MetaVersion = "io.modelcontextprotocol/protocolVersion";
    public const string MetaClientCaps = "io.modelcontextprotocol/clientCapabilities";
    public const string MetaClientInfo = "io.modelcontextprotocol/clientInfo";
    public const string MetaServerInfo = "io.modelcontextprotocol/serverInfo";
    public const string SkillsExt = "io.modelcontextprotocol/skills";

    public const int CodeMethodNotFound = -32601;
    public const int CodeInvalidParams = -32602;
    public const int CodeHeaderMismatch = -32020;
    public const int CodeUnsupportedProtocol = -32022;

    /// <summary>Returns the first text content of a tool result ("" if none).</summary>
    public static string Text(JsonObject? r) =>
        r?["content"] is JsonArray { Count: > 0 } c ? J.Str(c[0]?["text"]) ?? "" : "";

    /// <summary>The pending authorization inside ex (or its inner exceptions), or null.</summary>
    public static Pending? PendingOf(Exception? ex)
    {
        for (; ex != null; ex = ex.InnerException)
            if (ex is PendingAuthorizationException pe) return pe.Pending;
        return null;
    }
}

/// <summary>A JSON-RPC error returned by the server.</summary>
public sealed class McpRpcException(int code, string message, JsonNode? data = null) : Exception(message)
{
    public int Code { get; } = code;
    public new JsonNode? Data { get; } = data;

    internal JsonObject ToJson()
    {
        var o = new JsonObject { ["code"] = Code, ["message"] = Message };
        if (Data != null) o["data"] = Data.DeepClone();
        return o;
    }
}

/// <summary>Transport / protocol failure that is not a JSON-RPC error (never retried).</summary>
public sealed class McpException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>Small JSON helpers over System.Text.Json.Nodes.</summary>
public static class J
{
    public static readonly JsonSerializerOptions Options = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static string Str(JsonNode? n, string? fallback = null) =>
        n is JsonValue v && v.TryGetValue<string>(out var s) ? s : fallback!;

    public static double? Num(JsonNode? n)
    {
        if (n is not JsonValue v) return null;
        if (v.TryGetValue<double>(out var d)) return d;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<int>(out var i)) return i;
        return null;
    }

    public static bool True(JsonNode? n) => n is JsonValue v && v.TryGetValue<bool>(out var b) && b;

    public static string Serialize(JsonNode? n) => n?.ToJsonString(Options) ?? "null";

    public static JsonObject? ParseObject(string s)
    {
        try { return JsonNode.Parse(s) as JsonObject; } catch (JsonException) { return null; }
    }

    public static JsonArray Array(IEnumerable<string> xs) => new(xs.Select(x => (JsonNode?)JsonValue.Create(x)).ToArray());

    public static IEnumerable<string> Strings(JsonNode? n) =>
        n is JsonArray a ? a.Select(x => Str(x)).Where(x => x != null) : [];

    public static JsonNode? Clone(JsonNode? n) => n?.DeepClone();
}
