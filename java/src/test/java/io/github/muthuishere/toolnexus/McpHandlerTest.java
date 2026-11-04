package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpHandler (SPEC §7C.1, ADR 0039): the embeddable MCP server, exercised with the official MCP
 * Java SDK CLIENT over real streamable HTTP, plus raw HTTP checks for the 2025-06-18 transport
 * rules. Mirrors {@code golang/mcphandler_test.go} test for test.
 */
class McpHandlerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try { cleanup.get(i).close(); } catch (Exception ignored) { }
        }
    }

    record User(String name) {}

    private static Tool testTool(String name, AtomicInteger ran) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("id", Map.of("type", "string")));
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " tool"; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public String source() { return "native"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                if (ran != null) ran.incrementAndGet();
                Object id = McpHandler.identityFrom(ctx);
                return ToolResult.ok(name + ":" + (id instanceof User u ? u.name() : ""));
            }
        };
    }

    private Toolkit tk() {
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        cleanup.add(tk);
        return tk;
    }

    /** Mount the handler on a JDK HttpServer with a multi-threaded executor; return its URL. */
    private String serve(McpHandler h) {
        try {
            HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            srv.createContext("/mcp", h);
            srv.start();
            cleanup.add(() -> srv.stop(0));
            return "http://127.0.0.1:" + srv.getAddress().getPort() + "/mcp";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object bearerAuth(com.sun.net.httpserver.HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        String tok = h == null ? "" : h.replaceFirst("^Bearer ", "");
        if (tok.equals("alice") || tok.equals("bob")) return new User(tok);
        throw new IllegalArgumentException("bad token");
    }

    private McpSyncClient dial(String url, String token,
                               Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicit) {
        HttpRequest.Builder rb = HttpRequest.newBuilder();
        if (token != null && !token.isEmpty()) rb.header("Authorization", "Bearer " + token);
        HttpClientStreamableHttpTransport tr = HttpClientStreamableHttpTransport.builder(url.substring(0, url.length() - 4))
                .endpoint("/mcp")
                .requestBuilder(rb)
                .jsonMapper(McpServe.JSON_MAPPER).supportedProtocolVersions(List.of("2025-06-18"))
                .build();
        var spec = McpClient.sync(tr)
                .clientInfo(new McpSchema.Implementation("test", "1"))
                .requestTimeout(Duration.ofSeconds(20));
        if (elicit != null) {
            spec.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build());
            spec.elicitation(elicit);
        }
        McpSyncClient c = spec.build();
        c.initialize();
        cleanup.add(c::closeGracefully);
        return c;
    }

    private static McpSchema.CallToolResult call(McpSyncClient c, String name, Map<String, Object> args) {
        return c.callTool(new McpSchema.CallToolRequest(name, args == null ? Map.of() : args));
    }

    private static String text(McpSchema.CallToolResult r) {
        if (r == null || r.content().isEmpty()) return "";
        return r.content().get(0) instanceof McpSchema.TextContent t ? t.text() : "";
    }

    private static List<String> names(McpSyncClient c) {
        List<String> out = new ArrayList<>();
        for (McpSchema.Tool t : c.listTools().tools()) out.add(t.name());
        return out;
    }

    private static boolean isErr(McpSchema.CallToolResult r) {
        return Boolean.TRUE.equals(r.isError());
    }

    @Test
    void authRejectsWith401AndWwwAuthenticate() throws Exception {
        McpHandler h = tk().mcpHandler(new McpHandler.Config()
                .authenticate(McpHandlerTest::bearerAuth)
                .wwwAuthenticate((ex, e) -> "Bearer resource_metadata=\"https://example.test/.well-known/oauth-protected-resource\""));
        HttpResponse<String> r = rawPost(serve(h), "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", Map.of());
        assertEquals(401, r.statusCode());
        assertTrue(r.headers().firstValue("WWW-Authenticate").orElse("").contains("resource_metadata="));
    }

    @Test
    void perIdentityToolsAndNotFoundNeverExecutes() {
        AtomicInteger ranAdmin = new AtomicInteger();
        McpHandler h = tk().mcpHandler(new McpHandler.Config()
                .authenticate(McpHandlerTest::bearerAuth)
                .listTools(ctx -> {
                    List<Tool> tools = new ArrayList<>(List.of(testTool("search", null)));
                    if (((User) ctx.identity()).name().equals("alice")) tools.add(testTool("admin_reset", ranAdmin));
                    return tools;
                }));
        String url = serve(h);
        McpSyncClient alice = dial(url, "alice", null);
        McpSyncClient bob = dial(url, "bob", null);
        assertEquals(List.of("search", "admin_reset"), names(alice));
        assertEquals(List.of("search"), names(bob));
        assertEquals("search:bob", text(call(bob, "search", null)));
        Exception e = assertThrows(Exception.class, () -> call(bob, "admin_reset", null));
        assertTrue(String.valueOf(e.getMessage()).contains("tool not found"), e.getMessage());
        assertEquals(0, ranAdmin.get());
        assertEquals("admin_reset:alice", text(call(alice, "admin_reset", null)));
    }

    private record Gated(McpHandler handler, AtomicInteger ran) {}

    private Gated gated(ToolResult fallback) {
        AtomicInteger ran = new AtomicInteger();
        Tool del = Tool.withAnnotations(testTool("delete_record", ran),
                new ToolAnnotations("Delete", false, true, true, false));
        McpHandler h = tk().mcpHandler(new McpHandler.Config()
                .authenticate(McpHandlerTest::bearerAuth)
                .listTools(ctx -> List.of(del, testTool("read", null)))
                .beforeCall((ctx, tool, args) -> {
                    if (tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().destructiveHint())) {
                        if (args.contains("\"nonce-ok\"")) return McpHandler.Decision.allow();
                        return McpHandler.Decision.confirm("Delete the record?", fallback);
                    }
                    if (args.contains("\"forbidden\"")) return McpHandler.Decision.deny("not allowed");
                    return null;
                }));
        return new Gated(h, ran);
    }

    @Test
    void confirmViaElicitation() {
        Gated g = gated(null);
        String url = serve(g.handler());
        AtomicInteger asked = new AtomicInteger();
        McpSyncClient yes = dial(url, "alice", req -> {
            asked.incrementAndGet();
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("confirm", true));
        });
        McpSchema.CallToolResult r = call(yes, "delete_record", Map.of("id", "1"));
        assertFalse(isErr(r));
        assertEquals("delete_record:alice", text(r));
        assertEquals(1, asked.get());
        assertEquals(1, g.ran().get());

        McpSyncClient no = dial(url, "alice", req ->
                new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, Map.of("confirm", false)));
        r = call(no, "delete_record", Map.of("id", "1"));
        assertTrue(isErr(r));
        assertEquals("declined", text(r));
        assertEquals(1, g.ran().get());
    }

    @Test
    void confirmViaRawElicitationRoundTrip() throws Exception {
        // The same flow over raw HTTP: the POST's response upgrades to SSE carrying
        // elicitation/create; the answer arrives on a second POST; the tool result follows.
        Gated g = gated(null);
        String url = serve(g.handler());
        Map<String, String> auth = Map.of("Authorization", "Bearer alice", "MCP-Protocol-Version", "2025-06-18");
        HttpResponse<String> init = rawPost(url, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"elicitation\":{}}}}", Map.of("Authorization", "Bearer alice"));
        String sid = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        HttpRequest req = post(url, sid, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"delete_record\",\"arguments\":{\"id\":\"1\"}}}", auth);
        HttpResponse<java.io.InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals("text/event-stream", resp.headers().firstValue("Content-Type").orElse(""));
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body()));
        String first = nextData(reader);
        Map<String, Object> ask = Json.toMap(first);
        assertEquals("elicitation/create", ask.get("method"));
        assertTrue(first.contains("Delete the record?"));
        HttpResponse<String> ans = rawPost(url, sid, "{\"jsonrpc\":\"2.0\",\"id\":\"" + ask.get("id") + "\",\"result\":{\"action\":\"accept\",\"content\":{\"confirm\":true}}}", auth);
        assertEquals(202, ans.statusCode());
        String second = nextData(reader);
        assertTrue(second.contains("delete_record:alice"), second);
        assertEquals(1, g.ran().get());
    }

    private static String nextData(java.io.BufferedReader r) throws Exception {
        String line;
        while ((line = r.readLine()) != null) if (line.startsWith("data: ")) return line.substring(6);
        throw new AssertionError("stream ended");
    }

    @Test
    void confirmWithoutElicitation() {
        Gated g = gated(null);
        McpSyncClient c = dial(serve(g.handler()), "alice", null);
        McpSchema.CallToolResult r = call(c, "delete_record", Map.of("id", "1"));
        assertTrue(isErr(r));
        assertEquals("confirmation required: Delete the record?", text(r));
        assertEquals(0, g.ran().get());

        Gated g2 = gated(ToolResult.ok("re-call with {\"nonce\":\"nonce-ok\"}"));
        McpSyncClient c2 = dial(serve(g2.handler()), "alice", null);
        r = call(c2, "delete_record", Map.of("id", "1"));
        assertFalse(isErr(r));
        assertTrue(text(r).contains("nonce-ok"));
        assertEquals(0, g2.ran().get());
        r = call(c2, "delete_record", Map.of("id", "1", "nonce", "nonce-ok"));
        assertEquals("delete_record:alice", text(r));
        assertEquals(1, g2.ran().get());
    }

    @Test
    void denyAndAnnotations() {
        Gated g = gated(null);
        McpSyncClient c = dial(serve(g.handler()), "alice", null);
        McpSchema.CallToolResult r = call(c, "read", Map.of("id", "forbidden"));
        assertTrue(isErr(r));
        assertEquals("not allowed", text(r));
        McpSchema.ToolAnnotations a = c.listTools().tools().get(0).annotations();
        assertEquals("Delete", a.title());
        assertTrue(a.destructiveHint());
        assertFalse(a.readOnlyHint());
        assertTrue(a.idempotentHint());
        assertFalse(a.openWorldHint());
    }

    @Test
    void onCallEvent() {
        List<McpHandler.CallEvent> evs = new CopyOnWriteArrayList<>();
        Tool boom = new Tool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "boom"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "native"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                throw new RuntimeException("kaput");
            }
        };
        McpHandler h = tk().mcpHandler(new McpHandler.Config()
                .authenticate(McpHandlerTest::bearerAuth)
                .listTools(ctx -> List.of(boom))
                .onCall(ev -> { evs.add(ev); throw new RuntimeException("isolated"); }));
        McpSyncClient c = dial(serve(h), "bob", null);
        McpSchema.CallToolResult r = call(c, "boom", null);
        assertTrue(isErr(r));
        assertEquals("kaput", text(r));
        assertEquals(1, evs.size());
        McpHandler.CallEvent ev = evs.get(0);
        assertEquals("boom", ev.name());
        assertTrue(ev.isError());
        assertNotNull(ev.err());
        assertTrue(ev.resultBytes() > 0);
        assertTrue(ev.ms() >= 5);
        assertEquals("bob", ((User) ev.identity()).name());
        assertFalse(ev.sessionId().isEmpty());
        assertEquals(McpHandler.DecisionKind.ALLOW, ev.decision());
    }

    @Test
    void listChangedNotification() throws Exception {
        McpHandler h = tk().mcpHandler(new McpHandler.Config().listTools(ctx -> List.of(testTool("a", null))));
        String url = serve(h);
        // Raw GET stream (the SDK client's GET listener is optional): initialize, open the stream, notify.
        HttpResponse<String> init = rawPost(url, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}", Map.of());
        String sid = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        HttpRequest get = HttpRequest.newBuilder(URI.create(url)).GET()
                .header("Accept", "text/event-stream").header("Mcp-Session-Id", sid).build();
        HttpResponse<java.io.InputStream> resp = HTTP.send(get, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                got.set(nextData(new java.io.BufferedReader(new java.io.InputStreamReader(resp.body()))));
                latch.countDown();
            } catch (Exception ignored) { }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (latch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            h.notifyToolsChanged();
            latch.await(50, TimeUnit.MILLISECONDS);
        }
        assertNotNull(got.get(), "no list_changed received");
        assertEquals("notifications/tools/list_changed", Json.toMap(got.get()).get("method"));
        resp.body().close();
    }

    @Test
    void listChangedReachesSdkClient() throws Exception {
        McpHandler h = tk().mcpHandler(new McpHandler.Config().listTools(ctx -> List.of(testTool("a", null))));
        String url = serve(h);
        CountDownLatch latch = new CountDownLatch(1);
        HttpClientStreamableHttpTransport tr = HttpClientStreamableHttpTransport.builder(url.substring(0, url.length() - 4)).endpoint("/mcp")
                .jsonMapper(McpServe.JSON_MAPPER).supportedProtocolVersions(List.of("2025-06-18")).build();
        McpSyncClient c = McpClient.sync(tr).clientInfo(new McpSchema.Implementation("t", "1"))
                .toolsChangeConsumer(tools -> latch.countDown()).build();
        cleanup.add(c::closeGracefully);
        c.initialize();
        long deadline = System.currentTimeMillis() + 5000;
        while (latch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            h.notifyToolsChanged();
            latch.await(50, TimeUnit.MILLISECONDS);
        }
        assertEquals(0, latch.getCount(), "SDK client never saw list_changed");
    }

    @Test
    void serverInfoResourcesPrompts() {
        McpHandler h = tk().mcpHandler(new McpHandler.Config()
                .authenticate(McpHandlerTest::bearerAuth)
                .serverInfo(ctx -> new McpHandler.ServerInfo(
                        "srv-" + ctx.exchange().getRequestHeaders().getFirst("Host").substring(0, 9) + "-" + ((User) ctx.identity()).name(),
                        null, "9.9.9", "be careful"))
                .listResources(ctx -> List.of(new McpHandler.Resource("docs://api", "api", null, null, "text/markdown")))
                .readResource((ctx, uri) -> List.of(new McpHandler.ResourceContent(uri, "text/markdown",
                        "# API for " + ((User) ctx.identity()).name(), null)))
                .listPrompts(ctx -> List.of(new McpHandler.Prompt("greet", null, null,
                        List.of(new McpHandler.PromptArgument("who", null, true)))))
                .getPrompt((ctx, name, args) -> new McpHandler.PromptResult(null,
                        List.of(new McpHandler.PromptMessage("user", "hello " + args.get("who"))))));
        McpSyncClient c = dial(serve(h), "alice", null);
        assertEquals("srv-127.0.0.1-alice", c.getServerInfo().name());
        assertEquals("9.9.9", c.getServerInfo().version());
        assertEquals("be careful", c.getServerInstructions());
        assertNotNull(c.getServerCapabilities().resources());
        assertNotNull(c.getServerCapabilities().prompts());
        var rl = c.listResources();
        assertEquals(1, rl.resources().size());
        assertEquals("docs://api", rl.resources().get(0).uri());
        var rd = c.readResource(new McpSchema.ReadResourceRequest("docs://api"));
        assertEquals("# API for alice", ((McpSchema.TextResourceContents) rd.contents().get(0)).text());
        var pr = c.getPrompt(new McpSchema.GetPromptRequest("greet", Map.of("who", "world")));
        assertEquals("hello world", ((McpSchema.TextContent) pr.messages().get(0).content()).text());
        var pl = c.listPrompts();
        assertEquals("who", pl.prompts().get(0).arguments().get(0).name());
        assertTrue(pl.prompts().get(0).arguments().get(0).required());
    }

    // ── raw protocol checks (streamable HTTP, 2025-06-18) ────────────────

    private static HttpRequest post(String url, String sid, String body, Map<String, String> hdr) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sid != null && !sid.isEmpty()) b.header("Mcp-Session-Id", sid);
        hdr.forEach(b::setHeader);
        return b.build();
    }

    static HttpResponse<String> rawPost(String url, String sid, String body, Map<String, String> hdr) throws Exception {
        return HTTP.send(post(url, sid, body, hdr), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> send(HttpRequest r) throws Exception {
        return HTTP.send(r, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void protocolConformance() throws Exception {
        String url = serve(tk().mcpHandler(new McpHandler.Config().listTools(ctx -> List.of())));
        HttpResponse<String> r = rawPost(url, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"x\",\"version\":\"1\"}}}", Map.of());
        String sid = r.headers().firstValue("Mcp-Session-Id").orElse("");
        assertEquals(200, r.statusCode());
        assertFalse(sid.isEmpty());
        assertTrue(r.body().contains("\"protocolVersion\":\"2025-06-18\""));
        assertTrue(r.body().contains("\"listChanged\":true"));
        r = rawPost(url, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}", Map.of());
        assertTrue(r.body().contains("\"protocolVersion\":\"2025-06-18\""));
        Map<String, String> v = Map.of("MCP-Protocol-Version", "2025-06-18");
        assertEquals(202, rawPost(url, sid, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", v).statusCode());
        assertEquals(400, rawPost(url, "", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", v).statusCode());
        assertEquals(404, rawPost(url, "nope", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", v).statusCode());
        assertEquals(400, rawPost(url, sid, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", Map.of("MCP-Protocol-Version", "2099-01-01")).statusCode());
        assertTrue(rawPost(url, sid, "[{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}]", v).body().contains("-32600"));
        assertTrue(rawPost(url, sid, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}", v).body().contains("\"result\":{}"));
        assertTrue(rawPost(url, sid, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}", v).body().contains("-32601"));
        assertEquals(415, send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "text/plain").build()).statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(URI.create(url)).GET().build()).statusCode());
        assertEquals(200, send(HttpRequest.newBuilder(URI.create(url)).DELETE().header("Mcp-Session-Id", sid).build()).statusCode());
        assertEquals(404, rawPost(url, sid, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\"}", v).statusCode());
    }

    @Test
    void maxBodySize() throws Exception {
        String url = serve(tk().mcpHandler(new McpHandler.Config().stateless(true).maxBodyBytes(64)));
        String big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\"" + "x".repeat(100) + "\"}}";
        assertEquals(413, rawPost(url, "", big, Map.of()).statusCode());
        assertEquals(200, rawPost(url, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", Map.of()).statusCode());
    }

    @Test
    void customSessionIdsAndStateless() throws Exception {
        AtomicInteger gen = new AtomicInteger(), val = new AtomicInteger(), term = new AtomicInteger();
        McpHandler h = tk().mcpHandler(new McpHandler.Config()
                .sessionIds(new McpHandler.SessionIds() {
                    @Override public String generate(McpHandler.RequestContext ctx) { gen.incrementAndGet(); return "host-session-1"; }
                    @Override public boolean validate(McpHandler.RequestContext ctx, String id) { val.incrementAndGet(); return id.equals("host-session-1"); }
                    @Override public void terminate(McpHandler.RequestContext ctx, String id) { term.incrementAndGet(); }
                })
                .listTools(ctx -> List.of(testTool("a", null))));
        McpSyncClient c = dial(serve(h), "", null);
        assertEquals("a:", text(call(c, "a", null)));
        assertEquals(1, gen.get());
        assertTrue(val.get() > 0);

        String url2 = serve(tk().mcpHandler(new McpHandler.Config().stateless(true).listTools(ctx -> List.of(testTool("a", null)))));
        HttpResponse<String> r = rawPost(url2, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}", Map.of());
        assertTrue(r.headers().firstValue("Mcp-Session-Id").isEmpty());
        assertTrue(rawPost(url2, "", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"a\"}}", Map.of()).body().contains("a:"));
    }

    @Test
    void defaultsToToolkitTools() {
        Toolkit tk = tk();
        tk.register(testTool("registered", null));
        McpSyncClient c = dial(serve(tk.mcpHandler(new McpHandler.Config())), "", null);
        assertTrue(names(c).contains("registered"));
    }

    // ── PR #140 review follow-ups ────────────────────────────────────────

    @Test
    void structuredContentAndOutputSchema() {
        Tool st = Tool.withOutputSchema(
                NativeTool.of("stats", "stats", Map.of("type", "object"), args -> null),
                Map.of("type", "object", "properties", Map.of("count", Map.of("type", "integer"))));
        Tool stats = new Tool() {
            @Override public String name() { return st.name(); }
            @Override public String description() { return st.description(); }
            @Override public Map<String, Object> inputSchema() { return st.inputSchema(); }
            @Override public String source() { return "native"; }
            @Override public Map<String, Object> outputSchema() { return st.outputSchema(); }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return ToolResult.structured(Map.of("count", 3));
            }
        };
        Tool plain = testTool("plain", null);
        McpSyncClient c = dial(serve(tk().mcpHandler(new McpHandler.Config().listTools(ctx -> List.of(stats, plain)))), "", null);
        var lt = c.listTools();
        assertEquals("object", lt.tools().get(0).outputSchema().get("type"));
        McpSchema.CallToolResult r = call(c, "stats", null);
        assertEquals("{\"count\":3}", Json.stringify(r.structuredContent()));
        assertEquals("{\"count\":3}", text(r));
        String raw = Json.stringify(McpHandler.wireResult(ToolResult.ok("x")));
        assertFalse(raw.contains("structuredContent"));
        assertFalse(raw.contains("_meta"));
        assertFalse(McpHandler.wireTool(plain).containsKey("outputSchema"));
    }

    @Test
    void metaPassthrough() throws Exception {
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>(), seenHook = new AtomicReference<>();
        Tool m = new Tool() {
            @Override public String name() { return "m"; }
            @Override public String description() { return ""; }
            @Override public Map<String, Object> inputSchema() { return null; }
            @Override public String source() { return "native"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                seen.set(McpHandler.mcpRequestMetaFrom(ctx));
                return ToolResult.ok("ok").withMeta(Map.of("usage", Map.of("units", 2)));
            }
        };
        String url = serve(tk().mcpHandler(new McpHandler.Config().stateless(true)
                .listTools(ctx -> List.of(m))
                .beforeCall((ctx, t, a) -> { seenHook.set(ctx.requestMeta()); return null; })));
        String b = rawPost(url, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"m\",\"_meta\":{\"clientCaps\":{\"elicitation\":{}}}}}", Map.of()).body();
        assertTrue(b.contains("\"_meta\":{\"usage\":{\"units\":2}}"), b);
        assertTrue(seen.get().containsKey("clientCaps"));
        assertTrue(seenHook.get().containsKey("clientCaps"));
    }

    @Test
    void protocolVersionsPerHandler() throws Exception {
        Function<String, String> initBody = ver -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + ver + "\"}}";
        String def = serve(tk().mcpHandler(new McpHandler.Config().stateless(true)));
        String newer = serve(tk().mcpHandler(new McpHandler.Config().stateless(true)
                .protocolVersions("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26")
                .listTools(ctx -> List.of(testTool("a", null)))));
        assertTrue(rawPost(def, "", initBody.apply("2025-11-25"), Map.of()).body().contains("\"protocolVersion\":\"2025-06-18\""));
        assertEquals(400, rawPost(def, "", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", Map.of("MCP-Protocol-Version", "2025-11-25")).statusCode());
        assertTrue(rawPost(newer, "", initBody.apply("2025-11-25"), Map.of()).body().contains("\"protocolVersion\":\"2025-11-25\""));
        assertTrue(rawPost(newer, "", initBody.apply("2026-07-28"), Map.of()).body().contains("\"protocolVersion\":\"2025-11-25\""));
        assertEquals(400, rawPost(newer, "", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", Map.of("MCP-Protocol-Version", "2025-03-26")).statusCode());
        String call = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"a\",\"arguments\":[1]}}";
        String b = rawPost(newer, "", call, Map.of("MCP-Protocol-Version", "2025-11-25")).body();
        assertTrue(b.contains("\"isError\":true") && !b.contains("-32602"), b);
        assertTrue(rawPost(newer, "", call, Map.of("MCP-Protocol-Version", "2025-06-18")).body().contains("-32602"));
    }

    @Test
    void statelessSessionIdAndTitle() throws Exception {
        String url = serve(tk().mcpHandler(new McpHandler.Config().stateless(true).statelessSessionId("stateless").title("Records API")));
        HttpResponse<String> r = rawPost(url, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}", Map.of());
        assertEquals("stateless", r.headers().firstValue("Mcp-Session-Id").orElse(""));
        assertTrue(r.body().contains("\"title\":\"Records API\""));
        assertTrue(rawPost(url, "whatever", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", Map.of()).body().contains("\"result\":{}"));
        String url2 = serve(tk().mcpHandler(new McpHandler.Config().stateless(true)));
        assertFalse(rawPost(url2, "", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", Map.of()).body().contains("\"title\""));
    }

    @Test
    void serveStructuredResultViaMcpServer() throws Exception {
        // The §7C serve path maps structured/meta/outputSchema too, and stays byte-identical when absent.
        Tool x = NativeTool.of("x", "", Map.of("type", "object"), a -> "");
        String plainTool = McpServe.JSON_MAPPER.writeValueAsString(McpServe.toMcpTool(x));
        assertFalse(plainTool.contains("outputSchema"), plainTool);
        String withSchema = McpServe.JSON_MAPPER.writeValueAsString(McpServe.toMcpTool(Tool.withOutputSchema(x, Map.of("type", "object"))));
        assertTrue(withSchema.contains("\"outputSchema\":{\"type\":\"object\"}"), withSchema);
        String b = McpServe.JSON_MAPPER.writeValueAsString(McpServe.toCallToolResult(
                new ToolResult("hi", false, null, null, Map.of("a", 1), Map.of("k", "v"))));
        assertTrue(b.contains("\"structuredContent\":{\"a\":1}") && b.contains("\"_meta\":{\"k\":\"v\"}") && b.contains("\"text\":\"hi\""), b);
    }

    @Test
    void serveAbsentFieldsAreByteIdentical() throws Exception {
        // Pre-change wire for a plain result: exactly the builder output without the new fields.
        ToolResult plain = ToolResult.ok("hi");
        String now = McpServe.JSON_MAPPER.writeValueAsString(McpServe.toCallToolResult(plain));
        String before = McpServe.JSON_MAPPER.writeValueAsString(McpSchema.CallToolResult.builder()
                .addTextContent("hi").isError(false).build());
        assertEquals(before, now);
        Tool x = NativeTool.of("x", "d", Map.of("type", "object"), a -> "");
        String toolNow = McpServe.JSON_MAPPER.writeValueAsString(McpServe.toMcpTool(x));
        String toolBefore = McpServe.JSON_MAPPER.writeValueAsString(McpSchema.Tool.builder()
                .name("x").description("d").inputSchema(Map.of("type", "object")).build());
        assertEquals(toolBefore, toolNow);
    }
}
