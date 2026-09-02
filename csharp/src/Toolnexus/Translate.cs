using System.Collections.Generic;
using System.Linq;

namespace Toolnexus;

/// <summary>
/// Single-turn translation types and the pure translation functions (SPEC.md §11, ADR-0011).
/// <para>
/// <see cref="LlmClient.TranslateAsync"/> is toolnexus used as a pure wire-format translator:
/// OpenAI shapes in, exactly ONE provider call, OpenAI shapes out. No agent loop, no tool
/// execution, no conversation state — so a caller can run it statelessly.
/// </para>
/// <para>
/// It is the INBOUND half of the §5 adapters: <c>ToOpenAI</c>/<c>ToAnthropic</c>/<c>ToGemini</c>
/// send declarations out, this reads the provider's tool calls back in.
/// </para>
/// <para>
/// Use it when the CALLER owns the conversation and executes tools itself (the standard OpenAI
/// function-calling posture). When toolnexus owns the conversation, use the agent loop with
/// relay tools instead (§10).
/// </para>
/// </summary>
public static class Translate
{
    /// <summary>An OpenAI-shaped chat request, handed over verbatim (§11).</summary>
    public sealed class Request
    {
        /// <summary>
        /// The OpenAI <c>messages</c> array, verbatim — including assistant turns carrying
        /// <c>tool_calls</c> and <c>tool</c>-role results carrying <c>tool_call_id</c>.
        /// </summary>
        public List<object?> Messages { get; set; } = new();

        /// <summary>
        /// The OpenAI <c>tools</c> array, verbatim. Declaration-only — nothing here is ever
        /// executed.
        /// </summary>
        public List<object?>? Tools { get; set; }

        /// <summary>
        /// Declares an ordinary toolkit's tools to the provider WITHOUT executing any of them —
        /// MCP tools, skills, native functions, A2A agents, builtins. Composes with
        /// <see cref="Tools"/>; toolkit declarations come first.
        /// </summary>
        public Toolkit? Toolkit { get; set; }

        /// <summary>The OpenAI <c>tool_choice</c>, verbatim. Null omits it.</summary>
        public object? ToolChoice { get; set; }

        /// <summary>Overrides the system prompt. Null uses any <c>system</c> message in the list.</summary>
        public string? System { get; set; }

        /// <summary>Overrides the per-provider default max tokens. 0 uses the default.</summary>
        public int MaxTokens { get; set; }
    }

    /// <summary>One tool call the model asked for, in OpenAI shape (§11).</summary>
    /// <param name="Id">The tool-call id the caller must echo on its <c>tool</c> result message.</param>
    /// <param name="Name">The function name.</param>
    /// <param name="Arguments">
    /// Arguments as a JSON <b>string</b> — the OpenAI wire form, echoable byte-for-byte.
    /// </param>
    public sealed record ToolCall(string Id, string Name, string Arguments);

    /// <summary>An OpenAI-shaped single-turn result (§11).</summary>
    public sealed class Result
    {
        /// <summary>Assistant text ("" when the model only called tools).</summary>
        public string Text { get; set; } = "";

        /// <summary>The tool calls the model emitted, in provider order. None is ever dropped.</summary>
        public List<ToolCall> ToolCalls { get; set; } = new();

        /// <summary>
        /// The OpenAI finish reason. A turn with any tool call is always <c>"tool_calls"</c>.
        /// </summary>
        public string FinishReason { get; set; } = "stop";

        /// <summary>This single call's token usage.</summary>
        public LlmClient.Usage Usage { get; set; } = new();

        /// <summary>The model that answered.</summary>
        public string Model { get; set; } = "";

        /// <summary>The provider's decoded response, for fields this type does not model.</summary>
        public IDictionary<string, object?>? Raw { get; set; }

        /// <summary>
        /// Renders the tool calls as an OpenAI <c>tool_calls</c> array, ready to put on an
        /// assistant message. Convenience for assembling a response envelope.
        /// </summary>
        public List<object?> ToolCallsJson() =>
            ToolCalls.Select(tc => (object?)new Dictionary<string, object?>
            {
                ["id"] = tc.Id,
                ["type"] = "function",
                ["function"] = new Dictionary<string, object?> { ["name"] = tc.Name, ["arguments"] = tc.Arguments },
            }).ToList();
    }

