using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Toolnexus.Mcp2026;

namespace Toolnexus
{
    /// <summary>
    /// (SPEC §2A.1a) An OAuth token for one MCP resource, bound to the issuer that minted it. Its string
    /// form never shows a token value.
    /// </summary>
    public sealed record Token
    {
        public string AccessToken { get; init; } = "";
        public string? RefreshToken { get; init; }
        public DateTimeOffset? ExpiresAt { get; init; }
        public IReadOnlyList<string> Scopes { get; init; } = [];
        public string Issuer { get; init; } = "";
        public string ClientId { get; init; } = "";

        public override string ToString() => $"Token {{ Issuer = {Issuer}, ClientId = {ClientId}, Scopes = [{string.Join(" ", Scopes)}], redacted }}";
    }

    /// <summary>(SPEC §2A.1a) Where MCP OAuth tokens live, keyed by resource (the server URL). Keep them confidential.</summary>
    public interface ITokenStore
    {
        Token? Load(string resource);
        void Save(string resource, Token token);
        void Delete(string resource);
    }

    /// <summary>The default token store: in memory, per load.</summary>
    public sealed class MemoryTokenStore : ITokenStore
    {
        private readonly Dictionary<string, Token> _m = [];
        public Token? Load(string resource) { lock (_m) return _m.GetValueOrDefault(resource); }
        public void Save(string resource, Token token) { lock (_m) _m[resource] = token; }
        public void Delete(string resource) { lock (_m) _m.Remove(resource); }
    }
}

namespace Toolnexus.Mcp2026
{
    /// <summary>Thrown instead of blocking when authorization pauses (SPEC §2A.1 step 10, <c>pause</c> mode).</summary>
    internal sealed class PendingAuthorizationException(Request request) : Exception("authorization pending")
    {
        public Request Request { get; } = request;
    }

    /// <summary>The per-server <c>auth</c> config object (SPEC §2A.1).</summary>
    internal sealed class AuthConfig
    {
        public string ClientId = "", ClientSecret = "", TokenAuthMethod = "", ClientMetadataUrl = "", RedirectUri = "", Mode = "auto";
        public Dictionary<string, string> ClientIds = [];
        public bool OmitRefreshResource, AllowHttp;

        public static AuthConfig From(object? raw)
        {
            var c = new AuthConfig();
            if (raw is not IDictionary<string, object?> m) return c;
            string S(string k) => m.Get(k) as string ?? "";
            c.ClientId = S("clientId");
            c.ClientSecret = S("clientSecret");
            c.TokenAuthMethod = S("tokenAuthMethod");
            c.ClientMetadataUrl = S("clientMetadataUrl");
            c.RedirectUri = S("redirectUri");
            c.Mode = S("mode") == "pause" ? "pause" : "auto";
            c.OmitRefreshResource = m.Get("omitRefreshResource") is true;
            c.AllowHttp = m.Get("allowHttp") is true;
            if (m.Get("clientIds") is IDictionary<string, object?> ids)
                foreach (var (k, v) in ids) if (v is string s) c.ClientIds[k] = s;
            return c;
        }
    }

    /// <summary>
    /// The MCP OAuth 2.1 client (SPEC §2A.1): PRM → AS metadata → pre-registered client id or CIMD (never DCR) →
    /// code + PKCE S256 + resource → iss/state check → token → refresh (single-flight) → step-up.
    /// Tokens, codes, verifiers and secrets are never logged and never in error text.
    /// </summary>
    internal sealed class OAuthClient(AuthConfig cfg, ITokenStore store, Func<string, Task>? openUrl, HttpClient? http = null)
    {
        private readonly object _mu = new();
        private readonly SemaphoreSlim _authMu = new(1, 1); // one login / one refresh at a time
        private readonly Dictionary<string, string> _issuer = []; // resource -> confirmed issuer
        private readonly Dictionary<string, Flow> _flows = [];
        private HttpClient Hc => http ?? Conn.SharedHttp;

        /// <summary>Counts token-endpoint refresh requests (tests).</summary>
        internal int RefreshRequests;

        private sealed class Flow
        {
            public required string Resource, Issuer, ClientId, Redirect, Verifier, State, TokenEndpoint;
            public bool IssSupported;
            public required List<string> Scopes;
            public DateTimeOffset Expires;
        }

