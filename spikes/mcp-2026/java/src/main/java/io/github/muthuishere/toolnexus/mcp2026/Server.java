package io.github.muthuishere.toolnexus.mcp2026;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;

/** A stateless MCP 2026-07-28 server over POST /mcp. Serve it with any JDK HttpServer (it is an HttpHandler). */
public class Server implements HttpHandler {
    /** Larger request bodies get 413. */
    public static final int MAX_BODY_BYTES = 4 << 20;

    private record SkillFile(String uri, byte[] data) {}

    private record Skill(String uri, Map<String, Object> frontmatter, List<SkillFile> files) {
        Map<String, Object> entry() {
            List<Object> res = new ArrayList<>();
            for (SkillFile f : files) res.add(obj("uri", f.uri, "digest", "sha256:" + sha256Hex(f.data), "size", f.data.length));
            return obj("uri", uri, "frontmatter", frontmatter, "resources", res);
        }
    }

    private Object serverInfoValue; // fixture "server" object; null for NewServer()
    private final List<Skill> skills = new ArrayList<>();
    private final Map<String, ToolFunc> handlers = new LinkedHashMap<>();
    private final List<Map<String, Object>> tools = new ArrayList<>(); // fixture + code tools, sorted by name
    private volatile ServerAuth auth; // null = unprotected (the default; cross-port checks run without auth)

    /** An empty server: add tools with {@link #tool}, protect it with {@link #auth(ServerAuth)}. */
    public Server() {}

    public Server auth(ServerAuth a) { this.auth = a; return this; }

    public ServerAuth auth() { return auth; }

    /** Registers a code-defined tool (listed in tools/list, checked before fixture tools). Not safe once serving. */
    public Server tool(String name, String description, ToolFunc h) {
        handlers.put(name, h);
        tools.removeIf(t -> name.equals(t.get("name")));
        tools.add(obj("name", name, "description", description, "inputSchema", obj("type", "object")));
        tools.sort(Comparator.comparing(t -> (String) t.get("name")));
        return this;
    }

    /** The shared spike fixture (tools echo + greet, skill hello-world) from ../fixture.json or $FIXTURE. */
    public static Server fixture() {
        String fx = System.getenv("FIXTURE");
        Path p = fx != null && !fx.isEmpty() ? Path.of(fx) : findFixture();
        try {
            return load(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path findFixture() {
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            for (Path c : List.of(d.resolve("fixture.json"), d.resolve("spikes/mcp-2026/fixture.json")))
                if (Files.isRegularFile(c)) return c;
        }
        throw new IllegalStateException("fixture.json not found above " + Path.of("").toAbsolutePath() + " (set FIXTURE)");
    }

    public static Server load(Path fixturePath) throws IOException {
        Map<String, Object> fx = map(parse(Files.readAllBytes(fixturePath)));
        Server s = new Server();
        s.serverInfoValue = fx.get("server");
        for (Object t : list(fx.get("tools"))) {
            Map<String, Object> m = map(t);
            if (m == null || !(m.get("name") instanceof String)) throw new IOException("fixture tool without a name: " + t);
            s.tools.add(m);
        }
        s.tools.sort(Comparator.comparing(t -> (String) t.get("name")));
        Path root = fixturePath.toAbsolutePath().getParent().resolve(str(fx.get("skillsDir"))).normalize();
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : w.filter(p -> p.getFileName().toString().equals("SKILL.md") && Files.isRegularFile(p)).toList())
                s.skills.add(loadSkill(p.getParent()));
        }
        s.skills.sort(Comparator.comparing(Skill::uri));
        return s;
    }

