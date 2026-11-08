package io.github.muthuishere.toolnexus.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.muthuishere.toolnexus.mcp.Mcp.*;

/**
 * INTERNAL (used by {@code McpSource}): a stateless MCP 2026-07-28 client over Streamable HTTP
 * ({@link #Client(String)}) or stdio ({@link #stdio}). SPEC §2.2–§2.6. No MCP SDK.
 */
public class Client implements AutoCloseable {
    /** Bounds every request unless {@link #timeout(Duration)} says otherwise. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Lazy holder: building a JDK {@code HttpClient} initialises the default {@code SSLContext}
     * (~100 ms cold), which a stdio-only connection never needs.
     */
    private static final class DefaultHttp {
        static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The shared default HTTP client: HTTP/1.1, no redirects, system trust. */
    public static HttpClient defaultHttp() { return DefaultHttp.CLIENT; }

    /** Thrown when an HTTP reply is not a JSON-RPC response (status kept for the §2.2a fallback rule). */
    public static final class HttpStatusException extends McpException {
        public final int status;

        HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** A 2xx reply that is not a JSON-RPC response for the request (a §2.2a fallback trigger). */
    public static final class NotJsonRpcException extends McpException {
        NotJsonRpcException(String message) { super(message); }
    }

    final String url;
    private Map<String, Object> info = obj("name", "toolnexus", "version", "0.1.0");
    private final Map<String, Object> caps = obj("elicitation", obj("form", obj()));
    private HttpClient http;
    private Duration timeout = DEFAULT_TIMEOUT;
    final Map<String, String> headers = new LinkedHashMap<>(); // extra headers on every HTTP request (never logged)
    private OAuth auth;                            // null = no authorization (HTTP only)
    StdioConn stdio;
    private final AtomicLong nextId = new AtomicLong();
    private final Object toolMu = new Object();
    private List<Map<String, Object>> toolCache;
    private long toolExpiry;

    /** No handshake happens here: 2026-07-28 is stateless. */
    public Client(String url) { this.url = url; }

    public Client http(HttpClient hc) { this.http = hc; return this; }
    public Client header(String name, String value) { headers.put(name, value); return this; }
    public Client auth(OAuth o) { this.auth = o; return this; }
    public OAuth auth() { return auth; }
    public Client timeout(Duration d) { this.timeout = d; return this; }
    public Duration timeout() { return timeout; }
    public Client clientInfo(String name, String version) { this.info = obj("name", name, "version", version); return this; }
    public String url() { return url; }

    /** Starts the process (stderr inherited, never parsed) and returns a client with the same API. */
    public static Client stdio(ProcessBuilder pb) throws IOException { return stdio(pb, false); }

    /**
     * {@code probe=true} (§2.2a): the reader parks after the first response, so the caller must then
     * {@link #resumeStdio()} (2026 server) or {@link #detachStdio()} (old server, same child continues).
     */
    public static Client stdio(ProcessBuilder pb, boolean probe) throws IOException {
        // Spawn first: constructing a Client initialises Mcp's ObjectMapper (~50 ms cold), and the
        // child's own startup (an interpreter boot, typically) can overlap that instead of following it.
        StdioConn conn = StdioConn.start(pb, null, probe);
        Client c = new Client("");
        c.stdio = conn;
        return c;
    }

    public boolean isStdio() { return stdio != null; }

    /**
     * §2.2a stdio probe handoff: after a fast discover reply from an old server, returns the live child
     * so the frozen path continues on it (no respawn). This client must not be used or closed afterwards.
     */
    public Process detachStdio() { return stdio == null ? null : stdio.detach(); }

    /** After a 2026 discover result on stdio: resume reading responses on the same child. */
    public void resumeStdio() { if (stdio != null) stdio.resume(); }

    /** True when e is a stdio request that got no reply in time (the §2.2a stdio fallback trigger). */
    public static boolean isTimeout(Throwable e) { return e instanceof StdioConn.TimeoutException; }

    /** {@code server/discover} (SPEC §2.2); throws like {@link #call}. */
    public Map<String, Object> discover() { return call("server/discover", null); }