    /// <summary>
    /// Maps a provider stop reason onto an OpenAI finish reason. Tool calls win: a turn that
    /// emitted any tool call is always <c>"tool_calls"</c> to a conforming client.
    /// </summary>
    public static string FinishReasonFor(bool hasToolCalls, string? providerStop) =>
        hasToolCalls ? "tool_calls" : providerStop switch
        {
            "max_tokens" or "length" => "length",
            "refusal" or "content_filter" => "content_filter",
            _ => "stop",
        };

    /// <summary>
    /// Flattens an OpenAI <c>content</c> value to text: the string form and the parts-array
    /// form. Non-text parts are ignored.
    /// </summary>
    public static string ContentText(object? content)
    {
        if (content is string s) return s;
        if (content is IEnumerable<object?> parts)
        {
            var sb = new System.Text.StringBuilder();
            foreach (var p in parts)
            {
                if (p is ContentPart cp) { if (cp.Type == "text") sb.Append(cp.Text ?? ""); continue; }
                if (p is IDictionary<string, object?> pm && pm.Get("text") is string t) sb.Append(t);
            }
            return sb.ToString();
        }
        return "";
    }

    /// <summary>
    /// Parses a tool-call arguments value into an object, tolerating both wire forms — some
    /// clients send <c>arguments</c> as an object rather than a JSON string.
    /// </summary>
    public static IDictionary<string, object?> ArgsObject(object? args)
    {
        if (args is IDictionary<string, object?> m) return m;
        if (args is string s && !string.IsNullOrWhiteSpace(s))
        {
            try
            {
                if (Json.ParseLoose(s) is IDictionary<string, object?> parsed) return parsed;
            }
            catch
            {
                // a malformed arguments string is not fatal — fall through to {}
            }
        }
        return new Dictionary<string, object?>();
    }

    /// <summary>Renders a tool-call arguments value as the JSON string the OpenAI wire format uses.</summary>
    public static string ArgsString(object? args)
    {
        if (args is string s) return s;
        if (args is IDictionary<string, object?> m) return Json.Stringify(m);
        return "{}";
    }

    /// <summary>Reads an assistant message's OpenAI <c>tool_calls</c>.</summary>
    public static List<ToolCall> ToolCallsOf(IDictionary<string, object?> message)
    {
        var out_ = new List<ToolCall>();
        if (message.Get("tool_calls") is not IEnumerable<object?> list) return out_;
        foreach (var e in list)
        {
            if (e is not IDictionary<string, object?> tc) continue;
            if (tc.Get("function") is not IDictionary<string, object?> fn) continue;
            out_.Add(new ToolCall(
                tc.Get("id") as string ?? "",
                fn.Get("name") as string ?? "",
                ArgsString(fn.Get("arguments"))));
        }
        return out_;
    }

    /// <summary>The result of converting an OpenAI message list: provider messages + hoisted system.</summary>
    public sealed record Converted(List<object?> Messages, string System);

