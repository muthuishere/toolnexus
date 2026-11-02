package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt-cache replay (change add-prompt-cache, phase 1), Java port of
 * {@code golang/prompt_cache_test.go}. A Karya-shaped session runs against a mock anthropic server
 * that SIMULATES the provider's prefix cache: a request reads the longest prefix that ends at a
 * breakpoint an earlier request wrote. Cache reads must start on request 2 and never shrink.
 */
class PromptCacheTest {

    static Object strip(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!"cache_control".equals(e.getKey())) out.put(String.valueOf(e.getKey()), strip(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(strip(x));
            return out;
        }
        return v;
    }

    static boolean hasCacheControl(Object v) {
        return Json.stringify(v).contains("\"cache_control\"");
    }

    static final class CacheSim {
        final Set<String> written = new HashSet<>();
        final List<Integer> reads = new ArrayList<>();
        final List<Map<String, Object>> bodies = new ArrayList<>();
        int turn;

        @SuppressWarnings("unchecked")
        synchronized String handle(Map<String, Object> body) {
            List<String> sg = new ArrayList<>();
            List<Boolean> marks = new ArrayList<>();
            List<Object> segs = new ArrayList<>();
            segs.add(body.get("tools"));
            segs.add(body.get("system"));
            segs.addAll((List<Object>) body.get("messages"));
            for (Object s : segs) {
                sg.add(Json.stringify(strip(s)));
                marks.add(hasCacheControl(s));
            }
            int read = 0;
            for (int i = sg.size(); i > 0; i--) {
                if (written.contains(String.join("\u0000", sg.subList(0, i)))) {
                    read = String.join("", sg.subList(0, i)).length() / 4;
                    break;
                }
            }
            int write = 0;
            for (int i = 0; i < sg.size(); i++) {
                if (marks.get(i)) {
                    String key = String.join("\u0000", sg.subList(0, i + 1));
                    if (written.add(key)) write = String.join("", sg.subList(0, i + 1)).length() / 4 - read;
                }
            }
            reads.add(read);
            bodies.add(body);
            List<Object> msgs = (List<Object>) body.get("messages");
            Map<String, Object> last = (Map<String, Object>) msgs.get(msgs.size() - 1);
            boolean isToolResult = last.get("content") instanceof List<?> blocks && blocks.stream()
                    .anyMatch(b -> b instanceof Map<?, ?> bm && "tool_result".equals(bm.get("type")));
            List<Object> content;
            if (isToolResult) {
                content = List.of(Map.of("type", "text", "text", "ok"));
            } else {
                turn++;
                String name = turn == 2 ? "tool_search" : "read_notes"; // turn 2 loads a deferred schema
                content = List.of(Map.of("type", "tool_use", "id", "t" + turn, "name", name, "input", Map.of()));
            }
            return Json.stringify(Map.of("content", content,
                    "stop_reason", isToolResult ? "end_turn" : "tool_use",
                    "usage", Map.of("input_tokens", 10, "output_tokens", 5,
                            "cache_read_input_tokens", read, "cache_creation_input_tokens", write)));
        }
    }

    static Toolkit karyaToolkit() {
        Tool notes = NativeTool.of("read_notes", "Read the notes.", null, (Map<String, Object> a) -> "notes");
        Tool search = NativeTool.of("tool_search", "Load a deferred tool's schema.", null,
                (Map<String, Object> a) -> "{\"name\":\"deploy\",\"description\":\"Deploy.\",\"inputSchema\":{\"type\":\"object\"}}");
        return Toolkit.create(new Toolkit.Options().extraTools(notes, search));
    }

