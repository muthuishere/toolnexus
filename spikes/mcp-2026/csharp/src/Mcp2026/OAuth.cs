using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.WebUtilities;

namespace Mcp2026;

// Client-side OAuth for MCP 2026-07-28 (basic/authorization). BCL only.
// Flow: 401/403 -> WWW-Authenticate -> PRM (RFC 9728) -> AS metadata (RFC 8414 / OIDC)
// -> client id (pre-registered | CIMD) -> code + PKCE S256 + resource (RFC 8707)
// -> iss (RFC 9207) + state check -> token -> retry with Bearer.

/// <summary>Bound to the issuer that minted it and the resource it is for. Never log it (ToString is redacted).</summary>
public sealed record Token
{
    public string AccessToken { get; init; } = "";
    public string RefreshToken { get; init; } = "";
    public DateTimeOffset? Expiry { get; init; }
    public IReadOnlyList<string> Scopes { get; init; } = [];
    public string Issuer { get; init; } = "";
    public string Resource { get; init; } = "";
    public string ClientId { get; init; } = "";
    public override string ToString() => "Token{issuer=" + Issuer + ", redacted}";
}

/// <summary>Persists tokens per resource. Implementations must keep them confidential.</summary>
public interface ITokenStore
{
    Token? Load(string resource);
    void Save(string resource, Token token);
    void Delete(string resource);
}

public sealed class MemoryTokenStore : ITokenStore
{
    private readonly Dictionary<string, Token> _m = [];
    public Token? Load(string resource) { lock (_m) return _m.GetValueOrDefault(resource); }
    public void Save(string resource, Token token) { lock (_m) _m[resource] = token; }
    public void Delete(string resource) { lock (_m) _m.Remove(resource); }
}

/// <summary>Mirrors toolnexus SPEC §10 Request (kind "authorization").</summary>
public sealed record Pending(string Id, string Kind, string Prompt, string Url, string ExpiresAt);

/// <summary>Thrown instead of blocking when the Authorizer pauses. Call <see cref="OAuth.ResumeAsync"/>
/// with Pending.Id and the callback URL to finish and get the original result.</summary>
public sealed class PendingAuthorizationException(Pending pending) : Exception("authorization pending: " + pending.Prompt)
{
    public Pending Pending { get; } = pending;
}

/// <summary>Decides how the user reaches the authorization URL and how the callback comes back.</summary>
public interface IAuthorizer
{
    Task<IAuthSession> BeginAsync(CancellationToken ct);
}

public interface IAuthSession : IAsyncDisposable
{
    string RedirectUri { get; }
    /// <summary>The full callback URL, or null to pause (hand a Pending to the caller).</summary>
    Task<string?> WaitAsync(Pending pending, CancellationToken ct);
}

/// <summary>Never blocks: every authorization becomes a <see cref="PendingAuthorizationException"/>.</summary>
public sealed record PauseAuthorizer(string Redirect) : IAuthorizer, IAuthSession
{
    public Task<IAuthSession> BeginAsync(CancellationToken ct) => Task.FromResult<IAuthSession>(this);
    public string RedirectUri => Redirect;
    public Task<string?> WaitAsync(Pending pending, CancellationToken ct) => Task.FromResult<string?>(null);
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}

/// <summary>Opens a browser and listens on 127.0.0.1:&lt;random&gt;/callback (RFC 8252 §7.3).</summary>
public sealed class LoopbackAuthorizer : IAuthorizer
{
    /// <summary>null = the system browser.</summary>
    public Func<string, Task>? Open { get; init; }

    public async Task<IAuthSession> BeginAsync(CancellationToken ct)
    {
        var cb = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var host = await HttpHost.StartAsync(async http =>
        {
            if (http.Request.Path != "/callback") { http.Response.StatusCode = 404; return; }
            cb.TrySetResult("http://" + http.Request.Host + http.Request.Path + http.Request.QueryString);
            await http.Response.WriteAsync("Authorization received. You can close this window.");
        });
        return new Session(host, cb, Open ?? OpenBrowser);
    }

    private sealed class Session(HttpHost host, TaskCompletionSource<string> cb, Func<string, Task> open) : IAuthSession
    {
        public string RedirectUri => host.Url + "/callback";
        public ValueTask DisposeAsync() => host.DisposeAsync();

