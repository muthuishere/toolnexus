package io.github.muthuishere.toolnexus.mcp2026;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;

/** A stateless MCP 2026-07-28 client over Streamable HTTP ({@link #Client(String)}) or stdio ({@link #stdio}). */
public class Client implements AutoCloseable {
    /** Bounds every request (a hung server cannot hang the caller). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** Answers one MRTR input request (stands in for toolnexus §10 waitFor). */
    @FunctionalInterface
    public interface Responder {
        Map<String, Object> answer(String key, Map<String, Object> request) throws Exception;
    }

    final String url;
    String version = PROTOCOL_VERSION;
    private Map<String, Object> info = obj("name", "toolnexus-spike-java", "version", "0.0.1");
    private Map<String, Object> caps = obj("elicitation", obj("form", obj()));
    private HttpClient http;                       // null = shared default (timeout, trusts in-process dev servers)
    final Map<String, String> headers = new LinkedHashMap<>(); // extra/overriding headers on every HTTP request
    private OAuth auth;                            // null = no authorization (HTTP only)
    boolean toolSearch;
    String description = "";
    StdioConn stdio;
    private final AtomicLong nextId = new AtomicLong();
    private final Object toolMu = new Object();
    private List<Map<String, Object>> toolCache;
    private long toolExpiry;

    /** No handshake happens here: 2026-07-28 is stateless. */
    public Client(String url) { this.url = url; }

    public Client http(HttpClient hc) { this.http = hc; return this; }
    /** Adds a header to every HTTP request (e.g. a static API key); overrides the MCP headers of the same name. */
    public Client header(String name, String value) { headers.put(name, value); return this; }
    public Client auth(OAuth o) { this.auth = o; return this; }
    public OAuth auth() { return auth; }
    public Client version(String v) { this.version = v; return this; }
    /** Tools() exposes search_tools + call_tool instead of every server tool. */
    public Client toolSearch(boolean on) { this.toolSearch = on; return this; }
    /** What this server is for; shown in search_tools results. */
    public Client description(String d) { this.description = d; return this; }
    public String url() { return url; }
    public String description() { return description; }

    /** Starts the process and returns a client with the same API; stderr null = inherit. Close stops it. */
    public static Client stdio(ProcessBuilder pb, java.io.OutputStream stderr) throws IOException {
        Client c = new Client("");
        c.stdio = StdioConn.start(pb, stderr);
        return c;
    }

    public static Client stdio(ProcessBuilder pb) throws IOException { return stdio(pb, null); }

    /**
     * Sends one request; params gets the per-request _meta merged in.
     * <p>Retry rule (spec issue #3394): a request is re-sent ONLY after a 401, or a 403 insufficient_scope,
     * carrying WWW-Authenticate — the server rejected it before dispatch, so the tool never ran. A lost
     * connection, timeout, 5xx or unreadable response is thrown and NEVER retried.
     */
    public Map<String, Object> call(String method, Map<String, Object> params) {
        Map<String, Object> p = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        p.put("_meta", obj(META_VERSION, version, META_CLIENT_CAPS, caps, META_CLIENT_INFO, info));
        long id = nextId.incrementAndGet();
        byte[] body = jsonBytes(obj("jsonrpc", "2.0", "id", id, "method", method, "params", p));
        Map<String, Object> msg = stdio != null ? stdio.roundTrip(id, body) : httpRoundTrip(method, p, body);
        Map<String, Object> err = map(msg.get("error"));
        if (err != null) throw RpcError.fromJson(err);
        Map<String, Object> result = map(msg.get("result"));
        if (result == null) throw new McpException("response has neither result nor error"); // hostile/broken server
        result.putIfAbsent("resultType", "complete"); // earlier-protocol servers
        return result;
    }

    private Map<String, Object> httpRoundTrip(String method, Map<String, Object> params, byte[] body) {
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
        Map<String, Object> msg = null;
        int st = resp.statusCode();
        if (resp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            // Take the last data: payload that carries a result or error (the final response).
            for (String line : new String(resp.body(), StandardCharsets.UTF_8).split("\r?\n")) {
                if (!line.startsWith("data:")) continue;
                try {
                    Map<String, Object> m = map(parse(line.substring(5).getBytes(StandardCharsets.UTF_8)));
                    if (m != null && (m.get("result") != null || m.get("error") != null)) msg = m;
                } catch (IOException ignored) {
                    // not JSON: skipped
                }
            }
        } else {
            try {
                msg = map(parse(resp.body()));
            } catch (IOException e) {
                throw new McpException("HTTP " + st + ": " + e.getMessage(), e);
            }
        }
        if (msg == null || (msg.get("result") == null && msg.get("error") == null))
            throw new McpException("HTTP " + st + ": response has neither result nor error");
        return msg;
    }