    static CacheSim runSession(PromptCache cache) throws IOException {
        CacheSim sim = new CacheSim();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String resp = sim.handle(Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            HostEngineAsksTest.writeJson(ex, resp);
        });
        server.start();
        String catalog = "skill: does a thing in a stable way. ".repeat(700); // ~6.6k tokens
        long cacheRead = 0;
        try (Toolkit tk = karyaToolkit()) {
            LlmClient c = LlmClient.create(new LlmClient.Options()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).style("anthropic").model("m").apiKey("k")
                    .systemPrompt("You are Karya.\n\n" + catalog).promptCache(cache)
                    .store(new LlmClient.InMemoryConversationStore()));
            for (String p : List.of("first", "second", "third", "fourth")) {
                LlmClient.RunResult r = c.ask(p, tk, "session-1");
                cacheRead += r.usage.cacheReadTokens;
            }
        } finally {
            server.stop(0);
        }
        if (cache != null) assertTrue(cacheRead > 0, "usage never reported cache-read tokens");
        return sim;
    }

    /** End to end: the HTTP body sent with style anthropic marks ONLY the last tool (builtins + two extras). */
    @Test
    @SuppressWarnings("unchecked")
    void marksLastToolInRequestJson() throws IOException {
        List<Map<String, Object>> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            synchronized (bodies) { bodies.add(Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))); }
            HostEngineAsksTest.writeJson(ex, Json.stringify(Map.of("content", List.of(Map.of("type", "text", "text", "ok")),
                    "stop_reason", "end_turn", "usage", Map.of("input_tokens", 1, "output_tokens", 1))));
        });
        server.start();
        Tool a = NativeTool.of("extra_a", "A.", null, (Map<String, Object> x) -> "a");
        Tool b = NativeTool.of("extra_b", "B.", null, (Map<String, Object> x) -> "b");
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().extraTools(a, b))) {
            LlmClient c = LlmClient.create(new LlmClient.Options()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).style("anthropic").model("m").apiKey("k")
                    .promptCache(new PromptCache()));
            c.run("hi", tk);
        } finally {
            server.stop(0);
        }
        List<Object> tools = (List<Object>) bodies.get(0).get("tools");
        assertTrue(tools.size() > 2, "builtins missing: " + tools.size());
        for (int i = 0; i < tools.size(); i++) {
            Object cc = ((Map<String, Object>) tools.get(i)).get("cache_control");
            if (i == tools.size() - 1) assertEquals(Map.of("type", "ephemeral"), cc);
            else assertEquals(null, cc, "tool " + i + " marked");
        }
    }

    @Test
    void replayNoRegression() throws IOException {
        CacheSim sim = runSession(new PromptCache().key("session-1"));
        assertEquals(8, sim.reads.size(), "4 turns x 2 requests");
        assertEquals(0, sim.reads.get(0), "first request cannot read the cache: " + sim.reads);
        int floor = sim.reads.get(1);
        assertTrue(floor >= 6000, "2nd request read " + floor + " tokens, want the ~6.6k stable prefix");
        for (int i = 1; i < sim.reads.size(); i++) {
            assertTrue(sim.reads.get(i) >= floor, "cache read regressed: " + sim.reads);
            assertTrue(sim.reads.get(i) >= sim.reads.get(i - 1), "cache read shrank: " + sim.reads);
        }
        String t0 = Json.stringify(sim.bodies.get(0).get("tools"));
        String s0 = Json.stringify(sim.bodies.get(0).get("system"));
        for (Map<String, Object> b : sim.bodies) {
            assertEquals(t0, Json.stringify(b.get("tools")), "tools prefix changed");
            assertEquals(s0, Json.stringify(b.get("system")), "system prefix changed");
        }
    }

    @Test
    void offIsByteIdentical() throws IOException {
        CacheSim sim = runSession(null);
        for (Map<String, Object> b : sim.bodies) {
            assertFalse(hasCacheControl(b), "cache_control without promptCache");
            assertInstanceOf(String.class, b.get("system"), "system must stay a string");
        }
    }

    @Test
    void openAIKeyAndUsage() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", new ArrayList<>());
        PromptCache.apply(body, "openai", new PromptCache().key("s1"));
        assertEquals("s1", body.get("prompt_cache_key"));
        LlmClient.Usage u = new LlmClient.Usage();
        LlmClient.addUsage(u, Map.of("prompt_tokens", 100, "completion_tokens", 5,
                "prompt_tokens_details", Map.of("cached_tokens", 80)), "openai");
        assertEquals(80, u.cacheReadTokens);
        assertEquals(100, u.promptTokens);
        LlmClient.Usage a = new LlmClient.Usage();
        LlmClient.addUsage(a, Map.of("input_tokens", 10, "output_tokens", 5,
                "cache_read_input_tokens", 70, "cache_creation_input_tokens", 20), "anthropic");
        assertEquals(10, a.promptTokens);
        assertEquals(70, a.cacheReadTokens);
        assertEquals(20, a.cacheWriteTokens);
    }

    @Test
    void doesNotMutateTranscript() {
        Map<String, Object> msg = new LinkedHashMap<>(Map.of("role", "user", "content", "hi"));
        Map<String, Object> tool = new LinkedHashMap<>(Map.of("name", "x"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tools", new ArrayList<>(List.of(tool)));
        body.put("system", "s");
        body.put("messages", new ArrayList<>(List.of(msg)));
        PromptCache.apply(body, "anthropic", new PromptCache().longTTL(true));
        assertInstanceOf(String.class, msg.get("content"));
        assertFalse(tool.containsKey("cache_control"), "caller's maps were mutated");
        String b = Json.stringify(body);
        assertEquals(3, b.split("\"ttl\":\"1h\"", -1).length - 1, "want 3 breakpoints with 1h ttl: " + b);
    }
}
