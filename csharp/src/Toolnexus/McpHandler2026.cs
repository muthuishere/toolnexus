using System.Buffers.Text;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// MCP — INBOUND, the 2026-07-28 wire on McpHttpHandler (SPEC §7C.2, ADR 0040).
//
// Opt-in: list "2026-07-28" in McpHandlerConfig.ProtocolVersions. A POST is served on this profile
// when the version is accepted and the request is a 2026 request (Route2026). The profile is
// stateless: no initialize, no Mcp-Session-Id, no server→client request. Every hook keeps its
// meaning: Authenticate (after header validation), ListTools, BeforeCall, OnCall, resources,
// prompts, plus ListSkills for the SEP-2640 skills extension. A confirm decision and a tool
// suspension (§10 Pending) leave as an MRTR `input_required` result; the client's retry carries
// inputResponses and the echoed requestState. Go reference: golang/mcphandler2026.go.
// ---------------------------------------------------------------------------

/// <summary>One text file of an <see cref="McpSkill"/>; <see cref="Path"/> is relative, "/"-separated.</summary>
public sealed record McpSkillFile(string Path, string Text);

/// <summary>
/// One skill published through the skills extension (SEP-2640, SPEC §7C.2). <see cref="Files"/> MUST
/// include "SKILL.md". A null <see cref="Frontmatter"/> is sent as <c>{name}</c>.
/// </summary>
public sealed record McpSkill(string Name, IReadOnlyDictionary<string, object?>? Frontmatter, IReadOnlyList<McpSkillFile> Files);

/// <summary>
/// An <see cref="McpHandlerConfig.Authenticate"/> failure with an explicit HTTP status and challenge
/// (<see cref="McpServerAuth"/> throws it; any Authenticate hook may). On either wire the handler
/// answers <see cref="Status"/> with <c>WWW-Authenticate</c> = <see cref="Challenge"/> (a non-empty
/// <see cref="McpHandlerConfig.WwwAuthenticate"/> result wins) and the body <c>{"error":"unauthorized"}</c>.
/// <see cref="Reason"/> is never sent on the wire.
/// </summary>
public sealed class McpAuthError : Exception
{
    public McpAuthError(int status, string challenge, string reason = "")
        : base(string.IsNullOrEmpty(reason) ? "unauthorized" : reason)
    {
        Status = status == 0 ? 401 : status;
        Challenge = challenge;
        Reason = reason;
    }

    /// <summary>401 or 403.</summary>
    public int Status { get; }

    public string Challenge { get; }

    public string Reason { get; }
}

public sealed partial class McpHttpHandler
{
    /// <summary>The MCP 2026-07-28 protocol version string.</summary>
    public const string Protocol2026 = "2026-07-28";

    /// <summary>The skills extension id advertised by server/discover.</summary>
    public const string SkillsExtension = "io.modelcontextprotocol/skills";

    private const string MetaVersion = "io.modelcontextprotocol/protocolVersion";
    private const string MetaClientCaps = "io.modelcontextprotocol/clientCapabilities";
    private const string MetaServerInfo = "io.modelcontextprotocol/serverInfo";

    private sealed record Err26(int Code, string Message, int Status = 200, JsonNode? Data = null);

    private bool Only2026 => _versions.Count == 1 && _versions[0] == Protocol2026;

    /// <summary>An exchange whose request body was already read (the 2026 router buffers it).</summary>
    private sealed class BufferedExchange(McpHttpExchange inner, byte[] body) : McpHttpExchange
    {
        private readonly MemoryStream _body = new(body, writable: false);
        public override string Method => inner.Method;
        public override string? Header(string name) => inner.Header(name);
        public override Stream RequestBody => _body;
        public override CancellationToken Aborted => inner.Aborted;
        public override object? Native => inner.Native;
        public override string Path => inner.Path;
        public override string Scheme => inner.Scheme;
        public override void SetStatus(int status) => inner.SetStatus(status);
        public override void SetHeader(string name, string value) => inner.SetHeader(name, value);
        public override Stream ResponseBody => inner.ResponseBody;
    }