        public async Task<string?> WaitAsync(Pending p, CancellationToken ct)
        {
            var timeout = Task.Delay(TimeSpan.FromMinutes(5), ct);
            var opened = Task.Run(() => open(p.Url), ct); // the opener may block (tests follow redirects synchronously)
            var first = await Task.WhenAny(cb.Task, opened, timeout);
            if (first == opened)
            {
                // a failed opener must not leave us waiting 5 minutes for a callback that cannot come
                if (opened.Exception is { } ex) throw new McpException("open authorization URL: " + ex.InnerException!.Message, ex.InnerException);
                first = await Task.WhenAny(cb.Task, timeout);
            }
            if (first == cb.Task) return await cb.Task;
            ct.ThrowIfCancellationRequested();
            throw new McpException("authorization timed out");
        }
    }

    private static Task OpenBrowser(string url)
    {
        var psi = OperatingSystem.IsMacOS() ? new ProcessStartInfo("open", [url])
            : OperatingSystem.IsWindows() ? new ProcessStartInfo("rundll32", ["url.dll,FileProtocolHandler", url])
            : new ProcessStartInfo("xdg-open", [url]);
        Process.Start(psi);
        return Task.CompletedTask;
    }
}

/// <summary>The client-side auth engine; attach it as <see cref="McpClient.Auth"/>.</summary>
public sealed class OAuth
{
    /// <summary>null = new LoopbackAuthorizer() (system browser + loopback callback).</summary>
    public IAuthorizer? Authorizer { get; set; }
    /// <summary>Pre-registered client_id for any issuer not in ClientIds.</summary>
    public string ClientId { get; set; } = "";
    /// <summary>Optional: makes the pre-registered client confidential. Never logged.</summary>
    public string ClientSecret { get; set; } = "";
    /// <summary>"none" | "client_secret_basic" | "client_secret_post"; "" = basic if ClientSecret else none.</summary>
    public string TokenAuthMethod { get; set; } = "";
    /// <summary>Drops the RFC 8707 resource from refresh_token requests (authorize + code exchange still send it).
    /// Entra rejects it on refresh (python-sdk #2578). Default: sent everywhere.</summary>
    public bool OmitRefreshResource { get; set; }
    /// <summary>null = in-memory.</summary>
    public ITokenStore? Store { get; set; }
    /// <summary>issuer -> pre-registered client_id (bound to that issuer).</summary>
    public Dictionary<string, string> ClientIds { get; set; } = [];
    /// <summary>CIMD client_id (HTTPS URL), used when the AS advertises support.</summary>
    public string ClientMetadataUrl { get; set; } = "";
    /// <summary>For PRM / AS / token calls; null = the shared default (timeout, trusts in-process DevAuthServers).</summary>
    public HttpClient? Http { get; set; }
    /// <summary>Allow non-HTTPS AS endpoints (dev only; the spec requires HTTPS).</summary>
    public bool AllowHttp { get; set; }

    private readonly object _mu = new();
    private readonly SemaphoreSlim _authMu = new(1, 1); // one login / one refresh at a time
    private readonly Dictionary<string, string> _issuer = []; // resource -> discovered issuer (only then is a stored token sent)
    internal readonly Dictionary<string, AuthFlow> Flows = [];

    internal sealed class AuthFlow
    {
        public required string Resource, Issuer, ClientId, Redirect, Verifier, State, TokenEndpoint;
        public bool IssSupported;
        public required List<string> Scopes;
        public DateTimeOffset Expires;
        public required Func<Task<JsonObject>> Retry;
    }

    private HttpClient Hc => Http ?? DevTrust.Shared;

    internal ITokenStore TokenStore { get { lock (_mu) return Store ??= new MemoryTokenStore(); } }

    /// <summary>The token to send for resource, refreshed if expired. "" = send none.</summary>
    internal async Task<string> BearerAsync(string resource, CancellationToken ct)
    {
        string? iss;
        lock (_mu) iss = _issuer.GetValueOrDefault(resource);
        var t = TokenStore.Load(resource);
        if (t == null || iss == null || t.Issuer != iss) return ""; // only tokens from this server's AS (spec: Token Handling)
        if (!Expired(t)) return t.AccessToken;
        await _authMu.WaitAsync(ct); // one refresh for N callers (refresh tokens rotate: a 2nd refresh would fail)
        try
        {
            t = TokenStore.Load(resource);
            if (t == null) return "";
            if (Expired(t) && !await RefreshAsync(t, ct)) return "";
            return TokenStore.Load(resource)?.AccessToken ?? "";
        }
        finally { _authMu.Release(); }
    }

