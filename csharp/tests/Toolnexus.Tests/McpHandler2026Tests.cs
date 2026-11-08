using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Toolnexus.Tests;

// SPEC §7C.2 port-own tests (mirror golang/mcphandler2026_test.go): the default JWT verifier against
// the dev AS, the dev AS flows (pre-registered + CIMD, refresh), onCall / prompts / discover on the
// 2026 wire, and LoadMcpSkills.
public class McpHandler2026Tests
{
    private static readonly HttpClient NoRedirect = new(new HttpClientHandler { AllowAutoRedirect = false });

    private static async Task<(int Status, string Www, JsonNode? Body)> Post26(string url, string? token, string method, JsonObject? prm = null)
    {
        prm ??= new JsonObject();
        if (prm["_meta"] is not JsonObject meta) prm["_meta"] = meta = new JsonObject();
        meta["io.modelcontextprotocol/protocolVersion"] = "2026-07-28";
        var body = new JsonObject { ["jsonrpc"] = "2.0", ["id"] = 1, ["method"] = method, ["params"] = prm }.ToJsonString();
        var req = new HttpRequestMessage(HttpMethod.Post, url) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        req.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
        req.Headers.TryAddWithoutValidation("MCP-Protocol-Version", "2026-07-28");
        req.Headers.TryAddWithoutValidation("Mcp-Method", method);
        if (prm["name"] is JsonValue n) req.Headers.TryAddWithoutValidation("Mcp-Name", n.GetValue<string>());
        if (!string.IsNullOrEmpty(token)) req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
        var resp = await McpHandlerTests.Http.SendAsync(req);
        var raw = await resp.Content.ReadAsStringAsync();
        JsonNode? parsed = null;
        try { parsed = JsonNode.Parse(raw); } catch { }
        var www = resp.Headers.TryGetValues("WWW-Authenticate", out var w) ? string.Join(", ", w) : "";
        return ((int)resp.StatusCode, www, parsed);
    }

    private static CtxTool WhoTool() => new("who", (_, _) =>
        new ToolResult((McpContext.Current?.Identity as McpPrincipal)?.Subject ?? "", false));

    private static JsonObject CallWho() => new() { ["name"] = "who", ["arguments"] = new JsonObject() };

    private static string? Text(JsonNode? body) => body?["result"]?["content"]?[0]?["text"]?.GetValue<string>();

