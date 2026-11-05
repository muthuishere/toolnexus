using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;

namespace Mcp2026;

/// <summary>What a tool handler gets besides its arguments: the verified caller (when the server is protected).</summary>
public sealed class ToolContext
{
    public Principal? Principal { get; init; }
    public CancellationToken Cancel { get; init; }
}

/// <summary>A tool handler. Return a string (text result), a JsonObject with "content" (a full CallToolResult),
/// or any JSON-serializable value (structured). Throwing gives an isError result.</summary>
public delegate Task<object?> ToolFunc(ToolContext ctx, JsonObject args);

/// <summary>A stateless MCP 2026-07-28 server over POST /mcp.</summary>
public sealed class Server
{
    /// <summary>Requests larger than this get 413.</summary>
    public const int MaxBodyBytes = 4 << 20;

    private JsonObject _serverInfo = new() { ["name"] = "toolnexus-spike", ["version"] = "0.0.1" };
    private readonly List<JsonObject> _tools = []; // fixture + code tools, sorted by name
    private readonly Dictionary<string, ToolFunc> _handlers = [];
    private readonly List<Skill> _skills = []; // sorted by uri

    /// <summary>null = unprotected (the default; cross-port checks run without auth).</summary>
    public ServerAuth? Auth { get; set; }

    internal sealed record SkillFile(string Uri, byte[] Data);
    internal sealed record Skill(string Uri, JsonObject Frontmatter, List<SkillFile> Files)
    {
        public JsonObject Entry() => new()
        {
            ["uri"] = Uri,
            ["frontmatter"] = Frontmatter.DeepClone(),
            ["resources"] = new JsonArray(Files.Select(f => (JsonNode?)new JsonObject
            {
                ["uri"] = f.Uri,
                ["digest"] = "sha256:" + Convert.ToHexStringLower(SHA256.HashData(f.Data)),
                ["size"] = f.Data.Length,
            }).ToArray()),
        };
    }

    /// <summary>Registers a code-defined tool (listed in tools/list). Not safe once serving.</summary>
    public Server Tool(string name, string description, ToolFunc handler)
    {
        _handlers[name] = handler;
        _tools.RemoveAll(t => J.Str(t["name"]) == name);
        _tools.Add(new JsonObject { ["name"] = name, ["description"] = description, ["inputSchema"] = new JsonObject { ["type"] = "object" } });
        _tools.Sort((a, b) => string.CompareOrdinal(J.Str(a["name"]), J.Str(b["name"])));
        return this;
    }

    /// <summary>Synchronous handler overload.</summary>
    public Server Tool(string name, string description, Func<ToolContext, JsonObject, object?> handler) =>
        Tool(name, description, (ctx, args) => Task.FromResult(handler(ctx, args)));

    /// <summary>Loads a fixture: {server, tools, skillsDir} (skillsDir relative to the fixture file).</summary>
    public static Server Load(string fixturePath)
    {
        var fx = JsonNode.Parse(File.ReadAllText(fixturePath))!.AsObject();
        var s = new Server();
        if (fx["server"] is JsonObject info) s._serverInfo = info.DeepClone().AsObject();
        foreach (var t in fx["tools"]?.AsArray() ?? [])
        {
            if (t is not JsonObject to || J.Str(to["name"]) == null) throw new InvalidDataException($"fixture tool without a name: {t}");
            s._tools.Add(to.DeepClone().AsObject());
        }
        s._tools.Sort((a, b) => string.CompareOrdinal(J.Str(a["name"]), J.Str(b["name"])));
        var root = Path.GetFullPath(Path.Combine(Path.GetDirectoryName(Path.GetFullPath(fixturePath))!, J.Str(fx["skillsDir"], ".")));
        if (Directory.Exists(root))
            foreach (var md in Directory.EnumerateFiles(root, "SKILL.md", SearchOption.AllDirectories))
                s._skills.Add(LoadSkill(Path.GetDirectoryName(md)!));
        s._skills.Sort((a, b) => string.CompareOrdinal(a.Uri, b.Uri));
        return s;
    }

    /// <summary>The shared spike server (tools echo + greet, skill hello-world) from ../fixture.json or $FIXTURE.</summary>
    public static Server Fixture() => Load(FixturePath());