    private static bool Expired(Token t) => t.Expiry is { } e && DateTimeOffset.UtcNow > e.AddSeconds(-10);

    private async Task<bool> RefreshAsync(Token t, CancellationToken ct)
    {
        if (t.RefreshToken == "") return false;
        try
        {
            var md = await DiscoverAsAsync(Hc, t.Issuer, ct);
            var form = new Dictionary<string, string> { ["grant_type"] = "refresh_token", ["refresh_token"] = t.RefreshToken, ["client_id"] = t.ClientId };
            if (!OmitRefreshResource) form["resource"] = t.Resource;
            var nt = await TokenRequestAsync(J.Str(md["token_endpoint"], ""), form, t.Issuer, t.Resource, t.ClientId, [.. t.Scopes], ct);
            if (nt.RefreshToken == "") nt = nt with { RefreshToken = t.RefreshToken };
            TokenStore.Save(t.Resource, nt);
            return true;
        }
        catch (Exception e) when (e is McpException or HttpRequestException or TaskCanceledException)
        {
            TokenStore.Delete(t.Resource);
            return false;
        }
    }

    /// <summary>Handles a 401/403. Returns = retry the request now; throws PendingAuthorizationException = paused.
    /// sent is the access token the failed request carried ("" = none).</summary>
    internal async Task ChallengeAsync(string resource, int status, string www, string sent, Func<Task<JsonObject>> retry, CancellationToken ct)
    {
        var prms = ParseChallenge(www);
        var err = prms.GetValueOrDefault("error", "");
        await _authMu.WaitAsync(ct); // N concurrent 401s -> one authorization, the rest retry with its token
        try
        {
            var cur = TokenStore.Load(resource);
            string? known;
            lock (_mu) known = _issuer.GetValueOrDefault(resource);
            if (cur != null && known != null && cur.Issuer == known && cur.AccessToken != sent && !Expired(cur))
                return; // someone else refreshed/authorized while we waited
            if (status == 401 && cur is { RefreshToken.Length: > 0 } && err == "invalid_token")
            {
                if (await RefreshAsync(cur, ct)) return;
                cur = null;
            }
            if (status == 403 && err != "insufficient_scope") throw new McpException("forbidden: " + err);
            var prm = await FetchPrmAsync(resource, prms.GetValueOrDefault("resource_metadata", ""), ct);
            var issuer = J.Strings(prm["authorization_servers"]).FirstOrDefault() // RFC 9728 §7.6: client's choice; we take the first
                ?? throw new McpException("PRM lists no authorization_servers");
            if (!AllowHttp && !issuer.StartsWith("https://")) throw new McpException("authorization server must use https");
            var md = await DiscoverAsAsync(Hc, issuer, ct);
            if (!J.Strings(md["code_challenge_methods_supported"]).Contains("S256"))
                throw new McpException("authorization server does not advertise PKCE S256; refusing");
            lock (_mu) _issuer[resource] = issuer;
            if (status == 401 && cur != null && cur.Issuer == issuer && err == "")
                return; // first request after restart: we now know the issuer, so the stored token may be sent
            // Scope selection: challenge scope, else scopes_supported; on step-up union with previous.
            var scopes = prms.GetValueOrDefault("scope", "").Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
            if (scopes.Count == 0) scopes.AddRange(J.Strings(prm["scopes_supported"]));
            if (cur != null && status == 403) scopes.AddRange(cur.Scopes.Where(s => !scopes.Contains(s)).ToList());
            var cimd = J.True(md["client_id_metadata_document_supported"]);
            var clientId = ClientIds.GetValueOrDefault(issuer, "");
            if (clientId == "") clientId = ClientId;
            if (clientId == "" && ClientMetadataUrl != "" && cimd) clientId = ClientMetadataUrl;
            if (clientId == "") // no DCR, ever: the user registers an app once and configures its id
            {
                var why = cimd ? "OAuth.ClientMetadataUrl is not set" : "it does not support Client ID Metadata Documents";
                throw new McpException($"no client identity for {issuer}: {why} and no client id is configured — register an app with {issuer} and set OAuth.ClientId (dynamic client registration is not supported)");
            }
            var sess = await (Authorizer ?? new LoopbackAuthorizer()).BeginAsync(ct);
            var f = new AuthFlow
            {
                Resource = resource, Issuer = issuer, ClientId = clientId, Redirect = sess.RedirectUri, Verifier = RandStr(32), State = RandStr(16),
                TokenEndpoint = J.Str(md["token_endpoint"], ""), Scopes = scopes, Retry = retry,
                IssSupported = J.True(md["authorization_response_iss_parameter_supported"]), Expires = DateTimeOffset.UtcNow.AddMinutes(10),
            };
            var q = new List<KeyValuePair<string, string>>
            {
                new("client_id", clientId), new("code_challenge", B64Url(SHA256.HashData(Encoding.ASCII.GetBytes(f.Verifier)))),
                new("code_challenge_method", "S256"), new("redirect_uri", f.Redirect), new("resource", resource), new("response_type", "code"),
            };
            if (scopes.Count > 0) q.Add(new("scope", string.Join(' ', scopes)));
            q.Add(new("state", f.State));
            var p = new Pending("auth-" + RandStr(12), "authorization", "Authorize access to " + resource,
                J.Str(md["authorization_endpoint"], "") + "?" + string.Join('&', q.Select(kv => Uri.EscapeDataString(kv.Key) + "=" + Uri.EscapeDataString(kv.Value))),
                f.Expires.UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ssZ"));
            string? cb;
            try { cb = await sess.WaitAsync(p, ct); }
            catch { await sess.DisposeAsync(); throw; }
            if (cb == null)
            {
                lock (_mu)
                {
                    foreach (var id in Flows.Where(kv => DateTimeOffset.UtcNow > kv.Value.Expires).Select(kv => kv.Key).ToList())
                        Flows.Remove(id); // drop abandoned pendings so they cannot pile up
                    Flows[p.Id] = f;
                }
                throw new PendingAuthorizationException(p);
            }
            await sess.DisposeAsync();
            await CompleteAsync(f, cb, ct);
        }
        finally { _authMu.Release(); }
    }

    internal void SetRetry(string id, Func<Task<JsonObject>> retry)
    {
        lock (_mu) if (Flows.TryGetValue(id, out var f)) f.Retry = retry;
    }

    /// <summary>Finishes a paused authorization and re-runs the original operation, returning its result.</summary>
    public async Task<JsonObject> ResumeAsync(string id, string callbackUrl, CancellationToken ct = default)
    {
        AuthFlow? f;
        lock (_mu) { Flows.Remove(id, out f); }
        if (f == null) throw new McpException($"unknown pending authorization \"{id}\"");
        if (DateTimeOffset.UtcNow > f.Expires) throw new McpException("pending authorization expired");
        await CompleteAsync(f, callbackUrl, ct);
        return await f.Retry();
    }

    /// <summary>ResumeAsync for web apps: the pending is found by the callback's state.</summary>
    public Task<JsonObject> ResumeCallbackAsync(string callbackUrl, CancellationToken ct = default)
    {
        var st = QueryHelpers.ParseQuery(new Uri(callbackUrl).Query).GetValueOrDefault("state").ToString();
        string id;
        lock (_mu) id = Flows.FirstOrDefault(kv => st != "" && kv.Value.State == st).Key ?? "";
        return ResumeAsync(id, callbackUrl, ct);
    }

    /// <summary>Serves one tool call to a browser user, pausing for authorization when needed: call runs; if it
    /// pauses, the browser is redirected to the AS; when the AS redirects back to this same handler (use its URL as
    /// PauseAuthorizer.Redirect), the original call is resumed and its text result written. Errors become 502.</summary>
    public RequestDelegate WebHandler(Func<HttpContext, Task<JsonObject>> call) => async http =>
    {
        var q = http.Request.Query;
        try
        {
            var res = q["state"].ToString() != "" && (q.ContainsKey("code") || q.ContainsKey("error"))
                ? await ResumeCallbackAsync($"{http.Request.Scheme}://{http.Request.Host}{http.Request.Path}{http.Request.QueryString}", http.RequestAborted)
                : await call(http);
            http.Response.ContentType = "text/plain; charset=utf-8";
            await http.Response.WriteAsync(Mcp.Text(res));
        }
        catch (PendingAuthorizationException pe) { http.Response.Redirect(pe.Pending.Url); }
        catch (Exception e)
        {
            http.Response.StatusCode = 502;
            await http.Response.WriteAsync(e.Message);
        }
    };

    private async Task CompleteAsync(AuthFlow f, string callbackUrl, CancellationToken ct)
    {
        var q = QueryHelpers.ParseQuery(new Uri(callbackUrl).Query);
        // RFC 9207 §2.4 table (spec: Authorization Response Validation) — before anything else, error responses included.
        if (q.TryGetValue("iss", out var iss))
        {
            if (iss[0] != f.Issuer) throw new McpException("authorization response iss mismatch (possible mix-up attack)"); // no normalization
        }
        else if (f.IssSupported) throw new McpException("authorization response missing iss");
        if (q.GetValueOrDefault("state").ToString() != f.State) throw new McpException("authorization response state mismatch");
        if (q.GetValueOrDefault("error").ToString() is { Length: > 0 } e) throw new McpException("authorization denied: " + e);
        var t = await TokenRequestAsync(f.TokenEndpoint, new Dictionary<string, string>
        {
            ["grant_type"] = "authorization_code", ["code"] = q.GetValueOrDefault("code").ToString(), ["redirect_uri"] = f.Redirect,
            ["client_id"] = f.ClientId, ["code_verifier"] = f.Verifier, ["resource"] = f.Resource,
        }, f.Issuer, f.Resource, f.ClientId, f.Scopes, ct);
        TokenStore.Save(f.Resource, t);
    }

    private async Task<Token> TokenRequestAsync(string ep, Dictionary<string, string> form, string issuer, string resource, string clientId, List<string> scopes, CancellationToken ct)
    {
        var method = TokenAuthMethod;
        if (clientId == ClientMetadataUrl || ClientSecret == "") method = "none"; // CIMD and secretless clients are public
        else if (method == "") method = "client_secret_basic";
        if (method == "client_secret_post") form["client_secret"] = ClientSecret;
        if (method == "client_secret_basic") form.Remove("client_id"); // RFC 6749 §2.3.1: one method only
        using var req = new HttpRequestMessage(HttpMethod.Post, ep) { Content = new FormUrlEncodedContent(form) };
        req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json")); // GitHub answers form-encoded without it
        if (method == "client_secret_basic")
            req.Headers.Authorization = new AuthenticationHeaderValue("Basic",
                Convert.ToBase64String(Encoding.UTF8.GetBytes(System.Net.WebUtility.UrlEncode(clientId) + ":" + System.Net.WebUtility.UrlEncode(ClientSecret))));
        using var resp = await Hc.SendAsync(req, ct);
        var body = await resp.Content.ReadAsStringAsync(ct);
        string at = "", rt = "", scope = "", error = "";
        long expiresIn = 0;
        if (J.ParseObject(body) is { } o)
            (at, rt, scope, error, expiresIn) = (J.Str(o["access_token"], ""), J.Str(o["refresh_token"], ""), J.Str(o["scope"], ""), J.Str(o["error"], ""), (long)(J.Num(o["expires_in"]) ?? 0));
        else if (resp.Content.Headers.ContentType?.MediaType == "application/x-www-form-urlencoded")
        {
            // RFC 6749 §5.1 mandates JSON, but some ASes (GitHub) still default to form encoding.
            var v = QueryHelpers.ParseQuery(body);
            (at, rt, scope, error) = (v.GetValueOrDefault("access_token").ToString(), v.GetValueOrDefault("refresh_token").ToString(),
                v.GetValueOrDefault("scope").ToString(), v.GetValueOrDefault("error").ToString());
            long.TryParse(v.GetValueOrDefault("expires_in").ToString(), out expiresIn);
        }
        if ((int)resp.StatusCode != 200 || at == "")
            throw new McpException($"token endpoint: HTTP {(int)resp.StatusCode} {error}"); // never include the body
        return new Token
        {
            AccessToken = at, RefreshToken = rt, Issuer = issuer, Resource = resource, ClientId = clientId,
            Scopes = scope != "" ? scope.Split(' ', StringSplitOptions.RemoveEmptyEntries) : scopes.ToArray(),
            Expiry = expiresIn > 0 ? DateTimeOffset.UtcNow.AddSeconds(expiresIn) : null,
        };
    }

    /// <summary>Header URL if present, else path-inserted well-known, else root (spec: PRM Discovery Requirements).</summary>
    internal async Task<JsonObject> FetchPrmAsync(string resource, string hinted, CancellationToken ct)
    {
        var cands = new List<string>();
        if (hinted != "") cands.Add(hinted);
        else
        {
            var u = new Uri(resource);
            var root = u.GetLeftPart(UriPartial.Authority) + ServerAuth.PrmPath;
            var p = u.AbsolutePath.TrimEnd('/');
            if (p != "") cands.Add(root + p);
            cands.Add(root);
        }
        Exception? last = null;
        foreach (var c in cands)
        {
            try
            {
                var m = await GetJsonAsync(Hc, c, ct);
                if (J.Str(m["resource"]) != resource) throw new McpException($"PRM resource {m["resource"]} does not match {resource}"); // RFC 9728 §3.3
                return m;
            }
            catch (McpException e) when (e.Message.StartsWith("PRM resource")) { throw; }
            catch (Exception e) { last = e; }
        }
        throw new McpException("protected resource metadata: " + last?.Message, last);
    }

    /// <summary>RFC 8414 then OIDC endpoints in the spec's order; validates issuer equality.</summary>
    internal static async Task<JsonObject> DiscoverAsAsync(HttpClient hc, string issuer, CancellationToken ct)
    {
        var u = new Uri(issuer);
        var b = u.GetLeftPart(UriPartial.Authority);
        var p = u.AbsolutePath.TrimEnd('/');
        string[] cands = p != ""
            ? [b + "/.well-known/oauth-authorization-server" + p, b + "/.well-known/openid-configuration" + p, b + p + "/.well-known/openid-configuration"]
            : [b + "/.well-known/oauth-authorization-server", b + "/.well-known/openid-configuration"];
        foreach (var c in cands)
        {
            JsonObject m;
            try { m = await GetJsonAsync(hc, c, ct); } catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested) { continue; }
            if (J.Str(m["issuer"]) != issuer) throw new McpException($"AS metadata issuer {m["issuer"]} != {issuer}");
            return m;
        }
        throw new McpException("no authorization server metadata for " + issuer);
    }

