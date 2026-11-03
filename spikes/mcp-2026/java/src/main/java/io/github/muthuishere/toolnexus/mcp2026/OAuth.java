package io.github.muthuishere.toolnexus.mcp2026;

import com.sun.net.httpserver.HttpHandler;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;

/**
 * Client-side OAuth for MCP 2026-07-28 (basic/authorization); attach with {@link Client#auth(OAuth)}.
 * Flow: 401/403 -> WWW-Authenticate -> PRM (RFC 9728) -> AS metadata (RFC 8414 / OIDC)
 * -> client id (pre-registered | CIMD; never DCR) -> code + PKCE S256 + resource (RFC 8707)
 * -> iss (RFC 9207) + state check -> token -> retry with Bearer.
 */
public final class OAuth {
    /** Serves one tool call to a browser user; see {@link #webHandler}. */
    @FunctionalInterface
    public interface WebCall {
        Map<String, Object> call(Map<String, String> query) throws Exception;
    }

    private Authorizer authorizer;          // null = LoopbackAuthorizer (system browser + loopback callback)
    private String clientId = "";           // pre-registered client_id for any issuer not in clientIds
    private String clientSecret = "";       // optional: makes the pre-registered client confidential. Never logged.
    private String tokenAuthMethod = "";    // none | client_secret_basic | client_secret_post; "" = basic if secret else none
    private boolean omitRefreshResource;    // Entra rejects `resource` on refresh (python-sdk #2578)
    private TokenStore store = new MemoryTokenStore();
    private Map<String, String> clientIds = Map.of(); // issuer -> pre-registered client_id (bound to that issuer)
    private String clientMetadataUrl = "";  // CIMD client_id (HTTPS URL), used when the AS advertises support
    private HttpClient http;                // PRM / AS / token calls; null = shared default client
    private boolean allowHttp;              // allow non-HTTPS AS endpoints (dev only; spec requires HTTPS)

    private final Object mu = new Object();
    private final ReentrantLock authMu = new ReentrantLock(); // one login / one refresh at a time
    private final Map<String, String> issuer = new HashMap<>(); // resource -> discovered issuer
    private final Map<String, Flow> flows = new HashMap<>();
    private static final SecureRandom RNG = new SecureRandom();

    static final class Flow {
        String resource, issuer, clientId, redirect, verifier, state, tokenEndpoint;
        boolean issSupported;
        List<String> scopes;
        Instant expires;
        Supplier<Map<String, Object>> retry;
    }

    public OAuth authorizer(Authorizer a) { this.authorizer = a; return this; }
    public OAuth clientId(String id) { this.clientId = id; return this; }
    public OAuth clientSecret(String s) { this.clientSecret = s; return this; }
    public OAuth tokenAuthMethod(String m) { this.tokenAuthMethod = m; return this; }
    public OAuth omitRefreshResource(boolean b) { this.omitRefreshResource = b; return this; }
    public OAuth store(TokenStore s) { this.store = s; return this; }
    public OAuth clientIds(Map<String, String> m) { this.clientIds = m == null ? Map.of() : m; return this; }
    public OAuth clientMetadataUrl(String u) { this.clientMetadataUrl = u; return this; }
    public OAuth http(HttpClient hc) { this.http = hc; return this; }
    public OAuth allowHttp(boolean b) { this.allowHttp = b; return this; }
    public TokenStore store() { return store; }

    HttpClient hc() { return http != null ? http : DevTls.SHARED; }

    // test hook: the flow of a pending id
    Flow flow(String id) { synchronized (mu) { return flows.get(id); } }

    /** The token to send for resource, refreshing if expired. "" = send none. */
    String bearer(String resource) {
        String iss;
        synchronized (mu) { iss = issuer.get(resource); }
        Token t = store.load(resource).orElse(null);
        if (t == null || iss == null || !iss.equals(t.issuer())) return ""; // only tokens from this server's AS
        if (t.expired()) {
            authMu.lock(); // one refresh for N concurrent callers (refresh tokens rotate)
            try {
                t = store.load(resource).orElse(null);
                if (t == null) return "";
                if (t.expired()) {
                    try {
                        refresh(t);
                    } catch (Exception e) {
                        return "";
                    }
                }
                t = store.load(resource).orElse(null);
                if (t == null) return "";
            } finally {
                authMu.unlock();
            }
        }
        return t.accessToken();
    }

