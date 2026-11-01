package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change add-go-host-asks, Java port. Mirrors {@code golang/host_engine_asks_test.go} and
 * {@code golang/resolve_key_test.go}: concurrent register, per-server MCP readiness, waitForCtx,
 * tool hints + a permission gate on beforeTool, callId on parallel calls, API key by host,
 * listModels.
 */
class HostEngineAsksTest {

    static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static Tool tool(String name, java.util.function.BiFunction<Map<String, Object>, ToolContext, ToolResult> fn) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "d"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return fn.apply(args, ctx); }
        };
    }

    static void writeJson(HttpExchange ex, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    static void writeSse(HttpExchange ex, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    /** OpenAI stub: turn 1 calls get_balance; after a tool message, answers. */
    @SuppressWarnings("unchecked")
    static HttpServer balanceLLM() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, Object> req = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            List<Object> messages = (List<Object>) req.get("messages");
            boolean sawTool = messages.stream().anyMatch(m -> "tool".equals(((Map<String, Object>) m).get("role")));
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            if (sawTool) {
                message.put("content", "balance done");
            } else {
                message.put("content", null);
                message.put("tool_calls", List.of(Map.of("id", "c1", "type", "function",
                        "function", Map.of("name", "get_balance", "arguments", "{}"))));
            }
            writeJson(ex, Json.stringify(Map.of("choices", List.of(Map.of("message", message)),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2))));
        });
        server.start();
        return server;
    }

    static Toolkit balanceToolkit(AtomicBoolean authed, ToolHints hints) {
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        Tool t = tool("get_balance", (a, ctx) -> authed.get()
                ? ToolResult.ok("balance: 100")
                : ToolResult.authRequired("https://example.com/login", "Log in"));
        tk.register(hints == null ? t : Tool.withHints(t, hints));
        return tk;
    }

    static String base(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    // ---- concurrent register ------------------------------------------------------------------

    @Test
    void registerIsSafeConcurrentlyWithReads() throws Exception {
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            readers.add(Thread.ofPlatform().start(() -> {
                try {
                    while (!stop.get()) {
                        tk.tools();
                        tk.get("t0");
                        tk.execute("t0", null);
                        tk.toOpenAI();
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        for (int i = 0; i < 200; i++) {
            tk.register(NativeTool.of("t" + i, "d", null, (Map<String, Object> a) -> "ok"));
        }
        stop.set(true);
        for (Thread t : readers) t.join();
        assertTrue(errors.isEmpty(), "reader threw: " + errors);
        assertEquals(200, tk.tools().size());
    }

    // ---- per-server MCP readiness -------------------------------------------------------------

    @Test
    void loadMcpOnServerReportsFastServerFirst() {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH");
        String fixture = TestFixtures.fixture("mcp-progress-server.mjs");
        String cfg = "{\"mcpServers\":{"
                + "\"fast\":{\"command\":[\"node\"," + Json.stringify(fixture) + "],\"timeout\":5000},"
                + "\"slow\":{\"command\":[\"sh\",\"-c\"," + Json.stringify("sleep 1; exec node " + fixture) + "],\"timeout\":5000},"
                + "\"bad\":{\"command\":[\"/nonexistent/toolnexus-no-such-binary\"],\"timeout\":2000},"
                + "\"off\":{\"command\":[\"node\"," + Json.stringify(fixture) + "],\"enabled\":false}}}";
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        long start = System.nanoTime();
        List<String> order = new ArrayList<>();
        Map<String, Long> at = new ConcurrentHashMap<>();
        Map<String, McpSource.ServerResult> results = new ConcurrentHashMap<>();
        try (McpSource src = McpSource.load(cfg, new McpSource.LoadOptions().onServer(r -> {
            order.add(r.name());
            at.put(r.name(), (System.nanoTime() - start) / 1_000_000L);
            results.put(r.name(), r);
            tk.register(r.tools().toArray(new Tool[0]));
        }))) {
            assertEquals(4, order.size(), "callbacks: " + order);
            assertEquals("connected", results.get("fast").status());
            assertFalse(results.get("fast").tools().isEmpty());
            assertEquals("connected", results.get("slow").status());
            assertEquals("failed", results.get("bad").status());
            assertNotNull(results.get("bad").error());
            assertEquals("disabled", results.get("off").status());
            assertTrue(at.get("fast") < at.get("slow") && at.get("slow") - at.get("fast") >= 500,
                    "fast must arrive well before slow: " + at);
            assertNotNull(tk.get("fast_slow"), "fast server's tools registered via onServer");
            assertEquals("disabled", src.status().get("off"));
        }
    }

    // ---- waitForCtx ---------------------------------------------------------------------------

    @Test
    void waitForCtxCancelsWithTurn() throws Exception {
        HttpServer server = balanceLLM();
        try (Toolkit tk = balanceToolkit(new AtomicBoolean(false), null)) {
            LlmClient.CancelToken cancel = new LlmClient.CancelToken();
            AtomicReference<String> seen = new AtomicReference<>();
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base(server)).style("openai").model("stub").apiKey("k")
                    .waitFor(r -> { throw new AssertionError("waitFor must not run when waitForCtx is set"); })
                    .waitForCtx((req, token) -> {
                        Thread.ofVirtual().start(() -> {
                            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                            cancel.cancel();
                        });
                        long end = System.currentTimeMillis() + 5000;
                        while (System.currentTimeMillis() < end) {
                            if (token.isCancelled()) { seen.set("cancelled"); return new Answer(req.id(), false); }
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException e) {
                                seen.set(token.isCancelled() ? "cancelled" : "interrupted");
                                Thread.currentThread().interrupt();
                                return new Answer(req.id(), false);
                            }
                        }
                        seen.set("timeout");
                        return new Answer(req.id(), true);
                    }));
            long t0 = System.currentTimeMillis();
            assertThrows(LlmClient.CancelledException.class, () -> client.run("balance?", tk, null, cancel));
            assertTrue(System.currentTimeMillis() - t0 < 2000, "run ended promptly after cancel");
            long deadline = System.currentTimeMillis() + 2000;
            while (seen.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(5);
            assertEquals("cancelled", seen.get(), "waitForCtx saw the turn's token cancelled");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void waitForCtxResolves() throws Exception {
        HttpServer server = balanceLLM();
        AtomicBoolean authed = new AtomicBoolean(false);
        try (Toolkit tk = balanceToolkit(authed, null)) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base(server)).style("openai").model("stub").apiKey("k")
                    .waitForCtx((req, token) -> {
                        assertNotNull(token);
                        authed.set(true);
                        return new Answer(req.id(), true);
                    }));
            LlmClient.RunResult r = client.run("balance?", tk);
            assertEquals("done", r.status, r.text);
        } finally {
            server.stop(0);
        }
    }

    // ---- hints --------------------------------------------------------------------------------

    @Test
    void builtinHints() {
        Map<String, Boolean> want = Map.of("read", true, "grep", true, "glob", true, "webfetch", true,
                "question", true, "todowrite", true, "bash", false, "write", false, "edit", false, "apply_patch", false);
        for (Tool t : BuiltinTools.create()) {
            assertTrue(want.containsKey(t.name()), t.name());
            assertNotNull(t.hints(), t.name());
            assertEquals(want.get(t.name()), t.hints().readOnly(), t.name());
            assertEquals(!want.get(t.name()), t.hints().destructive(), t.name());
        }
        assertTrue(BuiltinTools.create().stream().filter(t -> t.name().equals("bash")).findFirst().orElseThrow()
                .hints().openWorld());
    }

    @Test
    void mcpHintsMapping() {
        assertNull(McpSource.mcpHints(null));
        assertNull(McpSource.mcpHints(new McpSchema.ToolAnnotations(null, null, null, null, null, null)));
        ToolHints ro = McpSource.mcpHints(new McpSchema.ToolAnnotations(null, true, null, null, null, null));
        assertTrue(ro.readOnly() && !ro.destructive() && ro.openWorld(), ro.toString());
        ToolHints safe = McpSource.mcpHints(new McpSchema.ToolAnnotations(null, null, false, null, false, null));
        assertTrue(!safe.readOnly() && !safe.destructive() && !safe.openWorld(), safe.toString());
        ToolHints dflt = McpSource.mcpHints(new McpSchema.ToolAnnotations(null, false, null, null, null, null));
        assertTrue(!dflt.readOnly() && dflt.destructive(), dflt.toString());
    }

    /** A plan-mode gate built on beforeTool: a mutating tool is denied, the model gets an error
     * tool result, and the run still finishes. */
    @Test
    void permissionGateDeniesMutatingTool() throws Exception {
        HttpServer server = balanceLLM();
        AtomicReference<ToolHints> seen = new AtomicReference<>();
        try (Toolkit tk = balanceToolkit(new AtomicBoolean(true), ToolHints.DESTRUCTIVE)) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base(server)).style("openai").model("stub").apiKey("k")
                    .hooks(new LlmClient.Hooks().beforeTool(ev -> {
                        seen.set(ev.hints());
                        if (ev.hints() == null || !ev.hints().readOnly()) {
                            return LlmClient.ToolOverride.withResult(
                                    ToolResult.error("denied: plan mode allows read-only tools only"));
                        }
                        return null;
                    })));
            LlmClient.RunResult r = client.run("balance?", tk);
            assertEquals("done", r.status);
            assertNotNull(seen.get());
            assertTrue(seen.get().destructive());
            assertEquals(1, r.toolCalls.size());
            assertTrue(r.toolCalls.get(0).isError);
            assertEquals("denied: plan mode allows read-only tools only", r.toolCalls.get(0).output);
        } finally {
            server.stop(0);
        }
    }

    // ---- callId -------------------------------------------------------------------------------

    /** Always answers with two calls: c1 (fast) + c2 (slow), in both styles, streaming or not. */
    @SuppressWarnings("unchecked")
    static HttpServer twoCallLLM() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, Object> req = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean stream = Boolean.TRUE.equals(req.get("stream"));
            boolean anthropic = ex.getRequestURI().getPath().endsWith("/messages");
            if (anthropic && !stream) {
                writeJson(ex, Json.stringify(Map.of("content", List.of(
                        Map.of("type", "tool_use", "id", "c1", "name", "fast", "input", Map.of()),
                        Map.of("type", "tool_use", "id", "c2", "name", "slow", "input", Map.of())),
                        "stop_reason", "tool_use")));
            } else if (anthropic) {
                StringBuilder s = new StringBuilder();
                String[][] calls = {{"c1", "fast"}, {"c2", "slow"}};
                for (int i = 0; i < 2; i++) {
                    s.append("data: ").append(Json.stringify(Map.of("type", "content_block_start", "index", i,
                            "content_block", Map.of("type", "tool_use", "id", calls[i][0], "name", calls[i][1])))).append("\n\n");
                    s.append("data: ").append(Json.stringify(Map.of("type", "content_block_delta", "index", i,
                            "delta", Map.of("type", "input_json_delta", "partial_json", "{}")))).append("\n\n");
                }
                s.append("data: ").append(Json.stringify(Map.of("type", "message_delta",
                        "delta", Map.of("stop_reason", "tool_use")))).append("\n\n");
                s.append("data: {\"type\":\"message_stop\"}\n\n");
                writeSse(ex, s.toString());
            } else if (!stream) {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "assistant");
                message.put("content", null);
                message.put("tool_calls", List.of(
                        Map.of("id", "c1", "type", "function", "function", Map.of("name", "fast", "arguments", "{}")),
                        Map.of("id", "c2", "type", "function", "function", Map.of("name", "slow", "arguments", "{}"))));
                writeJson(ex, Json.stringify(Map.of("choices", List.of(Map.of("message", message)))));
            } else {
                Object delta = Map.of("tool_calls", List.of(
                        Map.of("index", 0, "id", "c1", "function", Map.of("name", "fast", "arguments", "{}")),
                        Map.of("index", 1, "id", "c2", "function", Map.of("name", "slow", "arguments", "{}"))));
                writeSse(ex, "data: " + Json.stringify(Map.of("choices", List.of(Map.of("delta", delta, "finish_reason", "tool_calls"))))
                        + "\n\ndata: [DONE]\n\n");
            }
        });
        server.start();
        return server;
    }

    @Test
    void toolContextCallIdOnParallelCalls() throws Exception {
        Map<String, String> seen = new ConcurrentHashMap<>();
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        for (String n : List.of("fast", "slow")) {
            tk.register(tool(n, (a, ctx) -> {
                seen.put(n, ctx == null ? "<null>" : ctx.callId());
                return ToolResult.ok("ok");
            }));
        }
        HttpServer server = twoCallLLM();
        try {
            for (boolean stream : new boolean[]{false, true}) {
                for (String style : List.of("openai", "anthropic")) {
                    seen.clear();
                    LlmClient client = LlmClient.create(new LlmClient.Options()
                            .baseUrl(base(server)).style(style).model("m").apiKey("k").maxTurns(1));
                    if (stream) client.stream("go", tk, ev -> { });
                    else client.run("go", tk);
                    assertEquals("c1", seen.get("fast"), style + " stream=" + stream + " " + seen);
                    assertEquals("c2", seen.get("slow"), style + " stream=" + stream + " " + seen);
                }
            }
            assertEquals("", new ToolContext().callId(), "outside the loop callId is empty");
        } finally {
            server.stop(0);
        }
    }

    // ---- API key by host (security) -----------------------------------------------------------

    @Test
    void resolveKeyByHost() {
        Map<String, String> env = new java.util.HashMap<>(Map.of(
                "OPENROUTER_API_KEY", "fake-or", "OPENAI_API_KEY", "fake-oa",
                "ANTHROPIC_API_KEY", "fake-an", "TN_TEST_KEY", "fake-custom"));
        String[][] ok = {
                // base, style, apiKeyEnv, explicit, want
                {"https://api.openai.com/v1", null, null, null, "fake-oa"},
                {"https://api.anthropic.com", "anthropic", null, null, "fake-an"},
                {"https://openrouter.ai/api/v1", "anthropic", null, null, "fake-or"},
                {"https://API.OpenAI.com/v1", null, null, null, "fake-oa"},
                {"https://example.com/v1", null, null, "fake-explicit", "fake-explicit"},
                {"https://api.openai.com/v1", null, null, "fake-explicit", "fake-explicit"},
                {"https://example.com/v1", null, "TN_TEST_KEY", null, "fake-custom"},
        };
        for (String[] c : ok) {
            LlmClient.Options o = new LlmClient.Options().baseUrl(c[0]).style(c[1]).apiKeyEnv(c[2]).apiKey(c[3]);
            assertEquals(c[4], LlmClient.resolveKey(o, env::get), c[0]);
        }
        // never a cross-provider fallback
        for (String b : List.of("https://example.com/v1", "http://api.openai.com/v1", "https://api.openai.com.evil.test/",
                "https://evil-openrouter.ai/v1", "http://localhost:11434/v1")) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> LlmClient.resolveKey(new LlmClient.Options().baseUrl(b), env::get), b);
            assertFalse(e.getMessage().contains("fake-"), "error leaks a key value: " + e.getMessage());
        }
        // the right env var unset ⇒ an error naming it, not another provider's key
        env.put("OPENAI_API_KEY", "");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> LlmClient.resolveKey(new LlmClient.Options().baseUrl("https://api.openai.com/v1"), env::get));
        assertTrue(e.getMessage().contains("OPENAI_API_KEY"), e.getMessage());
    }

    // ---- listModels ---------------------------------------------------------------------------

    @Test
    void listModels() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            path.set(ex.getRequestURI().getPath() + "?" + (q == null ? "" : q));
            String xk = ex.getRequestHeaders().getFirst("x-api-key");
            if (xk != null) {
                auth.set("x-api-key");
                if (q == null || !q.contains("after_id")) {
                    writeJson(ex, "{\"data\":[{\"id\":\"claude-b\",\"display_name\":\"B\"}],\"has_more\":true,\"last_id\":\"claude-b\"}");
                } else {
                    writeJson(ex, "{\"data\":[{\"id\":\"claude-a\",\"display_name\":\"A\"}],\"has_more\":false}");
                }
                return;
            }
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            writeJson(ex, "{\"data\":[{\"id\":\"z/model\",\"name\":\"Z\",\"context_length\":200000},{\"id\":\"a/model\"}]}");
        });
        server.start();
        try {
            List<LlmClient.ModelInfo> ms = LlmClient.listModels(new LlmClient.Options()
                    .baseUrl(base(server) + "/api/v1").apiKey("fake"));
            assertEquals(2, ms.size());
            assertEquals("a/model", ms.get(0).id());
            assertEquals(200000, ms.get(1).contextLength());
            assertEquals("Bearer fake", auth.get());
            assertEquals("/api/v1/models?", path.get());
            ms = LlmClient.listModels(new LlmClient.Options().baseUrl(base(server)).style("anthropic").apiKey("fake"));
            assertEquals(2, ms.size());
            assertEquals("claude-a", ms.get(0).id());
            assertEquals("B", ms.get(1).name());
            assertEquals("x-api-key", auth.get());
            assertThrows(RuntimeException.class, () -> LlmClient.listModels(new LlmClient.Options().baseUrl(base(server))),
                    "unknown host without apiKey must not resolve an env key");
        } finally {
            server.stop(0);
        }
    }
}