    /// <summary>Reads and routes a POST when the 2026 profile is enabled. Returns handled=false (with the
    /// body-buffered exchange, when the body was read) when the request belongs to the legacy wire.</summary>
    private async Task<(bool Handled, McpHttpExchange? Buffered)> HandlePost2026Async(McpHttpExchange ex)
    {
        var ct = (ex.Header("Content-Type") ?? "").Split(';')[0].Trim().ToLowerInvariant();
        if (ct != "application/json") return (false, null); // the legacy path answers 415 identically
        byte[] body;
        try
        {
            using var ms = new MemoryStream();
            var buf = new byte[81920];
            int n;
            while ((n = await ex.RequestBody.ReadAsync(buf).ConfigureAwait(false)) > 0)
            {
                ms.Write(buf, 0, n);
                if (ms.Length > _maxBody) break;
            }
            body = ms.ToArray();
        }
        catch
        {
            return (false, new BufferedExchange(ex, Array.Empty<byte>()));
        }
        var buffered = new BufferedExchange(ex, body);
        if (body.LongLength > _maxBody) return (false, buffered);

        string text;
        try { text = new UTF8Encoding(false, true).GetString(body); }
        catch { text = "\uFFFD"; }
        var trimmed = text.Trim();
        var msg = trimmed.StartsWith('[') || text == "\uFFFD" ? null : ParseMessage(body);
        if (msg == null)
        {
            if (!Only2026) return (false, buffered);
            if (trimmed.StartsWith('['))
                await Write26Async(ex, 400, null, null, new Err26(-32600, "batch requests are not supported")).ConfigureAwait(false);
            else
                await Write26Async(ex, 400, null, null, new Err26(-32700, "Parse error")).ConfigureAwait(false);
            return (true, null);
        }
        var p = msg.Params is { ValueKind: JsonValueKind.Object } pe ? JsonNode.Parse(pe.GetRawText())!.AsObject() : null;
        var meta = p?["_meta"] as JsonObject;
        if (!Route2026(ex, msg.Method, meta)) return (false, buffered);
        await Serve2026Async(buffered, msg, p ?? new JsonObject(), meta).ConfigureAwait(false);
        return (true, null);
    }

    /// <summary>Whether this POST is served on the 2026-07-28 profile (SPEC §7C.2 routing).</summary>
    private bool Route2026(McpHttpExchange ex, string method, JsonObject? meta)
    {
        if (!_versions.Contains(Protocol2026)) return false;
        if (_legacy.Count == 0) return true;
        if (method == "initialize") return false;
        var hv = ex.Header("MCP-Protocol-Version") ?? "";
        if (hv == Protocol2026) return true;
        if (hv != "" && _versions.Contains(hv)) return false;
        return method == "server/discover" || !string.IsNullOrEmpty(ex.Header("Mcp-Method"))
            || (meta != null && meta.ContainsKey(MetaVersion));
    }

    private async Task Serve2026Async(McpHttpExchange ex, RpcMessage msg, JsonObject p, JsonObject? meta)
    {
        if (msg.Method == "" || !msg.HasId || msg.Id == null)
        {
            ex.SetStatus(202); // notification (or a stray response): acknowledged
            return;
        }
        if (Validate26(ex, msg.Method, p, meta) is { } verr)
        {
            await Write26Async(ex, verr.Status, msg.Id, null, verr).ConfigureAwait(false);
            return;
        }
        var ctx = new McpContext(ex);
        ctx.MakeCurrent();
        if (_cfg.Authenticate != null)
        {
            try { ctx.Identity = await _cfg.Authenticate(ex).ConfigureAwait(false); }
            catch (Exception err)
            {
                await WriteAuthFailureAsync(ex, err).ConfigureAwait(false);
                return;
            }
        }
        if (meta != null) ctx.Meta = (Dictionary<string, object?>)Json.FromElement(JsonDocument.Parse(meta.ToJsonString()).RootElement)!;
        JsonObject? res;
        Err26? e;
        try { (res, e) = await Dispatch2026Async(ctx, msg, p, meta).ConfigureAwait(false); }
        catch (Exception x) { (res, e) = (null, new Err26(-32603, x.Message)); }
        await Write26Async(ex, e?.Status ?? 200, msg.Id, res, e).ConfigureAwait(false);
    }