    private void refresh(Token t) {
        if (t.refreshToken() == null || t.refreshToken().isEmpty()) throw new McpException("no refresh token");
        Map<String, Object> md = discoverAS(hc(), t.issuer());
        Map<String, String> form = new TreeMap<>(Map.of("grant_type", "refresh_token", "refresh_token", t.refreshToken(), "client_id", t.clientId()));
        if (!omitRefreshResource) form.put("resource", t.resource());
        Token nt;
        try {
            nt = tokenRequest(str(md.get("token_endpoint")), form, t.issuer(), t.resource(), t.clientId(), t.scopes());
        } catch (RuntimeException e) {
            store.delete(t.resource());
            throw e;
        }
        if (nt.refreshToken() == null || nt.refreshToken().isEmpty())
            nt = new Token(nt.accessToken(), t.refreshToken(), nt.expiry(), nt.scopes(), nt.issuer(), nt.resource(), nt.clientId());
        store.save(t.resource(), nt);
    }

    /** Handles a 401/403. Returns normally = retry now; throws PendingException = paused. */
    void challenge(String resource, int status, String www, String sent, Supplier<Map<String, Object>> retry) {
        Map<String, String> params = parseChallenge(www);
        authMu.lock(); // N concurrent 401s -> one authorization, the rest retry with its token
        try {
            challengeLocked(resource, status, params, sent, retry);
        } finally {
            authMu.unlock();
        }
    }

