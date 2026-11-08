package io.github.muthuishere.toolnexus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC §7C.2 (MCP 2026-07-28 on McpHandler): McpServerAuth's default JWT verifier against the dev
 * authorization server, the dev AS flows, onCall/prompts/discover on the 2026 wire, loadMcpSkills.
 * Mirrors {@code golang/mcphandler2026_test.go}.
 */
class McpHandler2026Test {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try { cleanup.get(i).close(); } catch (Exception ignored) { }
        }
    }

    record Resp(int status, HttpResponse<String> raw, JsonNode body) {
        String www() { return raw.headers().firstValue("WWW-Authenticate").orElse(""); }
    }

    /** One 2026-07-28 request with the headers and _meta stamped. */
    private static Resp post26(String url, String token, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> p = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        p.put("_meta", meta);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", p);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", method);
        if (p.get("name") instanceof String n) b.header("Mcp-Name", n);
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j;
        try {
            j = M.readTree(r.body());
        } catch (Exception e) {
            j = null;
        }
        return new Resp(r.statusCode(), r, j);
    }

    private static Tool whoTool() {
        return new Tool() {
            @Override public String name() { return "who"; }
            @Override public String description() { return "who"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "native"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                Object id = McpHandler.identityFrom(ctx);
                return ToolResult.ok(id instanceof McpServerAuth.Principal p ? p.subject() : "");
            }
        };
    }

    private Toolkit tk() {
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        tk.register(whoTool());
        cleanup.add(tk);
        return tk;
    }

    private HttpServer server() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        cleanup.add(() -> srv.stop(0));
        return srv;
    }

    private static String base(HttpServer srv) {
        return "http://127.0.0.1:" + srv.getAddress().getPort();
    }

    private McpDevAuthServer devAs(McpDevAuthServer.Options o) {
        McpDevAuthServer as = McpDevAuthServer.start(o);
        cleanup.add(as);
        return as;
    }

    private static Map<String, Object> callWho() {
        return Map.of("name", "who", "arguments", Map.of());
    }

    @Test
    void defaultJwtVerifierWithDevAs() throws Exception {
        McpDevAuthServer as = devAs(new McpDevAuthServer.Options());
        McpServerAuth auth = new McpServerAuth(as.issuer())
                .scopes(Map.of("*", List.of("mcp"), "who", List.of("mcp:who")));
        HttpServer srv = server();
        srv.createContext("/mcp", tk().mcpHandler(new McpHandler.Config()
                .protocolVersions("2026-07-28").authenticate(auth::authenticate)));
        srv.createContext("/.well-known/oauth-protected-resource/", auth.metadata());
        srv.start();
        String u = base(srv) + "/mcp";
        long now = System.currentTimeMillis() / 1000;
        java.util.function.Function<Map<String, Object>, String> mint = over -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("iss", as.issuer());
            c.put("sub", "dev-user");
            c.put("aud", u);
            c.put("scope", "mcp mcp:who");
            c.put("exp", now + 60);
            c.putAll(over);
            return as.mint(c);
        };
        Resp ok = post26(u, mint.apply(Map.of()), "tools/call", callWho());
        assertEquals(200, ok.status(), ok.raw().body());
        assertEquals("dev-user", ok.body().at("/result/content/0/text").asText());

        String good = mint.apply(Map.of());
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("wrong audience", mint.apply(Map.of("aud", "https://other.example/mcp")));
        bad.put("aud array miss", mint.apply(Map.of("aud", List.of("x"))));
        bad.put("expired", mint.apply(Map.of("exp", now - 1)));
        bad.put("wrong issuer", mint.apply(Map.of("iss", "https://evil.example")));
        bad.put("tampered", good.substring(0, 20) + (good.charAt(20) == 'x' ? 'y' : 'x') + good.substring(21));
        bad.put("not a jwt", "opaque");
        for (Map.Entry<String, String> e : bad.entrySet()) {
            Resp r = post26(u, e.getValue(), "server/discover", null);
            assertEquals(401, r.status(), e.getKey());
            assertTrue(r.www().contains("error=\"invalid_token\""), e.getKey() + ": " + r.www());
            assertFalse(r.www().contains(e.getValue()), e.getKey() + " token leaked");
            assertEquals("{\"error\":\"unauthorized\"}", r.raw().body());
        }
        Resp up = post26(u, mint.apply(Map.of("scope", "mcp")), "tools/call", callWho());
        assertEquals(403, up.status());
        assertEquals("Bearer resource_metadata=\"" + base(srv) + "/.well-known/oauth-protected-resource/mcp\", scope=\"mcp mcp:who\", error=\"insufficient_scope\"",
                up.www());

        HttpResponse<String> prm = HTTP.send(HttpRequest.newBuilder(URI.create(base(srv) + "/.well-known/oauth-protected-resource/mcp")).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode doc = M.readTree(prm.body());
        assertEquals(u, doc.get("resource").asText());
        assertEquals(as.issuer(), doc.get("authorization_servers").get(0).asText());
        assertEquals("mcp", doc.get("scopes_supported").get(0).asText());
        assertEquals("header", doc.get("bearer_methods_supported").get(0).asText());
    }

    private static String form(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static HttpResponse<String> postForm(String url, Map<String, String> f) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(f))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    /** The authorization-code + PKCE flow against the dev AS, with no browser. */
    private static JsonNode pkceLogin(McpDevAuthServer as, String clientId, String redirect, String resource, String scope) throws Exception {
        String verifier = "v-" + "a".repeat(50);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", clientId);
        q.put("redirect_uri", redirect);
        q.put("state", "s1");
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        q.put("resource", resource);
        q.put("scope", scope);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(as.issuer() + "/authorize?" + form(q))).build(),
                HttpResponse.BodyHandlers.ofString()); // HttpClient.NEVER follows redirects by default
        assertEquals(302, r.statusCode(), r.body());
        URI loc = URI.create(r.headers().firstValue("Location").orElseThrow());
        Map<String, String> cb = query(loc.getRawQuery());
        assertEquals(as.issuer(), cb.get("iss"));
        assertEquals("s1", cb.get("state"));
        assertFalse(cb.getOrDefault("code", "").isEmpty());
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", cb.get("code"));
        f.put("code_verifier", verifier);
        f.put("client_id", clientId);
        f.put("redirect_uri", redirect);
        f.put("resource", resource);
        HttpResponse<String> tr = postForm(as.issuer() + "/token", f);
        assertEquals(200, tr.statusCode(), tr.body());
        return M.readTree(tr.body());
    }

    @Test
    void devAuthServerPreRegisteredAndCimd() throws Exception {
        HttpServer cimd = server();
        String docUrl = base(cimd) + "/client.json";
        cimd.createContext("/", ex -> {
            byte[] b = M.writeValueAsBytes(Map.of("client_id", docUrl, "client_name", "CIMD app",
                    "redirect_uris", List.of("http://127.0.0.1/cb")));
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        cimd.start();
        McpDevAuthServer as = devAs(new McpDevAuthServer.Options().clients(Map.of("app", List.of("http://127.0.0.1/cb"))));
        McpServerAuth auth = new McpServerAuth(as.issuer()).scopes(Map.of("*", List.of("mcp")));
        HttpServer srv = server();
        srv.createContext("/mcp", tk().mcpHandler(new McpHandler.Config()
                .protocolVersions("2026-07-28").authenticate(auth::authenticate)));
        srv.start();
        String u = base(srv) + "/mcp";
        for (String cid : List.of("app", docUrl)) {
            JsonNode tok = pkceLogin(as, cid, "http://127.0.0.1:5555/cb", u, "mcp");
            Resp r = post26(u, tok.get("access_token").asText(), "tools/call", callWho());
            assertEquals(200, r.status(), cid + " " + r.raw().body());
            assertEquals("dev-user", r.body().at("/result/content/0/text").asText());
            // refresh rotates and keeps working
            HttpResponse<String> rr = postForm(as.issuer() + "/token", Map.of("grant_type", "refresh_token",
                    "refresh_token", tok.get("refresh_token").asText(), "client_id", cid, "resource", u));
            assertEquals(200, rr.statusCode(), rr.body());
            HttpResponse<String> again = postForm(as.issuer() + "/token", Map.of("grant_type", "refresh_token",
                    "refresh_token", tok.get("refresh_token").asText(), "client_id", cid));
            assertEquals(400, again.statusCode(), "a rotated refresh token is single-use");
        }
        // unknown client / unregistered redirect never redirects
        HttpResponse<String> unknown = HTTP.send(HttpRequest.newBuilder(URI.create(
                as.issuer() + "/authorize?client_id=nobody&redirect_uri=http://127.0.0.1/cb")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, unknown.statusCode());
        assertEquals(3, as.authorizations());

        JsonNode md = M.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(as.issuer() + "/.well-known/oauth-authorization-server")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(as.issuer(), md.get("issuer").asText());
        assertTrue(md.get("client_id_metadata_document_supported").asBoolean());
        assertTrue(md.get("authorization_response_iss_parameter_supported").asBoolean());
    }

    @Test
    void onCallAndPromptsAndDiscoverOn2026() throws Exception {
        List<McpHandler.CallEvent> events = new CopyOnWriteArrayList<>();
        HttpServer srv = server();
        srv.createContext("/mcp", tk().mcpHandler(new McpHandler.Config()
                .protocolVersions("2026-07-28")
                .onCall(ev -> { events.add(ev); throw new RuntimeException("isolated"); })
                .listPrompts(ctx -> List.of(new McpHandler.Prompt("p", null, "d", null)))
                .getPrompt((ctx, name, args) -> new McpHandler.PromptResult(null,
                        List.of(new McpHandler.PromptMessage("user", "hi " + name))))));
        srv.start();
        String u = base(srv) + "/mcp";
        Resp r = post26(u, "", "tools/call", callWho());
        assertEquals(200, r.status());
        assertEquals(1, events.size());
        assertEquals("who", events.get(0).name());

        JsonNode caps = post26(u, "", "server/discover", null).body().at("/result/capabilities");
        assertTrue(caps.has("prompts") && caps.has("tools"));
        assertFalse(caps.has("extensions") || caps.has("resources"));

        JsonNode g = post26(u, "", "prompts/get", Map.of("name", "p")).body();
        assertEquals("hi p", g.at("/result/messages/0/content/text").asText());
        assertEquals("complete", g.at("/result/resultType").asText());
        assertEquals("toolnexus", g.at("/result/_meta/io.modelcontextprotocol~1serverInfo/name").asText());
    }

    @Test
    void loadMcpSkillsOnExamples() throws Exception {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path skills = null;
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isDirectory(d.resolve("examples/skills/hello-world"))) { skills = d.resolve("examples/skills"); break; }
        }
        assertNotNull(skills);
        List<McpHandler.Skill> got = McpHandler.loadMcpSkills(skills);
        assertEquals(1, got.size());
        McpHandler.Skill s = got.get(0);
        assertEquals("hello-world", s.name());
        assertEquals(List.of("SKILL.md", "scripts/greet.sh"), s.files().stream().map(McpHandler.SkillFile::path).toList());
        assertEquals("hello-world", s.frontmatter().get("name"));
        assertTrue(s.frontmatter().containsKey("description"));
    }
}
