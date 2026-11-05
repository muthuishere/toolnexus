using Microsoft.AspNetCore.Http;
using System.Text.Json.Nodes;

namespace Mcp2026.Tests;

/// <summary>A dev AS + a protected TLS MCP server (fixture + whoami) + a CIMD document host.</summary>
public sealed class AuthEnv : IAsyncDisposable
{
    public DevAuthServer As = null!;
    public Server Server = null!;
    public HttpHost Mcp = null!, Cimd = null!;
    public string Url = "", CimdUrl = "";

    public static async Task<AuthEnv> StartAsync(params Action<Server>[] cfg)
    {
        var e = new AuthEnv { As = await DevAuthServer.StartAsync() };
        e.Cimd = await HttpHost.StartAsync(async http =>
        {
            await http.Response.WriteAsync(J.Serialize(new JsonObject
            {
                ["client_id"] = e.CimdUrl, ["client_name"] = "toolnexus spike", ["redirect_uris"] = J.Array(["http://127.0.0.1/callback"]),
                ["grant_types"] = J.Array(["authorization_code", "refresh_token"]), ["response_types"] = J.Array(["code"]), ["token_endpoint_auth_method"] = "none",
            }));
        }, tls: true);
        e.CimdUrl = e.Cimd.Url + "/client.json";
        e.Server = Mcp2026.Server.Fixture();
        e.Server.Auth = new ServerAuth { Issuer = e.As.Issuer, Scopes = new() { ["*"] = ["mcp:read"], ["greet"] = ["mcp:greet"] } };
        e.Server.Tool("whoami", "who is calling", (ctx, _) =>
            ctx.Principal is { } p ? p.Subject + " " + string.Join(",", p.Scopes) : throw new Exception("no principal"));
        foreach (var f in cfg) f(e.Server);
        e.Mcp = await HttpHost.StartAsync(e.Server, tls: true);
        e.Url = e.Mcp.Url + "/mcp";
        return e;
    }

    public McpClient AutoClient() => new(Url)
    {
        Auth = new OAuth { ClientMetadataUrl = CimdUrl, Authorizer = new LoopbackAuthorizer { Open = As.ApproveAsync } },
    };

    public McpClient PauseClient()
    {
        As.Clients["spike-prereg"] = ["http://127.0.0.1:9/callback"];
        return new McpClient(Url)
        {
            Auth = new OAuth { ClientIds = new() { [As.Issuer] = "spike-prereg" }, Authorizer = new PauseAuthorizer("http://127.0.0.1:9/callback") },
        };
    }

    public static Task<string> Visit(string url) => DevAuthServer.CaptureRedirectAsync(url);

    public static async Task<Pending> PendingOf(Func<Task> f)
    {
        try { await f(); }
        catch (PendingAuthorizationException e) { return e.Pending; }
        throw new Xunit.Sdk.XunitException("want PendingAuthorizationException");
    }

    public string Claims(Action<JsonObject>? mut = null)
    {
        var c = new JsonObject { ["iss"] = As.Issuer, ["sub"] = "alice", ["aud"] = Url, ["scope"] = "mcp:read", ["exp"] = DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeSeconds() };
        mut?.Invoke(c);
        return As.Mint(c);
    }

    /// <summary>A raw tools/call with an optional bearer: (status, WWW-Authenticate, body).</summary>
    public async Task<(int, string, string)> RawCall(string token, string tool)
    {
        var req = new HttpRequestMessage(HttpMethod.Post, Url)
        {
            Content = new StringContent($$$$"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"{{{{tool}}}}","arguments":{"text":"x"},"_meta":{"{{{{Mcp2026.Mcp.MetaVersion}}}}":"{{{{Mcp2026.Mcp.ProtocolVersion}}}}"}}}""",
                System.Text.Encoding.UTF8, "application/json"),
        };
        req.Headers.Add("MCP-Protocol-Version", Mcp2026.Mcp.ProtocolVersion);
        req.Headers.Add("Mcp-Method", "tools/call");
        req.Headers.Add("Mcp-Name", tool);
        if (token != "") req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
        using var resp = await DevTrust.Shared.SendAsync(req);
        return ((int)resp.StatusCode, resp.Headers.WwwAuthenticate.ToString(), await resp.Content.ReadAsStringAsync());
    }

    public async ValueTask DisposeAsync()
    {
        await Mcp.DisposeAsync();
        await Cimd.DisposeAsync();
        await As.DisposeAsync();
    }
}

public class AuthTests
{
    static JsonObject T(string text) => new() { ["text"] = text };

    [Fact]
    public async Task ExistingStepsUnprotected()
    {
        await using var h = await HttpHost.StartAsync(Server.Fixture());
        Assert.True(await Checks.RunAsync(h.Url + "/mcp"));
    }

