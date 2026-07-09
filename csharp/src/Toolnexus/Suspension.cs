using System.Text.Json.Serialization;

namespace Toolnexus;

/// <summary>
/// §10 Suspension. A tool that needs an out-of-band, async resolution (login, approval,
/// input) returns a normal <see cref="ToolResult"/> carrying <c>Metadata["pending"] = Request</c>.
/// Byte-identical wire data — it crosses languages, processes, and agents unchanged, so the
/// JSON keys are pinned exactly (<c>id, kind, prompt, url, data, expiresAt</c>), NOT idiomatic-cased.
/// </summary>
public sealed record Request
{
    [JsonPropertyName("id")]
    public string Id { get; init; } = "";

    /// <summary>"authorization" | "approval" | "input" | ... (open vocabulary).</summary>
    [JsonPropertyName("kind")]
    public string Kind { get; init; } = "";

    [JsonPropertyName("prompt")]
    public string Prompt { get; init; } = "";

    [JsonPropertyName("url")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Url { get; init; }

    [JsonPropertyName("data")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public IDictionary<string, object?>? Data { get; init; }

    /// <summary>RFC3339; the request is stale after this.</summary>
    [JsonPropertyName("expiresAt")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ExpiresAt { get; init; }
}

/// <summary>
/// §10 Suspension. The resolution of a <see cref="Request"/>. Byte-identical wire data — keys
/// are pinned exactly (<c>id, ok, data</c>).
/// </summary>
public sealed record Answer
{
    /// <summary>Echoes <see cref="Request.Id"/>.</summary>
    [JsonPropertyName("id")]
    public string Id { get; init; } = "";

    /// <summary>Satisfied, vs declined / aborted / expired.</summary>
    [JsonPropertyName("ok")]
    public bool Ok { get; init; }

    [JsonPropertyName("data")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public IDictionary<string, object?>? Data { get; init; }
}