    HttpResponse<byte[]> post(String method, Map<String, Object> params, byte[] body, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(DEFAULT_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/event-stream");
        h.put("MCP-Protocol-Version", version);
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
            return (http != null ? http : DevTls.SHARED).send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted", e);
        } catch (IOException e) {
            throw new McpException("POST " + url + ": " + e, e);
        }
    }

    static String encodeHeader(String v) {
        boolean safe = v.equals(v.strip()) && !(v.startsWith("=?base64?") && v.endsWith("?="));
        for (char c : v.toCharArray()) if (c < 0x20 || c > 0x7e) safe = false;
        return safe ? v : "=?base64?" + Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** Runs tools/call, looping over input_required rounds via respond (may be null). */
    public Map<String, Object> callTool(String name, Map<String, Object> args, Responder respond) {
        return callTool(name, args, respond, null, null, 0);
    }

    private Map<String, Object> callTool(String name, Map<String, Object> args, Responder respond,
                                         Map<String, Object> responses, Object state, int round) {
        for (; round < 8; round++) {
            Map<String, Object> p = obj("name", name, "arguments", args);
            if (responses != null) p.put("inputResponses", responses);
            if (state != null) p.put("requestState", state);
            Map<String, Object> res;
            try {
                res = call("tools/call", p);
            } catch (PendingException pe) {
                // The paused request got 401/403 before dispatch: the server has NOT run this round. Resume sends
                // exactly this round (same inputResponses/requestState) for the first time; earlier rounds are not
                // replayed and the user is not re-asked.
                Map<String, Object> rs = responses;
                Object st = state;
                int r = round;
                auth.setRetry(pe.pending.id(), () -> callTool(name, args, respond, rs, st, r));
                throw pe;
            }
            if (!"input_required".equals(res.get("resultType"))) return res;
            state = res.get("requestState");
            responses = new LinkedHashMap<>();
            if (respond == null)
                throw new McpException("tools/call " + name + ": server asked for input but no Responder was given");
            Map<String, Object> reqs = map(res.get("inputRequests"));
            if (reqs != null) {
                for (var e : reqs.entrySet()) {
                    Map<String, Object> rq = map(e.getValue());
                    if (rq == null) throw new McpException("tools/call " + name + ": malformed input request \"" + e.getKey() + "\"");
                    try {
                        responses.put(e.getKey(), respond.answer(e.getKey(), rq));
                    } catch (RuntimeException x) {
                        throw x;
                    } catch (Exception x) {
                        throw new McpException(x.getMessage(), x);
                    }
                }
            }
        }
        throw new McpException("tools/call " + name + ": too many input_required rounds");
    }

    // ---- lazy tool search (ToolSearch.java holds the ranking) ----

    /** The tool definitions to give the model: every server tool, or (toolSearch) the two meta-tools. Run them with invoke. */
    public List<Map<String, Object>> tools() {
        return toolSearch ? ToolSearch.metaTools(description) : listTools();
    }

    /** Runs a tool the model asked for: a server tool, or search_tools / call_tool in toolSearch mode. */
    public Map<String, Object> invoke(String name, Map<String, Object> args, Responder respond) {
        if (!toolSearch) return callTool(name, args, respond);
        return switch (name) {
            case "search_tools" -> {
                int limit = args.get("limit") instanceof Number n && n.doubleValue() > 0 ? n.intValue() : 5;
                List<Map<String, Object>> hits = searchTools(str(args.get("query")), limit);
                yield obj("resultType", "complete", "content", List.of(obj("type", "text", "text", json(hits))),
                        "structuredContent", obj("tools", hits), "isError", false);
            }
            case "call_tool" -> callTool(str(args.get("name")), map(args.get("arguments")), respond);
            default -> throw new McpException("unknown tool \"" + name + "\" (ToolSearch mode exposes search_tools and call_tool)");
        };
    }

    /** Ranks the server's tools against query (BM25; name tokens count twice). */
    public List<Map<String, Object>> searchTools(String query, int limit) {
        return ToolSearch.rank(listTools(), query, limit, description);
    }

    /** tools/list, cached for the server's ttlMs. */
    List<Map<String, Object>> listTools() {
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

    // ---- skills ----

    /**
     * Fetches a skill (skills/get + resources/read for every file) into dir/&lt;name&gt;/, verifying each
     * file's sha256 digest. A file whose URI is not inside the skill is refused; nothing is written until all pass.
     */
    public void downloadSkill(String uri, Path dir) throws IOException {
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
            if (!d.isEmpty() && !d.equals("sha256:" + Server.sha256Hex(data))) throw new McpException(fu + ": digest mismatch");
            files.put(dir.resolve(name).resolve(rel), data);
        }
        for (var f : files.entrySet()) {
            Files.createDirectories(f.getKey().getParent());
            Files.write(f.getKey(), f.getValue());
        }
    }

    /** Stops a stdio server (close stdin, then kill after 2s); no-op for HTTP clients. */
    @Override
    public void close() {
        if (stdio != null) stdio.close();
    }
}