    private static async Task<Toolkit> NewTk() => await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });

    [Fact]
    public async Task DefaultJwtVerifierWithDevAs()
    {
        await using var dev = await McpDevAuthServer.StartAsync();
        await using var tk = await NewTk();
        var auth = new McpServerAuth
        {
            Issuer = dev.Issuer,
            Scopes = new Dictionary<string, IReadOnlyList<string>> { ["*"] = ["mcp"], ["who"] = ["mcp:who"] },
        };
        var handler = tk.McpHandler(new McpHandlerConfig
        {
            ProtocolVersions = ["2026-07-28"],
            Authenticate = auth.AuthenticateAsync,
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>([WhoTool()]),
        });
        using var host = new ListenerHost(ctx => ctx.Request.Url!.AbsolutePath == "/mcp" ? handler.HandleAsync(ctx) : auth.MetadataAsync(ctx));
        var origin = host.Url.TrimEnd('/');
        var u = origin + "/mcp";
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        string Mint(Dictionary<string, object?>? over = null)
        {
            var c = new Dictionary<string, object?> { ["iss"] = dev.Issuer, ["sub"] = "dev-user", ["aud"] = u, ["scope"] = "mcp mcp:who", ["exp"] = now + 60 };
            foreach (var (k, v) in over ?? new()) c[k] = v;
            return dev.Mint(c);
        }

        var (st, _, body) = await Post26(u, Mint(), "tools/call", CallWho());
        Assert.Equal(200, st);
        Assert.Equal("dev-user", Text(body));

        var good = Mint();
        var bad = new Dictionary<string, string>
        {
            ["wrong audience"] = Mint(new() { ["aud"] = "https://other.example/mcp" }),
            ["aud array miss"] = Mint(new() { ["aud"] = new List<object?> { "x" } }),
            ["expired"] = Mint(new() { ["exp"] = now - 1 }),
            ["wrong issuer"] = Mint(new() { ["iss"] = "https://evil.example" }),
            ["tampered"] = good[..20] + (good[20] == 'x' ? 'y' : 'x') + good[21..],
            ["not a jwt"] = "opaque",
        };
        foreach (var (name, tok) in bad)
        {
            var (s, www, _) = await Post26(u, tok, "server/discover");
            Assert.True(s == 401 && www.Contains("error=\"invalid_token\"") && !www.Contains(tok), $"{name}: {s} {www}");
        }
        // an audience array that contains the resource is accepted
        var (sa, _, ba) = await Post26(u, Mint(new() { ["aud"] = new List<object?> { "x", u } }), "tools/call", CallWho());
        Assert.Equal(200, sa);
        Assert.Equal("dev-user", Text(ba));

        var (s403, www403, _) = await Post26(u, Mint(new() { ["scope"] = "mcp" }), "tools/call", CallWho());
        Assert.Equal(403, s403);
        Assert.Equal($"Bearer resource_metadata=\"{origin}/.well-known/oauth-protected-resource/mcp\", scope=\"mcp mcp:who\", error=\"insufficient_scope\"", www403);

        var (s401, www401, b401) = await Post26(u, null, "server/discover");
        Assert.Equal(401, s401);
        Assert.Equal($"Bearer resource_metadata=\"{origin}/.well-known/oauth-protected-resource/mcp\", scope=\"mcp\"", www401);
        Assert.Equal("unauthorized", b401?["error"]?.GetValue<string>());

        var prm = JsonNode.Parse(await McpHandlerTests.Http.GetStringAsync(origin + "/.well-known/oauth-protected-resource/mcp"))!;
        Assert.Equal(u, prm["resource"]!.GetValue<string>());
        Assert.Equal(dev.Issuer, prm["authorization_servers"]![0]!.GetValue<string>());
        Assert.Equal("mcp", prm["scopes_supported"]![0]!.GetValue<string>());
        Assert.Equal(HttpStatusCode.NotFound, (await McpHandlerTests.Http.GetAsync(origin + "/.well-known/other")).StatusCode);
    }

    private static async Task<JsonNode> PkceLogin(McpDevAuthServer dev, string clientId, string redirect, string resource, string scope)
    {
        var verifier = "v-" + new string('a', 50);
        var challenge = System.Buffers.Text.Base64Url.EncodeToString(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        string Q(params (string K, string V)[] kv) => string.Join('&', kv.Select(x => Uri.EscapeDataString(x.K) + "=" + Uri.EscapeDataString(x.V)));
        var resp = await NoRedirect.GetAsync(dev.Issuer + "/authorize?" + Q(("response_type", "code"), ("client_id", clientId),
            ("redirect_uri", redirect), ("state", "s1"), ("code_challenge", challenge), ("code_challenge_method", "S256"),
            ("resource", resource), ("scope", scope)));
        Assert.Equal(302, (int)resp.StatusCode);
        var loc = resp.Headers.Location!;
        var cb = System.Web.HttpUtility.ParseQueryString(loc.Query);
        Assert.Equal(dev.Issuer, cb["iss"]);
        Assert.Equal("s1", cb["state"]);
        Assert.False(string.IsNullOrEmpty(cb["code"]));
        var tr = await McpHandlerTests.Http.PostAsync(dev.Issuer + "/token", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["grant_type"] = "authorization_code", ["code"] = cb["code"]!, ["code_verifier"] = verifier,
            ["client_id"] = clientId, ["redirect_uri"] = redirect, ["resource"] = resource,
        }));
        var tok = JsonNode.Parse(await tr.Content.ReadAsStringAsync())!;
        Assert.True(tr.StatusCode == HttpStatusCode.OK, tok.ToJsonString());
        return tok;
    }

    [Fact]
    public async Task DevAsPreRegisteredAndCimd()
    {
        string? docUrl = null;
        using var cimd = new ListenerHost(async ctx =>
        {
            var b = Encoding.UTF8.GetBytes(new JsonObject
            {
                ["client_id"] = docUrl, ["client_name"] = "CIMD app", ["redirect_uris"] = new JsonArray("http://127.0.0.1/cb"),
            }.ToJsonString());
            ctx.Response.ContentType = "application/json";
            await ctx.Response.OutputStream.WriteAsync(b);
        });
        docUrl = cimd.Url + "client.json";
        await using var dev = await McpDevAuthServer.StartAsync(new McpDevAuthOptions
        {
            Clients = new Dictionary<string, IReadOnlyList<string>> { ["app"] = ["http://127.0.0.1/cb"] },
        });
        await using var tk = await NewTk();
        var auth = new McpServerAuth { Issuer = dev.Issuer, Scopes = new Dictionary<string, IReadOnlyList<string>> { ["*"] = ["mcp"] } };
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            ProtocolVersions = ["2026-07-28"],
            Authenticate = auth.AuthenticateAsync,
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>([WhoTool()]),
        }));
        var u = host.Url + "mcp";
        foreach (var cid in new[] { "app", docUrl })
        {
            var tok = await PkceLogin(dev, cid, "http://127.0.0.1:5555/cb", u, "mcp");
            var (st, _, body) = await Post26(u, tok["access_token"]!.GetValue<string>(), "tools/call", CallWho());
            Assert.True(st == 200 && Text(body) == "dev-user", $"{cid}: {st} {body?.ToJsonString()}");
            // refresh rotates and keeps working; the old refresh token is spent
            var form = new Dictionary<string, string>
            {
                ["grant_type"] = "refresh_token", ["refresh_token"] = tok["refresh_token"]!.GetValue<string>(), ["client_id"] = cid, ["resource"] = u,
            };
            var rr = await McpHandlerTests.Http.PostAsync(dev.Issuer + "/token", new FormUrlEncodedContent(form));
            Assert.Equal(HttpStatusCode.OK, rr.StatusCode);
            var fresh = JsonNode.Parse(await rr.Content.ReadAsStringAsync())!;
            var (st2, _, body2) = await Post26(u, fresh["access_token"]!.GetValue<string>(), "tools/call", CallWho());
            Assert.True(st2 == 200 && Text(body2) == "dev-user");
            var again = await McpHandlerTests.Http.PostAsync(dev.Issuer + "/token", new FormUrlEncodedContent(form));
            Assert.Equal(HttpStatusCode.BadRequest, again.StatusCode);
        }
        // unknown client / unregistered redirect never redirects
        var resp = await NoRedirect.GetAsync(dev.Issuer + "/authorize?client_id=nobody&redirect_uri=http://127.0.0.1/cb");
        Assert.Equal(400, (int)resp.StatusCode);
        Assert.Equal(3, dev.Authorizations);

        var md = JsonNode.Parse(await McpHandlerTests.Http.GetStringAsync(dev.Issuer + "/.well-known/oauth-authorization-server"))!;
        Assert.Equal(dev.Issuer, md["issuer"]!.GetValue<string>());
        Assert.True(md["client_id_metadata_document_supported"]!.GetValue<bool>());
        Assert.Equal("S256", md["code_challenge_methods_supported"]![0]!.GetValue<string>());
    }

    [Fact]
    public async Task DevAsMissingResourceIsInvalidTarget()
    {
        await using var dev = await McpDevAuthServer.StartAsync(new McpDevAuthOptions
        {
            Clients = new Dictionary<string, IReadOnlyList<string>> { ["app"] = ["http://127.0.0.1/cb"] },
        });
        var resp = await NoRedirect.GetAsync(dev.Issuer + "/authorize?response_type=code&client_id=app&redirect_uri="
            + Uri.EscapeDataString("http://127.0.0.1/cb") + "&code_challenge=x&code_challenge_method=S256&state=s");
        Assert.Equal(302, (int)resp.StatusCode);
        Assert.Equal("invalid_target", System.Web.HttpUtility.ParseQueryString(resp.Headers.Location!.Query)["error"]);
    }

    [Fact]
    public async Task OnCallPromptsAndDiscoverOn2026()
    {
        var events = new List<McpCallEvent>();
        await using var tk = await NewTk();
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            ProtocolVersions = ["2026-07-28"],
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>([WhoTool()]),
            OnCall = ev =>
            {
                lock (events) events.Add(ev);
                throw new InvalidOperationException("isolated");
            },
            ListPrompts = _ => Task.FromResult<IReadOnlyList<McpPrompt>>([new McpPrompt("p", Description: "d")]),
            GetPrompt = (_, name, _) => Task.FromResult(new McpPromptResult([new McpPromptMessage("user", "hi " + name)])),
        }));
        var (st, _, _) = await Post26(host.Url, null, "tools/call", CallWho());
        Assert.Equal(200, st);
        Assert.Single(events);
        Assert.Equal("who", events[0].Name);

        var (_, _, d) = await Post26(host.Url, null, "server/discover");
        var caps = d!["result"]!["capabilities"]!.AsObject();
        Assert.True(caps.ContainsKey("prompts"));
        Assert.False(caps.ContainsKey("extensions"));
        Assert.False(caps.ContainsKey("resources"));

        var (_, _, g) = await Post26(host.Url, null, "prompts/get", new JsonObject { ["name"] = "p" });
        Assert.Equal("hi p", g!["result"]!["messages"]![0]!["content"]!["text"]!.GetValue<string>());
        Assert.Equal("complete", g["result"]!["resultType"]!.GetValue<string>());
        var (_, _, l) = await Post26(host.Url, null, "prompts/list");
        Assert.Equal("p", l!["result"]!["prompts"]![0]!["name"]!.GetValue<string>());
    }

    [Fact]
    public async Task AuthorizationUrlSuspensionIsUrlModeElicitation()
    {
        await using var tk = await NewTk();
        var login = new CtxTool("login", (_, tc) => tc?.Answer is { Ok: true }
            ? new ToolResult("signed in", false)
            : ToolResult.Pending(new Request { Id = "auth-1", Kind = "authorization", Prompt = "Sign in", Url = "https://login.example/x" }));
        using var host = new HandlerHost(tk.McpHandler(new McpHandlerConfig
        {
            ProtocolVersions = ["2026-07-28"],
            ListTools = _ => Task.FromResult<IReadOnlyList<ITool>>([login]),
        }));
        var (_, _, b) = await Post26(host.Url, null, "tools/call", new JsonObject { ["name"] = "login", ["arguments"] = new JsonObject() });
        var r = b!["result"]!;
        Assert.Equal("input_required", r["resultType"]!.GetValue<string>());
        var ir = r["inputRequests"]!["auth-1"]!["params"]!;
        Assert.Equal("url", ir["mode"]!.GetValue<string>());
        Assert.Equal("https://login.example/x", ir["url"]!.GetValue<string>());
        Assert.Equal("auth-1", ir["elicitationId"]!.GetValue<string>());
        var state = r["requestState"]!.GetValue<string>();
        var (_, _, b2) = await Post26(host.Url, null, "tools/call", new JsonObject
        {
            ["name"] = "login", ["arguments"] = new JsonObject(), ["requestState"] = state,
            ["inputResponses"] = new JsonObject { ["auth-1"] = new JsonObject { ["action"] = "accept" } },
        });
        Assert.Equal("signed in", Text(b2));
    }

    [Fact]
    public void LoadMcpSkillsOnExamples()
    {
        string? dir = null;
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null && dir == null; d = d.Parent)
            if (Directory.Exists(Path.Combine(d.FullName, "examples", "skills", "hello-world"))) dir = Path.Combine(d.FullName, "examples", "skills");
        var skills = McpHttpHandler.LoadMcpSkills(dir!);
        Assert.Single(skills);
        Assert.Equal("hello-world", skills[0].Name);
        Assert.Equal(new[] { "SKILL.md", "scripts/greet.sh" }, skills[0].Files.Select(f => f.Path).ToArray());
        Assert.Equal("hello-world", skills[0].Frontmatter!["name"]);
        Assert.StartsWith("A tiny example skill.", (string)skills[0].Frontmatter!["description"]!);
    }

    [Fact]
    public void ImplementedVersionsIncludes2026ButNeverDefaults()
    {
        Assert.Contains("2026-07-28", McpHttpHandler.ImplementedVersions);
        Assert.Equal(("hello-world", "a/b.md", (string?)null), McpHttpHandler.ParseSkillUri("skill://hello-world/a/b.md"));
        Assert.Equal("bad percent-encoding", McpHttpHandler.ParseSkillUri("skill://x/%zz").Why);
        Assert.Equal("not a skill:// URI", McpHttpHandler.ParseSkillUri("file:///x").Why);
    }
}
