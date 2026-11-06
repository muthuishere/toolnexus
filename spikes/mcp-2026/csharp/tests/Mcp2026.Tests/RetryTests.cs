using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;

namespace Mcp2026.Tests;

// Side effects run exactly once across auth pauses, refresh-after-401 and step-up; a lost or failed
// response is never retried (spec issue #3394).
public class RetryTests
{
    sealed class Counter { public int N; }

    /// <summary>Registers "tick" (base scope) and "charge" (needs mcp:charge), each counting executions.</summary>
    static Action<Server> Counted(Counter tick, Counter charge) => s =>
    {
        s.Tool("tick", "counts", (_, _) => { Interlocked.Increment(ref tick.N); return "tick"; });
        s.Tool("charge", "counts, extra scope", (_, _) => { Interlocked.Increment(ref charge.N); return "charged"; });
        if (s.Auth != null) s.Auth.Scopes["charge"] = ["mcp:charge"];
    };

    static void Expire(McpClient c, string url) =>
        c.Auth!.TokenStore.Save(url, c.Auth.TokenStore.Load(url)! with { Expiry = DateTimeOffset.UtcNow.AddMinutes(-1) });

    [Fact]
    public async Task PauseResumeExecutesOnce()
    {
        Counter tick = new(), charge = new();
        await using var e = await AuthEnv.StartAsync(Counted(tick, charge));
        var c = e.PauseClient();
        var p = await AuthEnv.PendingOf(() => c.CallToolAsync("tick"));
        Assert.Equal(0, tick.N);
        var cb = await AuthEnv.Visit(p.Url);
        Assert.Equal("tick", Mcp.Text(await c.Auth!.ResumeAsync(p.Id, cb)));
        await Assert.ThrowsAsync<McpException>(() => c.Auth.ResumeAsync(p.Id, cb));
        Assert.Equal(1, tick.N);
    }

    [Fact]
    public async Task RefreshAfter401ExecutesOnce()
    {
        Counter tick = new(), charge = new();
        await using var e = await AuthEnv.StartAsync(Counted(tick, charge));
        var c = e.AutoClient();
        await c.CallToolAsync("tick");
        // not expired client-side: the server's 401 invalid_token drives the refresh
        c.Auth!.TokenStore.Save(e.Url, c.Auth.TokenStore.Load(e.Url)! with { AccessToken = "revoked-by-server" });
        Assert.Equal("tick", Mcp.Text(await c.CallToolAsync("tick")));
        Assert.Equal(2, tick.N);
        Assert.Equal((1, 1), e.As.Stats());
    }

    [Fact]
    public async Task StepUpExecutesOnce()
    {
        Counter tick = new(), charge = new();
        await using var e = await AuthEnv.StartAsync(Counted(tick, charge));
        // 401 (base scope) -> login -> 403 insufficient_scope -> step-up login -> run.
        Assert.Equal("charged", Mcp.Text(await e.AutoClient().CallToolAsync("charge")));
        Assert.Equal(1, charge.N);
        Assert.Equal(2, e.As.Stats().Authorizations);
    }

    /// <summary>A pause in a later MRTR round resumes THAT round: the user is not re-asked, round 0 is not replayed.</summary>
    [Fact]
    public async Task PauseMidMrtrResumesSameRound()
    {
        await using var e = await AuthEnv.StartAsync(s => s.Auth!.Scopes.Remove("greet"));
        var c = e.PauseClient();
        var p = await AuthEnv.PendingOf(() => c.CallToolAsync("echo", new JsonObject { ["text"] = "login" }));
        await c.Auth!.ResumeAsync(p.Id, await AuthEnv.Visit(p.Url));
        var asked = 0;
        p = await AuthEnv.PendingOf(() => c.CallToolAsync("greet", [], (_, _) =>
        {
            asked++;
            c.Auth.TokenStore.Delete(e.Url); // token lost between rounds -> round 1 gets 401
            return Task.FromResult(new JsonObject { ["action"] = "accept", ["content"] = new JsonObject { ["name"] = "once" } });
        }));
        var r = await c.Auth.ResumeAsync(p.Id, await AuthEnv.Visit(p.Url));
        Assert.Equal("Hello, once!", Mcp.Text(r));
        Assert.Equal(1, asked);
    }

