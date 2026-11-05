using System.Buffers.Text;
using System.Collections.Specialized;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// McpDevAuthServer — DEVELOPMENT AND TESTS ONLY. NEVER USE IN PRODUCTION.
//
// An in-process OAuth 2.1 authorization server that auto-approves every request as "dev-user" (no
// login, no consent): RFC 8414 metadata, JWKS, authorization code + PKCE S256, resource indicators
// (RFC 8707), `iss` in the response (RFC 9207), refresh-token rotation, pre-registered clients and
// Client ID Metadata Documents. No dynamic client registration. Plain HTTP on 127.0.0.1, so a
// toolnexus client needs auth.allowHttp against it (SPEC §7C.2). Go reference: golang/mcp_devauth.go.
//
//   await using var dev = await McpDevAuthServer.StartAsync(new McpDevAuthOptions
//       { Clients = new() { ["my-app"] = ["http://127.0.0.1/callback"] } });
//   var auth = new McpServerAuth { Issuer = dev.Issuer, Scopes = new() { ["*"] = ["mcp"] } };
// ---------------------------------------------------------------------------

/// <summary>Configures <see cref="McpDevAuthServer.StartAsync"/> (DEV ONLY).</summary>
public sealed class McpDevAuthOptions
{
    /// <summary>Pre-registered client_id → allowed redirect URIs (a loopback redirect matches on any
    /// port, RFC 8252 §7.3).</summary>
    public IDictionary<string, IReadOnlyList<string>>? Clients { get; set; }

    /// <summary>Confidential clients: client_id → secret (client_secret_basic or _post).</summary>
    public IDictionary<string, string>? Secrets { get; set; }

    /// <summary>Lifetime of issued access tokens. Default 1 h.</summary>
    public TimeSpan AccessTtl { get; set; }

    /// <summary>Stop advertising (and accepting) Client ID Metadata Documents.</summary>
    public bool NoCimd { get; set; }

    /// <summary>Answer invalid_target when a refresh carries <c>resource</c> (an Entra-like provider).</summary>
    public bool RejectRefreshResource { get; set; }

    /// <summary>Fetches Client ID Metadata Documents. Default: a 10 s client.</summary>
    public HttpClient? MetadataClient { get; set; }
}

/// <summary>
/// The running dev authorization server. <b>DEVELOPMENT AND TESTS ONLY — never production</b>: it
/// approves every authorization request without a login.
/// </summary>
public sealed class McpDevAuthServer : IAsyncDisposable, IDisposable
{
    /// <summary><c>http://127.0.0.1:&lt;port&gt;</c>.</summary>
    public string Issuer { get; }

    private readonly McpDevAuthOptions _opts;
    private readonly HttpClient _meta;
    private readonly RSA _key = RSA.Create(2048);
    private readonly HttpListener _listener;
    private readonly object _mu = new();
    private readonly Dictionary<string, Grant> _codes = new();
    private readonly Dictionary<string, Grant> _rts = new();
    private int _auths;

    private sealed record Grant(string ClientId, string Redirect, string Challenge, string Resource, string Scope);

    private McpDevAuthServer(McpDevAuthOptions opts, HttpListener listener, string issuer)
    {
        _opts = opts;
        _listener = listener;
        Issuer = issuer;
        _meta = opts.MetadataClient ?? new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
    }

    /// <summary>Starts the dev AS on 127.0.0.1:&lt;random&gt; (DEV ONLY).</summary>
    public static Task<McpDevAuthServer> StartAsync(McpDevAuthOptions? opts = null)
    {
        opts ??= new McpDevAuthOptions();
        for (var attempt = 0; ; attempt++)
        {
            var probe = new TcpListener(IPAddress.Loopback, 0);
            probe.Start();
            var port = ((IPEndPoint)probe.LocalEndpoint).Port;
            probe.Stop();
            var l = new HttpListener();
            l.Prefixes.Add($"http://127.0.0.1:{port}/");
            try
            {
                l.Start();
            }
            catch (HttpListenerException) when (attempt < 20)
            {
                l.Close();
                continue;
            }
            var d = new McpDevAuthServer(opts, l, $"http://127.0.0.1:{port}");
            _ = Task.Run(d.LoopAsync);
            return Task.FromResult(d);
        }
    }

