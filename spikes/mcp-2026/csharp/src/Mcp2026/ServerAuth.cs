using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;

namespace Mcp2026;

// Server-side authorization: the MCP server as an OAuth 2.1 protected resource
// (MCP 2026-07-28 basic/authorization). One object, set Server.Auth, done.

/// <summary>The authenticated caller, available to tool handlers as <see cref="ToolContext.Principal"/>.</summary>
public sealed record Principal(string Subject, IReadOnlyList<string> Scopes, JsonObject? Claims = null);

/// <summary>Protects a Server. Scopes["*"] is required for every request (it is also the advertised
/// scopes_supported); Scopes[toolName] is additionally required for that tools/call.</summary>
public sealed class ServerAuth
{
    public const string PrmPath = "/.well-known/oauth-protected-resource";

    /// <summary>The authorization server's issuer identifier.</summary>
    public required string Issuer { get; init; }
    /// <summary>Canonical resource URI; "" = derived from the request (scheme://host/mcp).</summary>
    public string Audience { get; init; } = "";
    /// <summary>"*" = every request; tool name = that tool.</summary>
    public Dictionary<string, string[]> Scopes { get; init; } = [];
    /// <summary>Replaces the default JWT/JWKS verifier (opaque tokens, introspection, ...). Throw to reject.</summary>
    public Func<string, CancellationToken, Task<Principal>>? Verify { get; set; }
    /// <summary>For AS metadata + JWKS fetches; null = the shared default.</summary>
    public HttpClient? Http { get; init; }

    private readonly SemaphoreSlim _mu = new(1, 1);
    private Dictionary<string, RSA>? _keys; // JWKS cache by kid
    private DateTime _fetched; // unknown kids refetch at most every 30 s

    private string[] Base => Scopes.GetValueOrDefault("*") ?? [];

    private string AudienceFor(HttpRequest r) => Audience != "" ? Audience : $"{r.Scheme}://{r.Host}/mcp";

    /// <summary>Answers RFC 9728 PRM at both the path-inserted and root well-known URIs.</summary>
    internal async Task<bool> ServeMetadataAsync(HttpContext http)
    {
        var p = http.Request.Path.Value;
        if (p != PrmPath && p != PrmPath + "/mcp") return false;
        http.Response.ContentType = "application/json";
        await http.Response.WriteAsync(J.Serialize(new JsonObject
        {
            ["resource"] = AudienceFor(http.Request), ["authorization_servers"] = J.Array([Issuer]),
            ["scopes_supported"] = J.Array(Base), ["bearer_methods_supported"] = J.Array(["header"]),
        }));
        return true;
    }

    /// <summary>Authenticates the request; on failure writes 401/403 and returns false.</summary>
    internal async Task<(bool, Principal?)> CheckAsync(HttpContext http, string method, JsonObject p)
    {
        var r = http.Request;
        async Task<(bool, Principal?)> Challenge(int status, string extra, IReadOnlyList<string> scopes)
        {
            var v = $"Bearer resource_metadata=\"{r.Scheme}://{r.Host}{PrmPath}/mcp\"";
            if (scopes.Count > 0) v += $", scope=\"{string.Join(' ', scopes)}\"";
            http.Response.Headers["WWW-Authenticate"] = v + extra;
            http.Response.ContentType = "application/json";
            http.Response.StatusCode = status;
            await http.Response.WriteAsync("{\"error\":\"unauthorized\"}");
            return (false, null);
        }
        var auth = r.Headers.Authorization.ToString();
        if (!auth.StartsWith("Bearer ") || auth.Length == 7) return await Challenge(401, "", Base);
        var tok = auth[7..];
        Principal pr;
        try { pr = Verify != null ? await Verify(tok, http.RequestAborted) : await VerifyJwtAsync(tok, AudienceFor(r), http.RequestAborted); }
        catch (Exception) { return await Challenge(401, ", error=\"invalid_token\"", Base); } // never echo the token or the reason
        var need = Base.ToList();
        if (method == "tools/call") need.AddRange(Scopes.GetValueOrDefault(J.Str(p["name"], "")) ?? []);
        if (need.Any(s => !pr.Scopes.Contains(s))) return await Challenge(403, ", error=\"insufficient_scope\"", need);
        return (true, pr);
    }

    // --- default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ---

    private async Task<Principal> VerifyJwtAsync(string tok, string aud, CancellationToken ct)
    {
        var parts = tok.Split('.');
        if (parts.Length != 3) throw new McpException("malformed jwt");
        var hdr = J.ParseObject(Encoding.UTF8.GetString(B64UrlDecode(parts[0])));
        var claims = J.ParseObject(Encoding.UTF8.GetString(B64UrlDecode(parts[1])));
        if (hdr == null || claims == null || J.Str(hdr["alg"]) != "RS256") throw new McpException("bad jwt header/claims");
        var key = await KeyAsync(J.Str(hdr["kid"], ""), ct);
        if (!key.VerifyData(Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]), B64UrlDecode(parts[2]), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1))
            throw new McpException("bad signature");
        if (J.Str(claims["iss"]) != Issuer) throw new McpException("wrong issuer");
        var audOk = claims["aud"] switch
        {
            JsonValue v => J.Str(v) == aud,
            JsonArray a => J.Strings(a).Contains(aud),
            _ => false,
        };
        if (!audOk) throw new McpException("wrong audience");
        if ((long)(J.Num(claims["exp"]) ?? 0) <= DateTimeOffset.UtcNow.ToUnixTimeSeconds()) throw new McpException("expired");
        return new Principal(J.Str(claims["sub"], ""), J.Str(claims["scope"], "").Split(' ', StringSplitOptions.RemoveEmptyEntries), claims);
    }

    internal static byte[] B64UrlDecode(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/');
        try { return Convert.FromBase64String(s.PadRight(s.Length + (4 - s.Length % 4) % 4, '=')); }
        catch (FormatException) { throw new McpException("bad base64url"); }
    }

    private async Task<RSA> KeyAsync(string kid, CancellationToken ct)
    {
        await _mu.WaitAsync(ct);
        try
        {
            if (_keys != null && _keys.TryGetValue(kid, out var k)) return k;
            if (_keys != null && DateTime.UtcNow - _fetched < TimeSpan.FromSeconds(30)) throw new McpException("unknown kid"); // garbage kids must not hammer the AS
            var hc = Http ?? DevTrust.Shared;
            var md = await OAuth.DiscoverAsAsync(hc, Issuer, ct); // same RFC 8414 / OIDC order the client uses
            var jwks = await OAuth.GetJsonAsync(hc, J.Str(md["jwks_uri"], ""), ct);
            _keys = [];
            _fetched = DateTime.UtcNow;
            foreach (var x in jwks["keys"] as JsonArray ?? [])
            {
                if (J.Str(x?["kty"]) != "RSA") continue;
                try { _keys[J.Str(x?["kid"], "")] = RsaFrom(B64UrlDecode(J.Str(x?["n"], "")), B64UrlDecode(J.Str(x?["e"], ""))); }
                catch (Exception e) when (e is McpException or CryptographicException) { /* skip a bad key */ }
            }
            return _keys.TryGetValue(kid, out k) ? k : throw new McpException("unknown kid");
        }
        finally { _mu.Release(); }
    }

    private static RSA RsaFrom(byte[] n, byte[] e)
    {
        var rsa = RSA.Create();
        rsa.ImportParameters(new RSAParameters { Modulus = n, Exponent = e });
        return rsa;
    }
}
