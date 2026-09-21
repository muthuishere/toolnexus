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

    /// <summary>
    /// When <see cref="Ok"/> is false, why (advisory — the loop rule branches only on <see cref="Ok"/>).
    /// Distinguishes an explicit refusal from a dismissal/timeout; the MCP elicitation bridge maps
    /// <c>declined</c>→<c>decline</c> and everything else→<c>cancel</c>. (R1) One of
    /// <c>declined</c> | <c>cancelled</c> | <c>expired</c>.
    /// </summary>
    [JsonPropertyName("reason")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Reason { get; init; }

    /// <summary>
    /// (ADR 0026) The typed constructor for the single-result shape: <c>data["output"] = output</c>,
    /// <c>ok = true</c>. Hand-building that map is the failure this removes — the key stops being
    /// something a host can spell wrong on the way back out of a JSON column. Prefer this over
    /// <c>new Answer { Id = …, Ok = true, Data = new Dictionary… }</c> everywhere.
    /// </summary>
    /// <param name="id">Echoes the <see cref="Request.Id"/> being answered.</param>
    /// <param name="output">The tool's result. A non-string is rejected rather than silently
    /// degraded to <c>""</c> — the type is the contract.</param>
    public static Answer Output(string id, string output)
    {
        if (id is null) throw new ArgumentNullException(nameof(id));
        if (output is null) throw new ArgumentNullException(nameof(output));
        return new Answer
        {
            Id = id,
            Ok = true,
            Data = new Dictionary<string, object?> { ["output"] = output },
        };
    }

    /// <summary>(ADR 0026) A refusal, carrying the advisory <see cref="Reason"/>.</summary>
    public static Answer Declined(string id, string reason = "declined")
        => new() { Id = id, Ok = false, Reason = reason };
}
