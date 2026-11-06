using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Toolnexus;

namespace Mcp2026Interop;

/// <summary>
/// The CLIENT half of the interop matrix: runs the shared scenario list against one server runner and prints
/// <c>PASS &lt;id&gt;</c> / <c>FAIL &lt;id&gt;: &lt;reason&gt;</c> per scenario; exit 0 iff all passed.
/// Mirrors golang/internal/interop/mcp2026client.
/// </summary>
internal static class InteropClient
{
    private static bool _failed;
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(30) };
    private static readonly IDictionary<string, object?> NoArgs = new Dictionary<string, object?>();

    private sealed class Fail(string m) : Exception(m);

    private static async Task Scenario(string id, Func<Task> fn)
    {
        try
        {
            await fn();
            Console.WriteLine($"PASS {id}");
        }
        catch (Exception e)
        {
            _failed = true;
            var msg = e is Fail ? e.Message : $"{e.GetType().Name}: {e.Message}";
            Console.WriteLine($"FAIL {id}: {msg.Replace('\n', ' ')}");
        }
    }

    private static Task<Answer> Answers(Request r) => r.Prompt switch
    {
        "What is your name?" => Task.FromResult(new Answer { Id = r.Id, Ok = true, Data = new Dictionary<string, object?> { ["name"] = "toolnexus" } }),
        "Really wipe?" => Task.FromResult(new Answer { Id = r.Id, Ok = true, Data = new Dictionary<string, object?> { ["confirm"] = true } }),
        _ => throw new Fail($"unexpected request \"{r.Prompt}\""),
    };

    private static Dictionary<string, object?> D(params (string K, object? V)[] kv) => kv.ToDictionary(x => x.K, x => x.V);

    private static Dictionary<string, object?> Servers(IDictionary<string, object?> cfg) => D(("mcpServers", D(("srv", cfg))));

    private static async Task<McpSource> Load(IDictionary<string, object?> cfg, McpLoadOptions opts)
    {
        var src = await McpSource.LoadAsync(Servers(cfg), opts);
        if (src.Status.GetValueOrDefault("srv") != "connected")
        {
            await src.DisposeAsync();
            throw new Fail("not connected: " + string.Join(",", src.Status.Select(kv => kv.Key + "=" + kv.Value)));
        }
        return src;
    }

    private static ITool Tool(McpSource src, string name) => src.Tools.FirstOrDefault(t => t.Name == name) ?? throw new Fail("no tool " + name);

    private static Task<ToolResult> Run(McpSource src, string name, IDictionary<string, object?>? args = null) =>
        Tool(src, name).ExecuteAsync(args ?? NoArgs, new ToolContext());

    private static async Task Expect(McpSource src, string name, IDictionary<string, object?>? args, string want)
    {
        var r = await Run(src, name, args);
        if (r.IsError || r.Output != want) throw new Fail($"{name}: got \"{r.Output}\" (isError={r.IsError}), want \"{want}\"");
    }

    private static async Task Get(string u) { using var _ = await Http.GetAsync(u); }

    private static bool HasScope(Token? tok, string scope) => tok != null && tok.Scopes.Contains(scope);

    public static async Task<int> RunAsync(string[] args)
    {
        if (args.Length == 3 && args[0] == "--keycloak") await Keycloak(args[1]);
        else if (args.Length == 2) await Hermetic(args[0], args[1]);
        else
        {
            Console.Error.WriteLine("usage: client <base> <skillsDir> | client --keycloak <mcpURL> <skillsDir>");
            return 2;
        }
        return _failed ? 1 : 0;
    }

    private static async Task Hermetic(string b, string skillsDir)
    {
        var open = D(("url", b + "/open/mcp"));

        await Scenario("discover", async () =>
        {
            await using var src = await Load(open, new McpLoadOptions { WaitFor = Answers });
            var names = string.Join(",", src.Tools.Select(t => t.Name));
            if (names != "srv_echo,srv_greet,srv_whoami,srv_wipe") throw new Fail("tools " + names);
            await Expect(src, "srv_echo", D(("text", "hi")), "{\"text\":\"hi\"}");
            using var doc = JsonDocument.Parse(await Http.GetStringAsync(b + "/log"));
            foreach (var s in doc.RootElement.GetProperty("seen").EnumerateArray().Select(e => e.GetString() ?? ""))
                if (s.StartsWith("open ") && !s.StartsWith("open 2026-07-28 "))
                    throw new Fail($"non-2026 request on the 2026 mount: \"{s}\"");
        });

        await Scenario("mrtr_input", async () =>
        {
            await using var src = await Load(open, new McpLoadOptions { WaitFor = Answers });
            await Expect(src, "srv_greet", null, "Hello, toolnexus!");
        });

        await Scenario("mrtr_confirm", async () =>
        {
            await using var src = await Load(open, new McpLoadOptions { WaitFor = Answers });
            await Expect(src, "srv_wipe", null, "wiped");
        });

        await Scenario("mrtr_pending", async () =>
        {
            await using var src = await Load(open, new McpLoadOptions());
            var r = await Run(src, "srv_greet");
            var req = ToolResult.PendingOf(r);
            if (req == null || req.Kind != "input" || req.Prompt != "What is your name?")
                throw new Fail($"not pending input: {r.Output} isError={r.IsError}");
        });

        await Scenario("skills", async () =>
        {
            await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { McpConfig = Servers(open) });
            var skill = tk.Get("skill") ?? throw new Fail("no skill tool");
            var r = await skill.ExecuteAsync(D(("name", "hello-world")));
            var body = SkillBody(skillsDir);
            if (r.IsError || !r.Output.Contains(body))
                throw new Fail("remote skill output lacks the SKILL.md body: " + r.Output[..Math.Min(200, r.Output.Length)]);
        });

        await Scenario("tool_search", async () =>
        {
            await using var src = await Load(D(("url", b + "/open/mcp"), ("toolSearch", true), ("description", "interop")),
                new McpLoadOptions { WaitFor = Answers });
            if (src.Tools.Count != 2) throw new Fail($"tool search exposes {src.Tools.Count} tools, want 2");
            var r = await src.Tools[0].ExecuteAsync(D(("query", "echo text")), new ToolContext());
            if (!r.Output.StartsWith("[{\"name\":\"echo\"")) throw new Fail("search output " + r.Output[..Math.Min(200, r.Output.Length)]);
            r = await src.Tools[1].ExecuteAsync(D(("name", "greet")), new ToolContext());
            if (r.Output != "Hello, toolnexus!") throw new Fail($"call_tool greet \"{r.Output}\"");
        });

        var authCfg = D(("url", b + "/mcp"), ("auth", D(("clientId", "interop-client"), ("allowHttp", true))));
        var store = new MemoryTokenStore();
        McpSource? authed = null;
        await Scenario("oauth_preregistered", async () =>
        {
            authed = await Load(authCfg, new McpLoadOptions { OpenUrl = Get, TokenStore = store, WaitFor = Answers });
            await Expect(authed, "srv_echo", D(("text", "authed")), "{\"text\":\"authed\"}");
        });

        await Scenario("oauth_stepup", async () =>
        {
            if (authed == null) throw new Fail("needs oauth_preregistered");
            await using var _ = authed;
            await Expect(authed, "srv_whoami", null, "dev-user");
            var tok = store.Load(b + "/mcp");
            if (!HasScope(tok, "mcp:whoami")) throw new Fail($"stored token scopes lack mcp:whoami: {tok}");
        });

        await Scenario("oauth_cimd", async () =>
        {
            var l = new TcpListener(IPAddress.Loopback, 0);
            l.Start();
            var port = ((IPEndPoint)l.LocalEndpoint).Port;
            l.Stop();
            var docUrl = $"http://127.0.0.1:{port}/client.json";
            using var hl = new HttpListener();
            hl.Prefixes.Add($"http://127.0.0.1:{port}/");
            hl.Start();
            _ = Task.Run(async () =>
            {
                while (true)
                {
                    HttpListenerContext c;
                    try { c = await hl.GetContextAsync(); } catch { break; }
                    var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new Dictionary<string, object>
                    {
                        ["client_id"] = docUrl, ["client_name"] = "interop CIMD", ["redirect_uris"] = new[] { "http://127.0.0.1/callback" },
                    }));
                    c.Response.ContentType = "application/json";
                    await c.Response.OutputStream.WriteAsync(bytes);
                    c.Response.Close();
                }
            });
            string? bad = null;
            async Task Follow(string u)
            {
                if (!u.Contains("client_id=" + Uri.EscapeDataString(docUrl))) bad = "authorize URL lacks the CIMD client_id: " + u;
                await Get(u);
            }
            await using var src = await Load(D(("url", b + "/mcp"), ("auth", D(("clientMetadataUrl", docUrl), ("allowHttp", true)))),
                new McpLoadOptions { OpenUrl = Follow });
            await Expect(src, "srv_echo", D(("text", "cimd")), "{\"text\":\"cimd\"}");
            if (bad != null) throw new Fail(bad);
        });

        await Scenario("oauth_no_identity", async () =>
        {
            await using var src = await McpSource.LoadAsync(Servers(D(("url", b + "/mcp"), ("auth", D(("allowHttp", true))))),
                new McpLoadOptions { OpenUrl = _ => Task.CompletedTask });
            if (src.Status.GetValueOrDefault("srv") != "failed") throw new Fail($"status {src.Status.GetValueOrDefault("srv")}, want failed");
        });

        await Scenario("legacy_fallback", async () =>
        {
            await using var src = await Load(D(("url", b + "/legacy/mcp")), new McpLoadOptions());
            await Expect(src, "srv_echo", D(("text", "old")), "{\"text\":\"old\"}");
        });
    }

    /// <summary>hello-world's SKILL.md without its frontmatter, trimmed.</summary>
    private static string SkillBody(string dir)
    {
        var s = File.ReadAllText(Path.Combine(dir, "hello-world", "SKILL.md")).Replace("\r\n", "\n");
        if (s.StartsWith("---\n"))
        {
            var i = s.IndexOf("\n---", 4, StringComparison.Ordinal);
            if (i >= 0) s = s[(i + 4)..];
        }
        return s.Trim();
    }

    // ---- live Keycloak (interop/mcp2026/keycloak) ----

    private const string KcClientId = "toolnexus-mcp";
    private const string KcUser = "alice";
    private const string KcPassword = "alice-test-pw"; // throwaway user of the local-only realm-toolnexus.json

    private static readonly Regex KcLoginForm = new("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
    private static readonly Regex KcAnyForm = new("<form[^>]*action=\"([^\"]+)\"");

    /// <summary>A headless browser: follow the authorize URL, submit Keycloak's login (and consent) form, then
    /// deliver the loopback callback.</summary>
    private static Func<string, Task> KcOpener()
    {
        var hc = new HttpClient(new SocketsHttpHandler { AllowAutoRedirect = false, UseCookies = false }) { Timeout = TimeSpan.FromSeconds(15) };
        // A minimal jar: Keycloak marks its session cookies Secure, which CookieContainer never sends over
        // http://localhost (a browser treats localhost as a secure context and does). One host, one realm path.
        var jar = new Dictionary<string, string>();

        // Send, then follow redirects by hand until a 200 or a redirect to the 127.0.0.1 callback.
        async Task<(HttpResponseMessage Resp, Uri Url, string Body)> Go(HttpRequestMessage req)
        {
            for (var i = 0; ; i++)
            {
                if (jar.Count > 0) req.Headers.Add("Cookie", string.Join("; ", jar.Select(kv => kv.Key + "=" + kv.Value)));
                var resp = await hc.SendAsync(req);
                var url = req.RequestUri!;
                if (resp.Headers.TryGetValues("Set-Cookie", out var sets))
                    foreach (var sc in sets)
                    {
                        var nv = sc.Split(';', 2)[0];
                        var eq = nv.IndexOf('=');
                        if (eq > 0) jar[nv[..eq].Trim()] = nv[(eq + 1)..].Trim();
                    }
                if ((int)resp.StatusCode is >= 300 and < 400 && resp.Headers.Location is { } loc && i < 10)
                {
                    var next = new Uri(url, loc);
                    if (next.Host == "127.0.0.1") return (resp, url, "");
                    resp.Dispose();
                    req = new HttpRequestMessage(HttpMethod.Get, next);
                    continue;
                }
                return (resp, url, await resp.Content.ReadAsStringAsync());
            }
        }

        return async authUrl =>
        {
            var (resp, url, body) = await Go(new HttpRequestMessage(HttpMethod.Get, authUrl));
            for (var page = 0; resp.StatusCode == HttpStatusCode.OK && page < 3; page++)
            {
                string action;
                Dictionary<string, string> form;
                if (KcLoginForm.Match(body) is { Success: true } m)
                    (action, form) = (m.Groups[1].Value, new() { ["username"] = KcUser, ["password"] = KcPassword, ["credentialId"] = "" });
                else if (KcAnyForm.Match(body) is { Success: true } m2 && body.Contains("name=\"accept\""))
                    (action, form) = (m2.Groups[1].Value, new() { ["accept"] = "Yes" });
                else break;
                var target = new Uri(url, WebUtility.HtmlDecode(action));
                resp.Dispose();
                (resp, url, body) = await Go(new HttpRequestMessage(HttpMethod.Post, target) { Content = new FormUrlEncodedContent(form) });
            }
            using (resp)
            {
                if ((int)resp.StatusCode != 302 || resp.Headers.Location == null) throw new Fail($"keycloak login: HTTP {(int)resp.StatusCode}");
                await Get(new Uri(url, resp.Headers.Location).ToString());
            }
        };
    }

    private static async Task Keycloak(string mcpUrl)
    {
        var store = new MemoryTokenStore();
        McpSource? src = null;
        await Scenario("kc_login", async () =>
        {
            src = await Load(D(("url", mcpUrl), ("auth", D(("clientId", KcClientId), ("allowHttp", true)))),
                new McpLoadOptions { OpenUrl = KcOpener(), TokenStore = store, WaitFor = Answers });
            await Expect(src, "srv_echo", D(("text", "keycloak")), "{\"text\":\"keycloak\"}");
        });
        await Scenario("kc_stepup", async () =>
        {
            if (src == null) throw new Fail("needs kc_login");
            await using var _ = src;
            var r = await Run(src, "srv_whoami");
            if (r.IsError || r.Output == "") throw new Fail($"whoami {r.Output} isError={r.IsError}");
            var tok = store.Load(mcpUrl);
            if (!HasScope(tok, "mcp:greet")) throw new Fail($"stored token scopes lack mcp:greet: {tok}");
        });
    }
}
