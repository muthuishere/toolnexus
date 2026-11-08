package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.acp.AcpClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC §8C conformance: replays {@code conformance/acp-client/cases.json} (stream.acpDrip,
 * passthrough, agentExit, unknownRequest, asMcpServer) against {@link AcpSeamFake}.
 * stream.inProcess lives in {@code InProcessTest.generateStreamIsIncremental}.
 */
class AcpSeamConformanceTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cases() throws Exception {
        Path file = Path.of(TestFixtures.fixture("")).getParent().resolve("conformance/acp-client/cases.json");
        return Json.toMap(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String... keys) {
        Map<String, Object> cur = m;
        for (String k : keys) cur = (Map<String, Object>) cur.get(k);
        return cur;
    }

    private static Map<String, String> env(Map<String, Object> fakeEnv, Path rec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fakeEnv != null) fakeEnv.forEach((k, v) -> out.put(k, String.valueOf(v)));
        if (rec != null) out.put("TN_FAKE_REC", rec.toString());
        return out;
    }

    /** method -> params of the recorded requests, in order. */
    private static List<Map.Entry<String, Object>> recorded(Path rec) throws Exception {
        List<Map.Entry<String, Object>> out = new ArrayList<>();
        if (!Files.exists(rec)) return out;
        for (String l : Files.readAllLines(rec)) {
            int sp = l.indexOf(' ');
            out.add(Map.entry(l.substring(0, sp), Json.parseLoose(l.substring(sp + 1))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(List<Map.Entry<String, Object>> rec, String method) {
        for (var e : rec) if (e.getKey().equals(method)) return (Map<String, Object>) e.getValue();
        return null;
    }

    private static Map<String, Object> user(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", text);
        return m;
    }

    // ---- stream.acpDrip ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void streamAcpDrip() throws Exception {
        Map<String, Object> c = sub(cases(), "stream", "acpDrip");
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env((Map<String, Object>) c.get("fakeEnv"), null)).timeoutMs(10_000))) {
            LlmClient client = InProcess.createClient(new InProcess.Options().model("acp")
                    .generateStream((req, emit) -> {
                        try {
                            return acp.generateStream(req, emit);
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    }));
            List<String> deltas = new ArrayList<>();
            List<Map<String, Object>> thoughts = new ArrayList<>();
            AtomicLong firstDelta = new AtomicLong();
            AtomicLong doneAt = new AtomicLong();
            AtomicReference<LlmClient.RunResult> done = new AtomicReference<>();
            try (Toolkit tk = Toolkit.create(new Toolkit.Options())) {
                client.stream("go", tk, ev -> {
                    switch (ev.type()) {
                        case TEXT -> {
                            if (deltas.isEmpty()) firstDelta.set(System.nanoTime());
                            deltas.add(ev.delta());
                        }
                        case THOUGHT -> thoughts.add(Map.of("type", "thought", "delta", ev.delta(), "via", ev.via()));
                        case DONE -> {
                            doneAt.set(System.nanoTime());
                            done.set(ev.result());
                        }
                        default -> { }
                    }
                });
                assertEquals(c.get("chunks"), deltas);
                assertEquals(List.of(c.get("expectThought")), thoughts);
                assertEquals(c.get("expectDoneText"), done.get().text);
                long leadMs = (doneAt.get() - firstDelta.get()) / 1_000_000;
                assertTrue(leadMs >= ((Number) c.get("minLeadMs")).longValue(), "lead " + leadMs + "ms");
                assertEquals(c.get("expectDoneText"), client.run("again", tk).text);
            }
        }
    }

    // ---- stream.acpToolUpdate (issue #188) -------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void streamAcpToolUpdate() throws Exception {
        Map<String, Object> c = sub(cases(), "stream", "acpToolUpdate");
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env((Map<String, Object>) c.get("fakeEnv"), null)).timeoutMs(10_000))) {
            LlmClient client = InProcess.createClient(new InProcess.Options().model("acp")
                    .generateStream((req, emit) -> {
                        try {
                            return acp.generateStream(req, emit);
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    }));
            List<Map<String, Object>> events = new ArrayList<>();
            AtomicReference<LlmClient.RunResult> done = new AtomicReference<>();
            try (Toolkit tk = Toolkit.create(new Toolkit.Options())) {
                client.stream("go", tk, ev -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    switch (ev.type()) {
                        case TOOL_CALL -> {
                            e.put("type", "tool_call");
                            e.put("id", ev.id());
                            e.put("name", ev.name());
                            e.put("via", ev.via());
                        }
                        case TOOL_CALL_ARGS -> {
                            e.put("type", "tool_call_args");
                            e.put("id", ev.id());
                            e.put("name", ev.name());
                            e.put("args", ev.args());
                            e.put("via", ev.via());
                        }
                        case TOOL_RESULT -> {
                            e.put("type", "tool_result");
                            e.put("id", ev.id());
                            e.put("name", ev.name());
                            e.put("output", ev.output());
                            e.put("isError", ev.isError());
                            e.put("via", ev.via());
                        }
                        case DONE -> done.set(ev.result());
                        default -> { }
                    }
                    if (!e.isEmpty()) events.add(e);
                });
            }
            assertEquals(c.get("expectEvents"), events);
            assertEquals(c.get("expectDoneText"), done.get().text);
        }
    }

    // ---- passthrough ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void passthroughOptions() throws Exception {
        Map<String, Object> c = sub(cases(), "passthrough");
        Map<String, Object> o = (Map<String, Object>) c.get("options");
        Path rec = Files.createTempFile("acp-rec", ".log");
        Files.delete(rec);
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env(null, rec))
                .mcpServers((List<Map<String, Object>>) o.get("mcpServers"))
                .sessionMeta((Map<String, Object>) o.get("sessionMeta"))
                .clientCapsMeta((Map<String, Object>) o.get("clientCapsMeta"))
                .model((String) o.get("model")))) {
            assertNotNull(acp.sessionId());
        }
        var r = recorded(rec);
        Map<String, Object> init = paramsOf(r, "initialize");
        assertEquals(c.get("expectInitializeClientCapabilities"), init.get("clientCapabilities"));
        Map<String, Object> sn = paramsOf(r, "session/new");
        assertEquals(o.get("mcpServers"), sn.get("mcpServers"));
        assertEquals(Json.stringify(o.get("mcpServers")), Json.stringify(sn.get("mcpServers")), "verbatim, in order");
        assertEquals(o.get("sessionMeta"), sn.get("_meta"));
        Map<String, Object> want = new LinkedHashMap<>((Map<String, Object>) c.get("expectSetConfigOption"));
        want.put("sessionId", "s1");
        assertEquals(want, paramsOf(r, "session/set_config_option"));
        assertNull(paramsOf(r, "session/set_model"));

        // fallback: set_config_option rejected ⇒ set_model
        Map<String, Object> fb = (Map<String, Object>) c.get("fallbackSetModel");
        Path rec2 = Files.createTempFile("acp-rec", ".log");
        Files.delete(rec2);
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env((Map<String, Object>) fb.get("fakeEnv"), rec2)).model((String) o.get("model")))) {
            assertNotNull(acp.sessionId());
        }
        Map<String, Object> wantSm = new LinkedHashMap<>((Map<String, Object>) fb.get("expectSetModel"));
        wantSm.put("sessionId", "s1");
        assertEquals(wantSm, paramsOf(recorded(rec2), "session/set_model"));

        // bare
        Map<String, Object> bare = (Map<String, Object>) c.get("bareExpect");
        Path rec3 = Files.createTempFile("acp-rec", ".log");
        Files.delete(rec3);
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command()).env(env(null, rec3)))) {
            assertNotNull(acp.sessionId());
        }
        var r3 = recorded(rec3);
        Map<String, Object> sn3 = paramsOf(r3, "session/new");
        assertEquals(bare.get("mcpServers"), sn3.get("mcpServers"));
        assertEquals(bare.get("sessionNewHasMeta"), sn3.containsKey("_meta"));
        assertEquals(bare.get("clientCapabilitiesHasMeta"),
                ((Map<String, Object>) paramsOf(r3, "initialize").get("clientCapabilities")).containsKey("_meta"));
        assertNull(paramsOf(r3, "session/set_config_option"));
        assertNull(paramsOf(r3, "session/set_model"));
    }

    // ---- liveSwitch (#197) ----------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void liveSwitch() throws Exception {
        Map<String, Object> c = sub(cases(), "liveSwitch");
        String mode = (String) c.get("mode");
        String model = (String) c.get("model");
        InProcess.Request req = new InProcess.Request(List.of(user("hi")), List.of(), "m", Map.of());
        Path rec = Files.createTempFile("acp-rec", ".log");
        Files.delete(rec);
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env(null, rec)).timeoutMs(10_000))) {
            acp.setMode(mode);
            acp.setModel(model);
            assertEquals(c.get("expectText"), acp.generate(req).content);
        }
        var r = recorded(rec);
        assertEquals(((Number) c.get("expectInitializeCount")).intValue(),
                r.stream().filter(e -> e.getKey().equals("initialize")).count());
        assertEquals(((Number) c.get("expectSessionNewCount")).intValue(),
                r.stream().filter(e -> e.getKey().equals("session/new")).count());
        Map<String, Object> wantMode = new LinkedHashMap<>((Map<String, Object>) c.get("expectSetMode"));
        wantMode.put("sessionId", "s1");
        assertEquals(wantMode, paramsOf(r, "session/set_mode"));
        Map<String, Object> wantCfg = new LinkedHashMap<>((Map<String, Object>) c.get("expectSetConfigOption"));
        wantCfg.put("sessionId", "s1");
        assertEquals(wantCfg, paramsOf(r, "session/set_config_option"));
        assertNull(paramsOf(r, "session/set_model"));

        Map<String, Object> fb = (Map<String, Object>) c.get("fallback");
        Path rec2 = Files.createTempFile("acp-rec", ".log");
        Files.delete(rec2);
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env((Map<String, Object>) fb.get("fakeEnv"), rec2)).timeoutMs(10_000))) {
            acp.setModel(model);
        }
        Map<String, Object> wantSm = new LinkedHashMap<>((Map<String, Object>) fb.get("expectSetModel"));
        wantSm.put("sessionId", "s1");
        assertEquals(wantSm, paramsOf(recorded(rec2), "session/set_model"));

        Map<String, Object> rj = (Map<String, Object>) c.get("reject");
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env((Map<String, Object>) rj.get("fakeEnv"), null)).timeoutMs(10_000))) {
            java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> acp.setMode(mode));
            org.junit.jupiter.api.Assertions.assertTrue(
                    e.getMessage().contains((String) rj.get("expectErrorContains")), e.getMessage());
            assertEquals(rj.get("expectText"), acp.generate(req).content);
        }
        AcpClient closedAcp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command()));
        closedAcp.close();
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> closedAcp.setMode(mode));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> closedAcp.setModel(model));
    }

    // ---- agentExit -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void agentExitFailsPromptly() throws Exception {
        Map<String, Object> c = sub(cases(), "agentExit");
        long timeout = 20_000;
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env((Map<String, Object>) c.get("fakeEnv"), null)).timeoutMs(timeout))) {
            long t0 = System.nanoTime();
            InProcess.Request req = new InProcess.Request(List.of(user("hi")), List.of(), "m", Map.of());
            Exception e = assertThrows(Exception.class, () -> acp.generate(req));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            StringBuilder msgs = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) msgs.append(t.getMessage()).append(" | ");
            assertTrue(msgs.toString().contains((String) c.get("expectError")), msgs.toString());
            assertTrue(ms < timeout / 2, "failed after " + ms + "ms");
        }
    }

    // ---- unknownRequest ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void unknownAgentRequestIsMethodNotFound() throws Exception {
        Map<String, Object> c = sub(cases(), "unknownRequest");
        try (AcpClient acp = AcpClient.start(new AcpClient.Options().command(AcpSeamFake.command())
                .env(env((Map<String, Object>) c.get("fakeEnv"), null)).timeoutMs(10_000))) {
            InProcess.Request req = new InProcess.Request(List.of(user("hi")), List.of(), "m", Map.of());
            // the fake answers "ok" only when it got a -32601 error for its fs/read_text_file request
            assertEquals(-32601, ((Number) c.get("expectCode")).intValue());
            assertEquals("fs/read_text_file", c.get("method"));
            assertEquals(c.get("expectText"), acp.generate(req).content);
        }
    }

    // ---- asMcpServer ---------------------------------------------------------------------

    private static HttpResponse<String> raw(String method, String url, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (method.equals("POST")) {
            b.POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"));
        } else {
            b.GET();
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void asMcpServer() throws Exception {
        Map<String, Object> c = sub(cases(), "asMcpServer");
        Map<String, Object> textProp = Map.of("type", "string");
        Tool echo = NativeTool.of("echo", "echoes back",
                Map.of("type", "object", "properties", Map.of("text", textProp), "required", List.of("text")),
                args -> args.get("text"));
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false)
                .skillsDir(TestFixtures.skillsDir()).extraTools(echo))) {
            McpServe.LocalServer s = tk.asMcpServer();
            McpServe.LocalServer s2 = tk.asMcpServer(new McpServe.MCPServeConfig().name("other"));
            try {
                assertTrue(s.url().matches((String) c.get("urlPattern")), s.url());
                assertTrue(s.token().matches((String) c.get("tokenPattern")));
                assertNotEquals(s.token(), s2.token(), "twoServersDistinctTokens");
                assertFalse(s.toString().contains(s.token()));

                var noTok = raw("POST", s.url(), null);
                assertEquals(((Number) c.get("noTokenStatus")).intValue(), noTok.statusCode());
                assertEquals("Bearer", noTok.headers().firstValue("WWW-Authenticate").orElse(null));
                assertEquals(((Number) c.get("wrongTokenStatus")).intValue(), raw("POST", s.url(), "0".repeat(64)).statusCode());
                assertEquals(((Number) c.get("wrongTokenStatus")).intValue(), raw("POST", s.url(), s2.token()).statusCode());
                assertEquals(((Number) c.get("getWithTokenStatus")).intValue(), raw("GET", s.url(), s.token()).statusCode());
                assertEquals(((Number) c.get("otherPathStatus")).intValue(),
                        raw("POST", s.url().replace("/mcp", "/other"), s.token()).statusCode());
                var ok = raw("POST", s.url(), s.token());
                assertEquals(200, ok.statusCode());
                for (Object n : (List<Object>) c.get("toolsListContains")) {
                    assertTrue(ok.body().contains("\"name\":\"" + n + "\""), ok.body());
                }

                // the port's own MCP client
                HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(s.url())
                        .jsonMapper(McpServe.JSON_MAPPER)
                        .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + s.token()))
                        .build();
                McpSyncClient mc = McpClient.sync(transport)
                        .clientInfo(new McpSchema.Implementation("t", "1")).build();
                try {
                    mc.initialize();
                    List<String> names = new ArrayList<>();
                    for (McpSchema.Tool t : mc.listTools().tools()) names.add(t.name());
                    assertTrue(names.containsAll((List<String>) c.get("toolsListContains")), names.toString());
                    Map<String, Object> call = (Map<String, Object>) c.get("callEcho");
                    McpSchema.CallToolResult res = mc.callTool(new McpSchema.CallToolRequest(
                            (String) call.get("name"), (Map<String, Object>) call.get("arguments")));
                    assertEquals(call.get("expectText"), ((McpSchema.TextContent) res.content().get(0)).text());
                } finally {
                    mc.closeGracefully();
                }
            } finally {
                s.close();
                s.close(); // idempotent
                s2.close();
            }
            assertTrue(Boolean.TRUE.equals(c.get("afterCloseRefused")));
            assertThrows(java.io.IOException.class, () -> raw("POST", s.url(), s.token()));
        }
    }
}
