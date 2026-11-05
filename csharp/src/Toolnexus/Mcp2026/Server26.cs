using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Toolnexus.Mcp2026;

/// <summary>Signals SPEC §2.2a "fall back to the frozen path" (the server is not a 2026-07-28 server).</summary>
internal sealed class FallbackException(string message) : Exception(message);

/// <summary>
/// One MCP server on the 2026-07-28 path (SPEC §2.2–§2.8): discover, tools/list, tools/call with MRTR → §10,
/// OAuth pause/resume → §10, the skills extension and lazy tool search.
/// </summary>
internal sealed class Server26 : IAsyncDisposable
{
    public string Name { get; }
    public Conn Conn { get; }
    public long TimeoutMs { get; }
    public Func<Request, Task<Answer>>? WaitFor { get; }
    public JsonObject Discover { get; private set; } = new();
    public bool ToolSearch { get; init; }
    public string Description { get; init; } = "";
    /// <summary>The per-server <c>tools</c> filter (original names); applied before tool search ranks or runs.</summary>
    public Func<List<JsonObject>, List<JsonObject>>? Filter { get; set; }

    /// <summary>tools/list after the per-server filter — the only tools search_tools ranks and call_tool runs.</summary>
    public async Task<List<JsonObject>> AllowedToolsAsync(CancellationToken ct)
    {
        var all = await ListToolsAsync(ct).ConfigureAwait(false);
        return Filter == null ? all : Filter(all);
    }

    private List<JsonObject>? _toolCache;
    private DateTime _toolExpiry;
    private readonly SemaphoreSlim _toolMu = new(1, 1);

    /// <summary>Paused tool calls by §10 request id: an MRTR round awaiting input, or a request awaiting authorization.</summary>
    private readonly ConcurrentDictionary<string, Paused> _paused = new();

    public Server26(string name, Conn conn, long timeoutMs, Func<Request, Task<Answer>>? waitFor)
    {
        Name = name;
        Conn = conn;
        TimeoutMs = timeoutMs;
        WaitFor = waitFor;
    }

    public ValueTask DisposeAsync() => Conn.DisposeAsync();

    private CancellationTokenSource Budget(CancellationToken parent, long ms)
    {
        var cts = CancellationTokenSource.CreateLinkedTokenSource(parent);
        cts.CancelAfter(TimeSpan.FromMilliseconds(ms));
        return cts;
    }

    /// <summary>
    /// A load-time request: a pause is resolved inline through <see cref="WaitFor"/>; without one it is an
    /// isolated load failure (<c>authorization pending</c>, SPEC §2A.1a).
    /// </summary>
    private async Task<JsonObject> LoadCallAsync(string method, JsonObject? p, long budgetMs, CancellationToken ct)
    {
        for (var i = 0; ; i++)
        {
            try
            {
                using var cts = Budget(ct, budgetMs);
                return await Conn.CallAsync(method, p, cts.Token).ConfigureAwait(false);
            }
            catch (PendingAuthorizationException pa)
            {
                if (WaitFor == null || i >= 2) throw new WireException("authorization pending");
                await AnswerAuthorizationAsync(pa.Request, await WaitFor(pa.Request).ConfigureAwait(false), ct).ConfigureAwait(false);
            }
        }
    }