    /// <summary>
    /// Converts an OpenAI <c>messages</c> array into Anthropic-native messages plus the
    /// extracted system prompt, preserving the tool structure a text flattening destroys (§11):
    /// <list type="bullet">
    /// <item>an assistant turn's <c>tool_calls</c> become <c>tool_use</c> blocks, with
    /// <c>arguments</c> parsed back from its JSON string into an object;</item>
    /// <item>a <c>tool</c>-role result becomes a <c>tool_result</c> block keyed by
    /// <c>tool_call_id</c>, MERGED into a single user turn when consecutive (providers expect one
    /// result-bearing turn answering the preceding assistant turn);</item>
    /// <item><c>system</c>/<c>developer</c> messages are hoisted out, since Anthropic takes
    /// system separately.</item>
    /// </list>
    /// </summary>
    public static Converted OpenAIMessagesToAnthropic(IEnumerable<object?>? messages)
    {
        var outMsgs = new List<object?>();
        var systemParts = new List<string>();
        var pending = new List<object?>();

        void Flush()
        {
            if (pending.Count == 0) return;
            outMsgs.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = new List<object?>(pending) });
            pending.Clear();
        }

        foreach (var raw in messages ?? Enumerable.Empty<object?>())
        {
            if (raw is not IDictionary<string, object?> m) continue;
            var role = m.Get("role") as string ?? "";
            switch (role)
            {
                case "system":
                case "developer":
                {
                    Flush();
                    var s = ContentText(m.Get("content"));
                    if (s.Length > 0) systemParts.Add(s);
                    break;
                }
                case "tool":
                case "function":
                {
                    var block = new Dictionary<string, object?>
                    {
                        ["type"] = "tool_result",
                        // Anthropic's tool_result.content takes native blocks (§8A), so a tool
                        // result carrying an image survives translation too.
                        ["content"] = ContentToAnthropic(m.Get("content")),
                    };
                    if (m.Get("tool_call_id") is string id && id.Length > 0) block["tool_use_id"] = id;
                    pending.Add(block);
                    break;
                }
                case "assistant":
                {
                    Flush();
                    var blocks = new List<object?>();
                    var s = ContentText(m.Get("content"));
                    if (s.Length > 0) blocks.Add(new Dictionary<string, object?> { ["type"] = "text", ["text"] = s });
                    foreach (var tc in ToolCallsOf(m))
                    {
                        blocks.Add(new Dictionary<string, object?>
                        {
                            ["type"] = "tool_use",
                            ["id"] = tc.Id,
                            ["name"] = tc.Name,
                            ["input"] = ArgsObject(tc.Arguments),
                        });
                    }
                    if (blocks.Count == 0) break; // an empty assistant turn would be rejected
                    outMsgs.Add(new Dictionary<string, object?> { ["role"] = "assistant", ["content"] = blocks });
                    break;
                }
                default:
                {
                    Flush();
                    // §11: text parts concatenate, non-text parts translate into the provider's
                    // native block shape (§8A). Nothing is flattened away or dropped.
                    var translated = ContentToAnthropic(m.Get("content"));
                    if (translated is string str)
                    {
                        if (str.Length > 0)
                            outMsgs.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = str });
                    }
                    else if (translated is List<object?> blocks && blocks.Count > 0)
                    {
                        outMsgs.Add(new Dictionary<string, object?> { ["role"] = "user", ["content"] = blocks });
                    }
                    break;
                }
            }
        }
        Flush();
        return new Converted(outMsgs, string.Join("\n\n", systemParts));
    }

    /// <summary>
    /// Converts an OpenAI <c>tools</c> array into Anthropic tool declarations. Entries that are
    /// already provider-native pass through; anything unrecognized is skipped.
    /// </summary>
    public static List<Dictionary<string, object?>> OpenAIToolsToAnthropic(IEnumerable<object?>? tools)
    {
        var out_ = new List<Dictionary<string, object?>>();
        foreach (var raw in tools ?? Enumerable.Empty<object?>())
        {
            if (raw is not IDictionary<string, object?> t) continue;
            if (t.Get("function") is not IDictionary<string, object?> fn)
            {
                if (t.Get("name") != null) out_.Add(new Dictionary<string, object?>(t)); // already native
                continue;
            }
            if (fn.Get("name") is not string name || name.Length == 0) continue;
            var decl = new Dictionary<string, object?> { ["name"] = name };
            if (fn.Get("description") is string d && d.Length > 0) decl["description"] = d;
            decl["input_schema"] = fn.Get("parameters") is IDictionary<string, object?> p
                ? p
                : new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() };
            out_.Add(decl);
        }
        return out_;
    }

    /// <summary>
    /// Maps OpenAI <c>tool_choice</c> onto Anthropic's shape. Returns null for absent/"auto"
    /// (the provider default) and for anything unrecognized.
    /// </summary>
    public static Dictionary<string, object?>? OpenAIToolChoiceToAnthropic(object? choice)
    {
        if (choice is string s)
        {
            if (s is "required" or "any") return new Dictionary<string, object?> { ["type"] = "any" };
            if (s == "none") return new Dictionary<string, object?> { ["type"] = "none" };
            return null;
        }
        if (choice is IDictionary<string, object?> cm
            && cm.Get("function") is IDictionary<string, object?> fn
            && fn.Get("name") is string name && name.Length > 0)
        {
            return new Dictionary<string, object?> { ["type"] = "tool", ["name"] = name };
        }
        return null;
    }

    /// <summary>
    /// (§11) Translate an OpenAI <c>content</c> value into the Anthropic shape, preserving
    /// non-text parts instead of flattening them away. An all-text value collapses to the
    /// concatenated string (byte-identical to the previous behaviour); anything else becomes a
    /// native block array in the order given. A part the style cannot represent degrades to a
    /// named text placeholder — it is never dropped silently, and it never fails the call.
    /// </summary>
    public static object? ContentToAnthropic(object? content)
    {
        if (content is not IEnumerable<object?> raw || content is string) return ContentText(content);
        var items = raw.ToList();
        if (items.Count == 0) return "";

        var parts = items.Select(AsPart).ToList();
        if (parts.All(x => x is { Type: "text" }))
            return string.Concat(parts.Select(x => x!.Text ?? ""));

        var blocks = new List<object?>();
        for (var i = 0; i < items.Count; i++)
        {
            var part = parts[i];
            if (part == null) { blocks.Add(items[i]); continue; } // already provider-native
            var block = ContentEmitBlock(part);
            blocks.Add(block ?? new Dictionary<string, object?>
            {
                ["type"] = "text",
                ["text"] = part.UnsupportedPlaceholderText(),
            });
        }
        return blocks;
    }

    private static Dictionary<string, object?>? ContentEmitBlock(ContentPart part)
    {
        var block = ContentEmit.Encode(part, "anthropic");
        return block != null && block["type"] is "text" or "image" or "document" ? block : null;
    }

    /// <summary>
    /// Read one <c>content</c> entry as a §1B part. Returns null when the entry is already a
    /// provider-native block we should not touch.
    /// </summary>
    private static ContentPart? AsPart(object? item)
    {
        if (item is ContentPart cp) return cp;
        if (item is string s) return ContentPart.FromText(s);
        if (item is not IDictionary<string, object?> m) return null;
        var type = m.Get("type") as string ?? "";
        switch (type)
        {
            case "text":
                return ContentPart.FromText(m.Get("text") as string ?? "");
            case "image" or "file" or "audio" when m.Get("mimeType") is string mime:
                // Our own §1B shape, arriving as a plain map.
                return new ContentPart
                {
                    Type = type,
                    MimeType = mime,
                    Data = m.Get("data") as string,
                    Url = m.Get("url") as string,
                    Name = m.Get("name") as string,
                };
            case "image_url":
                return FromDataOrUrl("image", (m.Get("image_url") as IDictionary<string, object?>)?.Get("url") as string);
            case "input_audio":
            {
                var ia = m.Get("input_audio") as IDictionary<string, object?>;
                var data = ia?.Get("data") as string;
                if (data == null) return null;
                var fmt = ia?.Get("format") as string ?? "mpeg";
                return new ContentPart { Type = "audio", MimeType = fmt == "mp3" ? "audio/mpeg" : $"audio/{fmt}", Data = data };
            }
            default:
                return null; // already Anthropic-native (image/document with a source), or unknown
        }
    }

    private static ContentPart? FromDataOrUrl(string type, string? url)
    {
        if (string.IsNullOrEmpty(url)) return null;
        try { return ContentPart.FromUrl(url!, type == "image" ? "image/png" : null); }
        catch (ContentPart.InvalidPartException) { return null; }
    }

    /// <summary>True when the message list already carries a system-ish message.</summary>
    public static bool HasSystemMessage(IEnumerable<object?>? messages) =>
        (messages ?? Enumerable.Empty<object?>()).Any(m =>
            m is IDictionary<string, object?> mm
            && mm.Get("role") is string r
            && (r == "system" || r == "developer"));
}
