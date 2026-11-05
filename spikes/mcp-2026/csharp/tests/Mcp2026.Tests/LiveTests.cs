using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;

namespace Mcp2026.Tests;

/// <summary>Records every OAuth-side request (URL + form, never headers).</summary>
sealed class Recorder() : DelegatingHandler(new HttpClientHandler())
{
    public readonly List<(string Line, Dictionary<string, string> Form)> Reqs = [];
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage r, CancellationToken ct)
    {
        var form = new Dictionary<string, string>();
        if (r.Method == HttpMethod.Post && r.Content != null)
            foreach (var kv in Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(await r.Content.ReadAsStringAsync(ct)))
                form[kv.Key] = kv.Value.ToString();
        var resp = await base.SendAsync(r, ct);
        lock (Reqs) Reqs.Add(($"{r.Method} {r.RequestUri} -> {(int)resp.StatusCode}", form));
        return resp;
    }
    public List<Dictionary<string, string>> TokenForms() { lock (Reqs) return Reqs.Where(x => x.Line.Contains("/protocol/openid-connect/token")).Select(x => x.Form).ToList(); }
}

// Live tests against Keycloak (../golang/keycloak/docker-compose.yml). Gated by KEYCLOAK=1. Fixed port 7199
// (the realm's audience mapper pins aud = http://127.0.0.1:7199/mcp), so one class = sequential.
[Collection("port-7199")]
public class KeycloakTests
{
    const string Issuer = "http://localhost:8180/realms/toolnexus", McpUrl = "http://127.0.0.1:7199/mcp";
    static bool On => Environment.GetEnvironmentVariable("KEYCLOAK") == "1";

    sealed class Env : IAsyncDisposable
    {
        public HttpHost Host = null!;
        public Recorder Rec = new();
        public MemoryTokenStore Store = new();
        public Func<string, Task<Uri>> Login = HeadlessLogin.Login("alice", "alice-test-pw");
        public int Callbacks;
        public static async Task<Env> StartAsync(string audience = McpUrl, int port = 7199)
        {
            var srv = Server.Fixture().Tool("whoami", "who is calling", (ctx, _) => ctx.Principal!.Subject + " " + string.Join(",", ctx.Principal.Scopes));
            srv.Auth = new ServerAuth { Issuer = Issuer, Audience = audience, Scopes = new() { ["*"] = ["mcp:tools"], ["greet"] = ["mcp:greet"] } };
            return new Env { Host = await HttpHost.StartAsync(srv, port: port) };
        }
        public McpClient Client(Action<OAuth>? mod = null)
        {
            var o = new OAuth
            {
                Http = new HttpClient(Rec) { Timeout = TimeSpan.FromSeconds(15) }, AllowHttp = true, ClientIds = new() { [Issuer] = "toolnexus-mcp" }, Store = Store,
                Authorizer = new LoopbackAuthorizer { Open = async u => { Interlocked.Increment(ref Callbacks); using var r = await new HttpClient().GetAsync(await Login(u)); } },
            };
            mod?.Invoke(o);
            return new McpClient(McpUrl) { Auth = o };
        }
        public ValueTask DisposeAsync() => Host.DisposeAsync();
    }

    static JsonObject Claims(string jwt) => J.ParseObject(Encoding.UTF8.GetString(ServerAuth.B64UrlDecode(jwt.Split('.')[1])))!;