    /// <summary>Answers an Authenticate failure (both wires).</summary>
    private async Task WriteAuthFailureAsync(McpHttpExchange ex, Exception err)
    {
        var challenge = _cfg.WwwAuthenticate?.Invoke(ex, err);
        if (err is McpAuthError ae)
        {
            if (string.IsNullOrEmpty(challenge)) challenge = ae.Challenge;
            if (!string.IsNullOrEmpty(challenge)) ex.SetHeader("WWW-Authenticate", challenge);
            ex.SetStatus(ae.Status);
            ex.SetHeader("Content-Type", "application/json");
            await ex.ResponseBody.WriteAsync(Encoding.UTF8.GetBytes("{\"error\":\"unauthorized\"}")).ConfigureAwait(false);
            return;
        }
        if (!string.IsNullOrEmpty(challenge)) ex.SetHeader("WWW-Authenticate", challenge);
        await PlainAsync(ex, 401, "Unauthorized").ConfigureAwait(false);
    }

    /// <summary>SPEC §7C.2 header validation, in order.</summary>
    private static Err26? Validate26(McpHttpExchange ex, string method, JsonObject p, JsonObject? meta)
    {
        static Err26 Mismatch(string m) => new(-32020, "Header mismatch: " + m, 400);
        var hv = ex.Header("MCP-Protocol-Version") ?? "";
        if (hv == "") return Mismatch("MCP-Protocol-Version header missing");
        var hm = ex.Header("Mcp-Method") ?? "";
        if (hm != method) return Mismatch($"Mcp-Method header value '{hm}' does not match body value '{method}'");
        if (method is "tools/call" or "prompts/get" or "resources/read")
        {
            var want = StrOf(p[method == "resources/read" ? "uri" : "name"]);
            var (got, ok) = DecodeHeaderValue(ex.Header("Mcp-Name") ?? "");
            if (!ok || got != want) return Mismatch($"Mcp-Name header value '{got}' does not match body value '{want}'");
        }
        var mv = StrOf(meta?[MetaVersion]);
        if (mv != hv) return Mismatch($"MCP-Protocol-Version header value '{hv}' does not match _meta value '{mv}'");
        if (mv != Protocol2026)
            return new Err26(-32022, "Unsupported protocol version", 400,
                new JsonObject { ["supported"] = new JsonArray(Protocol2026), ["requested"] = mv });
        return null;
    }

    private static string StrOf(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) ? s : "";

    /// <summary>Decodes the <c>=?base64?…?=</c> header sentinel (SPEC §2.3); an empty header is not ok.</summary>
    internal static (string Value, bool Ok) DecodeHeaderValue(string v)
    {
        if (v.StartsWith("=?base64?", StringComparison.Ordinal) && v.EndsWith("?=", StringComparison.Ordinal) && v.Length >= 11)
        {
            try { return (Encoding.UTF8.GetString(Convert.FromBase64String(v[9..^2])), true); }
            catch (FormatException) { return ("", false); }
        }
        return (v, v != "");
    }

    private McpServerInfo Info26(McpContext ctx)
    {
        var info = _cfg.ServerInfo != null
            ? _cfg.ServerInfo(ctx)
            : new McpServerInfo(_cfg.Name, _cfg.Version, _cfg.Instructions, _cfg.Title);
        return info with
        {
            Name = string.IsNullOrEmpty(info.Name) ? "toolnexus" : info.Name,
            Version = string.IsNullOrEmpty(info.Version) ? "0.1.0" : info.Version,
        };
    }

    /// <summary>Stamps resultType and _meta.serverInfo (set last) onto a result.</summary>
    private JsonObject Complete26(McpContext ctx, JsonObject m, string resultType = "complete")
    {
        var info = Info26(ctx);
        if (m["_meta"] is not JsonObject meta)
        {
            meta = new JsonObject();
            m.Remove("_meta");
            m["_meta"] = meta;
        }
        meta.Remove(MetaServerInfo);
        meta[MetaServerInfo] = OmitEmpty(("name", info.Name), ("title", info.Title), ("version", info.Version));
        m["resultType"] = resultType;
        return m;
    }

