using System.Text;

namespace Toolnexus;

/// <summary>
/// <c>transcript_search</c> — a read-only tool over a conversation store (SPEC §7E "Memory
/// hierarchy" → transcript search). The ground-truth tier of agent memory: a dreaming persona
/// checks its notes against what actually happened, not against its own notes.
/// </summary>
public static class TranscriptSearch
{
    /// <summary>The tool description (SPEC §7E, byte-exact).</summary>
    public const string Description =
        "Search past conversation transcripts — the ground truth of what actually happened. " +
        "Case-insensitive substring match over message text. Returns one line per matching " +
        "message: <conversationId>#<index> <role>: <snippet>. Read-only.";

    /// <summary>Options for <see cref="Tool"/>.</summary>
    public sealed class Options
    {
        /// <summary>Most hits returned per call (and the ceiling for the <c>limit</c> input). Default 20.</summary>
        public int MaxResults { get; set; } = 20;

        /// <summary>Snippet length in Unicode code points (not bytes). Default 200.</summary>
        public int SnippetChars { get; set; } = 200;

        /// <summary>Tool name. Default <c>transcript_search</c>.</summary>
        public string Name { get; set; } = "transcript_search";
    }

    /// <summary>
    /// A read-only <see cref="ITool"/> over <paramref name="store"/>. Throws unless the store is an
    /// <see cref="IListableConversationStore"/>. Input <c>{query, conversationId?, limit?}</c>; output
    /// one line per hit, <c>&lt;conversationId&gt;#&lt;index&gt; &lt;role&gt;: &lt;snippet&gt;</c>,
    /// <c>no matches</c> when none, and a trailing <c>(+&lt;k&gt; more)</c> when hits exceed the limit.
    /// </summary>
    public static ITool Tool(IConversationStore store, Options? options = null)
    {
        if (store is not IListableConversationStore listable)
            throw new ArgumentException("transcriptSearchTool: the store must implement list()");
        options ??= new Options();
        var maxResults = options.MaxResults;
        var snippetChars = options.SnippetChars;
        if (maxResults < 1) throw new ArgumentException("transcriptSearchTool: maxResults must be an integer >= 1");
        if (snippetChars < 1) throw new ArgumentException("transcriptSearchTool: snippetChars must be an integer >= 1");

        var schema = new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = new Dictionary<string, object?>
            {
                ["query"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "Text to find (case-insensitive substring)." },
                ["conversationId"] = new Dictionary<string, object?> { ["type"] = "string", ["description"] = "Search only this conversation." },
                ["limit"] = new Dictionary<string, object?> { ["type"] = "integer", ["description"] = $"Most hits to return (default and max {maxResults})." },
            },
            ["required"] = new List<object?> { "query" },
        };

