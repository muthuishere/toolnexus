package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server authorization (SPEC §2A.2, §7C.2): an {@link McpHandler} as an OAuth 2.1 protected
 * resource. A plain helper producing the handler's {@code authenticate} hook and the RFC 9728
 * metadata handler; the host mounts both. Identity, tenants and token storage stay the host's.
 *
 * <pre>{@code
 * McpServerAuth auth = new McpServerAuth("https://login.example").scopes(Map.of("*", List.of("mcp")));
 * server.createContext("/mcp", tk.mcpHandler(new McpHandler.Config()
 *         .protocolVersions("2026-07-28").authenticate(auth::authenticate)));
 * server.createContext("/.well-known/oauth-protected-resource", auth.metadata());
 * }</pre>
 */
public final class McpServerAuth {

    /** The verified caller; what {@link McpHandler#identityFrom} returns under McpServerAuth. */
    public record Principal(String subject, List<String> scopes, Map<String, Object> claims) {}

    /** Replaces the default RS256/JWKS verifier (opaque tokens, introspection). Throw to reject. */
    @FunctionalInterface
    public interface Verifier {
        Principal verify(HttpExchange exchange, String token, String audience) throws Exception;
    }

    static final String PRM_PATH = "/.well-known/oauth-protected-resource";

    private final String issuer;
    private String audience = "";
    private String resourcePath = "/mcp";
    private Map<String, List<String>> scopes = Map.of();
    private Verifier verify;
    private HttpClient httpClient;

    private final Object keyLock = new Object();
    private Map<String, PublicKey> keys;
    private long fetchedAt;

    /**
     * @param issuer the authorization server's issuer identifier (advertised in the protected
     *               resource metadata, and the JWT {@code iss} the default verifier requires)
     */
    public McpServerAuth(String issuer) {
        this.issuer = issuer == null ? "" : issuer;
    }

    /** The canonical resource URI (PRM {@code resource}, JWT {@code aud}). "" ⇒ {@code <scheme>://<Host><resourcePath>}. */
    public McpServerAuth audience(String v) { this.audience = v == null ? "" : v; return this; }

    /** Where the handler is mounted. Default "/mcp". */
    public McpServerAuth resourcePath(String v) { this.resourcePath = v == null || v.isEmpty() ? "/mcp" : v; return this; }

    /** "*" is required for every request (and advertised as scopes_supported); a tool name adds scopes for that tools/call. */
    public McpServerAuth scopes(Map<String, List<String>> v) { this.scopes = v == null ? Map.of() : v; return this; }

    public McpServerAuth verify(Verifier v) { this.verify = v; return this; }

    /** Fetches AS metadata and JWKS. Default: a 10 s client. */
    public McpServerAuth httpClient(HttpClient v) { this.httpClient = v; return this; }

    public String issuer() { return issuer; }

