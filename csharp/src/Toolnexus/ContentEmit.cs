namespace Toolnexus;

/// <summary>
/// §8A Content-part emission. Turns a <see cref="ContentPart"/> into the provider's block shape
/// for a client style (<c>"openai" | "anthropic"</c>), guarded by a <b>positive allowlist</b>:
/// for each <c>(style, part.type)</c> there is either a defined block shape or an explicit
/// refusal, and the encoded block is asserted against that allowlist <b>before</b> the request is
/// sent. A part that produced no allowlisted block never reaches the wire.
///
/// <para>This lives here and not in <c>Adapters.cs</c> deliberately — the adapter module is tool
/// <b>schema</b> only, in every port. Emission belongs to message assembly.</para>
///
/// <para>Gemini request emission is out of scope: <c>ClientStyle</c> is <c>openai|anthropic</c>
/// and <c>ToGemini</c> emits declarations for a caller's own client, not requests.</para>
/// </summary>
internal static class ContentEmit
{
    /// <summary>The positive allowlist — block <c>type</c> values a style will accept.</summary>
    private static readonly IReadOnlyDictionary<string, HashSet<string>> Allowed =
        new Dictionary<string, HashSet<string>>
        {
            ["openai"] = new() { "text", "image_url", "file", "input_audio" },
            ["anthropic"] = new() { "text", "image", "document" },
        };

    /// <summary>How an unsupported part is handled — see <see cref="Provenance"/>.</summary>
    internal enum Provenance
    {
        /// <summary>The caller attached it: an unrepresentable part is a typed error, before any HTTP.</summary>
        Attached,

        /// <summary>A tool / MCP result volunteered it: degrade to a text placeholder, warn once.</summary>
        Derived,
    }

    /// <summary>Normalise a style string to one of the two known client styles.</summary>
    internal static string StyleOf(string? style) => style == "anthropic" ? "anthropic" : "openai";

    /// <summary>
    /// Encode one part into its provider block, or <c>null</c> when the style refuses it
    /// (<c>openai × file+url</c> — Chat Completions has no URL form for a file;
    /// <c>anthropic × audio</c> — the provider defines no audio block).
    /// </summary>
    internal static Dictionary<string, object?>? Encode(ContentPart p, string style)
    {
        if (p.Type == "text")
            return new Dictionary<string, object?> { ["type"] = "text", ["text"] = p.Text ?? "" };

        var mime = p.MimeType ?? "";
        var hasData = !string.IsNullOrEmpty(p.Data);

        if (style == "anthropic")
        {
            switch (p.Type)
            {
                case "image":
                    return new Dictionary<string, object?>
                    {
                        ["type"] = "image",
                        ["source"] = hasData
                            ? new Dictionary<string, object?> { ["type"] = "base64", ["media_type"] = mime, ["data"] = p.Data }
                            : new Dictionary<string, object?> { ["type"] = "url", ["url"] = p.Url },
                    };
                case "file":
                    return new Dictionary<string, object?>
                    {
                        ["type"] = "document",
                        ["source"] = hasData
                            ? new Dictionary<string, object?> { ["type"] = "base64", ["media_type"] = mime, ["data"] = p.Data }
                            : new Dictionary<string, object?> { ["type"] = "url", ["url"] = p.Url },
                    };
                default:
                    return null; // audio — Anthropic defines no audio block.
            }
        }

        switch (p.Type)
        {
            case "image":
                return new Dictionary<string, object?>
                {
                    ["type"] = "image_url",
                    ["image_url"] = new Dictionary<string, object?>
                    {
                        ["url"] = hasData ? DataUrl(mime, p.Data!) : p.Url,
                    },
                };
            case "file":
                // Chat Completions has no URL form for a file, and `file_data` REQUIRES the
                // `data:<mime>;base64,` prefix — a bare base64 string is a 400.
                if (!hasData) return null;
                return new Dictionary<string, object?>
                {
                    ["type"] = "file",
                    ["file"] = new Dictionary<string, object?>
                    {
                        ["filename"] = p.Name ?? "file",
                        ["file_data"] = DataUrl(mime, p.Data!),
                    },
                };
            case "audio":
                if (!hasData) return null;
                return new Dictionary<string, object?>
                {
                    ["type"] = "input_audio",
                    ["input_audio"] = new Dictionary<string, object?>
                    {
                        ["data"] = p.Data,
                        ["format"] = AudioFormat(mime),
                    },
                };
            default:
                return null;
        }
    }

