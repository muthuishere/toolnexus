using System.Buffers.Text;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// MCP server authorization (SPEC §2A.2, §7C.2): an McpHttpHandler as an OAuth 2.1 protected
// resource. McpServerAuth is a plain helper producing the handler's Authenticate hook and the
// RFC 9728 metadata handler; the host mounts both. Identity, tenants and token storage stay the
// host's. Go reference: golang/mcp_serverauth.go.
//
//   var auth = new McpServerAuth { Issuer = "https://login.example", Scopes = new() { ["*"] = ["mcp"] } };
//   var handler = tk.McpHandler(new McpHandlerConfig { ProtocolVersions = ["2026-07-28"], Authenticate = auth.AuthenticateAsync });
//   // route GET /.well-known/oauth-protected-resource[/mcp] to auth.Metadata()
// ---------------------------------------------------------------------------

/// <summary>
/// The verified caller. <see cref="McpServerAuth.AuthenticateAsync"/> returns it, so hooks and tools
/// read it as <c>(McpPrincipal)ctx.Identity</c> / <c>McpContext.Current.Identity</c>.
/// </summary>
public sealed record McpPrincipal(string Subject, IReadOnlyList<string> Scopes, IReadOnlyDictionary<string, object?>? Claims = null);

/// <summary>Protects an <see cref="McpHttpHandler"/> with bearer tokens (SPEC §2A.2).</summary>
public sealed class McpServerAuth
{
    /// <summary>The well-known path of the RFC 9728 protected resource metadata.</summary>
    public const string PrmPath = "/.well-known/oauth-protected-resource";

    /// <summary>The authorization server's issuer (advertised in the PRM, and the JWT <c>iss</c> the
    /// default verifier requires).</summary>
    public string Issuer { get; set; } = "";

    /// <summary>Canonical resource URI (PRM <c>resource</c>, the JWT <c>aud</c>). null/empty ⇒ derived
    /// per request: <c>&lt;scheme&gt;://&lt;Host&gt;&lt;ResourcePath&gt;</c>.</summary>
    public string? Audience { get; set; }

    /// <summary>Where the handler is mounted. Default "/mcp".</summary>
    public string? ResourcePath { get; set; }

    /// <summary>"*" is required for every request (and advertised as scopes_supported); a tool name adds
    /// scopes for that tools/call (2026-07-28 wire). Exact match, no hierarchy.</summary>
    public IDictionary<string, IReadOnlyList<string>> Scopes { get; set; } = new Dictionary<string, IReadOnlyList<string>>();

    /// <summary>Replaces the default RS256/JWKS verifier (opaque tokens, introspection): receives the
    /// bearer token and the expected audience; throw to reject.</summary>
    public Func<string, string, CancellationToken, Task<McpPrincipal>>? Verify { get; set; }

    /// <summary>Fetches AS metadata and JWKS. null ⇒ a shared 10 s client.</summary>
    public HttpClient? HttpClient { get; set; }

    private static readonly HttpClient DefaultHttp = new() { Timeout = TimeSpan.FromSeconds(10) };
    private readonly SemaphoreSlim _mu = new(1, 1);
    private Dictionary<string, RSA>? _keys;
    private DateTime _fetched;

    private string RPath => string.IsNullOrEmpty(ResourcePath) ? "/mcp" : ResourcePath!;

    private IReadOnlyList<string> Base => Scopes.TryGetValue("*", out var s) && s != null ? s : Array.Empty<string>();

    private static string Origin(McpHttpExchange ex) => ex.Scheme + "://" + (ex.Header("Host") ?? "");

    private string AudienceFor(McpHttpExchange ex) => !string.IsNullOrEmpty(Audience) ? Audience! : Origin(ex) + RPath;

    /// <summary>The RFC 9728 metadata URL the challenge names.</summary>
    public string MetadataUrl(McpHttpExchange ex)
    {
        if (!string.IsNullOrEmpty(Audience) && Uri.TryCreate(Audience, UriKind.Absolute, out var u) && u.Authority != "")
        {
            var afterScheme = Audience![(Audience!.IndexOf("://", StringComparison.Ordinal) + 3)..];
            var path = afterScheme.Contains('/') ? Uri.UnescapeDataString(u.AbsolutePath) : "";
            return u.Scheme + "://" + u.Authority + PrmPath + path;
        }
        return Origin(ex) + PrmPath + RPath;
    }

