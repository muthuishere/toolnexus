package io.github.muthuishere.toolnexus.mcp;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.muthuishere.toolnexus.mcp.Mcp.*;

/**
 * Server-side authorization: the MCP server as an OAuth 2.1 protected resource (MCP 2026-07-28
 * basic/authorization). {@code scopes["*"]} is required for every request (and advertised as
 * scopes_supported); {@code scopes[toolName]} is additionally required for that tools/call.
 */
public final class ServerAuth {
    /** Replaces the default JWT/JWKS verifier (opaque tokens, introspection, ...). Throw to reject. */
    @FunctionalInterface
    public interface Verifier {
        Principal verify(String token) throws Exception;
    }

    record Check(Principal principal, HttpResp deny) {}

    static final String PRM_PATH = "/.well-known/oauth-protected-resource";

    private final String issuer;
    private String audience = ""; // "" = derived from the request (scheme://host/mcp)
    private final Map<String, List<String>> scopes = new LinkedHashMap<>();
    private Verifier verify;
    private HttpClient http;

    private final Object keyLock = new Object();
    private Map<String, PublicKey> keys; // JWKS cache by kid
    private long fetchedAt;              // unknown kids refetch at most every 30s

    /** The whole required config: the authorization server's issuer identifier. */
    public ServerAuth(String issuer) { this.issuer = issuer; }

    public ServerAuth audience(String canonicalResourceUri) { this.audience = canonicalResourceUri; return this; }

    /** "*" = every request; a tool name = additionally for that tool. The map stays live (tests adjust it). */
    public ServerAuth scopes(Map<String, List<String>> s) { scopes.clear(); scopes.putAll(s); return this; }

    public Map<String, List<String>> scopes() { return scopes; }

    public ServerAuth verify(Verifier v) { this.verify = v; return this; }

    /** For AS metadata + JWKS fetches; default = the shared client (trusts in-process dev servers). */
    public ServerAuth http(HttpClient hc) { this.http = hc; return this; }

    public String issuer() { return issuer; }

    private String audience(HttpReq r) {
        return !audience.isEmpty() ? audience : (r.tls() ? "https" : "http") + "://" + r.host() + "/mcp";
    }

    /** RFC 9728 PRM at both the path-inserted and root well-known URIs; null = not a metadata path. */
    HttpResp serveMetadata(HttpReq r) {
        if (!r.path().equals(PRM_PATH) && !r.path().equals(PRM_PATH + "/mcp")) return null;
        return HttpResp.json(200, obj("resource", audience(r), "authorization_servers", List.of(issuer),
                "scopes_supported", scopes.get("*"), "bearer_methods_supported", List.of("header")));
    }

    /** Authenticates the request; on failure deny carries the 401/403. */
    Check check(HttpReq r, String method, Map<String, Object> p) {
        List<String> base = scopes.getOrDefault("*", List.of());
        String auth = r.header("Authorization");
        String tok = auth.startsWith("Bearer ") ? auth.substring(7) : "";
        if (tok.isEmpty()) return new Check(null, challenge(r, 401, "", scopes.get("*")));
        Principal pr;
        try {
            pr = verify != null ? verify.verify(tok) : verifyJwt(tok, audience(r));
            if (pr == null) throw new IllegalStateException("no principal");
        } catch (Exception e) { // never echo the token or the detailed reason
            return new Check(null, challenge(r, 401, ", error=\"invalid_token\"", scopes.get("*")));
        }
        List<String> need = new ArrayList<>(base);
        if (method.equals("tools/call")) need.addAll(scopes.getOrDefault(str(p.get("name")), List.of()));
        List<String> have = pr.scopes() == null ? List.of() : pr.scopes();
        if (!have.containsAll(need)) return new Check(null, challenge(r, 403, ", error=\"insufficient_scope\"", need));
        return new Check(pr, null);
    }

    private HttpResp challenge(HttpReq r, int status, String extra, List<String> sc) {
        String v = "Bearer resource_metadata=\"" + (r.tls() ? "https" : "http") + "://" + r.host() + PRM_PATH + "/mcp\"";
        if (sc != null && !sc.isEmpty()) v += ", scope=\"" + String.join(" ", sc) + "\"";
        HttpResp resp = new HttpResp(status, new LinkedHashMap<>(), "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
        resp.headers().put("WWW-Authenticate", v + extra);
        resp.headers().put("Content-Type", "application/json");
        return resp;
    }

    // --- default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ---

    private Principal verifyJwt(String tok, String aud) throws Exception {
        String[] parts = tok.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("malformed jwt");
        Base64.Decoder d = Base64.getUrlDecoder();
        Map<String, Object> hdr = map(parse(d.decode(parts[0])));
        Map<String, Object> claims = map(parse(d.decode(parts[1])));
        if (hdr == null || claims == null || !"RS256".equals(hdr.get("alg"))) throw new IllegalArgumentException("bad jwt header/claims");
        PublicKey key = key(str(hdr.get("kid")));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(key);
        s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!s.verify(d.decode(parts[2]))) throw new SecurityException("bad signature");
        if (!issuer.equals(claims.get("iss"))) throw new SecurityException("wrong issuer");
        Object a = claims.get("aud");
        if (!(aud.equals(a) || (a instanceof List<?> l && l.contains(aud)))) throw new SecurityException("wrong audience");
        if (!(claims.get("exp") instanceof Number exp) || exp.longValue() <= System.currentTimeMillis() / 1000)
            throw new SecurityException("expired");
        String sc = str(claims.get("scope"));
        return new Principal(str(claims.get("sub")), sc.isBlank() ? List.of() : Arrays.asList(sc.trim().split("\\s+")), claims);
    }

    private PublicKey key(String kid) throws Exception {
        synchronized (keyLock) {
            if (keys != null && keys.get(kid) != null) return keys.get(kid);
            if (keys != null && System.currentTimeMillis() - fetchedAt < 30_000) // garbage kids must not hammer the AS
                throw new SecurityException("unknown kid");
            HttpClient hc = http != null ? http : Client.defaultHttp();
            Map<String, Object> md = OAuth.discoverAS(hc, issuer); // same RFC 8414 / OIDC order the client uses
            Map<String, Object> jwks = OAuth.getJson(hc, str(md.get("jwks_uri")));
            keys = new HashMap<>();
            fetchedAt = System.currentTimeMillis();
            KeyFactory kf = KeyFactory.getInstance("RSA");
            Base64.Decoder dec = Base64.getUrlDecoder();
            for (Object o : list(jwks.get("keys"))) {
                Map<String, Object> k = map(o);
                if (k == null || !"RSA".equals(k.get("kty"))) continue;
                try {
                    keys.put(str(k.get("kid")), kf.generatePublic(new RSAPublicKeySpec(
                            new BigInteger(1, dec.decode(str(k.get("n")))), new BigInteger(1, dec.decode(str(k.get("e")))))));
                } catch (Exception ignored) {
                    // malformed key: skipped
                }
            }
            if (keys.get(kid) != null) return keys.get(kid);
            throw new SecurityException("unknown kid");
        }
    }
}
