package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <b>DEVELOPMENT AND TESTS ONLY. NEVER USE IN PRODUCTION.</b> (SPEC §7C.2)
 *
 * <p>An in-process OAuth 2.1 authorization server that auto-approves every request as
 * {@code dev-user} (no login, no consent): RFC 8414 metadata, JWKS (one RSA key, {@code kid:"dev"}),
 * authorization code + PKCE S256, resource indicators (RFC 8707), {@code iss} in the response
 * (RFC 9207), refresh-token rotation, pre-registered clients and Client ID Metadata Documents.
 * No dynamic client registration. Plain HTTP on 127.0.0.1, so a toolnexus client needs
 * {@code auth.allowHttp} against it.
 *
 * <pre>{@code
 * try (McpDevAuthServer as = McpDevAuthServer.start(new McpDevAuthServer.Options()
 *         .clients(Map.of("my-app", List.of("http://127.0.0.1/callback"))))) {
 *     McpServerAuth auth = new McpServerAuth(as.issuer()).scopes(Map.of("*", List.of("mcp")));
 * }
 * }</pre>
 */
public final class McpDevAuthServer implements AutoCloseable {

    /** Configures {@link #start}. Every field is optional. */
    public static final class Options {
        /** Pre-registered client_id → allowed redirect URIs (a loopback redirect matches on any port). */
        public Map<String, List<String>> clients = Map.of();
        /** Confidential clients: client_id → secret (basic or post). */
        public Map<String, String> secrets = Map.of();
        /** Lifetime of issued access tokens. Default 1 h. */
        public Duration accessTtl;
        /** Stops advertising (and accepting) Client ID Metadata Documents. */
        public boolean noCimd;
        /** Answers invalid_target when a refresh carries {@code resource} (an Entra-like provider). */
        public boolean rejectRefreshResource;
        /** Fetches Client ID Metadata Documents. Default: a 10 s client. */
        public HttpClient metadataClient;

        public Options clients(Map<String, List<String>> v) { this.clients = v; return this; }
        public Options secrets(Map<String, String> v) { this.secrets = v; return this; }
        public Options accessTtl(Duration v) { this.accessTtl = v; return this; }
        public Options noCimd(boolean v) { this.noCimd = v; return this; }
        public Options rejectRefreshResource(boolean v) { this.rejectRefreshResource = v; return this; }
        public Options metadataClient(HttpClient v) { this.metadataClient = v; return this; }
    }

    private record Grant(String clientId, String redirect, String challenge, String resource, String scope) {}

    private static final SecureRandom RNG = new SecureRandom();

    private final Options opts;
    private final KeyPair key;
    private final HttpServer srv;
    private final ExecutorService exec;
    private final String issuer;
    private final Object mu = new Object();
    private final Map<String, Grant> codes = new HashMap<>();
    private final Map<String, Grant> rts = new HashMap<>();
    private int auths;