        /// <summary>The bearer to send for resource, refreshed if expired. "" = send none.</summary>
        public async Task<string> BearerAsync(string resource, CancellationToken ct)
        {
            string? iss;
            lock (_mu) iss = _issuer.GetValueOrDefault(resource);
            var t = store.Load(resource);
            if (t == null || iss == null || t.Issuer != iss) return ""; // only tokens from this server's confirmed AS
            if (!Expired(t)) return t.AccessToken;
            await _authMu.WaitAsync(ct).ConfigureAwait(false); // single-flight refresh
            try
            {
                t = store.Load(resource);
                if (t == null) return "";
                if (Expired(t) && !await RefreshAsync(resource, t, ct).ConfigureAwait(false)) return "";
                return store.Load(resource)?.AccessToken ?? "";
            }
            finally { _authMu.Release(); }
        }

        private static bool Expired(Token t) => t.ExpiresAt is { } e && DateTimeOffset.UtcNow > e.AddSeconds(-10);

        private async Task<bool> RefreshAsync(string resource, Token t, CancellationToken ct)
        {
            if (string.IsNullOrEmpty(t.RefreshToken)) return false;
            try
            {
                var md = await DiscoverAsAsync(t.Issuer, ct).ConfigureAwait(false);
                var form = new Dictionary<string, string> { ["grant_type"] = "refresh_token", ["refresh_token"] = t.RefreshToken!, ["client_id"] = t.ClientId };
                if (!cfg.OmitRefreshResource) form["resource"] = resource;
                Interlocked.Increment(ref RefreshRequests);
                var nt = await TokenRequestAsync(J.Str(md["token_endpoint"], ""), form, t.Issuer, t.ClientId, [.. t.Scopes], ct).ConfigureAwait(false);
                if (string.IsNullOrEmpty(nt.RefreshToken)) nt = nt with { RefreshToken = t.RefreshToken };
                store.Save(resource, nt);
                return true;
            }
            catch (Exception e) when (e is WireException or HttpRequestException or TaskCanceledException)
            {
                store.Delete(resource);
                return false;
            }
        }

        /// <summary>Handles a 401/403 challenge. Returns ⇒ re-send now; throws <see cref="PendingAuthorizationException"/> ⇒ paused.
        /// <paramref name="sent"/> is the access token the failed request carried ("" = none).</summary>
        public async Task ChallengeAsync(string resource, int status, string www, string sent, CancellationToken ct)
        {
            var prms = ParseChallenge(www);
            var err = prms.GetValueOrDefault("error", "");
            await _authMu.WaitAsync(ct).ConfigureAwait(false); // N concurrent 401s -> one authorization
            try
            {
                var cur = store.Load(resource);
                string? known;
                lock (_mu) known = _issuer.GetValueOrDefault(resource);
                if (cur != null && known != null && cur.Issuer == known && cur.AccessToken != sent && !Expired(cur))
                    return; // someone else authorized/refreshed while we waited
                if (status == 401 && cur is { RefreshToken.Length: > 0 } && err == "invalid_token" && known != null && cur.Issuer == known)
                {
                    if (await RefreshAsync(resource, cur, ct).ConfigureAwait(false)) return;
                    cur = null;
                }
                if (status == 403 && err != "insufficient_scope") throw new WireException("forbidden: " + err);
                var prm = await FetchPrmAsync(resource, prms.GetValueOrDefault("resource_metadata", ""), ct).ConfigureAwait(false);
                var issuer = J.Strings(prm["authorization_servers"]).FirstOrDefault()
                    ?? throw new WireException("PRM lists no authorization_servers");
                if (!cfg.AllowHttp && !issuer.StartsWith("https://")) throw new WireException("authorization server must use https");
                var md = await DiscoverAsAsync(issuer, ct).ConfigureAwait(false);
                if (!J.Strings(md["code_challenge_methods_supported"]).Contains("S256"))
                    throw new WireException("authorization server does not advertise PKCE S256; refusing");
                lock (_mu) _issuer[resource] = issuer;
                if (status == 401 && cur != null && cur.Issuer == issuer && err == "" && cur.AccessToken != sent)
                    return; // first request after a restart: the issuer is now confirmed, so the stored token may be sent
                var scopes = prms.GetValueOrDefault("scope", "").Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
                if (scopes.Count == 0) scopes.AddRange(J.Strings(prm["scopes_supported"]));
                if (cur != null && status == 403) scopes.AddRange(cur.Scopes.Where(s => !scopes.Contains(s)).ToList());
                var cimd = J.True(md["client_id_metadata_document_supported"]);
                var clientId = cfg.ClientIds.GetValueOrDefault(issuer, "");
                if (clientId == "") clientId = cfg.ClientId;
                if (clientId == "" && cfg.ClientMetadataUrl != "" && cimd) clientId = cfg.ClientMetadataUrl;
                if (clientId == "") // no DCR, ever
                {
                    var why = cimd ? "auth.clientMetadataUrl is not set" : "it does not support Client ID Metadata Documents";
                    throw new WireException($"no client identity for {issuer}: {why} and no client id is configured — register an app with {issuer} and set auth.clientId (dynamic client registration is not supported)");
                }
                var pause = cfg.Mode == "pause";
                if (pause && cfg.RedirectUri == "") throw new WireException("auth.redirectUri is required for pause mode");
                Loopback? loop = pause ? null : await Loopback.StartAsync(cfg.RedirectUri, ct).ConfigureAwait(false);
                try
                {
                    var f = new Flow
                    {
                        Resource = resource, Issuer = issuer, ClientId = clientId, Redirect = loop?.RedirectUri ?? cfg.RedirectUri,
                        Verifier = RandStr(32), State = RandStr(16), TokenEndpoint = J.Str(md["token_endpoint"], ""), Scopes = scopes,
                        IssSupported = J.True(md["authorization_response_iss_parameter_supported"]),
                        Expires = DateTimeOffset.UtcNow.AddMinutes(pause ? 10 : 5),
                    };
                    var q = new List<KeyValuePair<string, string>>
                    {
                        new("client_id", clientId), new("code_challenge", B64Url(SHA256.HashData(Encoding.ASCII.GetBytes(f.Verifier)))),
                        new("code_challenge_method", "S256"), new("redirect_uri", f.Redirect), new("resource", resource), new("response_type", "code"),
                    };
                    if (scopes.Count > 0) q.Add(new("scope", string.Join(' ', scopes)));
                    q.Add(new("state", f.State));
                    var url = J.Str(md["authorization_endpoint"], "") + "?" + string.Join('&', q.Select(kv => Uri.EscapeDataString(kv.Key) + "=" + Uri.EscapeDataString(kv.Value)));
                    if (pause)
                    {
                        var req = new Request
                        {
                            Id = "auth-" + RandStr(12), Kind = "authorization", Prompt = "Authorize access to " + resource, Url = url,
                            ExpiresAt = f.Expires.UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ssZ"),
                        };
                        lock (_mu)
                        {
                            foreach (var id in _flows.Where(kv => DateTimeOffset.UtcNow > kv.Value.Expires).Select(kv => kv.Key).ToList())
                                _flows.Remove(id);
                            _flows[req.Id] = f;
                        }
                        throw new PendingAuthorizationException(req);
                    }
                    var cb = await loop!.WaitAsync(url, openUrl ?? OpenBrowser, ct).ConfigureAwait(false);
                    await CompleteAsync(f, cb, ct).ConfigureAwait(false);
                }
                finally { if (loop != null) await loop.DisposeAsync().ConfigureAwait(false); }
            }
            finally { _authMu.Release(); }
        }

