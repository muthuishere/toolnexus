package ex09;

import io.github.muthuishere.toolnexus.mcp2026.LoopbackAuthorizer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * TEST HARNESS, not something an app needs: it plays the person at the browser. It follows the
 * authorization URL, submits Keycloak's login form (and a consent page if shown), stops at the
 * 127.0.0.1 redirect and delivers it to the client's loopback listener.
 */
public final class HeadlessLogin implements LoopbackAuthorizer.Opener {
    static final Pattern LOGIN_FORM = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
    static final Pattern ANY_FORM = Pattern.compile("<form[^>]*action=\"([^\"]+)\"");

    private final String user, password;
    private final HttpClient hc = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
    // A tiny cookie jar: Keycloak marks its session cookies Secure even on http://localhost, which
    // java.net.CookieManager then refuses to send back (Go's cookiejar sends them to localhost).
    private final Map<String, String> cookies = new LinkedHashMap<>();
    public int forms, consents;

    public HeadlessLogin(String user, String password) {
        this.user = user;
        this.password = password;
    }

    @Override
    public void open(String authUrl) throws Exception {
        String cb = login(authUrl);
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(cb)).build(), HttpResponse.BodyHandlers.discarding()); // to the loopback listener
    }

    /** Walks the authorize URL to the loopback callback URL (logging in if Keycloak shows the form). */
    public String login(String authUrl) throws Exception {
        HttpResponse<String> resp = get(authUrl);
        for (int page = 0; resp.statusCode() == 200 && page < 3; page++) {
            String body = resp.body();
            Matcher m = LOGIN_FORM.matcher(body), any = ANY_FORM.matcher(body);
            Map<String, String> form = new LinkedHashMap<>();
            String action;
            if (m.find()) {
                forms++;
                action = m.group(1);
                form.put("username", user);
                form.put("password", password);
                form.put("credentialId", "");
            } else if (any.find() && body.contains("name=\"accept\"")) {
                consents++; // consent page (Keycloak forces it for CIMD clients)
                action = any.group(1);
                form.put("accept", "Yes");
            } else {
                break;
            }
            URI target = resp.uri().resolve(action.replace("&amp;", "&"));
            String enc = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
            resp = follow(send(HttpRequest.newBuilder(target).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(enc))));
        }
        String loc = resp.headers().firstValue("Location").orElse("");
        if (resp.statusCode() / 100 != 3 || !URI.create(loc).getHost().equals("127.0.0.1"))
            throw new IllegalStateException("login did not redirect back (HTTP " + resp.statusCode() + ")");
        return loc;
    }

    private HttpResponse<String> get(String u) throws Exception {
        return follow(send(HttpRequest.newBuilder(URI.create(u))));
    }

    /** Follows redirects, stopping at the 127.0.0.1 callback. */
    private HttpResponse<String> follow(HttpResponse<String> r) throws Exception {
        for (int i = 0; i < 10 && r.statusCode() / 100 == 3; i++) {
            URI next = r.uri().resolve(r.headers().firstValue("Location").orElseThrow());
            if ("127.0.0.1".equals(next.getHost())) return r;
            r = send(HttpRequest.newBuilder(next));
        }
        return r;
    }

    private synchronized HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        if (!cookies.isEmpty())
            b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
        HttpResponse<String> r = hc.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String c : r.headers().allValues("Set-Cookie")) {
            String nv = c.split(";", 2)[0];
            int eq = nv.indexOf('=');
            if (eq > 0) cookies.put(nv.substring(0, eq).trim(), nv.substring(eq + 1).trim());
        }
        return r;
    }
}
