using System.Collections.Generic;
using System.Text.Json;

namespace Toolnexus;

/// <summary>
/// SPEC §8 "Stop-reason recovery": the three pinned user messages (byte-exact in every port) and
/// the per-run state — budgets (corrective 1, continue 3, nudge 1) and the carried text.
/// </summary>
public static class StopRecoveryMessages
{
    public const string Nudge = "Your previous response contained no answer. Continue and give your answer.";
    public const string Continue = "Your previous response was cut off at the output token limit. Continue exactly where it stopped, without repeating anything.";
    public const string Corrective = "Your previous tool call could not be used: its arguments were not a valid JSON object. Call the tool again with arguments that are a single valid JSON object matching its input schema.";
}

internal sealed class StopRecoveryState
{
    private readonly bool _on;
    private int _correct, _cont, _nudges;
    public string Carry = "";

    public StopRecoveryState(bool on) { _on = on; }

    /// <summary>Classifies one response (SPEC §8 order). Returns the pinned text to append (null ⇒ no
    /// recovery) and whether the assistant message must be dropped from the transcript.</summary>
    public (string? Text, bool Drop) Decide(bool lastTurn, bool hasCalls, bool malformed, bool truncated, string text)
    {
        if (!_on || lastTurn) return (null, false);
        if (hasCalls && malformed && _correct < 1) { _correct++; return (StopRecoveryMessages.Corrective, true); }
        if (!hasCalls && truncated && _cont < 3) { _cont++; Carry += text; return (StopRecoveryMessages.Continue, false); }
        if (!hasCalls && !truncated && string.IsNullOrWhiteSpace(text) && _nudges < 1) { _nudges++; return (StopRecoveryMessages.Nudge, true); }
        return (null, false);
    }

    /// <summary>The run's text: carried continuations + the final response's text.</summary>
    public string Final(string text) => Carry + text;

    /// <summary>A raw arguments string that is non-empty after trim and not a JSON object.</summary>
    public static bool MalformedArgs(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return false;
        try
        {
            using var doc = JsonDocument.Parse(raw);
            return doc.RootElement.ValueKind != JsonValueKind.Object;
        }
        catch { return true; }
    }

    /// <summary>An Anthropic tool_use input that is present and not an object.</summary>
    public static bool MalformedInput(object? input) => input != null && input is not IDictionary<string, object?>;
}