    /// <summary>How many /authorize requests were served.</summary>
    public int Authorizations
    {
        get { lock (_mu) return _auths; }
    }

    /// <summary>Signs an access token with the server's key (tests: forge expired or wrong-audience tokens).</summary>
    public string Mint(IDictionary<string, object?> claims)
    {
        var input = Base64Url.EncodeToString(Encoding.UTF8.GetBytes("{\"alg\":\"RS256\",\"kid\":\"dev\",\"typ\":\"JWT\"}"))
            + "." + Base64Url.EncodeToString(Encoding.UTF8.GetBytes(Json.Stringify(claims)));
        var sig = _key.SignData(Encoding.ASCII.GetBytes(input), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        return input + "." + Base64Url.EncodeToString(sig);
    }

    public void Dispose()
    {
        try { _listener.Stop(); } catch { }
        try { _listener.Close(); } catch { }
    }

    public ValueTask DisposeAsync()
    {
        Dispose();
        return ValueTask.CompletedTask;
    }

    private async Task LoopAsync()
    {
        while (true)
        {
            HttpListenerContext ctx;
            try { ctx = await _listener.GetContextAsync().ConfigureAwait(false); }
            catch { break; }
            _ = Task.Run(async () =>
            {
                try { await ServeAsync(ctx).ConfigureAwait(false); }
                catch { try { ctx.Response.StatusCode = 500; } catch { } }
                finally { try { ctx.Response.Close(); } catch { } }
            });
        }
    }

    private static async Task JsAsync(HttpListenerContext ctx, int status, object v)
    {
        ctx.Response.StatusCode = status;
        ctx.Response.ContentType = "application/json";
        var b = Encoding.UTF8.GetBytes(Json.Stringify(v) + "\n");
        await ctx.Response.OutputStream.WriteAsync(b).ConfigureAwait(false);
    }

    private static async Task TextAsync(HttpListenerContext ctx, int status, string text)
    {
        ctx.Response.StatusCode = status;
        ctx.Response.ContentType = "text/plain; charset=utf-8";
        await ctx.Response.OutputStream.WriteAsync(Encoding.UTF8.GetBytes(text + "\n")).ConfigureAwait(false);
    }

    private async Task ServeAsync(HttpListenerContext ctx)
    {
        switch (ctx.Request.Url?.AbsolutePath)
        {
            case "/.well-known/oauth-authorization-server":
                await JsAsync(ctx, 200, new Dictionary<string, object?>
                {
                    ["issuer"] = Issuer,
                    ["authorization_endpoint"] = Issuer + "/authorize",
                    ["token_endpoint"] = Issuer + "/token",
                    ["jwks_uri"] = Issuer + "/jwks",
                    ["response_types_supported"] = new[] { "code" },
                    ["grant_types_supported"] = new[] { "authorization_code", "refresh_token" },
                    ["code_challenge_methods_supported"] = new[] { "S256" },
                    ["token_endpoint_auth_methods_supported"] = new[] { "none", "client_secret_basic", "client_secret_post" },
                    ["authorization_response_iss_parameter_supported"] = true,
                    ["client_id_metadata_document_supported"] = !_opts.NoCimd,
                }).ConfigureAwait(false);
                break;
            case "/jwks":
                var p = _key.ExportParameters(false);
                await JsAsync(ctx, 200, new Dictionary<string, object?>
                {
                    ["keys"] = new[]
                    {
                        new Dictionary<string, object?>
                        {
                            ["kty"] = "RSA", ["kid"] = "dev", ["alg"] = "RS256", ["use"] = "sig",
                            ["n"] = Base64Url.EncodeToString(p.Modulus!), ["e"] = Base64Url.EncodeToString(p.Exponent!),
                        },
                    },
                }).ConfigureAwait(false);
                break;
            case "/authorize":
                await AuthorizeAsync(ctx).ConfigureAwait(false);
                break;
            case "/token":
                await TokenAsync(ctx).ConfigureAwait(false);
                break;
            default:
                await TextAsync(ctx, 404, "404 page not found").ConfigureAwait(false);
                break;
        }
    }

    private static string Rand(int n) => Convert.ToHexString(RandomNumberGenerator.GetBytes(n)).ToLowerInvariant();

    private static string Encode(IEnumerable<(string K, string V)> kv)
        => string.Join('&', kv.OrderBy(x => x.K, StringComparer.Ordinal).Select(x => Uri.EscapeDataString(x.K) + "=" + Uri.EscapeDataString(x.V)));

    private async Task AuthorizeAsync(HttpListenerContext ctx)
    {
        var q = System.Web.HttpUtility.ParseQueryString(ctx.Request.Url?.Query ?? "");
        string G(string k) => q[k] ?? "";
        lock (_mu) _auths++;
        var (cid, redirect) = (G("client_id"), G("redirect_uri"));
        if (!await RedirectAllowedAsync(cid, redirect).ConfigureAwait(false))
        {
            await TextAsync(ctx, 400, "invalid_client or redirect_uri").ConfigureAwait(false); // never redirect to an unverified URI
            return;
        }
        void Back(params (string K, string V)[] v)
        {
            var all = v.Concat(new[] { ("state", G("state")), ("iss", Issuer) });
            ctx.Response.StatusCode = 302;
            ctx.Response.RedirectLocation = redirect + (redirect.Contains('?') ? "&" : "?") + Encode(all);
        }
        if (G("response_type") != "code" || G("code_challenge_method") != "S256" || G("code_challenge") == "")
        {
            Back(("error", "invalid_request"));
            return;
        }
        if (G("resource") == "")
        {
            Back(("error", "invalid_target"));
            return;
        }
        var code = Rand(16);
        lock (_mu) _codes[code] = new Grant(cid, redirect, G("code_challenge"), G("resource"), G("scope"));
        Back(("code", code));
    }

    /// <summary>Exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3). An http(s)
    /// client_id is a Client ID Metadata Document.</summary>
    private async Task<bool> RedirectAllowedAsync(string cid, string redirect)
    {
        IEnumerable<string> allowed = _opts.Clients != null && _opts.Clients.TryGetValue(cid, out var a) && a != null ? a : Array.Empty<string>();
        if (cid.StartsWith("https://", StringComparison.Ordinal) || cid.StartsWith("http://", StringComparison.Ordinal))
        {
            if (_opts.NoCimd) return false;
            try
            {
                var doc = await McpServerAuth.GetJsonAsync(_meta, cid, default).ConfigureAwait(false);
                if (doc.ValueKind != JsonValueKind.Object) return false;
                string S(string k) => doc.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString()! : "";
                if (S("client_id") != cid || S("client_name") == "") return false;
                allowed = doc.TryGetProperty("redirect_uris", out var r) && r.ValueKind == JsonValueKind.Array
                    ? r.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.String).Select(x => x.GetString()!).ToList()
                    : new List<string>();
            }
            catch
            {
                return false;
            }
        }
        if (!Uri.TryCreate(redirect, UriKind.Absolute, out var ru)) return false;
        foreach (var x in allowed)
        {
            if (x == redirect) return true;
            if (!Uri.TryCreate(x, UriKind.Absolute, out var au)) continue;
            if (IPAddress.TryParse(ru.Host.Trim('[', ']'), out var ip) && IPAddress.IsLoopback(ip) && au.Host == ru.Host
                && au.Scheme == ru.Scheme && au.AbsolutePath == ru.AbsolutePath)
                return true;
        }
        return false;
    }