    private async Task AnswerAuthorizationAsync(Request req, Answer? answer, CancellationToken ct)
    {
        if (answer == null || !answer.Ok)
            throw new WireException("authorization denied: " + (string.IsNullOrEmpty(answer?.Reason) ? "cancelled" : answer!.Reason));
        var cb = answer.Data?.Get("callbackUrl") as string ?? "";
        await Conn.Auth!.ResumeAsync(req.Id, cb, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// SPEC §2.2/§2.2a: <c>server/discover</c> first. Throws <see cref="FallbackException"/> when the server is
    /// not a 2026-07-28 server; any other exception is a load failure.
    /// </summary>
    public async Task DiscoverAsync(CancellationToken ct)
    {
        var budget = Conn.IsStdio ? Math.Min(TimeoutMs, 5000) : TimeoutMs;
        JsonObject res;
        try { res = await LoadCallAsync("server/discover", null, budget, ct).ConfigureAwait(false); }
        catch (RpcException e) { throw new FallbackException($"server/discover: {e.Code} {e.Message}"); }
        catch (HttpStatusException e) when (e.Status is 400 or 404 or 405 or >= 500) { throw new FallbackException(e.Message); } // 5xx: Java SDK servers answer unknown methods with 500
        catch (WireException e) when (!Conn.IsStdio && e.Message.Contains("neither result nor error")) { throw new FallbackException(e.Message); }
        catch (OperationCanceledException) when (Conn.IsStdio && !ct.IsCancellationRequested)
        {
            throw new FallbackException("server/discover: no reply"); // older stdio servers may ignore a pre-initialize request
        }
        if (!J.Strings(res["supportedVersions"]).Contains(Wire.ProtocolVersion))
            throw new FallbackException("server/discover: 2026-07-28 not supported");
        Discover = res;
    }

    public bool HasSkills => Discover["capabilities"]?["extensions"]?[Wire.SkillsExt] != null;

    /// <summary>tools/list, cached for its <c>ttlMs</c> (absent ⇒ fetched once per load).</summary>
    public async Task<List<JsonObject>> ListToolsAsync(CancellationToken ct)
    {
        await _toolMu.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_toolCache != null && (_toolExpiry == DateTime.MaxValue || DateTime.UtcNow < _toolExpiry)) return _toolCache;
            var r = await LoadCallAsync("tools/list", null, TimeoutMs, ct).ConfigureAwait(false);
            _toolCache = (r["tools"] as JsonArray ?? []).OfType<JsonObject>().Select(t => (JsonObject)t.DeepClone()).ToList();
            var ttl = J.Num(r["ttlMs"]);
            _toolExpiry = ttl is > 0 ? DateTime.UtcNow.AddMilliseconds(ttl.Value) : DateTime.MaxValue;
            return _toolCache;
        }
        finally { _toolMu.Release(); }
    }

    // ------------------------------------------------------------------ tools/call + MRTR (SPEC §2.5)

    private sealed class Paused
    {
        public required string Tool;
        public required JsonObject Args;
        public JsonObject? Responses;     // inputResponses of the round to send (null on round 0)
        public JsonNode? State;           // requestState echoed verbatim
        public int Round;
        public bool Authorization;        // true ⇒ waiting for an authorization callback, then send this round
        // MRTR input collection: requests still unanswered, and answers collected for the NEXT round.
        public List<(string Key, Request Req)> Asks = [];
        public JsonObject Collected = [];
    }

    private static readonly Dictionary<string, object?> NoArgs = [];