    [Fact]
    public async Task ExistingStepsProtectedAutoMode()
    {
        await using var e = await AuthEnv.StartAsync();
        Assert.True(await Checks.RunAsync(e.Url, e.AutoClient));
    }

    [Fact]
    public async Task PauseResume()
    {
        await using var e = await AuthEnv.StartAsync();
        var c = e.PauseClient();
        var p = await AuthEnv.PendingOf(() => c.CallToolAsync("echo", T("after login")));
        Assert.True(p.Kind == "authorization" && p.Id != "" && p.Url.StartsWith(e.As.Issuer + "/authorize?") && p.Prompt != "", p.ToString());
        var cb = await AuthEnv.Visit(p.Url);
        var q = e.As.LastAuthorizeQuery;
        Assert.Equal(e.Url, q["resource"]);
        Assert.Equal("S256", q["code_challenge_method"]);
        Assert.Equal("mcp:read", q["scope"]);
        Assert.Equal("after login", Mcp.Text(await c.Auth!.ResumeAsync(p.Id, cb)));
        await Assert.ThrowsAsync<McpException>(() => c.Auth.ResumeAsync(p.Id, cb));
        Assert.Equal("again", Mcp.Text(await c.CallToolAsync("echo", T("again"))));
        Assert.Equal(1, e.As.Stats().Authorizations);
    }

    [Fact]
    public async Task AutoModeWhoamiPrincipal()
    {
        await using var e = await AuthEnv.StartAsync();
        Assert.Equal("dev-user mcp:read", Mcp.Text(await e.AutoClient().CallToolAsync("whoami")));
    }

    [Fact]
    public async Task IssMismatchRejected()
    {
        await using var e = await AuthEnv.StartAsync();
        e.As.IssOverride = "https://evil.example";
        var c = e.AutoClient();
        var ex = await Assert.ThrowsAsync<McpException>(() => c.CallToolAsync("echo", T("x")));
        Assert.Contains("iss mismatch", ex.Message);
        Assert.Null(c.Auth!.TokenStore.Load(e.Url));
    }

    [Fact]
    public async Task StateMismatchRejected()
    {
        await using var e = await AuthEnv.StartAsync();
        var c = e.PauseClient();
        var p = await AuthEnv.PendingOf(() => c.CallToolAsync("echo", T("x")));
        var cb = await AuthEnv.Visit(p.Url);
        var i = cb.IndexOf("state=");
        cb = cb[..i] + "state=forged&" + cb[(i + 6)..];
        var ex = await Assert.ThrowsAsync<McpException>(() => c.Auth!.ResumeAsync(p.Id, cb));
        Assert.Contains("state mismatch", ex.Message);
    }

    [Fact]
    public async Task PkceVerifiedByAs()
    {
        await using var e = await AuthEnv.StartAsync();
        var c = e.PauseClient();
        var p = await AuthEnv.PendingOf(() => c.CallToolAsync("echo", T("x")));
        var cb = await AuthEnv.Visit(p.Url);
        c.Auth!.Flows[p.Id].Verifier = "not-the-verifier"; // the AS must reject the exchange
        var ex = await Assert.ThrowsAsync<McpException>(() => c.Auth.ResumeAsync(p.Id, cb));
        Assert.Contains("invalid_grant", ex.Message);
    }

    [Fact]
    public async Task RefreshOnExpiry()
    {
        await using var e = await AuthEnv.StartAsync();
        var c = e.AutoClient();
        await c.CallToolAsync("echo", T("1"));
        var tok = c.Auth!.TokenStore.Load(e.Url)!;
        c.Auth.TokenStore.Save(e.Url, tok with { Expiry = DateTimeOffset.UtcNow.AddMinutes(-1) });
        Assert.Equal("2", Mcp.Text(await c.CallToolAsync("echo", T("2"))));
        Assert.Equal((1, 1), e.As.Stats());
        Assert.NotEqual(tok.AccessToken, c.Auth.TokenStore.Load(e.Url)!.AccessToken);
    }

    [Fact]
    public async Task InsufficientScopeStepUp()
    {
        await using var e = await AuthEnv.StartAsync();
        var c = e.AutoClient();
        await c.CallToolAsync("echo", T("1"));
        var r = await c.CallToolAsync("greet", [], (_, _) => Task.FromResult(new JsonObject { ["action"] = "accept", ["content"] = new JsonObject { ["name"] = "scoped" } }));
        Assert.Equal("Hello, scoped!", Mcp.Text(r));
        var sc = e.As.LastAuthorizeQuery["scope"].Split(' ');
        Assert.Equal(2, e.As.Stats().Authorizations);
        Assert.Equal(2, sc.Length);
        Assert.Contains("mcp:read", sc);
        await c.CallToolAsync("echo", T("still"));
        Assert.Equal(2, e.As.Stats().Authorizations);
    }