    private async Task TokenAsync(HttpListenerContext ctx)
    {
        string raw;
        using (var sr = new StreamReader(ctx.Request.InputStream, Encoding.UTF8)) raw = await sr.ReadToEndAsync().ConfigureAwait(false);
        var f = ctx.Request.HttpMethod == "POST" ? System.Web.HttpUtility.ParseQueryString(raw) : new NameValueCollection();
        string F(string k) => f[k] ?? "";
        int status;
        object reply;
        lock (_mu) (status, reply) = TokenLocked(ctx.Request.Headers["Authorization"] ?? "", f, F);
        await JsAsync(ctx, status, reply).ConfigureAwait(false);
    }

    private (int, object) TokenLocked(string authz, NameValueCollection f, Func<string, string> F)
    {
        static (int, object) Bad(string e, int status = 400) => (status, new Dictionary<string, object?> { ["error"] = e });
        var secrets = _opts.Secrets ?? new Dictionary<string, string>();
        if (authz.StartsWith("Basic ", StringComparison.OrdinalIgnoreCase))
        {
            string dec;
            try { dec = Encoding.UTF8.GetString(Convert.FromBase64String(authz[6..].Trim())); }
            catch { return Bad("invalid_client", 401); }
            var i = dec.IndexOf(':');
            if (i < 0) return Bad("invalid_client", 401);
            var id = Uri.UnescapeDataString(dec[..i].Replace('+', ' '));
            var sec = Uri.UnescapeDataString(dec[(i + 1)..].Replace('+', ' '));
            if ((F("client_id") != "" && F("client_id") != id) || !secrets.TryGetValue(id, out var want) || want == "" || want != sec)
                return Bad("invalid_client", 401);
            f["client_id"] = id;
        }
        else if (secrets.TryGetValue(F("client_id"), out var want) && want != "" && F("client_secret") != want)
        {
            return Bad("invalid_client", 401);
        }
        Grant? g;
        switch (F("grant_type"))
        {
            case "authorization_code":
                if (!_codes.Remove(F("code"), out g)) return Bad("invalid_grant"); // single use
                var sum = Base64Url.EncodeToString(SHA256.HashData(Encoding.UTF8.GetBytes(F("code_verifier"))));
                if (sum != g.Challenge || F("client_id") != g.ClientId || F("redirect_uri") != g.Redirect || F("resource") != g.Resource)
                    return Bad("invalid_grant");
                break;
            case "refresh_token":
                if (!_rts.TryGetValue(F("refresh_token"), out g) || F("client_id") != g.ClientId) return Bad("invalid_grant");
                _rts.Remove(F("refresh_token")); // rotation
                if (F("resource") is { Length: > 0 } res && (res != g.Resource || _opts.RejectRefreshResource)) return Bad("invalid_target");
                break;
            default:
                return Bad("unsupported_grant_type");
        }
        var ttl = _opts.AccessTtl > TimeSpan.Zero ? _opts.AccessTtl : TimeSpan.FromHours(1);
        var rt = Rand(24);
        _rts[rt] = g;
        var now = DateTimeOffset.UtcNow;
        var at = Mint(new Dictionary<string, object?>
        {
            ["iss"] = Issuer, ["sub"] = "dev-user", ["aud"] = g.Resource, ["scope"] = g.Scope, ["client_id"] = g.ClientId,
            ["jti"] = Rand(8), ["exp"] = now.Add(ttl).ToUnixTimeSeconds(), ["iat"] = now.ToUnixTimeSeconds(),
        });
        return (200, new Dictionary<string, object?>
        {
            ["access_token"] = at, ["token_type"] = "Bearer", ["expires_in"] = (long)ttl.TotalSeconds,
            ["refresh_token"] = rt, ["scope"] = g.Scope,
        });
    }
}