    [Theory]
    [InlineData("connection dropped")]
    [InlineData("502 after execution")]
    public async Task LostOrFailedResponseNotRetried(string mode)
    {
        Counter tick = new(), charge = new();
        var s = Server.Fixture();
        Counted(tick, charge)(s);
        await using var ts = await HttpHost.StartAsync(async http =>
        {
            // the tool runs; its response is lost
            var shadow = new DefaultHttpContext();
            shadow.Request.Method = "POST";
            shadow.Request.Path = http.Request.Path;
            foreach (var h in http.Request.Headers) shadow.Request.Headers[h.Key] = h.Value;
            var body = new MemoryStream();
            await http.Request.Body.CopyToAsync(body);
            body.Position = 0;
            shadow.Request.Body = body;
            shadow.Response.Body = new MemoryStream();
            await s.HandleAsync(shadow);
            if (mode == "502 after execution") { http.Response.StatusCode = 502; await http.Response.WriteAsync("bad gateway"); return; }
            http.Abort();
        });
        var c = new McpClient(ts.Url + "/mcp");
        await Assert.ThrowsAsync<McpException>(() => c.CallToolAsync("tick"));
        await Task.Delay(50); // a hidden retry would land here
        Assert.Equal(1, tick.N);
    }

    [Fact]
    public async Task RefreshResourceConfigurable()
    {
        foreach (var omit in new[] { false, true })
        {
            await using var e = await AuthEnv.StartAsync();
            e.As.RejectRefreshResource = true; // Entra-like
            var c = e.AutoClient();
            c.Auth!.OmitRefreshResource = omit;
            await c.CallToolAsync("echo", new JsonObject { ["text"] = "1" });
            Assert.Equal(e.Url, e.As.LastAuthorizeQuery["resource"]);
            Assert.Equal(e.Url, e.As.LastTokenForm["resource"]); // code exchange always sends it
            Expire(c, e.Url);
            Assert.Equal("2", Mcp.Text(await c.CallToolAsync("echo", new JsonObject { ["text"] = "2" })));
            var (a, rf) = e.As.Stats();
            if (omit) Assert.True(rf == 1 && a == 1 && !e.As.LastTokenForm.ContainsKey("resource"), $"omit: {rf}/{a}");
            else Assert.True(rf == 0 && a == 2, $"default: {rf}/{a}"); // Entra rejects -> falls back to a new login
        }
        await using var e2 = await AuthEnv.StartAsync(); // default AS accepts resource on refresh and it is sent
        var c2 = e2.AutoClient();
        await c2.CallToolAsync("echo", new JsonObject { ["text"] = "1" });
        Expire(c2, e2.Url);
        await c2.CallToolAsync("echo", new JsonObject { ["text"] = "2" });
        Assert.Equal("refresh_token", e2.As.LastTokenForm["grant_type"]);
        Assert.Equal(e2.Url, e2.As.LastTokenForm["resource"]);
    }

    [Theory]
    [InlineData("")]
    [InlineData("client_secret_basic")]
    [InlineData("client_secret_post")]
    public async Task PreRegisteredConfidentialClient(string method)
    {
        await using var e = await AuthEnv.StartAsync();
        e.As.NoCimd = true;
        e.As.Clients["conf"] = ["http://127.0.0.1/callback"];
        e.As.Secrets = new() { ["conf"] = "test-only-secret" };
        var c = new McpClient(e.Url)
        {
            Auth = new OAuth { ClientId = "conf", ClientSecret = "test-only-secret", TokenAuthMethod = method, Authorizer = new LoopbackAuthorizer { Open = e.As.ApproveAsync } },
        };
        Assert.Equal("ok", Mcp.Text(await c.CallToolAsync("echo", new JsonObject { ["text"] = "ok" })));
        Assert.Equal(method == "client_secret_post", e.As.LastTokenForm.ContainsKey("client_secret"));
        c.Auth.ClientSecret = "wrong";
        Expire(c, e.Url);
        c.Auth.Authorizer = new PauseAuthorizer("http://127.0.0.1:9/callback");
        // a wrong secret must fail the refresh and fall back to a new authorization
        await AuthEnv.PendingOf(() => c.CallToolAsync("echo", new JsonObject { ["text"] = "x" }));
    }

    [Fact]
    public async Task NoCimdAndNoClientIdIsAClearError()
    {
        await using var e = await AuthEnv.StartAsync();
        e.As.NoCimd = true;
        var ex = await Assert.ThrowsAsync<McpException>(() => e.AutoClient().CallToolAsync("echo", new JsonObject { ["text"] = "x" })); // has a CIMD URL, AS can't use it
        Assert.Contains("does not support Client ID Metadata Documents", ex.Message);
        Assert.Contains("register an app with " + e.As.Issuer + " and set OAuth.ClientId", ex.Message);
        Assert.Equal(0, e.As.Stats().Authorizations);
    }
}