    /// <summary>Run a server tool through MRTR. Resumes a paused round when <paramref name="ctx"/> carries its Answer.</summary>
    public async Task<ToolResult> CallToolAsync(string tool, IDictionary<string, object?>? args, ToolContext? ctx)
    {
        var meta = new Dictionary<string, object?> { ["server"] = Name };
        var caller = ctx?.CancellationToken ?? default;
        Paused st;
        if (ctx?.Answer is { } answer && _paused.TryRemove(answer.Id, out var resumed) && resumed.Tool == tool)
        {
            st = resumed;
            try
            {
                if (st.Authorization)
                {
                    await AnswerAuthorizationAsync(new Request { Id = answer.Id }, answer, caller).ConfigureAwait(false);
                    st.Authorization = false;
                }
                else
                {
                    var (key, _) = st.Asks[0];
                    st.Asks.RemoveAt(0);
                    st.Collected[key] = AnswerToResponse(answer);
                    if (st.Asks.Count > 0) return Suspend(st, st.Asks[0].Req);
                    (st.Responses, st.Collected, st.Round) = (st.Collected, [], st.Round + 1);
                }
            }
            catch (Exception e) { return new ToolResult(e.Message, true, meta); }
        }
        else
        {
            st = new Paused { Tool = tool, Args = (J.FromClr(args ?? NoArgs) as JsonObject) ?? [] };
        }

        try
        {
            for (; st.Round < 16;)
            {
                var p = new JsonObject { ["name"] = st.Tool, ["arguments"] = st.Args.DeepClone() };
                if (st.Responses != null) p["inputResponses"] = st.Responses.DeepClone();
                if (st.State != null) p["requestState"] = st.State.DeepClone();
                JsonObject res;
                using (var cts = Budget(caller, TimeoutMs))
                {
                    try { res = await Conn.CallAsync("tools/call", p, cts.Token).ConfigureAwait(false); }
                    catch (PendingAuthorizationException pa)
                    {
                        // Rejected pre-dispatch: the server has NOT run this round. Resume sends exactly it, once.
                        if (WaitFor == null)
                        {
                            st.Authorization = true;
                            return Suspend(st, pa.Request);
                        }
                        await AnswerAuthorizationAsync(pa.Request, await WaitFor(pa.Request).ConfigureAwait(false), caller).ConfigureAwait(false);
                        continue;
                    }
                    catch (OperationCanceledException) when (!caller.IsCancellationRequested && cts.IsCancellationRequested)
                    {
                        return new ToolResult($"MCP tool \"{st.Tool}\" on server \"{Name}\" timed out after {TimeoutMs}ms without progress", true, meta);
                    }
                }
                var rt = J.Str(res["resultType"], "complete");
                if (rt == "complete") return MapResult(res, meta);
                if (rt != "input_required") return new ToolResult($"MCP tool \"{st.Tool}\": unknown resultType \"{rt}\"", true, meta);

                st.State = res["requestState"]?.DeepClone();
                st.Collected = [];
                st.Asks = [];
                foreach (var (k, v) in res["inputRequests"] as JsonObject ?? [])
                {
                    if (v is not JsonObject r) return new ToolResult($"tools/call {st.Tool}: malformed input request \"{k}\"", true, meta);
                    st.Asks.Add((k, InputToRequest(r)));
                }
                if (WaitFor == null)
                {
                    if (st.Asks.Count == 0) { (st.Responses, st.Round) = (new JsonObject(), st.Round + 1); continue; }
                    return Suspend(st, st.Asks[0].Req);
                }
                foreach (var (k, req) in st.Asks)
                    st.Collected[k] = AnswerToResponse(await WaitFor(req).ConfigureAwait(false));
                st.Asks = [];
                (st.Responses, st.Collected, st.Round) = (st.Collected, [], st.Round + 1);
            }
            return new ToolResult($"tools/call {st.Tool}: too many input_required rounds", true, meta);
        }
        catch (OperationCanceledException) when (caller.IsCancellationRequested) { throw; }
        catch (Exception e) { return new ToolResult(e.Message, true, meta); } // never re-sent (SPEC §2.6)
    }

    private ToolResult Suspend(Paused st, Request req)
    {
        _paused[req.Id] = st;
        return ToolResult.Pending(req);
    }


    /// <summary>SPEC §2.5: an MRTR input request → a §10 Request (same mapping as the §2.F elicitation bridge).</summary>
    internal static Request InputToRequest(JsonObject r)
    {
        var p = r["params"] as JsonObject ?? [];
        var isUrl = J.Str(p["mode"]) == "url";
        return new Request
        {
            Id = "mrtr-" + OAuthClient.RandStr(12),
            Kind = isUrl ? "authorization" : "input",
            Prompt = J.Str(p["message"], ""),
            Url = isUrl ? J.Str(p["url"]) : null,
            Data = !isUrl && p["requestedSchema"] is JsonObject schema
                ? new Dictionary<string, object?> { ["schema"] = J.ToClr(schema) }
                : null,
        };
    }

    /// <summary>SPEC §2.5: Answer → input response (accept / decline / cancel).</summary>
    internal static JsonObject AnswerToResponse(Answer? a)
    {
        if (a != null && a.Ok)
            return new JsonObject { ["action"] = "accept", ["content"] = J.FromClr(a.Data ?? new Dictionary<string, object?>()) };
        return new JsonObject { ["action"] = a?.Reason == "declined" ? "decline" : "cancel" };
    }