    private McpDevAuthServer(Options o) throws Exception {
        this.opts = o == null ? new Options() : o;
        if (opts.clients == null) opts.clients = Map.of();
        if (opts.secrets == null) opts.secrets = Map.of();
        if (opts.metadataClient == null) {
            opts.metadataClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        this.key = g.generateKeyPair();
        this.srv = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        this.exec = Executors.newVirtualThreadPerTaskExecutor();
        srv.setExecutor(exec);
        srv.createContext("/", this::handle);
        srv.start();
        this.issuer = "http://127.0.0.1:" + srv.getAddress().getPort();
    }

    /** Starts the dev AS on 127.0.0.1:&lt;random&gt;. DEV ONLY. */
    public static McpDevAuthServer start(Options opts) {
        try {
            return new McpDevAuthServer(opts);
        } catch (Exception e) {
            throw new IllegalStateException("toolnexus: cannot start the dev authorization server: " + e.getMessage(), e);
        }
    }

    /** Starts the dev AS with default options. DEV ONLY. */
    public static McpDevAuthServer start() {
        return start(new Options());
    }

    /** {@code http://127.0.0.1:<port>}. */
    public String issuer() { return issuer; }

    /** How many /authorize requests were served. */
    public int authorizations() {
        synchronized (mu) {
            return auths;
        }
    }

    @Override
    public void close() {
        srv.stop(0);
        exec.shutdownNow();
    }

    /** Signs an access token with the server's key (tests: forge expired or wrong-audience tokens). */
    public String mint(Map<String, Object> claims) {
        try {
            Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("alg", "RS256");
            h.put("typ", "JWT");
            h.put("kid", "dev");
            String in = e.encodeToString(Json.stringify(h).getBytes(StandardCharsets.UTF_8)) + "."
                    + e.encodeToString(Json.stringify(claims).getBytes(StandardCharsets.UTF_8));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key.getPrivate());
            s.update(in.getBytes(StandardCharsets.US_ASCII));
            return in + "." + e.encodeToString(s.sign());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String rand(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static void send(HttpExchange ex, int status, String ct, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void json(HttpExchange ex, int status, Map<String, Object> v) throws IOException {
        send(ex, status, "application/json", Json.stringify(v) + "\n");
    }

    private static Map<String, Object> kv(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String kvp : s.split("&")) {
            if (kvp.isEmpty()) continue;
            int i = kvp.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? kvp : kvp.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(kvp.substring(i + 1), StandardCharsets.UTF_8);
            m.putIfAbsent(k, v);
        }
        return m;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            switch (ex.getRequestURI().getPath()) {
                case "/.well-known/oauth-authorization-server" -> json(ex, 200, kv(
                        "issuer", issuer, "authorization_endpoint", issuer + "/authorize",
                        "token_endpoint", issuer + "/token", "jwks_uri", issuer + "/jwks",
                        "response_types_supported", List.of("code"),
                        "grant_types_supported", List.of("authorization_code", "refresh_token"),
                        "code_challenge_methods_supported", List.of("S256"),
                        "token_endpoint_auth_methods_supported", List.of("none", "client_secret_basic", "client_secret_post"),
                        "authorization_response_iss_parameter_supported", true,
                        "client_id_metadata_document_supported", !opts.noCimd));
                case "/jwks" -> {
                    RSAPublicKey pub = (RSAPublicKey) key.getPublic();
                    Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
                    json(ex, 200, kv("keys", List.of(kv("kty", "RSA", "kid", "dev", "alg", "RS256", "use", "sig",
                            "n", e.encodeToString(unsigned(pub.getModulus())),
                            "e", e.encodeToString(unsigned(pub.getPublicExponent()))))));
                }
                case "/authorize" -> authorize(ex);
                case "/token" -> token(ex);
                default -> send(ex, 404, "text/plain; charset=utf-8", "404 page not found\n");
            }
        } finally {
            ex.close();
        }
    }

    private void authorize(HttpExchange ex) throws IOException {
        Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
        synchronized (mu) {
            auths++;
        }
        String cid = q.getOrDefault("client_id", ""), redirect = q.getOrDefault("redirect_uri", "");
        if (!redirectAllowed(cid, redirect)) {
            send(ex, 400, "text/plain; charset=utf-8", "invalid_client or redirect_uri\n"); // never redirect to an unverified URI
            return;
        }
        Map<String, String> back = new LinkedHashMap<>();
        if (!"code".equals(q.get("response_type")) || !"S256".equals(q.get("code_challenge_method"))
                || q.getOrDefault("code_challenge", "").isEmpty()) {
            back.put("error", "invalid_request");
        } else if (q.getOrDefault("resource", "").isEmpty()) {
            back.put("error", "invalid_target");
        } else {
            String code = rand(16);
            synchronized (mu) {
                codes.put(code, new Grant(cid, redirect, q.get("code_challenge"), q.get("resource"), q.getOrDefault("scope", "")));
            }
            back.put("code", code);
        }
        back.put("iss", issuer);
        back.put("state", q.getOrDefault("state", ""));
        StringBuilder loc = new StringBuilder(redirect).append(redirect.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(back).entrySet()) {
            if (!first) loc.append('&');
            first = false;
            loc.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        ex.getResponseHeaders().set("Location", loc.toString());
        ex.sendResponseHeaders(302, -1);
    }

    @SuppressWarnings("unchecked")
    private boolean redirectAllowed(String cid, String redirect) {
        List<String> allowed = opts.clients.getOrDefault(cid, List.of());
        if (cid.startsWith("https://") || cid.startsWith("http://")) { // Client ID Metadata Document
            if (opts.noCimd) return false;
            try {
                Map<String, Object> doc = McpServerAuth.getJson(opts.metadataClient, cid);
                if (!cid.equals(doc.get("client_id")) || !(doc.get("client_name") instanceof String n) || n.isEmpty()) return false;
                allowed = doc.get("redirect_uris") instanceof List<?> l ? (List<String>) l : List.of();
            } catch (Exception e) {
                return false;
            }
        }
        URI ru;
        try {
            ru = URI.create(redirect);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Object ao : allowed) {
            if (!(ao instanceof String a)) continue;
            if (a.equals(redirect)) return true;
            try {
                URI au = URI.create(a);
                String h = ru.getHost() == null ? "" : ru.getHost();
                boolean loopback = h.matches("127\\.\\d+\\.\\d+\\.\\d+") || h.equals("[::1]") || h.equals("::1");
                if (loopback && h.equals(au.getHost()) && String.valueOf(ru.getScheme()).equals(au.getScheme())
                        && String.valueOf(ru.getPath()).equals(String.valueOf(au.getPath()))) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // a malformed entry is skipped
            }
        }
        return false;
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> f = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        synchronized (mu) {
            String basic = ex.getRequestHeaders().getFirst("Authorization");
            if (basic != null && basic.startsWith("Basic ")) {
                String dec;
                try {
                    dec = new String(Base64.getDecoder().decode(basic.substring(6)), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    json(ex, 401, kv("error", "invalid_client"));
                    return;
                }
                int c = dec.indexOf(':');
                if (c < 0) {
                    json(ex, 401, kv("error", "invalid_client"));
                    return;
                }
                String id = URLDecoder.decode(dec.substring(0, c), StandardCharsets.UTF_8);
                String sec = URLDecoder.decode(dec.substring(c + 1), StandardCharsets.UTF_8);
                String fid = f.getOrDefault("client_id", "");
                String want = opts.secrets.getOrDefault(id, "");
                if ((!fid.isEmpty() && !fid.equals(id)) || want.isEmpty() || !want.equals(sec)) {
                    json(ex, 401, kv("error", "invalid_client"));
                    return;
                }
                f.put("client_id", id);
            } else {
                String want = opts.secrets.getOrDefault(f.getOrDefault("client_id", ""), "");
                if (!want.isEmpty() && !want.equals(f.get("client_secret"))) {
                    json(ex, 401, kv("error", "invalid_client"));
                    return;
                }
            }
            Grant g;
            switch (f.getOrDefault("grant_type", "")) {
                case "authorization_code" -> {
                    g = codes.remove(f.getOrDefault("code", "")); // single use
                    if (g == null) {
                        json(ex, 400, kv("error", "invalid_grant"));
                        return;
                    }
                    String ch = Base64.getUrlEncoder().withoutPadding().encodeToString(
                            Mcp2026.sha256(f.getOrDefault("code_verifier", "").getBytes(StandardCharsets.UTF_8)));
                    if (!ch.equals(g.challenge()) || !g.clientId().equals(f.get("client_id"))
                            || !g.redirect().equals(f.get("redirect_uri")) || !g.resource().equals(f.get("resource"))) {
                        json(ex, 400, kv("error", "invalid_grant"));
                        return;
                    }
                }
                case "refresh_token" -> {
                    g = rts.get(f.getOrDefault("refresh_token", ""));
                    if (g == null || !g.clientId().equals(f.get("client_id"))) {
                        json(ex, 400, kv("error", "invalid_grant"));
                        return;
                    }
                    rts.remove(f.get("refresh_token")); // rotation
                    String res = f.getOrDefault("resource", "");
                    if (!res.isEmpty() && (!res.equals(g.resource()) || opts.rejectRefreshResource)) {
                        json(ex, 400, kv("error", "invalid_target"));
                        return;
                    }
                }
                default -> {
                    json(ex, 400, kv("error", "unsupported_grant_type"));
                    return;
                }
            }
            Duration ttl = opts.accessTtl == null || opts.accessTtl.isNegative() || opts.accessTtl.isZero()
                    ? Duration.ofHours(1) : opts.accessTtl;
            String rt = rand(24);
            rts.put(rt, g);
            long now = System.currentTimeMillis() / 1000;
            String at = mint(kv("iss", issuer, "sub", "dev-user", "aud", g.resource(), "scope", g.scope(),
                    "client_id", g.clientId(), "jti", rand(8), "exp", now + ttl.toSeconds(), "iat", now));
            json(ex, 200, kv("access_token", at, "token_type", "Bearer", "expires_in", ttl.toSeconds(),
                    "refresh_token", rt, "scope", g.scope()));
        }
    }
}