    private string CacheScope26 => _cfg.Authenticate != null ? "private" : "public";

    private async Task<(JsonObject?, Err26?)> Dispatch2026Async(McpContext ctx, RpcMessage msg, JsonObject p, JsonObject? meta)
    {
        static Err26 Internal(Exception e) => new(-32603, e.Message);
        static Err26 Invalid(string m) => new(-32602, m);
        switch (msg.Method)
        {
            case "server/discover":
            {
                var info = Info26(ctx);
                var caps = new JsonObject { ["tools"] = new JsonObject() };
                if (_cfg.ListResources != null || _cfg.ListSkills != null) caps["resources"] = new JsonObject();
                if (_cfg.ListPrompts != null) caps["prompts"] = new JsonObject();
                if (_cfg.ListSkills != null) caps["extensions"] = new JsonObject { [SkillsExtension] = new JsonObject() };
                var res = new JsonObject
                {
                    ["supportedVersions"] = new JsonArray(Protocol2026),
                    ["capabilities"] = caps,
                    ["ttlMs"] = 3600000,
                    ["cacheScope"] = "public",
                };
                if (!string.IsNullOrEmpty(info.Instructions)) res["instructions"] = info.Instructions;
                return (Complete26(ctx, res), null);
            }
            case "ping":
                return (Complete26(ctx, new JsonObject()), null);
            case "tools/list":
            {
                IReadOnlyList<ITool> tools;
                try { tools = await ToolsAsync(ctx).ConfigureAwait(false); }
                catch (Exception e) { return (null, Internal(e)); }
                var arr = new JsonArray();
                foreach (var t in tools.OrderBy(t => t.Name, StringComparer.Ordinal)) arr.Add(WireTool(t));
                return (Complete26(ctx, new JsonObject { ["tools"] = arr, ["ttlMs"] = 60000, ["cacheScope"] = CacheScope26 }), null);
            }
            case "tools/call":
                return await CallTool2026Async(ctx, p, meta).ConfigureAwait(false);
            case "skills/list" when _cfg.ListSkills != null:
            {
                IReadOnlyList<McpSkill> skills;
                try { skills = await Skills26Async(ctx).ConfigureAwait(false); }
                catch (Exception e) { return (null, Internal(e)); }
                var arr = new JsonArray();
                foreach (var s in skills)
                    arr.Add(new JsonObject { ["uri"] = "skill://" + s.Name + "/SKILL.md", ["frontmatter"] = Frontmatter(s) });
                return (Complete26(ctx, new JsonObject { ["skills"] = arr, ["ttlMs"] = 60000, ["cacheScope"] = CacheScope26 }), null);
            }
            case "skills/get" when _cfg.ListSkills != null:
            {
                var uri = StrOf(p["uri"]);
                var (_, _, why) = ParseSkillUri(uri);
                if (why != null) return (null, Invalid("Invalid skill URI: " + why));
                IReadOnlyList<McpSkill> skills;
                try { skills = await Skills26Async(ctx).ConfigureAwait(false); }
                catch (Exception e) { return (null, Internal(e)); }
                foreach (var s in skills)
                    if ("skill://" + s.Name + "/SKILL.md" == uri)
                        return (Complete26(ctx, new JsonObject { ["skill"] = SkillEntry(s) }), null);
                return (null, Invalid("Unknown skill: " + uri));
            }
            case "resources/list" when _cfg.ListResources != null:
            case "prompts/list" when _cfg.ListPrompts != null:
            case "prompts/get" when _cfg.ListPrompts != null:
            {
                // Same payload as the legacy profile; only the envelope differs.
                var (r, err) = await DispatchAsync(ctx, null, msg).ConfigureAwait(false);
                if (err != null) return (null, new Err26(err.Code, err.Message));
                return (Complete26(ctx, r as JsonObject ?? new JsonObject()), null);
            }
            case "resources/read":
            {
                var uri = StrOf(p["uri"]);
                if (uri.StartsWith("skill://", StringComparison.Ordinal) && _cfg.ListSkills != null)
                {
                    var (name, rel, why) = ParseSkillUri(uri);
                    if (why != null) return (null, Invalid("Invalid resource URI: " + why));
                    IReadOnlyList<McpSkill> skills;
                    try { skills = await Skills26Async(ctx).ConfigureAwait(false); }
                    catch (Exception e) { return (null, Internal(e)); }
                    foreach (var s in skills.Where(s => s.Name == name))
                        foreach (var f in s.Files.Where(f => f.Path == rel))
                            return (Complete26(ctx, new JsonObject
                            {
                                ["contents"] = new JsonArray(new JsonObject
                                {
                                    ["uri"] = uri,
                                    ["mimeType"] = rel.EndsWith(".md", StringComparison.Ordinal) ? "text/markdown" : "text/plain",
                                    ["text"] = f.Text,
                                }),
                                ["ttlMs"] = 60000,
                                ["cacheScope"] = CacheScope26,
                            }), null);
                    return (null, Invalid("Resource not found: " + uri));
                }
                if (_cfg.ListResources == null || _cfg.ReadResource == null) break;
                var (r, err) = await DispatchAsync(ctx, null, msg).ConfigureAwait(false);
                if (err != null) return (null, new Err26(err.Code, err.Message));
                return (Complete26(ctx, r as JsonObject ?? new JsonObject()), null);
            }
        }
        return (null, new Err26(-32601, "Method not found: " + msg.Method, 404));
    }

