package io.github.muthuishere.toolnexus.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.muthuishere.toolnexus.mcp.Mcp.*;

/**
 * DevAuthServer — DEV/TEST ONLY. NEVER USE IN PRODUCTION.
 * A one-line OAuth 2.1 authorization server that auto-approves every request as "dev-user" (no login, no
 * consent screen), so local dev and tests exercise the real MCP auth flow: RFC 8414 metadata, JWKS, auth code
 * + PKCE S256, resource (RFC 8707), iss in the response (RFC 9207), refresh-token rotation, pre-registered +
 * CIMD clients.
 * <pre>
 * var as = DevAuthServer.start();   // http://127.0.0.1:&lt;port&gt;/dev
 * srv.auth(new ServerAuth(as.issuer()).scopes(...));
 * </pre>
 */
public final class DevAuthServer implements AutoCloseable {
    private record Code(String clientId, String redirect, String challenge, String resource, String scope) {}

    private final LocalServer srv;
    private final KeyPair key;
    private final String issuer;
    /** Pre-registered client_id -> redirect URIs. */
    public final Map<String, List<String>> clients = new ConcurrentHashMap<>();
    /** Confidential clients: client_id -> secret (basic or post). */
    public final Map<String, String> secrets = new ConcurrentHashMap<>();
    // Test knobs: misbehave on purpose.
    public volatile Duration accessTtl = Duration.ofHours(1);
    public volatile String issOverride = "";            // non-empty: send this iss instead of the real one
    public volatile boolean noCimd;                     // don't advertise client_id_metadata_document_supported
    public volatile boolean rejectRefreshResource;      // Entra-like: invalid_target if a refresh carries resource

    private final Object mu = new Object();
    private final Map<String, Code> codes = new HashMap<>();
    private final Map<String, Code> rts = new HashMap<>();
    private int authorizations, refreshes;
    private Map<String, String> lastAuthorizeQuery = Map.of(), lastTokenForm = Map.of();