    /** True when a discover result lists {@code 2026-07-28} in {@code supportedVersions}. */
    public static boolean supports2026(Map<String, Object> discover) {
        return list(discover.get("supportedVersions")).contains(PROTOCOL_VERSION);
    }

    /**
     * Sends one request; params gets the per-request _meta merged in.
     * <p>Retry rule (§2.6): a request is re-sent ONLY after a 401, or a 403 insufficient_scope, carrying
     * WWW-Authenticate — the server rejected it before dispatch. A lost connection, timeout, 5xx or
     * unreadable response is thrown and NEVER retried.
     */
    public Map<String, Object> call(String method, Map<String, Object> params) {
        Map<String, Object> p = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        p.put("_meta", obj(META_VERSION, PROTOCOL_VERSION, META_CLIENT_CAPS, caps, META_CLIENT_INFO, info));
        long id = nextId.incrementAndGet();
        byte[] body = jsonBytes(obj("jsonrpc", "2.0", "id", id, "method", method, "params", p));
        Map<String, Object> msg = stdio != null ? stdio.roundTrip(id, body, timeout) : httpRoundTrip(method, p, body, id);
        Map<String, Object> err = map(msg.get("error"));
        if (err != null) throw RpcError.fromJson(err);
        Map<String, Object> result = map(msg.get("result"));
        if (result == null) throw new McpException("response has neither result nor error");
        result.putIfAbsent("resultType", "complete"); // §2.4
        return result;
    }

    private Map<String, Object> httpRoundTrip(String method, Map<String, Object> params, byte[] body, long id) {
        HttpResponse<byte[]> resp;
        for (int attempt = 0; ; attempt++) {
            String sent = auth != null ? auth.bearer(url) : "";
            resp = post(method, params, body, sent); // never retried: the server may have executed it
            String www = resp.headers().firstValue("WWW-Authenticate").orElse("");
            int st = resp.statusCode();
            if (auth == null || (st != 401 && st != 403) || www.isEmpty()) break;
            if (attempt >= 3) // spec: Step-Up retry limit
                throw new McpException("authorization failed: HTTP " + st + " after " + (attempt + 1) + " attempts");
            auth.challenge(url, st, www, sent, () -> call(method, params));
        }
        int st = resp.statusCode();
        if (st >= 500) throw new HttpStatusException(st, "HTTP " + st); // never retried (§2.6)
        if (st == 401 || st == 403) throw new HttpStatusException(st, "HTTP " + st + " (unauthorized)");
        Map<String, Object> msg = null;
        if (resp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            for (String line : new String(resp.body(), StandardCharsets.UTF_8).split("\r?\n")) {
                if (!line.startsWith("data:")) continue;
                try {
                    Map<String, Object> m = map(parse(line.substring(5).trim().getBytes(StandardCharsets.UTF_8)));
                    if (m != null && (m.get("result") != null || m.get("error") != null)) msg = m;
                } catch (IOException ignored) {
                    // not JSON: skipped
                }
            }
        } else {
            try {
                msg = map(parse(resp.body()));
            } catch (IOException e) {
                msg = null;
            }
        }
        boolean rpc = msg != null && (msg.get("result") != null || msg.get("error") != null) && sameId(msg.get("id"), id);
        if (!rpc) {
            if (st / 100 == 2) throw new NotJsonRpcException("HTTP " + st + ": not a JSON-RPC response");
            throw new HttpStatusException(st, "HTTP " + st);
        }
        return msg;
    }

    private static boolean sameId(Object got, long id) {
        if (got instanceof Number n) return n.longValue() == id;
        return got != null && String.valueOf(id).equals(String.valueOf(got));
    }

    HttpResponse<byte[]> post(String method, Map<String, Object> params, byte[] body, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/event-stream");
        h.put("MCP-Protocol-Version", PROTOCOL_VERSION);
        h.put("Mcp-Method", method);
        switch (method) {
            case "tools/call", "prompts/get" -> h.put("Mcp-Name", encodeHeader(str(params.get("name"))));
            case "resources/read" -> h.put("Mcp-Name", encodeHeader(str(params.get("uri"))));
            default -> { }
        }
        h.putAll(headers);
        if (!bearer.isEmpty()) h.put("Authorization", "Bearer " + bearer);
        h.forEach(b::setHeader);
        try {
            return (http != null ? http : DefaultHttp.CLIENT).send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new McpException("MCP request timed out after " + timeout.toMillis() + "ms", e);
        } catch (IOException e) {
            throw new McpException("POST " + url + ": " + e, e);
        }
    }

