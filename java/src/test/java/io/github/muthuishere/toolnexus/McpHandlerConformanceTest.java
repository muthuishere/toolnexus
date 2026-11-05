package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC §7C.1 cross-port conformance: replays {@code conformance/mcp_handler/cases.json} (harness
 * contract in {@code conformance/mcp_handler/README.md}); the Go reference is
 * {@code golang/mcphandler_conformance_test.go}.
 */
class McpHandlerConformanceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Path casesFile() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            Path p = d.resolve("conformance/mcp_handler/cases.json");
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("conformance/mcp_handler/cases.json not found above " + dir);
    }

    @TestFactory
    List<DynamicTest> conformance() throws Exception {
        JsonNode file = M.readTree(Files.readString(casesFile()));
        JsonNode fixture = file.get("fixture");
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode sc : file.get("scenarios")) {
            out.add(DynamicTest.dynamicTest(sc.get("name").asText(), () -> runScenario(fixture, sc)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode n) {
        return n == null || n.isNull() ? null : M.convertValue(n, LinkedHashMap.class);
    }

    private static ToolResult result(JsonNode r) {
        Object structured = r.hasNonNull("structured") ? M.convertValue(r.get("structured"), Object.class) : null;
        return new ToolResult(r.path("output").asText(""), r.path("isError").asBoolean(false), null, null,
                structured, map(r.get("meta")));
    }

    private static Boolean bool(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v != null && v.isBoolean() ? v.asBoolean() : null;
    }

    private static Tool fixtureTool(JsonNode ft, List<String> executed) {
        String name = ft.get("name").asText();
        Map<String, Object> input = map(ft.get("inputSchema"));
        Map<String, Object> output = map(ft.get("outputSchema"));
        JsonNode a = ft.get("annotations");
        ToolAnnotations ann = a == null ? null : new ToolAnnotations(
                a.hasNonNull("title") ? a.get("title").asText() : null,
                bool(a, "readOnlyHint"), bool(a, "destructiveHint"), bool(a, "idempotentHint"), bool(a, "openWorldHint"));
        JsonNode h = ft.get("hints");
        ToolHints hints = h == null ? null : new ToolHints(h.path("readOnly").asBoolean(),
                h.path("destructive").asBoolean(), h.path("openWorld").asBoolean());
        String behavior = ft.get("behavior").asText();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return ft.path("description").asText(""); }
            @Override public Map<String, Object> inputSchema() { return input; }
            @Override public String source() { return "native"; }
            @Override public ToolHints hints() { return hints; }
            @Override public Map<String, Object> outputSchema() { return output; }
            @Override public ToolAnnotations annotations() { return ann; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                executed.add(name);
                switch (behavior) {
                    case "echo":
                        return ToolResult.ok(String.valueOf(args.getOrDefault("text", "")));
                    case "ask": {
                        Answer a = ctx == null ? null : ctx.answer();
                        if (a != null && a.ok()) {
                            Object n = a.data() == null ? null : a.data().get("name");
                            return ToolResult.ok("Hello, " + (n instanceof String x ? x : "") + "!");
                        }
                        Map<String, Object> schema = new LinkedHashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", Map.of("name", Map.of("type", "string")));
                        schema.put("required", List.of("name"));
                        return ToolResult.pending(new Request("name", "input", "What is your name?", null,
                                Map.of("schema", schema), null));
                    }
                    case "whoami": {
                        String id = identityName(McpHandler.identityFrom(ctx));
                        Map<String, Object> meta = McpHandler.mcpRequestMetaFrom(ctx);
                        Object trace = meta == null ? null : meta.get("trace");
                        return ToolResult.ok(id + "|" + (trace == null ? "" : trace));
                    }
                    case "static":
                        return result(ft.get("result"));
                    case "error":
                        throw new RuntimeException(ft.get("error").asText());
                    default:
                        throw new IllegalStateException("unknown behavior " + behavior);
                }
            }
        };
    }

    /** README identity-name rule: a string verbatim, a Principal's subject, else "". */
    private static String identityName(Object v) {
        if (v instanceof String s) return s;
        if (v instanceof McpServerAuth.Principal p) return p.subject() == null ? "" : p.subject();
        return "";
    }

    /** README {@code mcp2026}: stamp params._meta protocolVersion unless already present. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stamp2026(Map<String, Object> body) {
        Object p = body.get("params");
        Map<String, Object> params = p instanceof Map<?, ?> ? (Map<String, Object>) p : new LinkedHashMap<>();
        Object m = params.get("_meta");
        Map<String, Object> meta = m instanceof Map<?, ?> ? (Map<String, Object>) m : new LinkedHashMap<>();
        meta.putIfAbsent("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        params.put("_meta", meta);
        body.put("params", params);
        return body;
    }

    private static Path repoRoot() {
        return casesFile().getParent().getParent().getParent();
    }

    private static void runScenario(JsonNode fixture, JsonNode sc) throws Exception {
        List<String> executed = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> onCalls = new CopyOnWriteArrayList<>();
        List<Tool> tools = new ArrayList<>();
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode ft : fixture.get("tools")) {
            tools.add(fixtureTool(ft, executed));
            byName.put(ft.get("name").asText(), ft);
        }
        JsonNode c = sc.path("config");
        McpHandler.Config cfg = new McpHandler.Config();
        if (c.path("auth").asBoolean(false)) {
            JsonNode tokens = fixture.path("auth").path("tokens");
            cfg.authenticate(ex -> {
                String tok = ex.getRequestHeaders().getFirst("Authorization");
                JsonNode id = tok == null ? null : tokens.get(tok);
                if (id == null) throw new IllegalArgumentException("unauthorized");
                return id.asText();
            });
            String www = fixture.path("auth").path("wwwAuthenticate").asText();
            cfg.wwwAuthenticate((ex, e) -> www);
        }
        cfg.listTools(ctx -> {
            List<Tool> visible = new ArrayList<>();
            for (Tool t : tools) {
                JsonNode vis = byName.get(t.name()).get("visibleTo");
                if (vis == null) { visible.add(t); continue; }
                for (JsonNode v : vis) if (v.asText().equals(identityName(ctx.identity()))) { visible.add(t); break; }
            }
            return visible;
        });
        cfg.beforeCall((ctx, tool, args) -> {
            JsonNode d = byName.get(tool.name()).get("decision");
            if (d == null) return McpHandler.Decision.allow();
            return new McpHandler.Decision(
                    McpHandler.DecisionKind.valueOf(d.get("kind").asText().toUpperCase()),
                    d.hasNonNull("message") ? d.get("message").asText() : null,
                    d.hasNonNull("result") ? result(d.get("result")) : null);
        });
        cfg.onCall(ev -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ev.name());
            m.put("isError", ev.isError());
            m.put("decision", ev.decision().wire());
            onCalls.add(m);
        });
        if (c.has("name")) cfg.name(c.get("name").asText());
        if (c.has("title")) cfg.title(c.get("title").asText());
        if (c.has("version")) cfg.version(c.get("version").asText());
        if (c.has("instructions")) cfg.instructions(c.get("instructions").asText());
        if (c.has("stateless")) cfg.stateless(c.get("stateless").asBoolean());
        if (c.has("statelessSessionId")) cfg.statelessSessionId(c.get("statelessSessionId").asText());
        if (c.has("protocolVersions")) {
            List<String> vs = new ArrayList<>();
            for (JsonNode v : c.get("protocolVersions")) vs.add(v.asText());
            cfg.protocolVersions(vs);
        }

        if (c.path("skills").asBoolean(false)) {
            Path skillsDir = repoRoot().resolve("examples/skills");
            cfg.listSkills(ctx -> McpHandler.loadMcpSkills(skillsDir));
        }
        McpServerAuth serverAuth = null;
        if (c.path("serverAuth").asBoolean(false)) {
            JsonNode fa = fixture.get("serverAuth");
            Map<String, List<String>> scopes = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = fa.get("scopes").fields(); it.hasNext(); ) {
                var e = it.next();
                List<String> l = new ArrayList<>();
                for (JsonNode x : e.getValue()) l.add(x.asText());
                scopes.put(e.getKey(), l);
            }
            JsonNode principals = fa.get("principals");
            serverAuth = new McpServerAuth(fa.get("issuer").asText()).audience(fa.path("audience").asText(""))
                    .scopes(scopes).verify((ex, token, aud) -> {
                        JsonNode pr = principals.get(token);
                        if (pr == null) throw new IllegalArgumentException("bad token");
                        List<String> psc = new ArrayList<>();
                        for (JsonNode x : pr.get("scopes")) psc.add(x.asText());
                        return new McpServerAuth.Principal(pr.get("subject").asText(), psc, null);
                    });
            McpServerAuth sa = serverAuth;
            cfg.authenticate(sa::authenticate);
        }

        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        HttpServer metaSrv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        metaSrv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        if (serverAuth != null) metaSrv.createContext("/", serverAuth.metadata());
        else metaSrv.createContext("/", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
        metaSrv.start();
        String metaUrl = "http://127.0.0.1:" + metaSrv.getAddress().getPort();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        srv.createContext("/mcp", tk.mcpHandler(cfg));
        srv.start();
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/mcp";
        try {
            String sid = "";
            int i = 0;
            for (JsonNode step : sc.get("steps")) {
                String where = sc.get("name").asText() + " step " + (i++);
                JsonNode rq = step.get("request");
                String method = rq.get("method").asText();
                boolean is2026 = rq.path("mcp2026").asBoolean(false);
                Map<String, Object> bodyMap = rq.has("body") && rq.get("body").isObject() ? map(rq.get("body")) : null;
                if (is2026 && bodyMap != null) bodyMap = stamp2026(bodyMap);
                String body = rq.hasNonNull("rawBody") ? rq.get("rawBody").asText()
                        : bodyMap != null ? M.writeValueAsString(bodyMap)
                        : rq.has("body") ? M.writeValueAsString(rq.get("body")) : "";
                String target = rq.hasNonNull("target") ? metaUrl + rq.get("target").asText() : url;
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target))
                        .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body));
                // Headers are collected case-insensitively so `headers` can override or remove them.
                Map<String, String> hs = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                if (method.equals("POST")) hs.put("Content-Type", rq.path("contentType").asText("application/json"));
                hs.put("Accept", rq.path("accept").asText("application/json, text/event-stream"));
                String token = rq.hasNonNull("token") ? rq.get("token").asText() : sc.path("token").asText("");
                if (!token.isEmpty()) hs.put("Authorization", token);
                if (rq.hasNonNull("sessionId")) hs.put("Mcp-Session-Id", rq.get("sessionId").asText());
                else if (rq.path("session").asBoolean(false) && !sid.isEmpty()) hs.put("Mcp-Session-Id", sid);
                if (rq.hasNonNull("version")) hs.put("MCP-Protocol-Version", rq.get("version").asText());
                if (is2026 && bodyMap != null) {
                    String bm = bodyMap.get("method") instanceof String x ? x : "";
                    hs.put("MCP-Protocol-Version", "2026-07-28");
                    hs.put("Mcp-Method", bm);
                    Object ps = bodyMap.get("params");
                    Map<?, ?> pmap = ps instanceof Map<?, ?> pm ? pm : Map.of();
                    if (bm.equals("tools/call") || bm.equals("prompts/get")) {
                        hs.put("Mcp-Name", pmap.get("name") instanceof String x ? x : "");
                    } else if (bm.equals("resources/read")) {
                        hs.put("Mcp-Name", pmap.get("uri") instanceof String x ? x : "");
                    }
                }
                if (rq.has("headers")) {
                    for (Iterator<Map.Entry<String, JsonNode>> it = rq.get("headers").fields(); it.hasNext(); ) {
                        var e = it.next();
                        if (e.getValue().asText().isEmpty()) hs.remove(e.getKey());
                        else hs.put(e.getKey(), e.getValue().asText());
                    }
                }
                for (Map.Entry<String, String> e : hs.entrySet()) {
                    if (!e.getValue().isEmpty()) b.header(e.getKey(), e.getValue());
                }

                HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if ("sid".equals(step.path("capture").asText())) sid = resp.headers().firstValue("Mcp-Session-Id").orElse("");

                JsonNode ex = step.get("expect");
                if (ex.has("status")) assertEquals(ex.get("status").asInt(), resp.statusCode(), where + " status; body=" + resp.body());
                if (ex.has("headers")) {
                    for (Iterator<Map.Entry<String, JsonNode>> it = ex.get("headers").fields(); it.hasNext(); ) {
                        var e = it.next();
                        assertEquals(e.getValue().asText(), resp.headers().firstValue(e.getKey()).orElse(null), where + " header " + e.getKey());
                    }
                }
                if (ex.has("headersPresent")) for (JsonNode hn : ex.get("headersPresent")) {
                    assertFalse(resp.headers().firstValue(hn.asText()).orElse("").isEmpty(), where + " header present " + hn.asText());
                }
                if (ex.has("headersAbsent")) for (JsonNode hn : ex.get("headersAbsent")) {
                    assertTrue(resp.headers().firstValue(hn.asText()).orElse("").isEmpty(), where + " header absent " + hn.asText());
                }
                if (ex.has("json") || ex.has("jsonPath")) {
                    JsonNode got = M.readTree(resp.body());
                    if (ex.has("json")) assertEquals(ex.get("json"), got, where + " json");
                    if (ex.has("jsonPath")) {
                        for (Iterator<Map.Entry<String, JsonNode>> it = ex.get("jsonPath").fields(); it.hasNext(); ) {
                            var e = it.next();
                            JsonNode cur = got;
                            for (String seg : e.getKey().split("\\.")) {
                                cur = seg.matches("\\d+") ? cur.path(Integer.parseInt(seg)) : cur.path(seg);
                            }
                            assertEquals(e.getValue(), cur, where + " jsonPath " + e.getKey());
                        }
                    }
                }
            }
            if (sc.has("expectExecuted")) {
                List<String> want = new ArrayList<>();
                for (JsonNode n : sc.get("expectExecuted")) want.add(n.asText());
                assertEquals(want, executed, sc.get("name").asText() + " executed");
            }
            if (sc.has("expectOnCall")) {
                assertEquals(M.convertValue(sc.get("expectOnCall"), List.class), new ArrayList<>(onCalls),
                        sc.get("name").asText() + " onCall");
            }
        } finally {
            srv.stop(0);
            metaSrv.stop(0);
            tk.close();
        }
    }
}