    private DevAuthServer() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        key = g.generateKeyPair();
        srv = LocalServer.start(ex -> handle(HttpReq.of(ex, 1 << 20)).writeTo(ex));
        issuer = srv.url() + "/dev";
    }

    /** Starts a plain-HTTP dev AS (clients set auth.allowHttp) on 127.0.0.1 with issuer http://127.0.0.1:&lt;port&gt;/dev; clients in this process trust it. */
    public static DevAuthServer start() {
        try {
            return new DevAuthServer();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String issuer() { return issuer; }

    public int authorizations() { synchronized (mu) { return authorizations; } }

    public int refreshes() { synchronized (mu) { return refreshes; } }

    public Map<String, String> lastAuthorizeQuery() { synchronized (mu) { return lastAuthorizeQuery; } }

    public Map<String, String> lastTokenForm() { synchronized (mu) { return lastTokenForm; } }

    /** An HttpClient that trusts the dev certificate (and follows no redirects). */
    public HttpClient httpClient() { return Client.defaultHttp(); }

    /** A headless "browser" GET: follows redirects (https -> http loopback included) when follow is true. */
    public HttpResponse<String> get(String url, boolean follow) {
        try {
            for (int i = 0; ; i++) {
                HttpResponse<String> r = Client.defaultHttp().send(HttpRequest.newBuilder(URI.create(url)).timeout(Client.DEFAULT_TIMEOUT).build(),
                        HttpResponse.BodyHandlers.ofString());
                String loc = r.headers().firstValue("Location").orElse("");
                if (!follow || r.statusCode() / 100 != 3 || loc.isEmpty() || i >= 10) return r;
                url = URI.create(url).resolve(loc).toString();
            }
        } catch (Exception e) {
            throw new McpException("GET " + url + ": " + e.getMessage(), e);
        }
    }

    /** A headless browser for dev/tests: visits an authorization URL and follows the redirect back to the callback. */
    public void approve(String url) { get(url, true); }

    @Override
    public void close() { srv.close(); }

    /** Signs an access token directly (tests: forge wrong audience / expired tokens). */
    public String mint(Map<String, Object> claims) {
        try {
            Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
            String in = e.encodeToString(Mcp.jsonBytes(obj("alg", "RS256", "typ", "JWT", "kid", "dev"))) + "." + e.encodeToString(Mcp.jsonBytes(claims));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key.getPrivate());
            s.update(in.getBytes(StandardCharsets.US_ASCII));
            return in + "." + e.encodeToString(s.sign());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private HttpResp handle(HttpReq r) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        switch (r.path()) {
            case "/.well-known/oauth-authorization-server/dev":
                return HttpResp.json(200, obj("issuer", issuer, "authorization_endpoint", issuer + "/authorize", "token_endpoint", issuer + "/token",
                        "jwks_uri", issuer + "/jwks", "response_types_supported", List.of("code"),
                        "grant_types_supported", List.of("authorization_code", "refresh_token"),
                        "code_challenge_methods_supported", List.of("S256"),
                        "token_endpoint_auth_methods_supported", List.of("none", "client_secret_basic", "client_secret_post"),
                        "authorization_response_iss_parameter_supported", true, "client_id_metadata_document_supported", !noCimd));
            case "/dev/jwks": {
                RSAPublicKey pub = (RSAPublicKey) key.getPublic();
                return HttpResp.json(200, obj("keys", List.of(obj("kty", "RSA", "kid", "dev", "alg", "RS256", "use", "sig",
                        "n", b64.encodeToString(unsigned(pub.getModulus().toByteArray())),
                        "e", b64.encodeToString(unsigned(pub.getPublicExponent().toByteArray()))))));
            }
            case "/dev/authorize":
                return authorize(r);
            case "/dev/token":
                return token(r);
            default:
                return HttpResp.text(404, "404 page not found\n");
        }
    }

    private static byte[] unsigned(byte[] b) {
        return b.length > 1 && b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private HttpResp authorize(HttpReq r) {
        Map<String, String> q = r.query();
        synchronized (mu) {
            authorizations++;
            lastAuthorizeQuery = q;
        }
        String cid = q.getOrDefault("client_id", ""), redirect = q.getOrDefault("redirect_uri", "");
        if (!redirectAllowed(cid, redirect)) return HttpResp.text(400, "invalid_client or redirect_uri\n"); // never redirect to an unverified URI
        String state = q.getOrDefault("state", "");
        if (!"code".equals(q.get("response_type")) || !"S256".equals(q.get("code_challenge_method")) || q.getOrDefault("code_challenge", "").isEmpty())
            return HttpResp.redirect(redirect + "?" + OAuth.encodeForm(Map.of("error", "invalid_request", "state", state, "iss", issuer)));
        if (q.getOrDefault("resource", "").isEmpty())
            return HttpResp.redirect(redirect + "?" + OAuth.encodeForm(Map.of("error", "invalid_target", "state", state, "iss", issuer)));
        String code = OAuth.randStr(16);
        synchronized (mu) {
            codes.put(code, new Code(cid, redirect, q.get("code_challenge"), q.get("resource"), q.getOrDefault("scope", "")));
        }
        String iss = issOverride.isEmpty() ? issuer : issOverride;
        return HttpResp.redirect(redirect + "?" + OAuth.encodeForm(Map.of("code", code, "state", state, "iss", iss)));
    }

    /** Exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3). */
    private boolean redirectAllowed(String cid, String redirect) {
        List<String> allowed = clients.getOrDefault(cid, List.of());
        if ((cid.startsWith("https://") || cid.startsWith("http://127.0.0.1"))) { // Client ID Metadata Document
            try {
                Map<String, Object> doc = OAuth.getJson(Client.defaultHttp(), cid);
                if (!cid.equals(doc.get("client_id")) || str(doc.get("client_name")).isEmpty()) return false;
                allowed = list(doc.get("redirect_uris")).stream().map(Mcp::str).toList();
            } catch (McpException e) {
                return false;
            }
        }
        URI ru;
        try {
            ru = URI.create(redirect);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (String a : allowed) {
            if (a.equals(redirect)) return true;
            try {
                URI au = URI.create(a);
                String h = ru.getHost() == null ? "" : ru.getHost();
                boolean loopback = h.matches("127\\.\\d+\\.\\d+\\.\\d+") || h.equals("[::1]");
                if (loopback && h.equals(au.getHost()) && ru.getScheme().equals(au.getScheme()) && String.valueOf(ru.getPath()).equals(String.valueOf(au.getPath())))
                    return true;
            } catch (IllegalArgumentException ignored) {
                // skip malformed entry
            }
        }
        return false;
    }

    private HttpResp token(HttpReq r) {
        Map<String, String> f = new TreeMap<>(HttpReq.parseQuery(new String(r.body(), StandardCharsets.UTF_8)));
        synchronized (mu) {
            lastTokenForm = f;
            String basic = r.header("Authorization");
            if (basic.startsWith("Basic ")) {
                String dec;
                try {
                    dec = new String(Base64.getDecoder().decode(basic.substring(6)), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return HttpResp.json(401, obj("error", "invalid_client"));
                }
                int c = dec.indexOf(':');
                String id = c < 0 ? dec : java.net.URLDecoder.decode(dec.substring(0, c), StandardCharsets.UTF_8);
                String sec = c < 0 ? "" : java.net.URLDecoder.decode(dec.substring(c + 1), StandardCharsets.UTF_8);
                String fid = f.getOrDefault("client_id", "");
                if (!fid.isEmpty() && !fid.equals(id) || secrets.getOrDefault(id, "").isEmpty() || !secrets.get(id).equals(sec))
                    return HttpResp.json(401, obj("error", "invalid_client"));
                f.put("client_id", id);
            } else {
                String want = secrets.getOrDefault(f.getOrDefault("client_id", ""), "");
                if (!want.isEmpty() && !want.equals(f.get("client_secret"))) return HttpResp.json(401, obj("error", "invalid_client"));
            }
            Code c;
            switch (f.getOrDefault("grant_type", "")) {
                case "authorization_code" -> {
                    c = codes.remove(f.getOrDefault("code", "")); // single use
                    if (c == null) return bad("invalid_grant");
                    String ch = Base64.getUrlEncoder().withoutPadding().encodeToString(OAuth.sha256(f.getOrDefault("code_verifier", "")));
                    if (!ch.equals(c.challenge)) return bad("invalid_grant"); // PKCE verified here
                    if (!c.clientId.equals(f.get("client_id")) || !c.redirect.equals(f.get("redirect_uri")) || !c.resource.equals(f.get("resource")))
                        return bad("invalid_grant");
                }
                case "refresh_token" -> {
                    c = rts.get(f.getOrDefault("refresh_token", ""));
                    if (c == null || !c.clientId.equals(f.get("client_id"))) return bad("invalid_grant");
                    rts.remove(f.get("refresh_token")); // rotation (required for public clients)
                    String res = f.getOrDefault("resource", "");
                    if (!res.isEmpty() && (!res.equals(c.resource) || rejectRefreshResource)) return bad("invalid_target");
                    refreshes++;
                }
                default -> {
                    return bad("unsupported_grant_type");
                }
            }
            String rt = OAuth.randStr(24);
            rts.put(rt, c);
            long now = System.currentTimeMillis() / 1000;
            String at = mint(obj("iss", issuer, "sub", "dev-user", "aud", c.resource, "scope", c.scope, "client_id", c.clientId,
                    "jti", OAuth.randStr(8), "exp", now + accessTtl.toSeconds(), "iat", now));
            return HttpResp.json(200, obj("access_token", at, "token_type", "Bearer", "expires_in", accessTtl.toSeconds(),
                    "refresh_token", rt, "scope", c.scope));
        }
    }

    private static HttpResp bad(String e) { return HttpResp.json(400, obj("error", e)); }
}