    /// <summary>
    /// Encode a list of parts into provider blocks, applying the §8A provenance rule and the
    /// positive allowlist. <paramref name="onUnsupportedPart"/> (<c>"error"</c>/<c>"text"</c>)
    /// overrides the provenance default uniformly; <paramref name="warnOnce"/> is invoked at most
    /// once per client for a degraded part.
    /// <para><paramref name="maxPartBytes"/> (§1B) is enforced HERE, at assembly, over every part
    /// regardless of provenance — a part that arrived from an MCP server never passed through an
    /// edge constructor, so a limit only checked there is not a limit. Going over follows the
    /// same provenance split as an unrepresentable part: an attached part errors, a tool-derived
    /// one degrades to the placeholder.</para>
    /// </summary>
    internal static List<object?> Blocks(IEnumerable<ContentPart> parts, string style,
        Provenance provenance, string? onUnsupportedPart, Action<string> warnOnce, long? maxPartBytes = null)
    {
        var s = StyleOf(style);
        var allow = Allowed[s];
        var blocks = new List<object?>();
        var strict = onUnsupportedPart == "error"
            || (onUnsupportedPart != "text" && provenance == Provenance.Attached);
        foreach (var raw in parts)
        {
            var p = raw.Validate();

            if (maxPartBytes is > 0 && p.Type != "text" && p.ByteLength > maxPartBytes.Value)
            {
                var oversize = $"content part: {p.ByteLength} decoded bytes exceeds maxPartBytes {maxPartBytes.Value}";
                if (strict) throw new ContentPart.InvalidPartException(oversize);
                warnOnce($"[toolnexus] {oversize} — sent as a text placeholder");
                blocks.Add(new Dictionary<string, object?> { ["type"] = "text", ["text"] = p.UnsupportedPlaceholderText() });
                continue;
            }

            var block = Encode(p, s);
            // The allowlist assertion: map-and-hope is the exact bug this rule removes — an
            // unknown block type upstream returns HTTP 200 with the content silently discarded.
            if (block != null && !allow.Contains(block["type"] as string ?? ""))
                block = null;
            if (block != null)
            {
                blocks.Add(block);
                continue;
            }
            var describe = $"{p.Type} part ({p.MimeType}) cannot be represented by the \"{s}\" style";
            if (strict) throw new ContentPart.InvalidPartException($"content part: {describe}");
            warnOnce($"[toolnexus] {describe} — sent as a text placeholder");
            blocks.Add(new Dictionary<string, object?>
            {
                ["type"] = "text",
                ["text"] = p.UnsupportedPlaceholderText(),
            });
        }
        return blocks;
    }

    /// <summary>True when the list holds nothing but <c>text</c> parts.</summary>
    internal static bool AllText(IEnumerable<ContentPart> parts) => parts.All(p => p.Type == "text");

    /// <summary>Concatenate the text of a text-only part list — the byte-identical string path.</summary>
    internal static string JoinText(IEnumerable<ContentPart> parts)
        => string.Concat(parts.Select(p => p.Text ?? ""));

    /// <summary>Non-text parts of a tool result, in order (empty when there are none).</summary>
    internal static List<ContentPart> NonText(IReadOnlyList<ContentPart>? parts)
        => parts == null ? new List<ContentPart>() : parts.Where(p => p.Type != "text").ToList();

    /// <summary>Text parts of a tool result, in order.</summary>
    internal static List<ContentPart> TextOnly(IReadOnlyList<ContentPart>? parts)
        => parts == null ? new List<ContentPart>() : parts.Where(p => p.Type == "text").ToList();

    private static string DataUrl(string mime, string b64) => $"data:{mime};base64,{b64}";

    /// <summary>OpenAI's <c>input_audio.format</c> — the subtype, so <c>audio/mpeg</c> ⇒ <c>mp3</c>.</summary>
    private static string AudioFormat(string mime) => mime switch
    {
        "audio/mpeg" or "audio/mp3" => "mp3",
        "audio/wav" or "audio/x-wav" or "audio/wave" => "wav",
        _ => mime.Contains('/') ? mime[(mime.IndexOf('/') + 1)..] : mime,
    };
}