    private async Task<IReadOnlyList<McpSkill>> Skills26Async(McpContext ctx)
        => (await _cfg.ListSkills!(ctx).ConfigureAwait(false)).OrderBy(s => s.Name, StringComparer.Ordinal).ToList();

    private static JsonNode Frontmatter(McpSkill s)
        => s.Frontmatter != null ? JsonNode.Parse(Json.Stringify(s.Frontmatter))! : new JsonObject { ["name"] = s.Name };

    private static JsonObject SkillEntry(McpSkill s)
    {
        var res = new JsonArray();
        foreach (var f in s.Files.OrderBy(f => f.Path, StringComparer.Ordinal))
        {
            var bytes = Encoding.UTF8.GetBytes(f.Text);
            res.Add(new JsonObject
            {
                ["uri"] = "skill://" + s.Name + "/" + f.Path,
                ["digest"] = "sha256:" + Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(),
                ["size"] = bytes.Length,
            });
        }
        return new JsonObject { ["uri"] = "skill://" + s.Name + "/SKILL.md", ["frontmatter"] = Frontmatter(s), ["resources"] = res };
    }

    /// <summary>Validates <c>skill://&lt;name&gt;/&lt;path&gt;</c> (SPEC §2.7 containment). Why is null when valid.</summary>
    internal static (string Name, string Rel, string? Why) ParseSkillUri(string uri)
    {
        if (!uri.StartsWith("skill://", StringComparison.Ordinal)) return ("", "", "not a skill:// URI");
        var rest = uri["skill://".Length..];
        var bytes = new List<byte>();
        for (var i = 0; i < rest.Length; i++)
        {
            if (rest[i] == '%')
            {
                if (i + 2 >= rest.Length || !Uri.IsHexDigit(rest[i + 1]) || !Uri.IsHexDigit(rest[i + 2]))
                    return ("", "", "bad percent-encoding");
                bytes.Add(Convert.ToByte(rest.Substring(i + 1, 2), 16));
                i += 2;
            }
            else bytes.AddRange(Encoding.UTF8.GetBytes(rest[i].ToString()));
        }
        var dec = Encoding.UTF8.GetString(bytes.ToArray());
        if (dec.IndexOfAny(new[] { '%', '\\', '\0' }) >= 0) return ("", "", "skill URI has encoded, backslash or NUL characters");
        var segs = dec.Split('/');
        if (segs.Length < 2) return ("", "", "skill URI needs skill://<name>/<path>");
        if (segs.Any(s => s is "" or "." or ".." || s.Contains(':')))
            return ("", "", "skill URI path escapes the skill (empty, '.', '..' or ':' segment)");
        return (segs[0], string.Join('/', segs.Skip(1)), null);
    }