    /// <summary>SPEC §2.F result mapping, applied to the 2026 path's JSON result.</summary>
    internal static ToolResult MapResult(JsonObject res, Dictionary<string, object?> meta)
    {
        var content = res["content"] as JsonArray ?? [];
        var parts = new List<ContentPart>();
        var resourceText = new List<string>();
        var texts = new List<string>();
        foreach (var b in content.OfType<JsonObject>())
        {
            switch (J.Str(b["type"]))
            {
                case "text":
                    texts.Add(J.Str(b["text"], ""));
                    break;
                case "image":
                case "audio":
                    parts.Add(new ContentPart { Type = J.Str(b["type"])!, MimeType = J.Str(b["mimeType"], ""), Data = J.Str(b["data"], "") });
                    break;
                case "resource_link":
                    parts.Add(new ContentPart { Type = "file", MimeType = J.Str(b["mimeType"]) ?? "application/octet-stream", Url = J.Str(b["uri"], ""), Name = J.Str(b["name"]) });
                    break;
                case "resource":
                    var r = b["resource"] as JsonObject ?? [];
                    if (J.Str(r["blob"]) is { } blob)
                    {
                        var mime = J.Str(r["mimeType"]) ?? "application/octet-stream";
                        parts.Add(new ContentPart { Type = ContentPart.TypeForMime(J.Str(r["mimeType"]) ?? ""), MimeType = mime, Data = blob, Name = J.Str(r["uri"]) });
                    }
                    else if (J.Str(r["text"]) is { Length: > 0 } t) resourceText.Add(t);
                    break;
            }
        }
        var ps = parts.Count == 0 ? null : parts;
        if (J.True(res["isError"]))
        {
            var joined = string.Join("\n\n", texts.Where(t => !string.IsNullOrWhiteSpace(t)));
            return new ToolResult(joined.Length == 0 ? "MCP tool returned an error" : joined, true, meta, ps);
        }
        if (res["structuredContent"] is { } sc && sc.GetValueKind() != JsonValueKind.Null)
            return new ToolResult(Json.Stringify(J.ToClr(sc)), false, meta, ps);
        var text = string.Join("\n", texts);
        if (resourceText.Count > 0)
            text = text.Length == 0 ? string.Join("\n", resourceText) : text + "\n" + string.Join("\n", resourceText);
        if (text.Length == 0 && ps != null)
            text = string.Join("\n", ps.Select(x => x.DescribeInText()));
        return new ToolResult(text, false, meta, ps);
    }

    // ------------------------------------------------------------------ tool search (SPEC §2.8)

    /// <summary>BM25 over name (×2, split on _ and camelCase) and description.</summary>
    public async Task<List<Dictionary<string, object?>>> SearchToolsAsync(string query, int limit, CancellationToken ct)
    {
        var tools = await AllowedToolsAsync(ct).ConfigureAwait(false);
        var docs = new List<List<string>>();
        var df = new Dictionary<string, int>();
        var total = 0;
        foreach (var t in tools)
        {
            var nt = Tokens(J.Str(t["name"], ""));
            var d = nt.Concat(nt).Concat(Tokens(J.Str(t["description"], ""))).ToList();
            docs.Add(d);
            total += d.Count;
            foreach (var w in d.Distinct()) df[w] = df.GetValueOrDefault(w) + 1;
        }
        var avg = (double)total / Math.Max(1, tools.Count);
        var q = Tokens(query);
        var hits = new List<(int I, double Score)>();
        for (var i = 0; i < docs.Count; i++)
        {
            var tf = docs[i].GroupBy(w => w).ToDictionary(g => g.Key, g => g.Count());
            var s = 0.0;
            foreach (var w in q)
            {
                double f = tf.GetValueOrDefault(w);
                if (f == 0) continue;
                var idf = Math.Log(1 + (tools.Count - df[w] + 0.5) / (df[w] + 0.5));
                s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * docs[i].Count / avg));
            }
            if (s > 0) hits.Add((i, s));
        }
        return hits.OrderByDescending(h => h.Score).ThenBy(h => h.I).Take(limit).Select(h => new Dictionary<string, object?>
        {
            ["name"] = J.Str(tools[h.I]["name"], ""),
            ["description"] = J.Str(tools[h.I]["description"], ""),
            ["inputSchema"] = J.ToClr(tools[h.I]["inputSchema"]) ?? new Dictionary<string, object?>(),
            ["server"] = Description,
        }).ToList();
    }

    internal static List<string> Tokens(string s)
    {
        var outp = new List<string>();
        var cur = new StringBuilder();
        void Flush() { if (cur.Length > 0) { outp.Add(cur.ToString().ToLowerInvariant()); cur.Clear(); } }
        var prevLower = false;
        foreach (var r in s)
        {
            if (char.IsUpper(r) && prevLower) { Flush(); cur.Append(r); }
            else if (char.IsLetterOrDigit(r)) cur.Append(r);
            else Flush();
            prevLower = char.IsLower(r);
        }
        Flush();
        return outp;
    }

    // ------------------------------------------------------------------ skills extension (SPEC §2.7)

    /// <summary>skills/list + skills/get + resources/read → logical skill definitions for the <c>skill</c> tool.
    /// Every URI is containment-checked and every file's sha256 verified before the skill is accepted.</summary>
    public async Task<List<SkillSource.SkillDef>> LoadSkillsAsync(CancellationToken ct)
    {
        var defs = new List<SkillSource.SkillDef>();
        var list = await LoadCallAsync("skills/list", null, TimeoutMs, ct).ConfigureAwait(false);
        foreach (var entry in (list["skills"] as JsonArray ?? []).OfType<JsonObject>())
        {
            var uri = J.Str(entry["uri"], "");
            try { defs.Add(await LoadSkillAsync(uri, ct).ConfigureAwait(false)); }
            catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested)
            {
                Console.Error.WriteLine($"[toolnexus] MCP server \"{Name}\" skill {uri}: {e.Message}");
            }
        }
        return defs;
    }

    private async Task<SkillSource.SkillDef> LoadSkillAsync(string uri, CancellationToken ct)
    {
        if (!SkillUri.TryParse(uri, out var name, out _, out var why)) throw new WireException(why);
        var r = await LoadCallAsync("skills/get", new JsonObject { ["uri"] = uri }, TimeoutMs, ct).ConfigureAwait(false);
        string? skillMd = null;
        var resources = new List<string>();
        foreach (var x in (r["skill"]?["resources"] as JsonArray ?? []).OfType<JsonObject>())
        {
            var fu = J.Str(x["uri"], "");
            if (!SkillUri.TryParse(fu, out var n, out var rel, out _) || n != name)
                throw new WireException($"refusing skill resource \"{fu}\": outside skill {name}");
            var rr = await LoadCallAsync("resources/read", new JsonObject { ["uri"] = fu }, TimeoutMs, ct).ConfigureAwait(false);
            if (rr["contents"] is not JsonArray { Count: > 0 } cs) throw new WireException($"{fu}: empty resources/read");
            var text = J.Str(cs[0]?["text"], "");
            var d = J.Str(x["digest"], "");
            if (d != "" && d != "sha256:" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text))))
                throw new WireException($"{fu}: digest mismatch");
            if (rel == "SKILL.md") skillMd = text;
            else resources.Add(rel);
        }
        skillMd ??= "";
        var (fm, body) = SkillSource.SplitFrontmatter(skillMd);
        var fmName = fm.GetValueOrDefault("name");
        resources.Sort(string.CompareOrdinal);
        return new SkillSource.SkillDef(string.IsNullOrEmpty(fmName) ? name : fmName, fm.GetValueOrDefault("description"), body, resources, $"skill://{name}/");
    }
}