        /// <summary>Finishes a paused authorization from the callback URL the AS redirected to. The caller then
        /// re-sends the paused request.</summary>
        public async Task ResumeAsync(string id, string callbackUrl, CancellationToken ct)
        {
            Flow? f;
            lock (_mu) _flows.Remove(id, out f);
            if (f == null) throw new WireException($"unknown pending authorization \"{id}\"");
            if (DateTimeOffset.UtcNow > f.Expires) throw new WireException("pending authorization expired");
            await CompleteAsync(f, callbackUrl, ct).ConfigureAwait(false);
        }

        private async Task CompleteAsync(Flow f, string callbackUrl, CancellationToken ct)
        {
            Dictionary<string, string> q;
            try { q = J.ParseQuery(new Uri(callbackUrl).Query); }
            catch (UriFormatException) { throw new WireException("authorization response is not a URL"); }
            if (q.TryGetValue("iss", out var iss))
            {
                if (iss != f.Issuer) throw new WireException("authorization response iss mismatch (possible mix-up attack)");
            }
            else if (f.IssSupported) throw new WireException("authorization response missing iss");
            if (q.GetValueOrDefault("state", "") != f.State) throw new WireException("authorization response state mismatch");
            if (q.GetValueOrDefault("error", "") is { Length: > 0 } e) throw new WireException("authorization denied: " + e);
            var t = await TokenRequestAsync(f.TokenEndpoint, new Dictionary<string, string>
            {
                ["grant_type"] = "authorization_code", ["code"] = q.GetValueOrDefault("code", ""), ["redirect_uri"] = f.Redirect,
                ["client_id"] = f.ClientId, ["code_verifier"] = f.Verifier, ["resource"] = f.Resource,
            }, f.Issuer, f.ClientId, f.Scopes, ct).ConfigureAwait(false);
            store.Save(f.Resource, t);
            lock (_mu) _issuer[f.Resource] = f.Issuer;
        }