    [Fact]
    public async Task PrmWellKnownFallbackAndNoClientIdentity()
    {
        await using var e = await AuthEnv.StartAsync();
        var m = await new OAuth().FetchPrmAsync(e.Url, "", default);
        Assert.Equal(e.Url, J.Str(m["resource"]));
        var c = new McpClient(e.Url) { Auth = new OAuth { Authorizer = new PauseAuthorizer("http://127.0.0.1:9/callback") } };
        var ex = await Assert.ThrowsAsync<McpException>(() => c.CallAsync("tools/list"));
        Assert.Contains("register an app with " + e.As.Issuer, ex.Message);
    }

    // --- server side ---

    [Fact]
    public async Task ServerJwtViaJwksAndPrincipal()
    {
        await using var e = await AuthEnv.StartAsync();
        var (st, _, body) = await e.RawCall(e.Claims(), "whoami");
        Assert.Equal(200, st);
        Assert.Contains("alice mcp:read", body);
    }

    [Fact]
    public async Task ServerRejects()
    {
        await using var e = await AuthEnv.StartAsync();
        var cases = new Dictionary<string, string>
        {
            ["missing"] = "",
            ["wrong audience"] = e.Claims(c => c["aud"] = "https://other.example/mcp"),
            ["expired"] = e.Claims(c => c["exp"] = DateTimeOffset.UtcNow.AddMinutes(-1).ToUnixTimeSeconds()),
            ["wrong issuer"] = e.Claims(c => c["iss"] = "https://evil.example"),
            ["tampered"] = e.Claims() + "x",
        };
        foreach (var (name, tok) in cases)
        {
            var (st, www, _) = await e.RawCall(tok, "echo");
            Assert.True(st == 401 && www.Contains($"resource_metadata=\"{e.Mcp.Url}/.well-known/oauth-protected-resource/mcp"), $"{name}: {st} {www}");
            if (tok != "") Assert.True(www.Contains("error=\"invalid_token\""), $"{name}: {www}");
        }
    }

    [Fact]
    public async Task ServerPerToolScope403()
    {
        await using var e = await AuthEnv.StartAsync();
        var (st, www, _) = await e.RawCall(e.Claims(), "greet");
        Assert.Equal(403, st);
        Assert.Contains("error=\"insufficient_scope\"", www);
        Assert.Contains("scope=\"mcp:read mcp:greet\"", www);
        (st, _, _) = await e.RawCall(e.Claims(c => c["scope"] = "mcp:read mcp:greet"), "greet");
        Assert.Equal(200, st);
    }

    [Fact]
    public async Task ServerCustomVerify()
    {
        await using var e = await AuthEnv.StartAsync();
        e.Server.Auth!.Verify = (tok, _) => tok == "opaque-ok" ? Task.FromResult(new Principal("svc", ["mcp:read"])) : throw new Exception("nope");
        var (st, _, body) = await e.RawCall("opaque-ok", "whoami");
        Assert.Equal(200, st);
        Assert.Contains("svc mcp:read", body);
        (st, _, _) = await e.RawCall(e.Claims(), "whoami");
        Assert.Equal(401, st); // a JWT must not bypass custom Verify
    }

    [Fact]
    public async Task LoopbackOpenerErrorSurfaces()
    {
        await using var e = await AuthEnv.StartAsync();
        var c = e.AutoClient();
        c.Auth!.Authorizer = new LoopbackAuthorizer { Open = _ => throw new Exception("no browser") };
        var call = c.CallToolAsync("echo", T("x"));
        Assert.Same(call, await Task.WhenAny(call, Task.Delay(5000))); // not parked for the 5-minute callback timeout
        var ex = await Assert.ThrowsAsync<McpException>(() => call);
        Assert.Contains("no browser", ex.Message);
    }

    [Fact]
    public void TokenToStringIsRedacted() =>
        Assert.Equal("Token{issuer=https://x, redacted}", new Token { AccessToken = "secret-value", Issuer = "https://x" }.ToString());

    [Fact]
    public void ParseChallengeQuotedAndBare()
    {
        var m = OAuth.ParseChallenge("Bearer resource_metadata=\"https://a/b\", scope=\"x y\", error=insufficient_scope");
        Assert.Equal("https://a/b", m["resource_metadata"]);
        Assert.Equal("x y", m["scope"]);
        Assert.Equal("insufficient_scope", m["error"]);
    }
}
