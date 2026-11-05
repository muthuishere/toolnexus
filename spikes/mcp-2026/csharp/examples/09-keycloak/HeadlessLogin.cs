using System.Net;
using System.Text.RegularExpressions;

// TEST HARNESS, not something an app needs: it plays the person at the browser. It follows the
// authorization URL, submits Keycloak's login form (and a consent page if shown), stops at the
// 127.0.0.1 redirect and delivers it to the client's loopback listener.
static partial class HeadlessLogin
{
    [GeneratedRegex("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"")] private static partial Regex LoginForm();
    [GeneratedRegex("<form[^>]*action=\"([^\"]+)\"")] private static partial Regex AnyForm();

    public static Func<string, Task> Open(string user, string password)
    {
        var login = Login(user, password);
        return async authUrl =>
        {
            using var cb = await new HttpClient().GetAsync(await login(authUrl)); // hand the callback to the loopback listener
        };
    }

    /// <summary>Logs in and returns the 127.0.0.1 callback URL without visiting it. One cookie jar = one SSO session.</summary>
    public static Func<string, Task<Uri>> Login(string user, string password)
    {
        // Keycloak marks its cookies Secure even on http://localhost (browsers treat localhost as secure);
        // CookieContainer would drop them over http, so this harness keeps a minimal jar of its own.
        var jar = new Dictionary<string, string>();
        var hc = new HttpClient(new JarHandler(jar)) { Timeout = TimeSpan.FromSeconds(15) };
        return async authUrl =>
        {
            var (resp, body, url) = await Follow(hc, new Uri(authUrl), null);
            for (var page = 0; resp == 200 && page < 3; page++)
            {
                string action;
                Dictionary<string, string> form;
                if (LoginForm().Match(body) is { Success: true } m)
                    (action, form) = (m.Groups[1].Value, new() { ["username"] = user, ["password"] = password, ["credentialId"] = "" });
                else if (AnyForm().Match(body) is { Success: true } m2 && body.Contains("name=\"accept\""))
                    (action, form) = (m2.Groups[1].Value, new() { ["accept"] = "Yes" });
                else break;
                (resp, body, url) = await Follow(hc, new Uri(url, WebUtility.HtmlDecode(action)), form);
            }
            if (resp != 302) throw new Exception($"login did not redirect back (HTTP {resp}): {Regex.Match(body, "<title>([^<]*)").Groups[1].Value} {Regex.Match(body, "(?s)kc-feedback-text[^>]*>([^<]*)").Groups[1].Value.Trim()} {url}");
            return url;
        };
    }

    // Follows redirects until a 200 page or a redirect to 127.0.0.1 (returned as 302 + its URL).
    private static async Task<(int, string, Uri)> Follow(HttpClient hc, Uri u, Dictionary<string, string>? form)
    {
        for (var i = 0; i < 10; i++)
        {
            using var r = form != null ? await hc.PostAsync(u, new FormUrlEncodedContent(form)) : await hc.GetAsync(u);
            form = null;
            if ((int)r.StatusCode is >= 300 and < 400 && r.Headers.Location is { } loc)
            {
                var next = new Uri(u, loc);
                if (next.Host == "127.0.0.1") return (302, "", next);
                u = next;
                continue;
            }
            return ((int)r.StatusCode, await r.Content.ReadAsStringAsync(), u);
        }
        throw new Exception("too many redirects");
    }

    private sealed class JarHandler(Dictionary<string, string> jar) : DelegatingHandler(new HttpClientHandler { UseCookies = false, AllowAutoRedirect = false })
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage r, CancellationToken ct)
        {
            lock (jar) if (jar.Count > 0) r.Headers.Add("Cookie", string.Join("; ", jar.Select(kv => kv.Key + "=" + kv.Value)));
            var resp = await base.SendAsync(r, ct);
            if (resp.Headers.TryGetValues("Set-Cookie", out var cs))
                foreach (var c in cs)
                {
                    var nv = c.Split(';')[0];
                    var i = nv.IndexOf('=');
                    if (i > 0) lock (jar) jar[nv[..i].Trim()] = nv[(i + 1)..];
                }
            return resp;
        }
    }
}