    private void challengeLocked(String resource, int status, Map<String, String> params, String sent, Supplier<Map<String, Object>> retry) {
        Token cur = store.load(resource).orElse(null);
        String known;
        synchronized (mu) { known = issuer.get(resource); }
        if (cur != null && known != null && known.equals(cur.issuer()) && !cur.accessToken().equals(sent) && !cur.expired())
            return; // someone else refreshed/authorized while we waited
        String err = params.getOrDefault("error", "");
        if (status == 401 && cur != null && cur.refreshToken() != null && !cur.refreshToken().isEmpty() && err.equals("invalid_token")) {
            try {
                refresh(cur);
                return;
            } catch (RuntimeException e) {
                cur = null;
            }
        }
        if (status == 403 && !err.equals("insufficient_scope")) throw new McpException("forbidden: " + err);
        Map<String, Object> prm = fetchPRM(resource, params.getOrDefault("resource_metadata", ""));
        List<Object> servers = list(prm.get("authorization_servers"));
        if (servers.isEmpty()) throw new McpException("PRM lists no authorization_servers");
        String iss = str(servers.get(0)); // RFC 9728 §7.6: client's choice; we take the first
        if (!allowHttp && !iss.startsWith("https://")) throw new McpException("authorization server must use https");
        Map<String, Object> md = discoverAS(hc(), iss);
        if (!list(md.get("code_challenge_methods_supported")).contains("S256"))
            throw new McpException("authorization server does not advertise PKCE S256; refusing");
        synchronized (mu) { issuer.put(resource, iss); }
        if (status == 401 && cur != null && iss.equals(cur.issuer()) && err.isEmpty())
            return; // first request after restart: we now know the issuer, so the stored token may be sent
        // Scope selection: challenge scope, else scopes_supported; on step-up union with previous.
        List<String> scopes = new ArrayList<>(fields(params.getOrDefault("scope", "")));
        if (scopes.isEmpty()) for (Object s : list(prm.get("scopes_supported"))) if (s instanceof String x) scopes.add(x);
        if (cur != null && status == 403 && cur.scopes() != null)
            for (String s : cur.scopes()) if (!scopes.contains(s)) scopes.add(s);
        String cid = clientIds.getOrDefault(iss, "");
        if (cid.isEmpty()) cid = clientId;
        boolean cimd = Boolean.TRUE.equals(md.get("client_id_metadata_document_supported"));
        if (cid.isEmpty() && !clientMetadataUrl.isEmpty() && cimd) cid = clientMetadataUrl;
        if (cid.isEmpty()) { // no DCR, ever: the user registers an app once and configures its id
            String why = cimd ? "OAuth.ClientMetadataURL is not set" : "it does not support Client ID Metadata Documents";
            throw new McpException("no client identity for " + iss + ": " + why + " and no client id is configured — register an app with "
                    + iss + " and set OAuth.ClientID (dynamic client registration is not supported)");
        }
        Authorizer az = authorizer != null ? authorizer : new LoopbackAuthorizer();
        Authorizer.Session sess;
        try {
            sess = az.begin();
        } catch (Exception e) {
            throw e instanceof McpException m ? m : new McpException(e.getMessage(), e);
        }
        Flow f = new Flow();
        f.resource = resource; f.issuer = iss; f.clientId = cid; f.redirect = sess.redirectUri();
        f.verifier = randStr(32); f.state = randStr(16); f.tokenEndpoint = str(md.get("token_endpoint"));
        f.scopes = scopes; f.retry = retry;
        f.issSupported = Boolean.TRUE.equals(md.get("authorization_response_iss_parameter_supported"));
        f.expires = Instant.now().plus(Duration.ofMinutes(10));
        Map<String, String> q = new TreeMap<>();
        q.put("response_type", "code"); q.put("client_id", cid); q.put("redirect_uri", f.redirect); q.put("state", f.state);
        q.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(f.verifier)));
        q.put("code_challenge_method", "S256"); q.put("resource", resource);
        if (!scopes.isEmpty()) q.put("scope", String.join(" ", scopes));
        Pending p = new Pending("auth-" + randStr(12), "authorization", "Authorize access to " + resource,
                str(md.get("authorization_endpoint")) + "?" + encodeForm(q), f.expires.truncatedTo(ChronoUnit.SECONDS).toString());
        String cb;
        try {
            cb = sess.await(p);
        } catch (Authorizer.Pause pause) {
            synchronized (mu) {
                flows.values().removeIf(old -> Instant.now().isAfter(old.expires)); // drop abandoned pendings
                flows.put(p.id(), f);
            }
            throw new PendingException(p);
        } catch (Exception e) {
            sess.close();
            throw e instanceof McpException m ? m : new McpException(e.getMessage(), e);
        }
        sess.close();
        complete(f, cb);
    }

    void setRetry(String id, Supplier<Map<String, Object>> f) {
        synchronized (mu) {
            Flow fl = flows.get(id);
            if (fl != null) fl.retry = f;
        }
    }

    /** Finishes a paused authorization and re-runs the original operation, returning its result. */
    public Map<String, Object> resume(String id, String callbackUrl) {
        Flow f;
        synchronized (mu) { f = flows.remove(id); }
        if (f == null) throw new McpException("unknown pending authorization \"" + id + "\"");
        if (Instant.now().isAfter(f.expires)) throw new McpException("pending authorization expired");
        complete(f, callbackUrl);
        return f.retry.get();
    }

    /** Resume for web apps: the pending is found by the callback's state. */
    public Map<String, Object> resumeCallback(String callbackUrl) {
        String st = HttpReq.parseQuery(URI.create(callbackUrl).getRawQuery()).getOrDefault("state", "");
        String id = "";
        synchronized (mu) {
            for (var e : flows.entrySet()) if (!st.isEmpty() && e.getValue().state.equals(st)) id = e.getKey();
        }
        return resume(id, callbackUrl);
    }

    /**
     * Serves one tool call to a browser user, pausing for authorization when needed: call runs; if it
     * is pending, the browser is redirected to the AS; when the AS redirects back to this same handler
     * (use its URL as the PauseAuthorizer redirect), the original call is resumed and its text written.
     * Errors become 502.
     */
    public HttpHandler webHandler(WebCall call) {
        return ex -> {
            HttpReq r = HttpReq.of(ex, 0);
            Map<String, String> q = r.query();
            HttpResp resp;
            try {
                Map<String, Object> res = !q.getOrDefault("state", "").isEmpty() && (q.containsKey("code") || q.containsKey("error"))
                        ? resumeCallback(ex.getRequestURI().toString())
                        : call.call(q);
                resp = HttpResp.text(200, text(res));
            } catch (Exception e) {
                Pending p = Pending.of(e);
                resp = p != null ? HttpResp.redirect(p.url()) : HttpResp.text(502, e.getMessage() + "\n");
            }
            resp.writeTo(ex);
        };
    }

    private void complete(Flow f, String callbackUrl) {
        Map<String, String> q = HttpReq.parseQuery(URI.create(callbackUrl).getRawQuery());
        // RFC 9207 §2.4 (spec: Authorization Response Validation) — before anything else, error responses included.
        if (q.containsKey("iss")) {
            if (!q.get("iss").equals(f.issuer)) // simple string comparison, no normalization
                throw new McpException("authorization response iss mismatch (possible mix-up attack)");
        } else if (f.issSupported) {
            throw new McpException("authorization response missing iss");
        }
        if (!f.state.equals(q.getOrDefault("state", ""))) throw new McpException("authorization response state mismatch");
        if (!q.getOrDefault("error", "").isEmpty()) throw new McpException("authorization denied: " + q.get("error"));
        Map<String, String> form = new TreeMap<>();
        form.put("grant_type", "authorization_code"); form.put("code", q.getOrDefault("code", ""));
        form.put("redirect_uri", f.redirect); form.put("client_id", f.clientId);
        form.put("code_verifier", f.verifier); form.put("resource", f.resource);
        store.save(f.resource, tokenRequest(f.tokenEndpoint, form, f.issuer, f.resource, f.clientId, f.scopes));
    }

    private Token tokenRequest(String ep, Map<String, String> form, String iss, String resource, String cid, List<String> scopes) {
        String method = tokenAuthMethod;
        if (cid.equals(clientMetadataUrl) || clientSecret.isEmpty()) method = "none"; // CIMD + secretless clients are public
        else if (method.isEmpty()) method = "client_secret_basic";
        if (method.equals("client_secret_post")) form.put("client_secret", clientSecret);
        if (method.equals("client_secret_basic")) form.remove("client_id"); // RFC 6749 §2.3.1: one method only
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(ep)).timeout(Client.DEFAULT_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json") // GitHub answers form-encoded without it
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)));
        if (method.equals("client_secret_basic")) {
            String cred = qesc(cid) + ":" + qesc(clientSecret);
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<byte[]> resp;
        try {
            resp = hc().send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new McpException("token endpoint: " + e.getMessage(), e);
        }
        String at = "", rt = "", scope = "", error = "";
        long expiresIn = 0;
        boolean ok = false;
        try {
            Map<String, Object> m = map(parse(resp.body()));
            if (m != null) {
                at = str(m.get("access_token")); rt = str(m.get("refresh_token")); scope = str(m.get("scope")); error = str(m.get("error"));
                expiresIn = m.get("expires_in") instanceof Number n ? n.longValue() : 0;
                ok = true;
            }
        } catch (Exception e) {
            // RFC 6749 §5.1 mandates JSON, but some ASes (GitHub) still default to form encoding.
            if (resp.headers().firstValue("Content-Type").orElse("").startsWith("application/x-www-form-urlencoded")) {
                Map<String, String> v = HttpReq.parseQuery(new String(resp.body(), StandardCharsets.UTF_8));
                at = v.getOrDefault("access_token", ""); rt = v.getOrDefault("refresh_token", "");
                scope = v.getOrDefault("scope", ""); error = v.getOrDefault("error", "");
                try { expiresIn = Long.parseLong(v.getOrDefault("expires_in", "0")); } catch (NumberFormatException ignored) { }
                ok = true;
            }
        }
        if (!ok || resp.statusCode() != 200 || at.isEmpty())
            throw new McpException("token endpoint: HTTP " + resp.statusCode() + " " + error); // never include the body
        return new Token(at, rt, expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null,
                scope.isEmpty() ? scopes : fields(scope), iss, resource, cid);
    }

    /** Header URL if present, else path-inserted well-known, else root (spec: PRM Discovery Requirements). */
    Map<String, Object> fetchPRM(String resource, String hinted) {
        List<String> cands = new ArrayList<>();
        if (!hinted.isEmpty()) cands.add(hinted);
        else {
            URI u = URI.create(resource);
            String root = u.getScheme() + "://" + u.getRawAuthority() + ServerAuth.PRM_PATH;
            String p = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/$", "");
            if (!p.isEmpty()) cands.add(root + p);
            cands.add(root);
        }
        RuntimeException last = null;
        for (String c : cands) {
            try {
                Map<String, Object> m = getJson(hc(), c);
                if (!resource.equals(m.get("resource"))) // RFC 9728 §3.3
                    throw new McpException("PRM resource " + m.get("resource") + " does not match " + resource); // not retried with the next candidate
                return m;
            } catch (McpException e) {
                if (e.getMessage().startsWith("PRM resource ")) throw e;
                last = e;
            }
        }
        throw new McpException("protected resource metadata: " + (last == null ? "" : last.getMessage()), last);
    }

    /** RFC 8414 then OIDC endpoints in the spec's order; validates issuer equality. */
    static Map<String, Object> discoverAS(HttpClient hc, String iss) {
        URI u = URI.create(iss);
        String base = u.getScheme() + "://" + u.getRawAuthority();
        String p = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/$", "");
        List<String> cands = !p.isEmpty()
                ? List.of(base + "/.well-known/oauth-authorization-server" + p, base + "/.well-known/openid-configuration" + p,
                base + p + "/.well-known/openid-configuration")
                : List.of(base + "/.well-known/oauth-authorization-server", base + "/.well-known/openid-configuration");
        for (String c : cands) {
            Map<String, Object> m;
            try {
                m = getJson(hc, c);
            } catch (McpException e) {
                continue;
            }
            if (!iss.equals(m.get("issuer"))) throw new McpException("AS metadata issuer " + m.get("issuer") + " != " + iss);
            return m;
        }
        throw new McpException("no authorization server metadata for " + iss);
    }

    static Map<String, Object> getJson(HttpClient hc, String u) {
        try {
            HttpResponse<byte[]> r = hc.send(HttpRequest.newBuilder(URI.create(u)).timeout(Client.DEFAULT_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new McpException("GET " + u + ": HTTP " + r.statusCode());
            Map<String, Object> m = map(parse(r.body()));
            if (m == null) throw new McpException("GET " + u + ": not a JSON object");
            return m;
        } catch (McpException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted", e);
        } catch (Exception e) {
            throw new McpException("GET " + u + ": " + e.getMessage(), e);
        }
    }

    /** Parses {@code Bearer k="v", k2=v2} (RFC 6750 §3 / RFC 9110 auth-param). */
    static Map<String, String> parseChallenge(String h) {
        Map<String, String> out = new HashMap<>();
        h = h.trim();
        if (h.startsWith("Bearer")) h = h.substring(6);
        h = h.trim();
        while (!h.isEmpty()) {
            int eq = h.indexOf('=');
            if (eq < 0) break;
            String k = h.substring(0, eq).trim().toLowerCase();
            h = h.substring(eq + 1).trim();
            String v;
            if (h.startsWith("\"")) {
                int end = 1;
                while (end < h.length() && h.charAt(end) != '"') {
                    if (h.charAt(end) == '\\') end++;
                    end++;
                }
                v = h.substring(1, Math.min(end, h.length())).replace("\\\"", "\"");
                h = h.substring(Math.min(end + 1, h.length()));
            } else {
                int end = h.indexOf(',');
                if (end < 0) end = h.length();
                v = h.substring(0, end).trim();
                h = h.substring(end);
            }
            out.put(k, v);
            h = h.trim();
            if (h.startsWith(",")) h = h.substring(1);
            h = h.trim();
        }
        return out;
    }

    static List<String> fields(String s) { return s.isBlank() ? List.of() : Arrays.asList(s.trim().split("\\s+")); }

    /** Go's url.QueryEscape. */
    static String qesc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** Sorted-key form encoding (Go's url.Values.Encode). */
    static String encodeForm(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(m).forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "&").append(qesc(k)).append('=').append(qesc(v)));
        return sb.toString();
    }

    static String randStr(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
