using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.WebUtilities;

namespace Mcp2026Fixture;

/// <summary>
/// DEV/TEST ONLY. NEVER USE IN PRODUCTION. A one-line OAuth 2.1 authorization server that auto-approves every
/// request as "dev-user" (no login, no consent). It exists so local dev and tests exercise the real MCP auth flow:
/// RFC 8414 metadata, JWKS, auth code + PKCE S256, resource (RFC 8707), iss (RFC 9207), refresh-token rotation,
/// pre-registered + CIMD clients. Its TLS certificate is trusted by default clients in this process.
/// <code>await using var auth = await DevAuthServer.StartAsync();   // https://127.0.0.1:&lt;port&gt;/dev</code>
/// </summary>
public sealed class DevAuthServer : IAsyncDisposable
{
    public string Issuer { get; private set; } = "";
    /// <summary>Pre-registered client_id -> redirect URIs.</summary>
    public Dictionary<string, string[]> Clients { get; } = [];
    /// <summary>Confidential clients: client_id -> secret (basic or post).</summary>
    public Dictionary<string, string> Secrets { get; set; } = [];
    public TimeSpan AccessTtl { get; set; } = TimeSpan.FromHours(1);
    /// <summary>Fetches CIMD documents.</summary>
    public HttpClient FetchHttp { get; set; } = DevTrust.Shared;
    // Test knobs: misbehave on purpose.
    public string IssOverride { get; set; } = "";
    public bool NoCimd { get; set; }
    /// <summary>Omit S256 from code_challenge_methods_supported.</summary>
    public bool NoS256 { get; set; }
    /// <summary>Answer the token endpoint form-encoded (GitHub without Accept).</summary>
    public bool FormTokens { get; set; }
    /// <summary>Entra-like: invalid_target if a refresh_token grant carries resource.</summary>
    public bool RejectRefreshResource { get; set; }
    // Read after a test (under the lock via Stats()).
    public int Authorizations { get; private set; }
    public int Refreshes { get; private set; }
    public Dictionary<string, string> LastAuthorizeQuery { get; private set; } = [];
    public Dictionary<string, string> LastTokenForm { get; private set; } = [];

    private HttpHost _host = null!;
    private readonly RSA _key = RSA.Create(2048);
    private readonly object _mu = new();
    private readonly Dictionary<string, Code> _codes = [];
    private readonly Dictionary<string, Code> _rts = [];
    private sealed record Code(string ClientId, string Redirect, string Challenge, string Resource, string Scope);

    public static async Task<DevAuthServer> StartAsync()
    {
        var d = new DevAuthServer();
        d._host = await HttpHost.StartAsync(d.HandleAsync); // plain http on loopback: clients set auth.allowHttp
        d.Issuer = d._host.Url + "/dev";
        return d;
    }

    public (int Authorizations, int Refreshes) Stats() { lock (_mu) return (Authorizations, Refreshes); }

    public ValueTask DisposeAsync() => _host.DisposeAsync();

    /// <summary>A headless "browser" for dev/tests: visits an authorization URL and follows every redirect back to
    /// the callback (use as <c>new LoopbackAuthorizer { Open = auth.ApproveAsync }</c>).</summary>
    public async Task ApproveAsync(string url) => await BrowseAsync(url);

    /// <summary>GET url following redirects by hand (HttpClient refuses https -> http hops, which every loopback
    /// callback is). Returns the final status and body.</summary>
    public static async Task<(int Status, string Body)> BrowseAsync(string url)
    {
        using var hc = new HttpClient(DevTrust.Handler(followRedirects: false)) { Timeout = TimeSpan.FromSeconds(30) };
        var u = new Uri(url);
        for (var i = 0; i < 10; i++)
        {
            using var resp = await hc.GetAsync(u);
            if ((int)resp.StatusCode is >= 300 and < 400 && resp.Headers.Location is { } loc) { u = new Uri(u, loc); continue; }
            return ((int)resp.StatusCode, await resp.Content.ReadAsStringAsync());
        }
        throw new McpException("too many redirects");
    }

    /// <summary>Opens an authorization URL and returns where the AS redirected (without following it).</summary>
    public static async Task<string> CaptureRedirectAsync(string url)
    {
        using var hc = new HttpClient(DevTrust.Handler(followRedirects: false));
        using var resp = await hc.GetAsync(url);
        return resp.Headers.Location?.ToString() ?? throw new McpException($"authorize: HTTP {(int)resp.StatusCode}");
    }