    private static Skill loadSkill(Path dir) throws IOException {
        Map<String, Object> fm = parseFrontmatter(Files.readString(dir.resolve("SKILL.md")));
        String name = str(fm.get("name"));
        if (name.isEmpty() || !name.equals(dir.getFileName().toString()))
            throw new IOException("skill " + dir + ": name must match dir");
        List<SkillFile> files = new ArrayList<>();
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                files.add(new SkillFile("skill://" + name + "/" + rel, Files.readAllBytes(p)));
            }
        }
        files.sort(Comparator.comparing(SkillFile::uri));
        return new Skill("skill://" + name + "/SKILL.md", fm, files);
    }

    /** The flat `key: value` YAML subset the fixture skills use. */
    static Map<String, Object> parseFrontmatter(String md) {
        Map<String, Object> fm = new LinkedHashMap<>();
        md = md.replace("\r\n", "\n");
        if (!md.startsWith("---\n")) return fm;
        int end = md.substring(4).indexOf("\n---");
        if (end < 0) return fm;
        for (String line : md.substring(4, 4 + end).split("\n", -1)) {
            int c = line.indexOf(':');
            if (c >= 0 && !line.startsWith(" "))
                fm.put(line.substring(0, c).trim(), trimQuotes(line.substring(c + 1).trim()));
        }
        return fm;
    }

    private static String trimQuotes(String v) {
        int a = 0, b = v.length();
        while (a < b && (v.charAt(a) == '"' || v.charAt(a) == '\'')) a++;
        while (b > a && (v.charAt(b - 1) == '"' || v.charAt(b - 1) == '\'')) b--;
        return v.substring(a, b);
    }

    private Map<String, Object> serverInfo() { return obj(META_SERVER_INFO, serverInfoValue); }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        handle(HttpReq.of(ex, MAX_BODY_BYTES)).writeTo(ex);
    }

    /** The whole server as a function: request in, response out (no sockets needed). */
    public HttpResp handle(HttpReq r) {
        ServerAuth a = auth;
        if (a != null && r.method().equals("GET")) {
            HttpResp md = a.serveMetadata(r);
            if (md != null) return md;
        }
        if (!r.path().equals("/mcp")) return HttpResp.text(404, "404 page not found\n");
        if (!r.method().equals("POST")) return HttpResp.status(405);
        if (r.body().length > MAX_BODY_BYTES)
            return rpc(413, null, null, new RpcError(-32600, "Request body too large or unreadable"));
        Map<String, Object> req;
        try {
            req = map(parse(r.body()));
            if (req == null) throw new IOException("not an object");
        } catch (Exception e) {
            return rpc(400, null, null, new RpcError(-32700, "Parse error"));
        }
        Object id = req.get("id");
        if (id == null) return HttpResp.status(202); // notification: none defined in core; accept
        String method = str(req.get("method"));
        Map<String, Object> params = map(req.get("params"));
        if (params == null) params = new LinkedHashMap<>();
        RpcError bad = validate(r, method, params);
        if (bad != null) return rpc(400, id, null, bad);
        Principal principal = null;
        if (a != null) {
            ServerAuth.Check c = a.check(r, method, params);
            if (c.deny() != null) return c.deny();
            principal = c.principal();
        }
        try {
            return rpc(200, id, dispatch(new ToolContext(principal), method, params), null);
        } catch (RpcError e) {
            return rpc(e.code == CODE_METHOD_NOT_FOUND ? 404 : 200, id, null, e);
        }
    }

    private RpcError validate(HttpReq r, String method, Map<String, Object> p) {
        String hv = r.header("MCP-Protocol-Version");
        if (hv.isEmpty()) return mismatch("MCP-Protocol-Version header missing");
        String hm = r.header("Mcp-Method");
        if (!hm.equals(method))
            return mismatch("Mcp-Method header value '" + hm + "' does not match body value '" + method + "'");
        if (method.equals("tools/call") || method.equals("resources/read") || method.equals("prompts/get")) {
            String want = str(p.get(method.equals("resources/read") ? "uri" : "name"));
            String got = decodeHeader(r.header("Mcp-Name"));
            if (got == null || !got.equals(want))
                return mismatch("Mcp-Name header value '" + (got == null ? "" : got) + "' does not match body value '" + want + "'");
        }
        Map<String, Object> meta = map(p.get("_meta"));
        String mv = meta == null ? "" : str(meta.get(META_VERSION));
        if (!mv.equals(hv))
            return mismatch("MCP-Protocol-Version header value '" + hv + "' does not match _meta value '" + mv + "'");
        if (!mv.equals(PROTOCOL_VERSION)) return unsupported(mv);
        return null;
    }

    private static RpcError mismatch(String msg) { return new RpcError(CODE_HEADER_MISMATCH, "Header mismatch: " + msg); }

    static RpcError unsupported(String requested) {
        return new RpcError(CODE_UNSUPPORTED_PROTOCOL, "Unsupported protocol version",
                obj("supported", List.of(PROTOCOL_VERSION), "requested", requested));
    }

    /** Decodes the =?base64?...?= sentinel; null = invalid or empty. */
    static String decodeHeader(String v) {
        if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length() >= 11) {
            try {
                return new String(Base64.getDecoder().decode(v.substring(9, v.length() - 2)), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return v.isEmpty() ? null : v;
    }

    Map<String, Object> dispatch(ToolContext ctx, String method, Map<String, Object> p) {
        switch (method) {
            case "server/discover":
                return obj("resultType", "complete", "supportedVersions", List.of(PROTOCOL_VERSION),
                        "capabilities", obj("tools", obj(), "resources", obj(), "extensions", obj(SKILLS_EXT, obj())),
                        "_meta", serverInfo(), "ttlMs", 3600000, "cacheScope", "public");
            case "tools/list":
                return obj("resultType", "complete", "tools", tools, "ttlMs", 60000, "cacheScope", "public", "_meta", serverInfo());
            case "tools/call":
                return callTool(ctx, p);
            case "skills/list": {
                List<Object> l = new ArrayList<>();
                for (Skill sk : skills) l.add(sk.entry());
                return obj("resultType", "complete", "skills", l, "ttlMs", 60000, "cacheScope", "public", "_meta", serverInfo());
            }
            case "skills/get": {
                String uri = str(p.get("uri"));
                checkUri(uri, "Invalid skill URI: ");
                for (Skill sk : skills)
                    if (sk.uri.equals(uri)) return obj("resultType", "complete", "skill", sk.entry(), "_meta", serverInfo());
                throw new RpcError(CODE_INVALID_PARAMS, "Unknown skill: " + uri);
            }
            case "resources/read": {
                String uri = str(p.get("uri"));
                checkUri(uri, "Invalid resource URI: ");
                for (Skill sk : skills)
                    for (SkillFile f : sk.files)
                        if (f.uri.equals(uri)) {
                            String mime = uri.endsWith(".md") ? "text/markdown" : "text/plain";
                            return obj("resultType", "complete", "contents",
                                    List.of(obj("uri", uri, "mimeType", mime, "text", new String(f.data, StandardCharsets.UTF_8))),
                                    "ttlMs", 60000, "cacheScope", "public", "_meta", serverInfo());
                        }
                throw new RpcError(CODE_INVALID_PARAMS, "Resource not found: " + uri);
            }
            default:
                throw new RpcError(CODE_METHOD_NOT_FOUND, "Method not found: " + method);
        }
    }

    private static void checkUri(String uri, String prefix) {
        try {
            SkillUri.parse(uri);
        } catch (IllegalArgumentException e) {
            throw new RpcError(CODE_INVALID_PARAMS, prefix + e.getMessage());
        }
    }

    private Map<String, Object> callTool(ToolContext ctx, Map<String, Object> p) {
        String name = str(p.get("name"));
        Map<String, Object> args = map(p.get("arguments"));
        ToolFunc h = handlers.get(name);
        if (h != null) {
            Object v;
            try {
                v = h.call(ctx, args);
            } catch (Exception e) {
                return obj("resultType", "complete", "content", List.of(obj("type", "text", "text", String.valueOf(e.getMessage()))), "isError", true);
            }
            if (v instanceof String s) return obj("resultType", "complete", "content", List.of(obj("type", "text", "text", s)), "isError", false);
            Map<String, Object> m = map(v);
            if (m != null && m.containsKey("content")) {
                m = new LinkedHashMap<>(m);
                m.putIfAbsent("resultType", "complete");
                return m;
            }
            return obj("resultType", "complete", "content", List.of(obj("type", "text", "text", json(v))), "structuredContent", v, "isError", false);
        }
        switch (name) {
            case "echo": {
                String t = args == null ? "" : str(args.get("text"));
                return text(t, obj("text", t));
            }
            case "greet": {
                Map<String, Object> resp = map(p.get("inputResponses"));
                Map<String, Object> nr = resp == null ? null : map(resp.get("name"));
                if (nr != null && "accept".equals(nr.get("action")) && map(nr.get("content")) != null) {
                    String n = str(map(nr.get("content")).get("name"));
                    if (!n.isEmpty()) return text("Hello, " + n + "!", obj("greeting", "Hello, " + n + "!"));
                }
                // Missing/declined answer: ask (again), per MRTR error-handling guidance.
                return obj("resultType", "input_required", "_meta", serverInfo(), "inputRequests", obj(
                        "name", obj("method", "elicitation/create", "params", obj(
                                "mode", "form", "message", "What is your name?",
                                "requestedSchema", obj("type", "object", "properties", obj("name", obj("type", "string")),
                                        "required", List.of("name"))))));
            }
            default:
                throw new RpcError(CODE_INVALID_PARAMS, "Unknown tool: " + name);
        }
    }

    private Map<String, Object> text(String t, Object structured) {
        Map<String, Object> r = obj("resultType", "complete", "content", List.of(obj("type", "text", "text", t)), "isError", false, "_meta", serverInfo());
        if (structured != null) r.put("structuredContent", structured);
        return r;
    }

    static HttpResp rpc(int status, Object id, Map<String, Object> result, RpcError e) {
        Map<String, Object> msg = obj("jsonrpc", "2.0", "id", id);
        if (e != null) msg.put("error", e.toJson());
        else msg.put("result", result);
        return HttpResp.json(status, msg);
    }

    static String sha256Hex(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Serves this server as newline-delimited JSON-RPC over in/out (one message per line) until in closes.
     * No auth and no HTTP headers exist on stdio; only the _meta protocol version is checked.
     */
    public void serveStdio(InputStream in, OutputStream out) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line; (line = r.readLine()) != null; ) {
            Map<String, Object> req;
            try {
                req = map(parse(line.getBytes(StandardCharsets.UTF_8)));
                if (req == null) throw new IOException("not an object");
            } catch (Exception e) {
                writeLine(out, obj("jsonrpc", "2.0", "id", null, "error", new RpcError(-32700, "Parse error").toJson()));
                continue;
            }
            Object id = req.get("id");
            if (id == null) continue;
            Map<String, Object> params = map(req.get("params"));
            if (params == null) params = new LinkedHashMap<>();
            Map<String, Object> meta = map(params.get("_meta"));
            String mv = meta == null ? "" : str(meta.get(META_VERSION));
            Map<String, Object> msg = obj("jsonrpc", "2.0", "id", id);
            try {
                if (!mv.equals(PROTOCOL_VERSION)) throw unsupported(mv);
                msg.put("result", dispatch(new ToolContext(null), str(req.get("method")), params));
            } catch (RpcError e) {
                msg.put("error", e.toJson());
            }
            writeLine(out, msg);
        }
    }

    private static void writeLine(OutputStream out, Object msg) throws IOException {
        out.write(jsonBytes(msg));
        out.write('\n');
        out.flush();
    }
}