        private async Task<Token> TokenRequestAsync(string ep, Dictionary<string, string> form, string issuer, string clientId, List<string> scopes, CancellationToken ct)
        {
            var method = cfg.TokenAuthMethod;
            if (clientId == cfg.ClientMetadataUrl || cfg.ClientSecret == "") method = "none"; // CIMD and secretless clients are public
            else if (method == "") method = "client_secret_basic";
            if (method == "client_secret_post") form["client_secret"] = cfg.ClientSecret;
            if (method == "client_secret_basic") form.Remove("client_id");
            using var req = new HttpRequestMessage(HttpMethod.Post, ep) { Content = new FormUrlEncodedContent(form) };
            req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
            if (method == "client_secret_basic")
                req.Headers.Authorization = new AuthenticationHeaderValue("Basic",
                    Convert.ToBase64String(Encoding.UTF8.GetBytes(WebUtility.UrlEncode(clientId) + ":" + WebUtility.UrlEncode(cfg.ClientSecret))));
            using var resp = await Hc.SendAsync(req, ct).ConfigureAwait(false);
            var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            string at = "", rt = "", scope = "", error = "";
            long expiresIn = 0;
            if (J.ParseObject(body) is { } o)
                (at, rt, scope, error, expiresIn) = (J.Str(o["access_token"], ""), J.Str(o["refresh_token"], ""), J.Str(o["scope"], ""), J.Str(o["error"], ""), (long)(J.Num(o["expires_in"]) ?? 0));
            else if (resp.Content.Headers.ContentType?.MediaType == "application/x-www-form-urlencoded")
            {
                var v = J.ParseQuery(body); // RFC 6749 mandates JSON; GitHub still answers form-encoded
                (at, rt, scope, error) = (v.GetValueOrDefault("access_token", ""), v.GetValueOrDefault("refresh_token", ""), v.GetValueOrDefault("scope", ""), v.GetValueOrDefault("error", ""));
                long.TryParse(v.GetValueOrDefault("expires_in", ""), out expiresIn);
            }
            if ((int)resp.StatusCode != 200 || at == "")
                throw new WireException($"token endpoint: HTTP {(int)resp.StatusCode} {error}".TrimEnd()); // never the body
            return new Token
            {
                AccessToken = at, RefreshToken = rt == "" ? null : rt, Issuer = issuer, ClientId = clientId,
                Scopes = scope != "" ? scope.Split(' ', StringSplitOptions.RemoveEmptyEntries) : scopes.ToArray(),
                ExpiresAt = expiresIn > 0 ? DateTimeOffset.UtcNow.AddSeconds(expiresIn) : null,
            };
        }

        private const string PrmPath = "/.well-known/oauth-protected-resource";

        private async Task<JsonObject> FetchPrmAsync(string resource, string hinted, CancellationToken ct)
        {
            var cands = new List<string>();
            if (hinted != "") cands.Add(hinted);
            else
            {
                var u = new Uri(resource);
                var root = u.GetLeftPart(UriPartial.Authority) + PrmPath;
                var p = u.AbsolutePath.TrimEnd('/');
                if (p != "") cands.Add(root + p);
                cands.Add(root);
            }
            Exception? last = null;
            foreach (var c in cands)
            {
                JsonObject m;
                try { m = await GetJsonAsync(c, ct).ConfigureAwait(false); }
                catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested) { last = e; continue; }
                if (J.Str(m["resource"]) != resource) throw new WireException($"PRM resource {J.Str(m["resource"], "")} does not match {resource}");
                return m;
            }
            throw new WireException("protected resource metadata: " + last?.Message, last);
        }

