using System.Text;
using System.Text.Json;

namespace Toolnexus.Tests;

// SPEC §7C.1 cross-port conformance: replays ../conformance/mcp_handler/cases.json (harness contract
// in conformance/mcp_handler/README.md). The Go reference is golang/mcphandler_conformance_test.go.
public class McpHandlerConformanceTests
{
    private static string CasesPath()
    {
        foreach (var start in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            for (var dir = new DirectoryInfo(start); dir != null; dir = dir.Parent)
            {
                var p = Path.Combine(dir.FullName, "conformance", "mcp_handler", "cases.json");
                if (File.Exists(p)) return p;
            }
        }
        throw new FileNotFoundException("conformance/mcp_handler/cases.json not found above the test binary");
    }

    private static JsonElement Root() => JsonDocument.Parse(File.ReadAllText(CasesPath())).RootElement.Clone();

    public static IEnumerable<object[]> Scenarios()
        => Root().GetProperty("scenarios").EnumerateArray().Select(s => new object[] { s.GetProperty("name").GetString()! });

    private static string? Str(JsonElement e, string k)
        => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static bool Bool(JsonElement e, string k)
        => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.True;

    private static bool Has(JsonElement e, string k, out JsonElement v)
    {
        v = default;
        return e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out v) && v.ValueKind != JsonValueKind.Null;
    }

    private static Dictionary<string, object?>? Dict(JsonElement e, string k)
        => Has(e, k, out var v) ? (Dictionary<string, object?>)Json.FromElement(v)! : null;

    private static ToolResult ToResult(JsonElement r) => new(Str(r, "output") ?? "", Bool(r, "isError"))
    {
        Structured = Has(r, "structured", out var s) ? Json.FromElement(s) : null,
        Meta = Dict(r, "meta"),
    };

    [Theory]
    [MemberData(nameof(Scenarios))]
    public async Task Conformance(string scenario)
    {
        var root = Root();
        var fixture = root.GetProperty("fixture");
        var sc = root.GetProperty("scenarios").EnumerateArray().First(s => s.GetProperty("name").GetString() == scenario);
        var config = sc.TryGetProperty("config", out var cfgEl) ? cfgEl : default;

        var gate = new object();
        var executed = new List<string>();
        var onCalls = new List<string>();
        var byName = fixture.GetProperty("tools").EnumerateArray().ToDictionary(t => t.GetProperty("name").GetString()!);

        var tools = new List<ITool>();
        foreach (var (name, ft) in byName)
        {
            ToolAnnotations? ann = null;
            if (Has(ft, "annotations", out var a))
                ann = new ToolAnnotations
                {
                    Title = Str(a, "title"),
                    ReadOnlyHint = a.TryGetProperty("readOnlyHint", out var x1) ? x1.GetBoolean() : null,
                    DestructiveHint = a.TryGetProperty("destructiveHint", out var x2) ? x2.GetBoolean() : null,
                    IdempotentHint = a.TryGetProperty("idempotentHint", out var x3) ? x3.GetBoolean() : null,
                    OpenWorldHint = a.TryGetProperty("openWorldHint", out var x4) ? x4.GetBoolean() : null,
                };
            ToolHints? hints = Has(ft, "hints", out var h)
                ? new ToolHints(Bool(h, "readOnly"), Bool(h, "destructive"), Bool(h, "openWorld"))
                : null;
            var behavior = Str(ft, "behavior");
            tools.Add(new FnTool(name, args =>
            {
                lock (gate) executed.Add(name);
                switch (behavior)
                {
                    case "echo":
                        return new ToolResult(args.TryGetValue("text", out var t) ? t as string ?? "" : "", false);
                    case "whoami":
                        var c = McpContext.Current;
                        var trace = c?.Meta != null && c.Meta.TryGetValue("trace", out var tr) ? tr as string : null;
                        return new ToolResult((c?.Identity as string ?? "") + "|" + (trace ?? ""), false);
                    case "error":
                        throw new InvalidOperationException(Str(ft, "error"));
                }
                return ToResult(ft.GetProperty("result"));
            })
            {
                Description = Str(ft, "description") ?? "",
                InputSchema = Dict(ft, "inputSchema")!,
                OutputSchema = Dict(ft, "outputSchema"),
                Annotations = ann,
                Hints = hints,
            });
        }

        var cfg = new McpHandlerConfig
        {
            Name = Str(config, "name"),
            Title = Str(config, "title"),
            Version = Str(config, "version"),
            Instructions = Str(config, "instructions"),
            Stateless = Bool(config, "stateless"),
            StatelessSessionId = Str(config, "statelessSessionId"),
            ProtocolVersions = Has(config, "protocolVersions", out var pv) ? pv.EnumerateArray().Select(v => v.GetString()!).ToList() : null,
            ListTools = ctx =>
            {
                var id = ctx.Identity as string;
                IReadOnlyList<ITool> visible = tools.Where(t =>
                    !Has(byName[t.Name], "visibleTo", out var vt) || vt.EnumerateArray().Any(v => v.GetString() == id)).ToList();
                return Task.FromResult(visible);
            },
            BeforeCall = (_, tool, _) =>
            {
                if (!Has(byName[tool.Name], "decision", out var d)) return Task.FromResult(McpDecision.Allow());
                var kind = Str(d, "kind") switch
                {
                    "deny" => McpDecisionKind.Deny,
                    "confirm" => McpDecisionKind.Confirm,
                    _ => McpDecisionKind.Allow,
                };
                return Task.FromResult(new McpDecision(kind, Str(d, "message"), Has(d, "result", out var r) ? ToResult(r) : null));
            },
            OnCall = ev =>
            {
                lock (gate) onCalls.Add(JsonSerializer.Serialize(new Dictionary<string, object?>
                {
                    ["decision"] = ev.Decision.ToString().ToLowerInvariant(),
                    ["isError"] = ev.IsError,
                    ["name"] = ev.Name,
                }));
            },
        };
        if (Bool(config, "auth"))
        {
            var auth = fixture.GetProperty("auth");
            cfg.Authenticate = ex =>
            {
                var tok = ex.Header("Authorization");
                if (tok != null && auth.GetProperty("tokens").TryGetProperty(tok, out var id)) return Task.FromResult<object?>(id.GetString());
                throw new UnauthorizedAccessException("unauthorized");
            };
            cfg.WwwAuthenticate = (_, _) => Str(auth, "wwwAuthenticate");
        }

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        using var host = new HandlerHost(tk.McpHandler(cfg));
        string? sid = null;
        var i = 0;
        foreach (var st in sc.GetProperty("steps").EnumerateArray())
        {
            var rq = st.GetProperty("request");
            var method = Str(rq, "method")!;
            var req = new HttpRequestMessage(new HttpMethod(method), host.Url);
            string? body = Str(rq, "rawBody") ?? (Has(rq, "body", out var b) ? b.GetRawText() : null);
            if (body != null || method == "POST")
            {
                req.Content = new ByteArrayContent(Encoding.UTF8.GetBytes(body ?? ""));
                if (method == "POST")
                    req.Content.Headers.TryAddWithoutValidation("Content-Type", Str(rq, "contentType") ?? "application/json");
            }
            req.Headers.TryAddWithoutValidation("Accept", Str(rq, "accept") ?? "application/json, text/event-stream");
            var tok = Str(rq, "token") ?? Str(sc, "token");
            if (!string.IsNullOrEmpty(tok)) req.Headers.TryAddWithoutValidation("Authorization", tok);
            if (!string.IsNullOrEmpty(Str(rq, "sessionId"))) req.Headers.TryAddWithoutValidation("Mcp-Session-Id", Str(rq, "sessionId"));
            else if (Bool(rq, "session") && sid != null) req.Headers.TryAddWithoutValidation("Mcp-Session-Id", sid);
            if (Str(rq, "version") is { } ver) req.Headers.TryAddWithoutValidation("MCP-Protocol-Version", ver);

            var resp = await McpHandlerTests.Http.SendAsync(req);
            var rb = await resp.Content.ReadAsStringAsync();
            string Header(string k) =>
                resp.Headers.TryGetValues(k, out var v) ? string.Join(", ", v)
                : resp.Content.Headers.TryGetValues(k, out var cv) ? string.Join(", ", cv) : "";

            var ex = st.GetProperty("expect");
            var at = $"{scenario} step {i}";
            if (ex.TryGetProperty("status", out var status))
                Assert.True(status.GetInt32() == (int)resp.StatusCode, $"{at}: status {(int)resp.StatusCode} want {status} ({rb})");
            if (ex.TryGetProperty("headers", out var hs))
                foreach (var hp in hs.EnumerateObject())
                    Assert.True(Header(hp.Name) == hp.Value.GetString(), $"{at}: header {hp.Name}={Header(hp.Name)}");
            if (ex.TryGetProperty("headersPresent", out var hpres))
                foreach (var k in hpres.EnumerateArray())
                    Assert.True(Header(k.GetString()!) != "", $"{at}: header {k} missing");
            if (ex.TryGetProperty("headersAbsent", out var habs))
                foreach (var k in habs.EnumerateArray())
                    Assert.True(Header(k.GetString()!) == "", $"{at}: header {k} present");
            if (Str(st, "capture") == "sid") sid = Header("Mcp-Session-Id");
            var hasJson = ex.TryGetProperty("json", out var wantJson);
            var hasPath = ex.TryGetProperty("jsonPath", out var paths);
            if (hasJson || hasPath)
            {
                JsonElement got;
                try { got = JsonDocument.Parse(rb).RootElement.Clone(); }
                catch { throw new Xunit.Sdk.XunitException($"{at}: body not JSON: {rb}"); }
                if (hasJson) Assert.True(DeepEqual(got, wantJson), $"{at}: body\n got {rb}\nwant {wantJson.GetRawText()}");
                if (hasPath)
                    foreach (var p in paths.EnumerateObject())
                    {
                        var v = JsonPathOf(got, p.Name);
                        Assert.True(v is { } vv ? DeepEqual(vv, p.Value) : p.Value.ValueKind == JsonValueKind.Null,
                            $"{at}: {p.Name} = {v?.GetRawText()} want {p.Value.GetRawText()}");
                    }
            }
            i++;
        }

        if (Has(sc, "expectExecuted", out var ee))
            Assert.Equal(ee.EnumerateArray().Select(e => e.GetString()!).ToList(), executed);
        if (Has(sc, "expectOnCall", out var eo))
            Assert.Equal(eo.EnumerateArray().Select(e => JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["decision"] = e.GetProperty("decision").GetString(),
                ["isError"] = e.GetProperty("isError").GetBoolean(),
                ["name"] = e.GetProperty("name").GetString(),
            })).ToList(), onCalls);
    }

    private static JsonElement? JsonPathOf(JsonElement v, string path)
    {
        foreach (var seg in path.Split('.'))
        {
            if (v.ValueKind == JsonValueKind.Object)
            {
                if (!v.TryGetProperty(seg, out v)) return null;
            }
            else if (v.ValueKind == JsonValueKind.Array)
            {
                if (!int.TryParse(seg, out var n) || n >= v.GetArrayLength()) return null;
                v = v[n];
            }
            else return null;
        }
        return v;
    }

    /// <summary>Parsed-JSON deep equality; object key order ignored, numbers compared by value.</summary>
    private static bool DeepEqual(JsonElement a, JsonElement b)
    {
        if (a.ValueKind != b.ValueKind) return false;
        switch (a.ValueKind)
        {
            case JsonValueKind.Object:
                var ap = a.EnumerateObject().ToList();
                if (ap.Count != b.EnumerateObject().Count()) return false;
                return ap.All(p => b.TryGetProperty(p.Name, out var bv) && DeepEqual(p.Value, bv));
            case JsonValueKind.Array:
                return a.GetArrayLength() == b.GetArrayLength() && a.EnumerateArray().Zip(b.EnumerateArray()).All(t => DeepEqual(t.First, t.Second));
            case JsonValueKind.Number:
                return a.GetDecimal() == b.GetDecimal();
            case JsonValueKind.String:
                return a.GetString() == b.GetString();
            default:
                return true;
        }
    }
}