    /// <summary>
    /// The RFC 9728 handler: GET <c>/.well-known/oauth-protected-resource</c> and
    /// <c>…/oauth-protected-resource&lt;ResourcePath&gt;</c> answer
    /// <c>{resource, authorization_servers, scopes_supported, bearer_methods_supported}</c>; anything else 404.
    /// Routes on <see cref="McpHttpExchange.Path"/>.
    /// </summary>
    public Func<McpHttpExchange, Task> Metadata() => async ex =>
    {
        if (ex.Method != "GET" || (ex.Path != PrmPath && ex.Path != PrmPath + RPath))
        {
            ex.SetStatus(404);
            ex.SetHeader("Content-Type", "text/plain; charset=utf-8");
            await ex.ResponseBody.WriteAsync(Encoding.UTF8.GetBytes("404 page not found\n")).ConfigureAwait(false);
            return;
        }
        var doc = new Dictionary<string, object?>
        {
            ["resource"] = AudienceFor(ex),
            ["authorization_servers"] = new[] { Issuer },
            ["scopes_supported"] = Base,
            ["bearer_methods_supported"] = new[] { "header" },
        };
        ex.SetStatus(200);
        ex.SetHeader("Content-Type", "application/json");
        await ex.ResponseBody.WriteAsync(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(doc) + "\n")).ConfigureAwait(false);
    };

    /// <summary>Serves <see cref="Metadata"/> for an HttpListener request, closing its response.</summary>
    public async Task MetadataAsync(System.Net.HttpListenerContext ctx)
    {
        try { await Metadata()(McpHttpExchange.FromListener(ctx)).ConfigureAwait(false); }
        finally { try { ctx.Response.OutputStream.Close(); } catch { } }
    }

    private McpAuthError Challenge(McpHttpExchange ex, int status, IReadOnlyList<string> scopes, string err)
    {
        var v = $"Bearer resource_metadata=\"{MetadataUrl(ex)}\"";
        if (scopes.Count > 0) v += $", scope=\"{string.Join(' ', scopes)}\"";
        if (err != "") v += $", error=\"{err}\"";
        return new McpAuthError(status, v, err);
    }

    /// <summary>
    /// An <see cref="McpHandlerConfig.Authenticate"/> hook. Missing token ⇒ 401; invalid / expired /
    /// wrong audience ⇒ 401 <c>error="invalid_token"</c>; a missing scope ⇒ 403
    /// <c>error="insufficient_scope"</c> naming the full needed set. Per-tool scopes come from the
    /// validated Mcp-Method / Mcp-Name headers (2026-07-28 wire); older wires check <c>Scopes["*"]</c>
    /// only. Throws <see cref="McpAuthError"/>; the token and the reason never reach the wire.
    /// </summary>
    public async Task<object?> AuthenticateAsync(McpHttpExchange ex)
    {
        var authz = ex.Header("Authorization") ?? "";
        if (!authz.StartsWith("Bearer ", StringComparison.Ordinal) || authz[7..].Trim() == "")
            throw Challenge(ex, 401, Base, "");
        var tok = authz[7..];
        McpPrincipal p;
        try
        {
            p = Verify != null
                ? await Verify(tok, AudienceFor(ex), ex.Aborted).ConfigureAwait(false)
                : await VerifyJwtAsync(tok, AudienceFor(ex), ex.Aborted).ConfigureAwait(false);
        }
        catch
        {
            throw Challenge(ex, 401, Base, "invalid_token");
        }
        var need = Base.ToList();
        if (ex.Header("Mcp-Method") == "tools/call")
        {
            var (name, ok) = McpHttpHandler.DecodeHeaderValue(ex.Header("Mcp-Name") ?? "");
            if (ok && Scopes.TryGetValue(name, out var extra) && extra != null)
                foreach (var s in extra)
                    if (!need.Contains(s)) need.Add(s);
        }
        if (need.Any(s => !(p.Scopes ?? Array.Empty<string>()).Contains(s)))
            throw Challenge(ex, 403, need, "insufficient_scope");
        return p;
    }

    private async Task<McpPrincipal> VerifyJwtAsync(string tok, string aud, CancellationToken ct)
    {
        var parts = tok.Split('.');
        if (parts.Length != 3) throw new InvalidOperationException("malformed jwt");
        using var hdr = JsonDocument.Parse(Base64Url.DecodeFromChars(parts[0]));
        using var claimsDoc = JsonDocument.Parse(Base64Url.DecodeFromChars(parts[1]));
        var claims = claimsDoc.RootElement;
        if (hdr.RootElement.ValueKind != JsonValueKind.Object || claims.ValueKind != JsonValueKind.Object
            || Str(hdr.RootElement, "alg") != "RS256")
            throw new InvalidOperationException("bad jwt header or claims");
        var key = await KeyAsync(Str(hdr.RootElement, "kid") ?? "", ct).ConfigureAwait(false);
        if (!key.VerifyData(Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]), Base64Url.DecodeFromChars(parts[2]),
                HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1))
            throw new InvalidOperationException("bad signature");
        if (Str(claims, "iss") != Issuer) throw new InvalidOperationException("wrong issuer");
        var audOk = claims.TryGetProperty("aud", out var a) && a.ValueKind switch
        {
            JsonValueKind.String => a.GetString() == aud,
            JsonValueKind.Array => a.EnumerateArray().Any(x => x.ValueKind == JsonValueKind.String && x.GetString() == aud),
            _ => false,
        };
        if (!audOk) throw new InvalidOperationException("wrong audience");
        var exp = claims.TryGetProperty("exp", out var e) && e.ValueKind == JsonValueKind.Number ? (long)e.GetDouble() : 0;
        if (exp <= DateTimeOffset.UtcNow.ToUnixTimeSeconds()) throw new InvalidOperationException("expired");
        var scope = Str(claims, "scope") ?? "";
        return new McpPrincipal(Str(claims, "sub") ?? "", scope.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries),
            (Dictionary<string, object?>)Json.FromElement(claims)!);
    }

    private static string? Str(JsonElement o, string k)
        => o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    /// <summary>The JWKS key for kid; an unknown kid refetches at most every 30 s.</summary>
    private async Task<RSA> KeyAsync(string kid, CancellationToken ct)
    {
        await _mu.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_keys != null && _keys.TryGetValue(kid, out var k)) return k;
            if (_keys != null && DateTime.UtcNow - _fetched < TimeSpan.FromSeconds(30))
                throw new InvalidOperationException("unknown kid");
            var hc = HttpClient ?? DefaultHttp;
            var md = await DiscoverAsAsync(hc, Issuer, ct).ConfigureAwait(false);
            var jwksUri = Str(md, "jwks_uri") ?? "";
            var jwks = await GetJsonAsync(hc, jwksUri, ct).ConfigureAwait(false);
            var keys = new Dictionary<string, RSA>();
            if (jwks.TryGetProperty("keys", out var arr) && arr.ValueKind == JsonValueKind.Array)
                foreach (var x in arr.EnumerateArray())
                {
                    if (x.ValueKind != JsonValueKind.Object || Str(x, "kty") != "RSA") continue;
                    try
                    {
                        var rsa = RSA.Create();
                        rsa.ImportParameters(new RSAParameters
                        {
                            Modulus = Base64Url.DecodeFromChars(Str(x, "n") ?? ""),
                            Exponent = Base64Url.DecodeFromChars(Str(x, "e") ?? ""),
                        });
                        keys[Str(x, "kid") ?? ""] = rsa;
                    }
                    catch { /* skip a bad key */ }
                }
            _keys = keys;
            _fetched = DateTime.UtcNow;
            return _keys.TryGetValue(kid, out k) ? k : throw new InvalidOperationException("unknown kid");
        }
        finally { _mu.Release(); }
    }

    /// <summary>AS metadata in the SPEC §2A.1 step 3 order; its <c>issuer</c> must equal the issuer.</summary>
    internal static async Task<JsonElement> DiscoverAsAsync(HttpClient hc, string issuer, CancellationToken ct)
    {
        var u = new Uri(issuer);
        var origin = u.Scheme + "://" + u.Authority;
        var path = u.AbsolutePath.TrimEnd('/');
        var cands = new List<string>
        {
            origin + "/.well-known/oauth-authorization-server" + path,
            origin + "/.well-known/openid-configuration" + path,
        };
        if (path != "") cands.Add(origin + path + "/.well-known/openid-configuration");
        foreach (var c in cands)
        {
            try
            {
                var md = await GetJsonAsync(hc, c, ct).ConfigureAwait(false);
                if (md.ValueKind == JsonValueKind.Object && Str(md, "issuer") == issuer) return md;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch { /* next candidate */ }
        }
        throw new InvalidOperationException("authorization server metadata not found for " + issuer);
    }

    internal static async Task<JsonElement> GetJsonAsync(HttpClient hc, string url, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("Accept", "application/json");
        using var resp = await hc.SendAsync(req, ct).ConfigureAwait(false);
        if ((int)resp.StatusCode != 200) throw new HttpRequestException($"GET {url}: HTTP {(int)resp.StatusCode}");
        var body = await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(body);
        return doc.RootElement.Clone();
    }
}