        private async Task<JsonObject> DiscoverAsAsync(string issuer, CancellationToken ct)
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
                try { m = await GetJsonAsync(c, ct).ConfigureAwait(false); }
                catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested) { continue; }
                if (J.Str(m["issuer"]) != issuer) throw new WireException($"AS metadata issuer {J.Str(m["issuer"], "")} != {issuer}");
                return m;
            }
            throw new WireException("no authorization server metadata for " + issuer);
        }

        private async Task<JsonObject> GetJsonAsync(string url, CancellationToken ct)
        {
            using var resp = await Hc.GetAsync(url, ct).ConfigureAwait(false);
            if ((int)resp.StatusCode != 200) throw new WireException($"GET {url}: HTTP {(int)resp.StatusCode}");
            return J.ParseObject(await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false)) ?? throw new WireException($"GET {url}: not a JSON object");
        }

        /// <summary>Parses <c>Bearer k="v", k2=v2</c> (RFC 6750 §3).</summary>
        internal static Dictionary<string, string> ParseChallenge(string h)
        {
            var outp = new Dictionary<string, string>();
            h = h.Trim();
            if (h.StartsWith("Bearer", StringComparison.OrdinalIgnoreCase)) h = h[6..];
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

        private static Task OpenBrowser(string url)
        {
            var psi = OperatingSystem.IsMacOS() ? new ProcessStartInfo("open") : OperatingSystem.IsWindows()
                ? new ProcessStartInfo("rundll32") : new ProcessStartInfo("xdg-open");
            if (OperatingSystem.IsWindows()) psi.ArgumentList.Add("url.dll,FileProtocolHandler");
            psi.ArgumentList.Add(url);
            psi.UseShellExecute = false;
            Process.Start(psi)?.Dispose();
            return Task.CompletedTask;
        }
    }

    /// <summary>The <c>auto</c>-mode callback receiver: <c>http://127.0.0.1:&lt;random&gt;/callback</c> (RFC 8252 §7.3), BCL sockets only.</summary>
    internal sealed class Loopback : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly string _path;
        private readonly CancellationTokenSource _stop = new();
        private readonly TaskCompletionSource<string> _cb = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public string RedirectUri { get; }

        private Loopback(TcpListener l, string redirect, string path) { _listener = l; RedirectUri = redirect; _path = path; }

        public static Task<Loopback> StartAsync(string configured, CancellationToken ct)
        {
            var port = 0;
            var path = "/callback";
            if (configured != "" && Uri.TryCreate(configured, UriKind.Absolute, out var u)) { port = u.Port; path = u.AbsolutePath; }
            var l = new TcpListener(IPAddress.Loopback, port);
            l.Start();
            var actual = ((IPEndPoint)l.LocalEndpoint).Port;
            var lb = new Loopback(l, $"http://127.0.0.1:{actual}{path}", path);
            _ = Task.Run(lb.AcceptLoopAsync);
            return Task.FromResult(lb);
        }

        private async Task AcceptLoopAsync()
        {
            while (!_stop.IsCancellationRequested)
            {
                TcpClient c;
                try { c = await _listener.AcceptTcpClientAsync(_stop.Token).ConfigureAwait(false); }
                catch { return; }
                _ = Task.Run(async () =>
                {
                    using (c)
                    {
                        try
                        {
                            var s = c.GetStream();
                            var reader = new StreamReader(s, Encoding.ASCII);
                            var line = await reader.ReadLineAsync().ConfigureAwait(false) ?? "";
                            string? hdr;
                            while (!string.IsNullOrEmpty(hdr = await reader.ReadLineAsync().ConfigureAwait(false))) { }
                            var parts = line.Split(' ');
                            var target = parts.Length > 1 ? parts[1] : "";
                            var qi = target.IndexOf('?');
                            var path = qi < 0 ? target : target[..qi];
                            var ok = parts[0] == "GET" && path == _path;
                            var body = ok ? "Authorization received. You can close this window." : "not found";
                            var resp = $"HTTP/1.1 {(ok ? "200 OK" : "404 Not Found")}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {Encoding.UTF8.GetByteCount(body)}\r\nConnection: close\r\n\r\n{body}";
                            await s.WriteAsync(Encoding.UTF8.GetBytes(resp)).ConfigureAwait(false);
                            if (ok) _cb.TrySetResult(RedirectUri.Split('?')[0][..^_path.Length] + target);
                        }
                        catch { /* a bad client never breaks the flow */ }
                    }
                });
            }
        }

        /// <summary>Opens the URL and waits up to 5 minutes for the callback.</summary>
        public async Task<string> WaitAsync(string url, Func<string, Task> open, CancellationToken ct)
        {
            var timeout = Task.Delay(TimeSpan.FromMinutes(5), ct);
            var opened = Task.Run(() => open(url), ct); // the opener may block (tests follow redirects)
            var first = await Task.WhenAny(_cb.Task, opened, timeout).ConfigureAwait(false);
            if (first == opened)
            {
                if (opened.Exception is { } ex) throw new WireException("open authorization URL: " + ex.InnerException!.Message, ex.InnerException);
                first = await Task.WhenAny(_cb.Task, timeout).ConfigureAwait(false);
            }
            if (first == _cb.Task) return await _cb.Task.ConfigureAwait(false);
            ct.ThrowIfCancellationRequested();
            throw new WireException("authorization timed out");
        }

        public ValueTask DisposeAsync()
        {
            _stop.Cancel();
            try { _listener.Stop(); } catch { }
            return ValueTask.CompletedTask;
        }
    }
}