    private static String origin(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isEmpty()) {
            host = ex.getLocalAddress().getHostString() + ":" + ex.getLocalAddress().getPort();
        }
        return (ex instanceof HttpsExchange ? "https" : "http") + "://" + host;
    }

    private String audience(HttpExchange ex) {
        return !audience.isEmpty() ? audience : origin(ex) + resourcePath;
    }

    /** The RFC 9728 metadata URL the challenge names. */
    public String metadataUrl(HttpExchange ex) {
        if (!audience.isEmpty()) {
            try {
                URI u = URI.create(audience);
                if (u.getRawAuthority() != null && !u.getRawAuthority().isEmpty()) {
                    return u.getScheme() + "://" + u.getRawAuthority() + PRM_PATH + (u.getRawPath() == null ? "" : u.getRawPath());
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to the request-derived URL
            }
        }
        return origin(ex) + PRM_PATH + resourcePath;
    }

    private List<String> base() {
        List<String> b = scopes.get("*");
        return b == null ? List.of() : b;
    }

    /**
     * Serves the protected resource metadata for GET at {@code /.well-known/oauth-protected-resource}
     * and {@code …/oauth-protected-resource<resourcePath>}; anything else 404.
     */
    public HttpHandler metadata() {
        return ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                if (!"GET".equals(ex.getRequestMethod()) || !(PRM_PATH.equals(path) || (PRM_PATH + resourcePath).equals(path))) {
                    send(ex, 404, "text/plain; charset=utf-8", "404 page not found\n");
                    return;
                }
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("resource", audience(ex));
                doc.put("authorization_servers", List.of(issuer));
                doc.put("scopes_supported", base());
                doc.put("bearer_methods_supported", List.of("header"));
                send(ex, 200, "application/json", Json.stringify(doc) + "\n");
            } finally {
                ex.close();
            }
        };
    }

    private static void send(HttpExchange ex, int status, String ct, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private McpAuthError challenge(HttpExchange ex, int status, List<String> sc, String err) {
        StringBuilder v = new StringBuilder("Bearer resource_metadata=\"").append(metadataUrl(ex)).append('"');
        if (sc != null && !sc.isEmpty()) v.append(", scope=\"").append(String.join(" ", sc)).append('"');
        if (!err.isEmpty()) v.append(", error=\"").append(err).append('"');
        return new McpAuthError(status, v.toString(), err);
    }

    /**
     * The {@link McpHandler.Authenticate} hook. Missing token ⇒ 401; invalid/expired/wrong audience ⇒
     * 401 {@code error="invalid_token"}; a missing scope ⇒ 403 {@code error="insufficient_scope"}
     * naming the full needed set. Per-tool scopes are read from the validated Mcp-Method / Mcp-Name
     * headers (2026-07-28 wire); older wires check {@code scopes["*"]} only. The token and the failure
     * reason are never echoed.
     */
    public Principal authenticate(HttpExchange ex) throws McpAuthError {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String tok = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
        if (tok.isBlank()) throw challenge(ex, 401, base(), "");
        Principal p;
        try {
            p = verify != null ? verify.verify(ex, tok, audience(ex)) : verifyJwt(tok, audience(ex));
            if (p == null) throw new IllegalStateException("no principal");
        } catch (Exception e) {
            throw challenge(ex, 401, base(), "invalid_token");
        }
        List<String> need = new ArrayList<>(base());
        if ("tools/call".equals(ex.getRequestHeaders().getFirst("Mcp-Method"))) {
            String mn = ex.getRequestHeaders().getFirst("Mcp-Name");
            String[] name = McpHandler2026.decodeHeaderValue(mn == null ? "" : mn);
            if (name[1] != null) {
                for (String s : scopes.getOrDefault(name[0], List.of())) if (!need.contains(s)) need.add(s);
            }
        }
        List<String> have = p.scopes() == null ? List.of() : p.scopes();
        for (String s : need) {
            if (!have.contains(s)) throw challenge(ex, 403, need, "insufficient_scope");
        }
        return p;
    }

    // ── default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ──

    private Principal verifyJwt(String tok, String aud) throws Exception {
        String[] parts = tok.split("\\.", -1);
        if (parts.length != 3) throw new SecurityException("malformed jwt");
        Base64.Decoder d = Base64.getUrlDecoder();
        Map<String, Object> hdr = Json.toMap(new String(d.decode(parts[0]), StandardCharsets.UTF_8));
        Map<String, Object> claims = Json.toMap(new String(d.decode(parts[1]), StandardCharsets.UTF_8));
        if (!"RS256".equals(hdr.get("alg"))) throw new SecurityException("bad jwt header");
        PublicKey key = key(hdr.get("kid") instanceof String k ? k : "");
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(key);
        s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!s.verify(d.decode(parts[2]))) throw new SecurityException("bad signature");
        if (!issuer.equals(claims.get("iss"))) throw new SecurityException("wrong issuer");
        Object a = claims.get("aud");
        if (!(aud.equals(a) || (a instanceof List<?> l && l.contains(aud)))) throw new SecurityException("wrong audience");
        if (!(claims.get("exp") instanceof Number exp) || exp.longValue() <= System.currentTimeMillis() / 1000) {
            throw new SecurityException("expired");
        }
        String sub = claims.get("sub") instanceof String x ? x : "";
        String sc = claims.get("scope") instanceof String x ? x.strip() : "";
        return new Principal(sub, sc.isEmpty() ? List.of() : List.of(sc.split("\\s+")), claims);
    }

    private HttpClient client() {
        return httpClient != null ? httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** The JWKS key for kid; an unknown kid refetches at most every 30 s. */
    private PublicKey key(String kid) throws Exception {
        synchronized (keyLock) {
            if (keys != null && keys.get(kid) != null) return keys.get(kid);
            if (keys != null && System.currentTimeMillis() - fetchedAt < 30_000) throw new SecurityException("unknown kid");
            HttpClient hc = client();
            Map<String, Object> md = discoverAs(hc, issuer);
            Map<String, Object> jwks = getJson(hc, md.get("jwks_uri") instanceof String u ? u : "");
            keys = new HashMap<>();
            fetchedAt = System.currentTimeMillis();
            KeyFactory kf = KeyFactory.getInstance("RSA");
            Base64.Decoder dec = Base64.getUrlDecoder();
            if (jwks.get("keys") instanceof List<?> list) for (Object o : list) {
                if (!(o instanceof Map<?, ?> k) || !"RSA".equals(k.get("kty"))) continue;
                try {
                    keys.put(String.valueOf(k.get("kid")), kf.generatePublic(new RSAPublicKeySpec(
                            new BigInteger(1, dec.decode((String) k.get("n"))), new BigInteger(1, dec.decode((String) k.get("e"))))));
                } catch (Exception ignored) {
                    // a malformed key is skipped
                }
            }
            if (keys.get(kid) != null) return keys.get(kid);
            throw new SecurityException("unknown kid");
        }
    }

    /** AS metadata in the SPEC §2A.1 step 3 order; its {@code issuer} must equal {@code issuer}. */
    static Map<String, Object> discoverAs(HttpClient hc, String issuer) throws Exception {
        URI u = URI.create(issuer);
        String origin = u.getScheme() + "://" + u.getRawAuthority();
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        List<String> cands = new ArrayList<>(List.of(origin + "/.well-known/oauth-authorization-server" + path,
                origin + "/.well-known/openid-configuration" + path));
        if (!path.isEmpty()) cands.add(origin + path + "/.well-known/openid-configuration");
        for (String c : cands) {
            try {
                Map<String, Object> md = getJson(hc, c);
                if (issuer.equals(md.get("issuer"))) return md;
            } catch (Exception ignored) {
                // next candidate
            }
        }
        throw new SecurityException("authorization server metadata not found for " + issuer);
    }

    static Map<String, Object> getJson(HttpClient hc, String url) throws Exception {
        HttpResponse<String> r = hc.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("GET " + url + ": HTTP " + r.statusCode());
        return Json.toMap(r.body());
    }
}