    /** SPEC §2.3 Mcp-Name: values needing it are sent as {@code =?base64?<std-base64>?=}. */
    public static String encodeHeader(String v) {
        boolean safe = v.equals(v.strip()) && !(v.startsWith("=?base64?") && v.endsWith("?="));
        for (char c : v.toCharArray()) if (c < 0x20 || c > 0x7e) safe = false;
        return safe ? v : "=?base64?" + Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** tools/list, cached for the server's ttlMs (no hint ⇒ fetched again next time). */
    public List<Map<String, Object>> listTools() {
        synchronized (toolMu) {
            if (toolCache != null && System.currentTimeMillis() < toolExpiry) return toolCache;
            Map<String, Object> r = call("tools/list", null);
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Object t : list(r.get("tools"))) if (map(t) != null) tools.add(map(t));
            long ttl = r.get("ttlMs") instanceof Number n ? n.longValue() : 0;
            toolCache = tools;
            toolExpiry = System.currentTimeMillis() + ttl;
            return tools;
        }
    }

    /** Ranks the server's tools against query (BM25; name tokens count twice). §2.8. */
    public List<Map<String, Object>> searchTools(List<Map<String, Object>> tools, String query, int limit, String server) {
        return ToolSearch.rank(tools, query, limit, server);
    }

    /** The two tool-search meta tools (name, description, inputSchema) for {@code about}. */
    public static List<Map<String, Object>> searchMetaTools(String about) { return ToolSearch.metaTools(about); }

    /** {@code skills/list} → the skill URIs (§2.7). */
    public List<String> listSkills() {
        List<String> out = new ArrayList<>();
        for (Object s : list(call("skills/list", null).get("skills"))) {
            Map<String, Object> m = map(s);
            if (m != null && !str(m.get("uri")).isEmpty()) out.add(str(m.get("uri")));
        }
        return out;
    }

    /**
     * Fetches a skill (skills/get + resources/read for every file) into dir/&lt;name&gt;/, verifying each
     * file's sha256 digest. A URI outside the skill is refused; nothing is written until all pass. §2.7.
     */
    public Path downloadSkill(String uri, Path dir) throws IOException {
        String name = SkillUri.parse(uri).name();
        Map<String, Object> sk = map(call("skills/get", obj("uri", uri)).get("skill"));
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (Object x : list(sk == null ? null : sk.get("resources"))) { // validate everything before writing anything
            Map<String, Object> m = map(x);
            String fu = m == null ? "" : str(m.get("uri"));
            SkillUri su;
            try {
                su = SkillUri.parse(fu);
            } catch (IllegalArgumentException e) {
                su = null;
            }
            Path rel = su == null ? null : Path.of(su.rel()).normalize();
            if (su == null || !su.name().equals(name) || rel.isAbsolute() || rel.startsWith(".."))
                throw new McpException("refusing skill resource \"" + fu + "\": outside skill " + name);
            List<Object> cs = list(call("resources/read", obj("uri", fu)).get("contents"));
            if (cs.isEmpty() || map(cs.get(0)) == null) throw new McpException(fu + ": empty resources/read");
            byte[] data = str(map(cs.get(0)).get("text")).getBytes(StandardCharsets.UTF_8);
            String d = str(m.get("digest"));
            if (!d.isEmpty() && !d.equals("sha256:" + sha256Hex(data))) throw new McpException(fu + ": digest mismatch");
            files.put(dir.resolve(name).resolve(rel), data);
        }
        for (var f : files.entrySet()) {
            Files.createDirectories(f.getKey().getParent());
            Files.write(f.getKey(), f.getValue());
        }
        return dir.resolve(name);
    }

    static String sha256Hex(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stops a stdio server (close stdin, then kill after 2s); no-op for HTTP clients. */
    @Override
    public void close() {
        if (stdio != null) stdio.close();
    }
}