        return NativeTool.OfAsync(options.Name, Description, schema, async (args, _) =>
        {
            var query = args.TryGetValue("query", out var q) && q is string qs ? Normalize(qs) : "";
            if (query.Length == 0) return ToolResult.Error("query is required");
            var limit = maxResults;
            if (args.TryGetValue("limit", out var l))
            {
                // Present-but-null is an error, not "absent" (§7E).
                if (l == null || !TryInteger(l, out var n) || n < 1) return ToolResult.Error("limit must be an integer >= 1");
                limit = (int)Math.Min(n, maxResults);
            }
            List<string> ids = args.TryGetValue("conversationId", out var c) && c is string cid
                ? new List<string> { cid }
                : (await listable.ListAsync().ConfigureAwait(false)).Select(e => e.Id).ToList();
            var needle = CodePoints(Fold(query));
            var hits = new List<string>();
            var total = 0;
            foreach (var id in ids)
            {
                var msgs = await listable.GetAsync(id).ConfigureAwait(false);
                if (msgs == null) continue;
                for (var index = 0; index < msgs.Count; index++)
                {
                    var msg = AsMap(msgs[index]);
                    var cps = CodePoints(MessageText(msg));
                    var at = IndexOf(cps.Select(FoldCp).ToArray(), needle);
                    if (at < 0) continue;
                    total++;
                    if (hits.Count < limit)
                    {
                        var role = msg != null && msg.TryGetValue("role", out var r) && r is string rs ? rs : "unknown";
                        hits.Add($"{id}#{index} {role}: {Snippet(cps, at, needle.Length, snippetChars)}");
                    }
                }
            }
            if (total == 0) return "no matches";
            if (total > hits.Count) hits.Add($"(+{total - hits.Count} more)");
            return (object?)string.Join("\n", hits);
        });
    }

    /// <summary>
    /// The searchable text of one stored message (SPEC §7E): string content, text parts, tool_use
    /// input as compact JSON, tool_result content (recursively), then each OpenAI tool call's
    /// <c>arguments</c> string — pieces joined by "\n", then whitespace-normalized.
    /// </summary>
    public static string MessageText(object? message)
    {
        var m = message as IDictionary<string, object?> ?? AsMap(message);
        if (m == null) return "";
        var pieces = PartsText(m.TryGetValue("content", out var content) ? content : null);
        if (m.TryGetValue("tool_calls", out var tcs) && tcs is IEnumerable<object?> calls)
        {
            foreach (var tc in calls)
                if (AsMap(tc) is { } tcm && tcm.TryGetValue("function", out var fn) && AsMap(fn) is { } f
                    && f.TryGetValue("arguments", out var a) && a is string s)
                    pieces.Add(s);
        }
        return Normalize(string.Join("\n", pieces));
    }

    private static List<string> PartsText(object? content)
    {
        var outp = new List<string>();
        if (content is string s) { outp.Add(s); return outp; }
        if (content is not IEnumerable<object?> list) return outp;
        foreach (var p in list)
        {
            if (AsMap(p) is not { } part) continue;
            var type = part.TryGetValue("type", out var t) ? t as string : null;
            if (type == "text" && part.TryGetValue("text", out var tx) && tx is string txs) outp.Add(txs);
            else if (type == "tool_use")
                {
                var sb = new System.Text.StringBuilder();
                WriteJson(sb, SortKeys(Json.ParseLoose(Json.Stringify(part.TryGetValue("input", out var inp) && inp != null ? inp : new Dictionary<string, object?>()))));
                outp.Add(sb.ToString());
            }
            else if (type == "tool_result") outp.AddRange(PartsText(part.TryGetValue("content", out var rc) ? rc : null));
        }
        return outp;
    }

    /// <summary>A copy with every object's keys sorted by code point, recursively (§7E); lists keep order.</summary>
    private static object? SortKeys(object? v)
    {
        if (v is IDictionary<string, object?> d)
        {
            var keys = new List<string>(d.Keys);
            keys.Sort(ByCodePoint);
            var o = new Dictionary<string, object?>();
            foreach (var k in keys) o[k] = SortKeys(d[k]);
            return o;
        }
        if (v is IList<object?> l)
        {
            var a = new List<object?>(l.Count);
            foreach (var x in l) a.Add(SortKeys(x));
            return a;
        }
        return v;
    }

    /// <summary>
    /// Compact JSON with JSON.stringify's string escaping (§7E): only <c>"</c>, <c>\\</c> and
    /// control characters are escaped — non-ASCII, U+2028/U+2029 and <c>&lt; &gt; &amp;</c> are
    /// emitted as-is (System.Text.Json's encoders escape some of these).
    /// </summary>
    private static void WriteJson(System.Text.StringBuilder sb, object? v)
    {
        switch (v)
        {
            case string s:
                WriteString(sb, s);
                break;
            case IDictionary<string, object?> d:
                sb.Append('{');
                var first = true;
                foreach (var (k, x) in d)
                {
                    if (!first) sb.Append(',');
                    first = false;
                    WriteString(sb, k);
                    sb.Append(':');
                    WriteJson(sb, x);
                }
                sb.Append('}');
                break;
            case IList<object?> l:
                sb.Append('[');
                for (var i = 0; i < l.Count; i++)
                {
                    if (i > 0) sb.Append(',');
                    WriteJson(sb, l[i]);
                }
                sb.Append(']');
                break;
            default:
                sb.Append(Json.Stringify(v)); // numbers, booleans, null
                break;
        }
    }

    private static void WriteString(System.Text.StringBuilder sb, string s)
    {
        sb.Append('"');
        for (var i = 0; i < s.Length; i++)
        {
            var c = s[i];
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\b': sb.Append("\\b"); break;
                case '\f': sb.Append("\\f"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (c < 0x20) sb.Append("\\u").Append(((int)c).ToString("x4"));
                    else if (char.IsHighSurrogate(c) && i + 1 < s.Length && char.IsLowSurrogate(s[i + 1])) { sb.Append(c).Append(s[++i]); }
                    else if (char.IsSurrogate(c)) sb.Append("\\u").Append(((int)c).ToString("x4")); // lone surrogate, as JSON.stringify
                    else sb.Append(c);
                    break;
            }
        }
        sb.Append('"');
    }

    private static int ByCodePoint(string a, string b)
    {
        int i = 0, j = 0;
        while (i < a.Length && j < b.Length)
        {
            int x = CodePointAt(a, i), y = CodePointAt(b, j);
            if (x != y) return x.CompareTo(y);
            i += x > 0xFFFF ? 2 : 1;
            j += y > 0xFFFF ? 2 : 1;
        }
        return (a.Length - i).CompareTo(b.Length - j);
    }

    private static int CodePointAt(string s, int i) =>
        char.IsSurrogatePair(s, i) ? char.ConvertToUtf32(s[i], s[i + 1]) : s[i];

    /// <summary>Messages may be stored as plain maps or as JSON nodes/elements; view them as maps.</summary>
    private static IDictionary<string, object?>? AsMap(object? o) => o switch
    {
        null => null,
        IDictionary<string, object?> d => d,
        string => null,
        _ => Json.ParseLoose(Json.Stringify(o)) as IDictionary<string, object?>,
    };

    private static bool TryInteger(object o, out long n)
    {
        switch (o)
        {
            case int i: n = i; return true;
            case long l: n = l; return true;
            case double d when Math.Floor(d) == d && !double.IsInfinity(d): n = (long)d; return true;
            case decimal m when Math.Floor(m) == m: n = (long)m; return true;
            case System.Text.Json.JsonElement je when je.ValueKind == System.Text.Json.JsonValueKind.Number:
                if (je.TryGetInt64(out n)) return true;
                var dv = je.GetDouble();
                if (Math.Floor(dv) == dv) { n = (long)dv; return true; }
                break;
        }
        n = 0;
        return false;
    }

    private static bool IsAsciiSpace(char c) => c is ' ' or '\t' or '\n' or '\r' or '\f' or '\v';

    /// <summary>Collapse every run of ASCII whitespace to one space and trim it (SPEC §7E).</summary>
    private static string Normalize(string s)
    {
        var sb = new StringBuilder(s.Length);
        var pendingSpace = false;
        foreach (var c in s)
        {
            if (IsAsciiSpace(c)) { pendingSpace = true; continue; }
            if (pendingSpace && sb.Length > 0) sb.Append(' ');
            pendingSpace = false;
            sb.Append(c);
        }
        return sb.ToString();
    }

    /// <summary>ASCII-only case fold — never Unicode lowercasing.</summary>
    private static string Fold(string s)
    {
        var a = s.ToCharArray();
        for (var i = 0; i < a.Length; i++) if (a[i] is >= 'A' and <= 'Z') a[i] = (char)(a[i] + 32);
        return new string(a);
    }

    private static string FoldCp(string cp) => cp.Length == 1 ? Fold(cp) : cp;

    /// <summary>Split into code points (a lone surrogate stays its own element).</summary>
    private static string[] CodePoints(string s)
    {
        var outp = new List<string>(s.Length);
        for (var i = 0; i < s.Length; i++)
        {
            if (char.IsHighSurrogate(s[i]) && i + 1 < s.Length && char.IsLowSurrogate(s[i + 1]))
            {
                outp.Add(s.Substring(i, 2));
                i++;
            }
            else outp.Add(s[i].ToString());
        }
        return outp.ToArray();
    }

    private static int IndexOf(string[] hay, string[] needle)
    {
        for (var i = 0; i + needle.Length <= hay.Length; i++)
        {
            var ok = true;
            for (var j = 0; j < needle.Length; j++)
                if (!string.Equals(hay[i + j], needle[j], StringComparison.Ordinal)) { ok = false; break; }
            if (ok) return i;
        }
        return -1;
    }

    /// <summary>Snippet of <paramref name="size"/> code points centred on the match; "…" marks elision.</summary>
    private static string Snippet(string[] cps, int at, int qlen, int size)
    {
        if (cps.Length <= size) return string.Concat(cps);
        var start = at + qlen / 2 - size / 2;
        start = Math.Max(0, Math.Min(start, cps.Length - size));
        var end = start + size;
        return (start > 0 ? "…" : "") + string.Concat(cps[start..end]) + (end < cps.Length ? "…" : "");
    }
}