    public static string FixturePath()
    {
        var env = Environment.GetEnvironmentVariable("FIXTURE");
        if (!string.IsNullOrEmpty(env)) return env;
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null; d = d.Parent)
        {
            var f = Path.Combine(d.FullName, "fixture.json");
            if (File.Exists(f) && Directory.Exists(Path.Combine(d.FullName, "csharp"))) return f;
        }
        throw new FileNotFoundException("spikes/mcp-2026/fixture.json not found; set FIXTURE");
    }

    private static Skill LoadSkill(string dir)
    {
        var fm = ParseFrontmatter(File.ReadAllText(Path.Combine(dir, "SKILL.md")));
        var name = J.Str(fm["name"], "");
        if (name == "" || name != Path.GetFileName(dir)) throw new InvalidDataException($"skill {dir}: name must match dir");
        var files = Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories)
            .Select(p => new SkillFile($"skill://{name}/{Path.GetRelativePath(dir, p).Replace('\\', '/')}", File.ReadAllBytes(p)))
            .OrderBy(f => f.Uri, StringComparer.Ordinal).ToList();
        return new Skill($"skill://{name}/SKILL.md", fm, files);
    }

    /// <summary>The flat <c>key: value</c> YAML subset the fixture skills use.</summary>
    internal static JsonObject ParseFrontmatter(string md)
    {
        var fm = new JsonObject();
        md = md.Replace("\r\n", "\n");
        if (!md.StartsWith("---\n")) return fm;
        var end = md.IndexOf("\n---", 4, StringComparison.Ordinal);
        if (end < 0) return fm;
        foreach (var line in md[4..end].Split('\n'))
        {
            var i = line.IndexOf(':');
            if (i >= 0 && !line.StartsWith(' ')) fm[line[..i].Trim()] = line[(i + 1)..].Trim().Trim('"', '\'');
        }
        return fm;
    }

    private JsonObject ServerInfo() => new() { [Mcp.MetaServerInfo] = _serverInfo.DeepClone() };

    /// <summary>The ASP.NET Core entry point: serve with <see cref="HttpHost"/> or map it into your own app.</summary>
    public async Task HandleAsync(HttpContext http)
    {
        var r = http.Request;
        if (Auth != null && HttpMethods.IsGet(r.Method) && await Auth.ServeMetadataAsync(http)) return;
        if (r.Path != "/mcp") { http.Response.StatusCode = 404; return; }
        if (!HttpMethods.IsPost(r.Method)) { http.Response.StatusCode = 405; return; }
        var body = await ReadCappedAsync(r.Body, MaxBodyBytes, http.RequestAborted);
        if (body == null)
        {
            await WriteRpcAsync(http, 413, null, null, new McpRpcException(-32600, "Request body too large or unreadable"));
            return;
        }
        JsonObject? req;
        try { req = JsonNode.Parse(body) as JsonObject; } catch (JsonException) { req = null; }
        if (req == null)
        {
            await WriteRpcAsync(http, 400, null, null, new McpRpcException(-32700, "Parse error"));
            return;
        }
        var id = req["id"];
        if (id == null) { http.Response.StatusCode = 202; return; } // notification: none defined in core; accept
        var method = J.Str(req["method"], "");
        var p = req["params"] as JsonObject ?? [];
        var (status, verr) = Validate(r, method, p);
        if (verr != null) { await WriteRpcAsync(http, status, id, null, verr); return; }
        Principal? principal = null;
        if (Auth != null)
        {
            var (ok, pr) = await Auth.CheckAsync(http, method, p);
            if (!ok) return;
            principal = pr;
        }
        var (res, err) = await DispatchAsync(new ToolContext { Principal = principal, Cancel = http.RequestAborted }, method, p);
        await WriteRpcAsync(http, err?.Code == Mcp.CodeMethodNotFound ? 404 : 200, id, res, err);
    }

    private static async Task<byte[]?> ReadCappedAsync(Stream s, int max, CancellationToken ct)
    {
        var ms = new MemoryStream();
        var buf = new byte[16384];
        try
        {
            int n;
            while ((n = await s.ReadAsync(buf, ct)) > 0)
            {
                ms.Write(buf, 0, n);
                if (ms.Length > max) return null;
            }
        }
        catch (Exception e) when (e is IOException or BadHttpRequestException) { return null; }
        return ms.ToArray();
    }

    private static (int, McpRpcException?) Validate(HttpRequest r, string method, JsonObject p)
    {
        static (int, McpRpcException?) Mismatch(string msg) => (400, new McpRpcException(Mcp.CodeHeaderMismatch, "Header mismatch: " + msg));
        var hv = r.Headers["MCP-Protocol-Version"].ToString();
        if (hv == "") return Mismatch("MCP-Protocol-Version header missing");
        var hm = r.Headers["Mcp-Method"].ToString();
        if (hm != method) return Mismatch($"Mcp-Method header value '{hm}' does not match body value '{method}'");
        if (method is "tools/call" or "resources/read" or "prompts/get")
        {
            var want = J.Str(method == "resources/read" ? p["uri"] : p["name"], "");
            var (got, ok) = DecodeHeader(r.Headers["Mcp-Name"].ToString());
            if (!ok || got != want) return Mismatch($"Mcp-Name header value '{got}' does not match body value '{want}'");
        }
        var mv = J.Str((p["_meta"] as JsonObject)?[Mcp.MetaVersion], "");
        if (mv != hv) return Mismatch($"MCP-Protocol-Version header value '{hv}' does not match _meta value '{mv}'");
        if (mv != Mcp.ProtocolVersion) return (400, Unsupported(mv));
        return (0, null);
    }

    internal static McpRpcException Unsupported(string requested) =>
        new(Mcp.CodeUnsupportedProtocol, "Unsupported protocol version",
            new JsonObject { ["supported"] = J.Array([Mcp.ProtocolVersion]), ["requested"] = requested });

    internal static (string, bool) DecodeHeader(string v)
    {
        if (v.StartsWith("=?base64?") && v.EndsWith("?=") && v.Length >= 11)
        {
            try { return (Encoding.UTF8.GetString(Convert.FromBase64String(v[9..^2])), true); }
            catch (FormatException) { return ("", false); }
        }
        return (v, v != "");
    }

    internal async Task<(JsonObject?, McpRpcException?)> DispatchAsync(ToolContext ctx, string method, JsonObject p)
    {
        JsonObject Complete(JsonObject o) { o["resultType"] = "complete"; o["_meta"] = ServerInfo(); return o; }
        switch (method)
        {
            case "server/discover":
                return (Complete(new JsonObject
                {
                    ["supportedVersions"] = J.Array([Mcp.ProtocolVersion]),
                    ["capabilities"] = new JsonObject { ["tools"] = new JsonObject(), ["resources"] = new JsonObject(), ["extensions"] = new JsonObject { [Mcp.SkillsExt] = new JsonObject() } },
                    ["ttlMs"] = 3600000, ["cacheScope"] = "public",
                }), null);
            case "tools/list":
                return (Complete(new JsonObject { ["tools"] = new JsonArray(_tools.Select(t => t.DeepClone()).ToArray()), ["ttlMs"] = 60000, ["cacheScope"] = "public" }), null);
            case "tools/call":
                return await CallToolAsync(ctx, p);
            case "skills/list":
                return (Complete(new JsonObject { ["skills"] = new JsonArray(_skills.Select(s => (JsonNode?)s.Entry()).ToArray()), ["ttlMs"] = 60000, ["cacheScope"] = "public" }), null);
            case "skills/get":
            {
                var uri = J.Str(p["uri"], "");
                if (!SkillUri.TryParse(uri, out _, out _, out var why)) return (null, new McpRpcException(Mcp.CodeInvalidParams, "Invalid skill URI: " + why));
                var sk = _skills.FirstOrDefault(s => s.Uri == uri);
                return sk == null ? (null, new McpRpcException(Mcp.CodeInvalidParams, "Unknown skill: " + uri)) : (Complete(new JsonObject { ["skill"] = sk.Entry() }), null);
            }
            case "resources/read":
            {
                var uri = J.Str(p["uri"], "");
                if (!SkillUri.TryParse(uri, out _, out _, out var why)) return (null, new McpRpcException(Mcp.CodeInvalidParams, "Invalid resource URI: " + why));
                var f = _skills.SelectMany(s => s.Files).FirstOrDefault(f => f.Uri == uri);
                if (f == null) return (null, new McpRpcException(Mcp.CodeInvalidParams, "Resource not found: " + uri));
                return (Complete(new JsonObject
                {
                    ["contents"] = new JsonArray(new JsonObject { ["uri"] = uri, ["mimeType"] = uri.EndsWith(".md") ? "text/markdown" : "text/plain", ["text"] = Encoding.UTF8.GetString(f.Data) }),
                    ["ttlMs"] = 60000, ["cacheScope"] = "public",
                }), null);
            }
        }
        return (null, new McpRpcException(Mcp.CodeMethodNotFound, "Method not found: " + method));
    }

    private static JsonArray TextContent(string t) => new(new JsonObject { ["type"] = "text", ["text"] = t });

    private async Task<(JsonObject?, McpRpcException?)> CallToolAsync(ToolContext ctx, JsonObject p)
    {
        var name = J.Str(p["name"], "");
        var args = p["arguments"] as JsonObject ?? [];
        if (_handlers.TryGetValue(name, out var h))
        {
            object? v;
            try { v = await h(ctx, (JsonObject)args.DeepClone()); }
            catch (Exception e) { return (new JsonObject { ["resultType"] = "complete", ["content"] = TextContent(e.Message), ["isError"] = true }, null); }
            switch (v)
            {
                case string s:
                    return (new JsonObject { ["resultType"] = "complete", ["content"] = TextContent(s), ["isError"] = false }, null);
                case JsonObject o when o.ContainsKey("content"):
                    o["resultType"] ??= "complete";
                    return (o, null);
            }
            JsonNode? node;
            try { node = v as JsonNode ?? JsonSerializer.SerializeToNode(v, J.Options); }
            catch (Exception e) { return (null, new McpRpcException(-32603, "tool result is not JSON: " + e.Message)); }
            return (new JsonObject { ["resultType"] = "complete", ["content"] = TextContent(J.Serialize(node)), ["structuredContent"] = node?.DeepClone(), ["isError"] = false }, null);
        }
        JsonObject Text(string t, JsonObject structured) => new()
        {
            ["resultType"] = "complete", ["content"] = TextContent(t), ["isError"] = false, ["_meta"] = ServerInfo(), ["structuredContent"] = structured,
        };
        switch (name)
        {
            case "echo":
            {
                var t = J.Str(args["text"], "");
                return (Text(t, new JsonObject { ["text"] = t }), null);
            }
            case "greet":
            {
                if (p["inputResponses"]?["name"] is JsonObject nr && J.Str(nr["action"]) == "accept" && J.Str(nr["content"]?["name"], "") is { Length: > 0 } n)
                    return (Text($"Hello, {n}!", new JsonObject { ["greeting"] = $"Hello, {n}!" }), null);
                // Missing/declined answer: ask (again), per MRTR error-handling guidance.
                return (new JsonObject
                {
                    ["resultType"] = "input_required", ["_meta"] = ServerInfo(),
                    ["inputRequests"] = new JsonObject
                    {
                        ["name"] = new JsonObject
                        {
                            ["method"] = "elicitation/create",
                            ["params"] = new JsonObject
                            {
                                ["mode"] = "form", ["message"] = "What is your name?",
                                ["requestedSchema"] = new JsonObject
                                {
                                    ["type"] = "object", ["properties"] = new JsonObject { ["name"] = new JsonObject { ["type"] = "string" } }, ["required"] = J.Array(["name"]),
                                },
                            },
                        },
                    },
                }, null);
            }
        }
        return (null, new McpRpcException(Mcp.CodeInvalidParams, "Unknown tool: " + name));
    }

    private static async Task WriteRpcAsync(HttpContext http, int status, JsonNode? id, JsonObject? result, McpRpcException? e)
    {
        var msg = RpcMessage(id, result, e);
        http.Response.StatusCode = status;
        http.Response.ContentType = "application/json";
        await http.Response.WriteAsync(J.Serialize(msg) + "\n");
    }

    internal static JsonObject RpcMessage(JsonNode? id, JsonObject? result, McpRpcException? e)
    {
        var msg = new JsonObject { ["jsonrpc"] = "2.0", ["id"] = id?.DeepClone() };
        if (e != null) msg["error"] = e.ToJson(); else msg["result"] = result;
        return msg;
    }
}