    /// <summary>Signs an access token directly (tests: forge wrong audience / expired tokens).</summary>
    public string Mint(JsonObject claims)
    {
        var input = AuthUtil.B64Url(Encoding.UTF8.GetBytes("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"dev\"}")) + "." + AuthUtil.B64Url(Encoding.UTF8.GetBytes(J.Serialize(claims)));
        return input + "." + AuthUtil.B64Url(_key.SignData(Encoding.ASCII.GetBytes(input), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
    }

    private static async Task Js(HttpContext http, int status, JsonObject v)
    {
        http.Response.StatusCode = status;
        http.Response.ContentType = "application/json";
        await http.Response.WriteAsync(J.Serialize(v));
    }

    private async Task HandleAsync(HttpContext http)
    {
        switch (http.Request.Path.Value)
        {
            case "/.well-known/oauth-authorization-server/dev":
                await Js(http, 200, new JsonObject
                {
                    ["issuer"] = Issuer, ["authorization_endpoint"] = Issuer + "/authorize", ["token_endpoint"] = Issuer + "/token",
                    ["jwks_uri"] = Issuer + "/jwks", ["response_types_supported"] = J.Array(["code"]),
                    ["grant_types_supported"] = J.Array(["authorization_code", "refresh_token"]),
                    ["code_challenge_methods_supported"] = J.Array(NoS256 ? ["plain"] : ["S256"]),
                    ["token_endpoint_auth_methods_supported"] = J.Array(["none", "client_secret_basic", "client_secret_post"]),
                    ["authorization_response_iss_parameter_supported"] = true, ["client_id_metadata_document_supported"] = !NoCimd,
                });
                break;
            case "/dev/jwks":
                var p = _key.ExportParameters(false);
                await Js(http, 200, new JsonObject
                {
                    ["keys"] = new JsonArray(new JsonObject { ["kty"] = "RSA", ["kid"] = "dev", ["alg"] = "RS256", ["use"] = "sig", ["n"] = AuthUtil.B64Url(p.Modulus!), ["e"] = AuthUtil.B64Url(p.Exponent!) }),
                });
                break;
            case "/dev/authorize": await AuthorizeAsync(http); break;
            case "/dev/token": await TokenAsync(http); break;
            default: http.Response.StatusCode = 404; break;
        }
    }

    private static string Query(IEnumerable<(string, string)> kv) => string.Join('&', kv.Select(x => Uri.EscapeDataString(x.Item1) + "=" + Uri.EscapeDataString(x.Item2)));

    private async Task AuthorizeAsync(HttpContext http)
    {
        var q = http.Request.Query.ToDictionary(kv => kv.Key, kv => kv.Value.ToString());
        string G(string k) => q.GetValueOrDefault(k, "");
        lock (_mu) { Authorizations++; LastAuthorizeQuery = q; }
        var (cid, redirect) = (G("client_id"), G("redirect_uri"));
        if (!await RedirectAllowedAsync(cid, redirect))
        {
            http.Response.StatusCode = 400; // never redirect to an unverified URI
            await http.Response.WriteAsync("invalid_client or redirect_uri");
            return;
        }
        void Fail(string e) => http.Response.Redirect(redirect + "?" + Query([("error", e), ("iss", Issuer), ("state", G("state"))]));
        if (G("response_type") != "code" || G("code_challenge_method") != "S256" || G("code_challenge") == "") { Fail("invalid_request"); return; }
        if (G("resource") == "") { Fail("invalid_target"); return; }
        var code = AuthUtil.RandStr(16);
        lock (_mu) _codes[code] = new Code(cid, redirect, G("code_challenge"), G("resource"), G("scope"));
        http.Response.Redirect(redirect + "?" + Query([("code", code), ("iss", IssOverride != "" ? IssOverride : Issuer), ("state", G("state"))]));
    }

    /// <summary>Exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3).</summary>
    private async Task<bool> RedirectAllowedAsync(string cid, string redirect)
    {
        IEnumerable<string> allowed = Clients.GetValueOrDefault(cid) ?? [];
        if (cid.StartsWith("https://") || cid.StartsWith("http://127.0.0.1")) // Client ID Metadata Document (loopback http in tests)
        {
            try
            {
                var doc = await AuthUtil.GetJsonAsync(FetchHttp, cid, default);
                if (J.Str(doc["client_id"]) != cid || J.Str(doc["client_name"], "") == "") return false;
                allowed = J.Strings(doc["redirect_uris"]).ToList();
            }
            catch (Exception) { return false; }
        }
        if (!Uri.TryCreate(redirect, UriKind.Absolute, out var ru)) return false;
        foreach (var a in allowed)
        {
            if (!Uri.TryCreate(a, UriKind.Absolute, out var au)) continue;
            if (a == redirect) return true;
            if (IPAddress.TryParse(ru.Host, out var ip) && IPAddress.IsLoopback(ip) && au.Host == ru.Host && au.Scheme == ru.Scheme && au.AbsolutePath == ru.AbsolutePath)
                return true;
        }
        return false;
    }

    private async Task TokenAsync(HttpContext http)
    {
        var form = (await http.Request.ReadFormAsync()).ToDictionary(kv => kv.Key, kv => kv.Value.ToString());
        string F(string k) => form.GetValueOrDefault(k, "");
        JsonObject? reply;
        var status = 400;
        lock (_mu)
        {
            LastTokenForm = form;
            reply = TokenLocked(http.Request.Headers.Authorization.ToString(), form, F, ref status);
        }
        if (FormTokens && status == 200)
        {
            http.Response.ContentType = "application/x-www-form-urlencoded";
            await http.Response.WriteAsync(Query(reply.Select(kv => (kv.Key, kv.Value is JsonValue v ? v.ToString() : J.Serialize(kv.Value)))));
            return;
        }
        await Js(http, status, reply);
    }

    private JsonObject TokenLocked(string authz, Dictionary<string, string> form, Func<string, string> F, ref int status)
    {
        JsonObject Bad(string e) => new() { ["error"] = e };
        if (authz.StartsWith("Basic "))
        {
            var dec = Encoding.UTF8.GetString(Convert.FromBase64String(authz[6..]));
            var i = dec.IndexOf(':');
            var (id, sec) = (WebUtility.UrlDecode(dec[..Math.Max(i, 0)]), WebUtility.UrlDecode(dec[(i + 1)..]));
            if ((F("client_id") != "" && F("client_id") != id) || Secrets.GetValueOrDefault(id, "") == "" || Secrets[id] != sec)
            {
                status = 401;
                return Bad("invalid_client");
            }
            form["client_id"] = id;
        }
        else if (Secrets.GetValueOrDefault(F("client_id"), "") is { Length: > 0 } want && F("client_secret") != want)
        {
            status = 401;
            return Bad("invalid_client");
        }
        Code? c;
        switch (F("grant_type"))
        {
            case "authorization_code":
                if (!_codes.Remove(F("code"), out c)) return Bad("invalid_grant"); // single use
                if (AuthUtil.B64Url(SHA256.HashData(Encoding.ASCII.GetBytes(F("code_verifier")))) != c.Challenge) return Bad("invalid_grant"); // PKCE
                if (F("client_id") != c.ClientId || F("redirect_uri") != c.Redirect || F("resource") != c.Resource) return Bad("invalid_grant");
                break;
            case "refresh_token":
                if (!_rts.TryGetValue(F("refresh_token"), out c) || F("client_id") != c.ClientId) return Bad("invalid_grant");
                _rts.Remove(F("refresh_token")); // rotation (required for public clients)
                if (F("resource") is { Length: > 0 } res && (res != c.Resource || RejectRefreshResource)) return Bad("invalid_target");
                Refreshes++;
                break;
            default:
                return Bad("unsupported_grant_type");
        }
        var rt = AuthUtil.RandStr(24);
        _rts[rt] = c;
        var now = DateTimeOffset.UtcNow;
        var at = Mint(new JsonObject
        {
            ["iss"] = Issuer, ["sub"] = "dev-user", ["aud"] = c.Resource, ["scope"] = c.Scope, ["client_id"] = c.ClientId,
            ["jti"] = AuthUtil.RandStr(8), ["exp"] = now.Add(AccessTtl).ToUnixTimeSeconds(), ["iat"] = now.ToUnixTimeSeconds(),
        });
        status = 200;
        return new JsonObject { ["access_token"] = at, ["token_type"] = "Bearer", ["expires_in"] = (long)AccessTtl.TotalSeconds, ["refresh_token"] = rt, ["scope"] = c.Scope };
    }
}