    // ── tools/call + MRTR ─────────────────────────────────────────────────

    private sealed record State26(string Tool, bool Confirmed);

    /// <summary>base64url-nopad(compact JSON {"tool":…[,"confirmed":true]}) — not integrity-protected.</summary>
    internal static string EncodeState26(string tool, bool confirmed)
    {
        var json = "{\"tool\":" + GoString(tool) + (confirmed ? ",\"confirmed\":true" : "") + "}";
        return Base64Url.EncodeToString(Encoding.UTF8.GetBytes(json));
    }

    /// <summary>A JSON string literal escaped the way Go's encoding/json does (HTML-safe).</summary>
    private static string GoString(string s)
        => JsonSerializer.Serialize(s, WireJson).Replace("<", "\\u003c").Replace(">", "\\u003e").Replace("&", "\\u0026")
            .Replace("\u2028", "\\u2028").Replace("\u2029", "\\u2029");

    private static State26? DecodeState26(JsonNode? v)
    {
        var s = StrOf(v);
        if (s == "" && v is not JsonValue) return null;
        try
        {
            if (s.Contains('=')) return null; // nopad only
            using var doc = JsonDocument.Parse(Base64Url.DecodeFromChars(s));
            var r = doc.RootElement;
            if (r.ValueKind != JsonValueKind.Object) return null;
            var tool = "";
            var confirmed = false;
            if (r.TryGetProperty("tool", out var t))
            {
                if (t.ValueKind == JsonValueKind.String) tool = t.GetString()!;
                else if (t.ValueKind != JsonValueKind.Null) return null;
            }
            if (r.TryGetProperty("confirmed", out var c))
            {
                if (c.ValueKind is JsonValueKind.True or JsonValueKind.False) confirmed = c.GetBoolean();
                else if (c.ValueKind != JsonValueKind.Null) return null;
            }
            return new State26(tool, confirmed);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Maps one MRTR input response onto a §10 Answer (null when not an object).</summary>
    private static Answer? InputResponse26(string key, JsonNode? v)
    {
        if (v is not JsonObject m) return null;
        var action = StrOf(m["action"]);
        var ok = action == "accept";
        IDictionary<string, object?>? data = m["content"] is JsonObject c
            ? (Dictionary<string, object?>)Json.FromElement(JsonDocument.Parse(c.ToJsonString()).RootElement)!
            : null;
        return new Answer { Id = key, Ok = ok, Data = data, Reason = ok ? null : action == "decline" ? "declined" : "cancelled" };
    }

    private static bool CanElicit26(JsonObject? meta) => meta?[MetaClientCaps] is JsonObject caps && caps.ContainsKey("elicitation");

    private async Task<(JsonObject?, Err26?)> CallTool2026Async(McpContext ctx, JsonObject p, JsonObject? meta)
    {
        var name = StrOf(p["name"]);
        IReadOnlyList<ITool> tools;
        try { tools = await ToolsAsync(ctx).ConfigureAwait(false); }
        catch (Exception e) { return (null, new Err26(-32603, e.Message)); }
        var tool = tools.FirstOrDefault(t => t.Name == name);
        if (tool == null) return (null, new Err26(-32602, "Unknown tool: " + name));

        var started = System.Diagnostics.Stopwatch.GetTimestamp();
        var decision = McpDecisionKind.Allow;
        (JsonObject?, Err26?) Finish(JsonObject m, bool isErr, Exception? execErr)
        {
            if (_cfg.OnCall != null)
            {
                var ms = (long)System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds;
                var bytes = Encoding.UTF8.GetByteCount(m.ToJsonString(WireJson));
                try { _cfg.OnCall(new McpCallEvent(tool.Name, tool.Source, ms, isErr, execErr, bytes, decision, ctx.Identity, "")); }
                catch { /* host callback errors are isolated */ }
            }
            return (m, null);
        }
        (JsonObject?, Err26?) Result(ToolResult res, Exception? execErr)
            => Finish(Complete26(ctx, ResultNode(res).Node.AsObject()), res.IsError, execErr);

        var args = new Dictionary<string, object?>();
        var argsJson = "{}";
        if (p.TryGetPropertyValue("arguments", out var rawArgs) && rawArgs != null)
        {
            if (rawArgs is not JsonObject ao) return Result(new ToolResult("invalid arguments: must be an object", true), null);
            argsJson = ao.ToJsonString();
            args = (Dictionary<string, object?>)Json.FromElement(JsonDocument.Parse(argsJson).RootElement)!;
        }

        var state = DecodeState26(p["requestState"]);
        if (state != null && state.Tool != name) state = null;
        var responses = p["inputResponses"] as JsonObject;
        var confirmed = state?.Confirmed ?? false;

        if (_cfg.BeforeCall != null)
        {
            McpDecision d;
            using (var doc = JsonDocument.Parse(argsJson))
            {
                try { d = await _cfg.BeforeCall(ctx, tool, doc.RootElement.Clone()).ConfigureAwait(false) ?? McpDecision.Allow(); }
                catch (Exception e) { d = McpDecision.Deny(e.Message); }
            }
            decision = d.Kind;
            switch (d.Kind)
            {
                case McpDecisionKind.Deny:
                    if (d.Result != null) return Result(d.Result, null);
                    return Result(new ToolResult(string.IsNullOrEmpty(d.Message) ? "denied" : d.Message, true), null);
                case McpDecisionKind.Confirm:
                    if (state is { Confirmed: true }) break; // accepted in an earlier round of this call
                    if (state != null && InputResponse26("confirm", responses?["confirm"]) is { } ans)
                    {
                        if (!ans.Ok) return Result(new ToolResult("declined", true), null);
                        if (ans.Data != null && ans.Data.TryGetValue("confirm", out var cv) && cv is false)
                            return Result(new ToolResult("declined", true), null);
                        confirmed = true;
                        responses = null; // the confirm round is consumed
                        break;
                    }
                    if (!CanElicit26(meta))
                    {
                        if (d.Result != null) return Result(d.Result, null);
                        return Result(new ToolResult("confirmation required: " + d.Message, true), null);
                    }
                    var message = string.IsNullOrEmpty(d.Message) ? "Confirm this action?" : d.Message;
                    return Finish(Complete26(ctx, new JsonObject
                    {
                        ["inputRequests"] = new JsonObject
                        {
                            ["confirm"] = new JsonObject
                            {
                                ["method"] = "elicitation/create",
                                ["params"] = new JsonObject
                                {
                                    ["mode"] = "form",
                                    ["message"] = message,
                                    ["requestedSchema"] = new JsonObject
                                    {
                                        ["type"] = "object",
                                        ["properties"] = new JsonObject
                                        {
                                            ["confirm"] = new JsonObject { ["type"] = "boolean", ["title"] = "Confirm", ["description"] = message },
                                        },
                                    },
                                },
                            },
                        },
                        ["requestState"] = EncodeState26(name, false),
                    }, "input_required"), false, null);
            }
        }

        Answer? answer = null;
        if (state != null && responses is { Count: 1 })
        {
            var only = responses.First();
            answer = InputResponse26(only.Key, only.Value);
        }
        ToolResult result;
        Exception? err = null;
        try
        {
            result = await tool.ExecuteAsync(args, new ToolContext(cancellationToken: ctx.CancellationToken, answer: answer)).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            err = e;
            result = new ToolResult(e.Message, true);
        }
        if (err == null && ToolResult.PendingOf(result) is { } req)
            return Finish(Complete26(ctx, new JsonObject
            {
                ["inputRequests"] = new JsonObject { [req.Id] = InputRequest26(req) },
                ["requestState"] = EncodeState26(name, confirmed),
            }, "input_required"), false, null);
        return Result(result, err);
    }

    /// <summary>Maps a §10 Request onto an MRTR input request: kind "authorization" with a URL ⇒
    /// URL-mode elicitation, anything else ⇒ form (requestedSchema = data.schema or an empty object).</summary>
    private static JsonObject InputRequest26(Request r)
    {
        if (r.Kind == "authorization" && !string.IsNullOrEmpty(r.Url))
            return new JsonObject
            {
                ["method"] = "elicitation/create",
                ["params"] = new JsonObject { ["mode"] = "url", ["message"] = r.Prompt, ["url"] = r.Url, ["elicitationId"] = r.Id },
            };
        JsonNode? schema = null;
        if (r.Data != null && r.Data.TryGetValue("schema", out var sv) && sv != null)
        {
            try { schema = JsonNode.Parse(Json.Stringify(sv)) as JsonObject; } catch { schema = null; }
        }
        schema ??= new JsonObject { ["type"] = "object", ["properties"] = new JsonObject() };
        return new JsonObject
        {
            ["method"] = "elicitation/create",
            ["params"] = new JsonObject { ["mode"] = "form", ["message"] = r.Prompt, ["requestedSchema"] = schema },
        };
    }

    private static async Task Write26Async(McpHttpExchange ex, int status, JsonNode? id, JsonObject? result, Err26? e)
    {
        var msg = new JsonObject { ["jsonrpc"] = "2.0", ["id"] = id?.DeepClone() };
        if (e != null)
        {
            var er = new JsonObject { ["code"] = e.Code, ["message"] = e.Message };
            if (e.Data != null) er["data"] = e.Data;
            msg["error"] = er;
        }
        else msg["result"] = result;
        ex.SetStatus(status);
        ex.SetHeader("Content-Type", "application/json");
        await ex.ResponseBody.WriteAsync(Encoding.UTF8.GetBytes(msg.ToJsonString(WireJson) + "\n")).ConfigureAwait(false);
    }

    // ── loadMcpSkills ─────────────────────────────────────────────────────

    /// <summary>
    /// Reads every skill under <paramref name="dir"/> (any depth, <c>**/SKILL.md</c>, symlinks not
    /// followed) for <see cref="McpHandlerConfig.ListSkills"/>. The frontmatter is {name, description}
    /// parsed as in §3 (trimmed); a skill without a name is skipped. Files are every regular UTF-8 text
    /// file under the skill directory (binary files are skipped — the wire carries text), paths
    /// "/"-separated and sorted. Skills are sorted by name.
    /// </summary>
    public static IReadOnlyList<McpSkill> LoadMcpSkills(string dir)
    {
        var strict = new UTF8Encoding(false, true);
        var outList = new List<McpSkill>();
        foreach (var skillMd in Walk(dir).Where(f => System.IO.Path.GetFileName(f) == "SKILL.md"))
        {
            var (data, _, malformed) = SkillSource.ParseFrontmatter(File.ReadAllText(skillMd));
            var name = data.TryGetValue("name", out var n) ? (n ?? "").Trim() : "";
            if (malformed || name == "") continue;
            var desc = data.TryGetValue("description", out var de) ? (de ?? "").Trim() : "";
            var root = System.IO.Path.GetDirectoryName(skillMd)!;
            var files = new List<McpSkillFile>();
            foreach (var f in Walk(root))
            {
                string text;
                try { text = strict.GetString(File.ReadAllBytes(f)); }
                catch { continue; }
                files.Add(new McpSkillFile(System.IO.Path.GetRelativePath(root, f).Replace('\\', '/'), text));
            }
            files.Sort((a, b) => string.CompareOrdinal(a.Path, b.Path));
            outList.Add(new McpSkill(name, new Dictionary<string, object?> { ["name"] = name, ["description"] = desc }, files));
        }
        return outList.OrderBy(s => s.Name, StringComparer.Ordinal).ToList();

        // Regular files under d, depth first, lexical order; symlinks (files or dirs) are not followed.
        static IEnumerable<string> Walk(string d)
        {
            var entries = new DirectoryInfo(d).GetFileSystemInfos().OrderBy(e => e.Name, StringComparer.Ordinal);
            foreach (var e in entries)
            {
                if (e.LinkTarget != null) continue;
                if (e is DirectoryInfo sub)
                {
                    foreach (var x in Walk(sub.FullName)) yield return x;
                }
                else if (e is FileInfo) yield return e.FullName;
            }
        }
    }
}