/// <summary>
/// Skill URI containment (SPEC §2.7): percent-decode once, then reject leftover '%', '\', NUL, ':' and any
/// empty / '.' / '..' segment.
/// </summary>
internal static class SkillUri
{
    public static bool TryParse(string uri, out string name, out string rel, out string why)
    {
        name = rel = "";
        if (!uri.StartsWith("skill://")) { why = "not a skill:// URI"; return false; }
        if (!TryPathUnescape(uri[8..], out var dec)) { why = "bad percent-encoding"; return false; }
        if (dec.IndexOfAny(['%', '\\', '\0']) >= 0) { why = "skill URI has encoded, backslash or NUL characters"; return false; }
        var segs = dec.Split('/');
        if (segs.Length < 2) { why = "skill URI needs skill://<name>/<path>"; return false; }
        if (segs.Any(s => s is "" or "." or ".." || s.Contains(':')))
        {
            why = "skill URI path escapes the skill (empty, '.', '..' or ':' segment)";
            return false;
        }
        (name, rel, why) = (segs[0], string.Join('/', segs[1..]), "");
        return true;
    }

    private static bool TryPathUnescape(string s, out string result)
    {
        result = "";
        var bytes = new List<byte>(s.Length);
        for (var i = 0; i < s.Length; i++)
        {
            if (s[i] != '%') { bytes.AddRange(Encoding.UTF8.GetBytes(s[i].ToString())); continue; }
            if (i + 2 >= s.Length || !Uri.IsHexDigit(s[i + 1]) || !Uri.IsHexDigit(s[i + 2])) return false;
            bytes.Add(Convert.ToByte(s.Substring(i + 1, 2), 16));
            i += 2;
        }
        result = Encoding.UTF8.GetString(bytes.ToArray());
        return true;
    }
}