    internal static async Task<JsonObject> GetJsonAsync(HttpClient hc, string url, CancellationToken ct)
    {
        using var resp = await hc.GetAsync(url, ct);
        if ((int)resp.StatusCode != 200) throw new McpException($"GET {url}: HTTP {(int)resp.StatusCode}");
        return await resp.Content.ReadFromJsonAsync<JsonObject>(ct) ?? throw new McpException($"GET {url}: not a JSON object");
    }

    /// <summary>Parses <c>Bearer k="v", k2=v2</c> (RFC 6750 §3 / RFC 9110 auth-param).</summary>
    internal static Dictionary<string, string> ParseChallenge(string h)
    {
        var outp = new Dictionary<string, string>();
        h = h.Trim();
        if (h.StartsWith("Bearer")) h = h[6..];
        for (h = h.Trim(); h != "";)
        {
            var eq = h.IndexOf('=');
            if (eq < 0) break;
            var k = h[..eq].Trim().ToLowerInvariant();
            h = h[(eq + 1)..].Trim();
            string v;
            if (h.StartsWith('"'))
            {
                var end = 1;
                while (end < h.Length && h[end] != '"') { if (h[end] == '\\') end++; end++; }
                v = h[1..Math.Min(end, h.Length)].Replace("\\\"", "\"");
                h = h[Math.Min(end + 1, h.Length)..];
            }
            else
            {
                var end = h.IndexOf(',');
                if (end < 0) end = h.Length;
                (v, h) = (h[..end].Trim(), h[end..]);
            }
            outp[k] = v;
            h = h.Trim().TrimStart(',').Trim();
        }
        return outp;
    }

    internal static string B64Url(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    internal static string RandStr(int n) => B64Url(RandomNumberGenerator.GetBytes(n));
}