    [Fact]
    public async Task EndToEndAndEightSteps()
    {
        if (!On) return;
        await using var e = await Env.StartAsync();
        var r = await e.Client().CallToolAsync("whoami");
        Assert.Contains("mcp:tools", Mcp.Text(r));
        var asmd = e.Rec.Reqs.Select(x => x.Line).First(l => l.Contains("/.well-known/") && l.EndsWith("-> 200") && !l.Contains(ServerAuth.PrmPath));
        Assert.Contains("/.well-known/oauth-authorization-server/realms/toolnexus", asmd); // RFC 8414 path-insert
        var tf = e.Rec.TokenForms();
        Assert.Single(tf);
        Assert.Equal(McpUrl, tf[0]["resource"]);
        Assert.NotEqual("", tf[0]["code_verifier"]);
        var c = Claims(e.Store.Load(McpUrl)!.AccessToken);
        Console.WriteLine($"aud={c["aud"]?.ToJsonString()} scope={c["scope"]} azp={c["azp"]}");
        Assert.True(await Checks.RunAsync(McpUrl, () => e.Client()));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Refresh(bool omit)
    {
        if (!On) return;
        await using var e = await Env.StartAsync();
        var c = e.Client(o => o.OmitRefreshResource = omit);
        await c.CallToolAsync("echo", new JsonObject { ["text"] = "a" });
        var old = e.Store.Load(McpUrl)!;
        e.Store.Save(McpUrl, old with { Expiry = DateTimeOffset.UtcNow.AddMinutes(-1) });
        Assert.Equal("b", Mcp.Text(await c.CallToolAsync("echo", new JsonObject { ["text"] = "b" })));
        var last = e.Rec.TokenForms()[^1];
        Assert.Equal("refresh_token", last["grant_type"]);
        Assert.Equal(!omit, last.ContainsKey("resource"));
        Assert.NotEqual(old.AccessToken, e.Store.Load(McpUrl)!.AccessToken);
        Assert.Equal(1, e.Callbacks);
    }

    [Fact]
    public async Task StepUp()
    {
        if (!On) return;
        await using var e = await Env.StartAsync();
        var c = e.Client();
        await c.CallToolAsync("echo", new JsonObject { ["text"] = "a" });
        var r = await c.CallToolAsync("greet", [], (_, _) => Task.FromResult(new JsonObject { ["action"] = "accept", ["content"] = new JsonObject { ["name"] = "Alice" } }));
        Assert.Equal("Hello, Alice!", Mcp.Text(r));
        Assert.Equal(2, e.Callbacks);
        Assert.Contains("mcp:greet", e.Store.Load(McpUrl)!.Scopes);
    }

    [Fact]
    public async Task PauseResume()
    {
        if (!On) return;
        await using var e = await Env.StartAsync();
        var c = e.Client(o => o.Authorizer = new PauseAuthorizer("http://127.0.0.1:9/callback"));
        var p = await AuthEnv.PendingOf(() => c.CallToolAsync("echo", new JsonObject { ["text"] = "after login" }));
        var cb = await e.Login(p.Url);
        Assert.Equal("after login", Mcp.Text(await c.Auth!.ResumeAsync(p.Id, cb.ToString())));
    }

    [Fact]
    public async Task WrongAudienceRejected()
    {
        if (!On) return;
        await using var e = await Env.StartAsync();
        await e.Client().CallToolAsync("echo", new JsonObject { ["text"] = "a" });
        var tok = e.Store.Load(McpUrl)!.AccessToken;
        await using var other = await Env.StartAsync("http://127.0.0.1:7200/mcp", 7200); // aud not in the token
        async Task<HttpResponseMessage> Call(string u) =>
            await new McpClient(u).PostAsync("tools/list", [], $$$$"""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"{{{{Mcp.MetaVersion}}}}":"{{{{Mcp.ProtocolVersion}}}}"}}}""", tok, default);
        var bad = await Call("http://127.0.0.1:7200/mcp");
        Assert.Equal(401, (int)bad.StatusCode);
        Assert.Contains("error=\"invalid_token\"", bad.Headers.WwwAuthenticate.ToString());
        Assert.Equal(200, (int)(await Call(McpUrl)).StatusCode);
    }
}

// Live interop with GitHub's hosted MCP server. Gated: GITHUB_OAUTH=1 under
// `sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET`; the consent page opens in chrome-agent.
public class GitHubTests
{
    [Fact]
    public async Task PauseResumeAndForcedRefreshLive()
    {
        if (Environment.GetEnvironmentVariable("GITHUB_OAUTH") != "1") return;
        var cb = new TaskCompletionSource<string>();
        await using var host = await HttpHost.StartAsync(async http =>
        {
            cb.TrySetResult($"http://{http.Request.Host}{http.Request.Path}{http.Request.QueryString}");
            await http.Response.WriteAsync("ok");
        });
        var store = new MemoryTokenStore();
        const string gh = "https://api.githubcopilot.com/mcp/";
        var c = new McpClient(gh)
        {
            Auth = new OAuth
            {
                ClientId = Environment.GetEnvironmentVariable("GITHUB_MCP_CLIENT_ID")!, ClientSecret = Environment.GetEnvironmentVariable("GITHUB_MCP_CLIENT_SECRET")!,
                Store = store, Authorizer = new PauseAuthorizer(host.Url + "/callback"),
            },
        };
        var p = await AuthEnv.PendingOf(() => c.CallToolAsync("get_me", []));
        await Process.Start(new ProcessStartInfo("chrome-agent", ["goto", p.Url]) { RedirectStandardOutput = true })!.WaitForExitAsync();
        var url = await cb.Task.WaitAsync(TimeSpan.FromSeconds(120));
        var r = await c.Auth.ResumeAsync(p.Id, url);
        Assert.Equal("complete", J.Str(r["resultType"]));
        var tok = store.Load(gh)!;
        Assert.NotEqual("", tok.RefreshToken); // OAuth App needs expiring user tokens enabled
        store.Save(gh, tok with { Expiry = DateTimeOffset.UtcNow.AddMinutes(-1) });
        await c.CallAsync("tools/list");
        Assert.NotEqual(tok.AccessToken, store.Load(gh)!.AccessToken); // compared, never printed
    }
}
