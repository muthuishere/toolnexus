package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the ADR-0001 rag_go consumer needs (7 gaps) + the DisableTools/DisableSkills
 * ergonomic layer, ported to the Java port. Mirrors {@code golang/rag_consumer_test.go}.
 * Client-side gaps use an in-process {@link HttpServer} that captures the decoded request body;
 * MCP-side gaps stand up a real streamable-HTTP MCP server by serving a toolkit (the same pattern
 * {@link McpServeTest} uses, since the Java MCP SDK ships no in-memory transport). Hermetic — no
 * network, no live LLM.
 */
class RagConsumerTest {

    // Response body that satisfies BOTH the openai (choices) and anthropic (content) decoders.
    private static final String LLM_RESPONSE =
            "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                    + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2,"
                    + "\"input_tokens\":1,\"output_tokens\":1}}";

    /** An ephemeral LLM server that records the last decoded request body. */
    private static final class CaptureLLM implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<Map<String, Object>> last = new AtomicReference<>();

        CaptureLLM() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                byte[] raw = ex.getRequestBody().readAllBytes();
                try {
                    last.set(Json.toMap(new String(raw, StandardCharsets.UTF_8)));
                } catch (RuntimeException ignored) {
                    last.set(new LinkedHashMap<>());
                }
                byte[] b = LLM_RESPONSE.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        Map<String, Object> body() {
            return last.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Map<String, Object> objSchema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", new LinkedHashMap<>());
        return s;
    }

    private static Tool mkTool(String name) {
        return NativeTool.of(name, name, objSchema(), args -> name);
    }

    /** Build a config map from alternating key/value pairs. */
    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    private static List<String> names(List<Tool> ts) {
        List<String> out = new ArrayList<>();
        for (Tool t : ts) out.add(t.name());
        return out;
    }

    // ---- Gap 1: requestParams merge (caller wins) + bodyTransform last + forbidden keys ----

    @Test
    void gap1RequestParamsMergeAndCallerWins() throws IOException {
        try (CaptureLLM llm = new CaptureLLM()) {
            Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));

            LlmClient oa = LlmClient.create(new LlmClient.Options()
                    .baseUrl(llm.url()).style("openai").model("m").apiKey("k")
                    .requestParams(m("temperature", 0.42, "chat_template_kwargs", m("enable_thinking", false))));
            oa.run("hi", tk);
            assertEquals(0.42, ((Number) llm.body().get("temperature")).doubleValue(), 1e-9);
            assertNotNull(llm.body().get("chat_template_kwargs"), "chat_template_kwargs missing");

            LlmClient an = LlmClient.create(new LlmClient.Options()
                    .baseUrl(llm.url()).style("anthropic").model("m").apiKey("k")
                    .requestParams(m("max_tokens", 999)));
            an.run("hi", tk);
            assertEquals(999, ((Number) llm.body().get("max_tokens")).intValue(),
                    "caller wins over the anthropic default 4096");

            tk.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void gap1BodyTransformAndForbiddenKeys() throws IOException {
        try (CaptureLLM llm = new CaptureLLM()) {
            Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
            LlmClient c = LlmClient.create(new LlmClient.Options()
                    .baseUrl(llm.url()).style("openai").model("m").apiKey("k")
                    .requestParams(m("temperature", 0.5, "messages", List.of("nope"),
                            "tools", List.of(1), "stream", true))
                    .bodyTransform(b -> {
                        b.put("injected", "yes");
                        b.remove("temperature");
                        return b;
                    }));
            c.run("hi", tk);

            assertEquals("yes", llm.body().get("injected"), "bodyTransform output not sent");
            assertFalse(llm.body().containsKey("temperature"),
                    "bodyTransform ran before marshal but key survived");
            assertFalse(llm.body().containsKey("stream"),
                    "forbidden 'stream' override leaked into a non-stream call");
            List<Object> msgs = (List<Object>) llm.body().get("messages");
            assertNotNull(msgs);
            assertFalse(msgs.isEmpty(), "forbidden 'messages' override replaced the real messages");
            assertTrue(msgs.get(0) instanceof Map, "real messages should be role/content objects, not 'nope'");

            tk.close();
        }
    }

    // ---- Gap 2: injectable HTTP client (LLM path only) ----

    @Test
    void gap2HttpClientInjected() throws IOException {
        try (CaptureLLM llm = new CaptureLLM()) {
            AtomicInteger hits = new AtomicInteger(0);
            // A recording ProxySelector proves THIS client made the call; NO_PROXY keeps it direct.
            ProxySelector recording = new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    hits.incrementAndGet();
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                }
            };
            HttpClient injected = HttpClient.newBuilder().proxy(recording).build();

            Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
            LlmClient c = LlmClient.create(new LlmClient.Options()
                    .baseUrl(llm.url()).style("openai").model("m").apiKey("k")
                    .httpClient(injected));
            c.run("hi", tk);
            assertTrue(hits.get() > 0, "injected HttpClient was not used");
            tk.close();
        }
    }

    // ---- Gap 5: empty tool list omits tools/tool_choice; non-empty keeps them ----

    @Test
    void gap5OmitEmptyTools() throws IOException {
        try (CaptureLLM llm = new CaptureLLM()) {
            Toolkit empty = Toolkit.create(new Toolkit.Options().builtins(false));
            LlmClient c1 = LlmClient.create(new LlmClient.Options()
                    .baseUrl(llm.url()).style("openai").model("m").apiKey("k"));
            c1.run("hi", empty);
            assertFalse(llm.body().containsKey("tools"), "empty toolkit still sent tools");
            assertFalse(llm.body().containsKey("tool_choice"), "empty toolkit still sent tool_choice");
            empty.close();

            Toolkit one = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(mkTool("t")));
            LlmClient c2 = LlmClient.create(new LlmClient.Options()
                    .baseUrl(llm.url()).style("openai").model("m").apiKey("k"));
            c2.run("hi", one);
            assertTrue(llm.body().containsKey("tools"), "non-empty toolkit dropped tools");
            assertEquals("auto", llm.body().get("tool_choice"), "non-empty toolkit dropped tool_choice");
            one.close();
        }
    }

    // ---- Gap 4: conversationStore accessor (identity + default reflects saved transcript) ----

    @Test
    void gap4ConversationStoreAccessor() throws IOException {
        LlmClient.InMemoryConversationStore custom = new LlmClient.InMemoryConversationStore();
        LlmClient c1 = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://x").style("openai").model("m").store(custom));
        assertTrue(c1.conversationStore() == custom, "accessor did not return the supplied store");

        try (CaptureLLM llm = new CaptureLLM()) {
            Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
            LlmClient c2 = LlmClient.create(new LlmClient.Options()
                    .baseUrl(llm.url()).style("openai").model("m").apiKey("k"));
            assertNotNull(c2.conversationStore(), "default store is nil");
            c2.ask("hi", tk, "conv1");
            List<Object> hist = c2.conversationStore().get("conv1");
            assertNotNull(hist);
            assertTrue(hist.size() >= 2,
                    "default store did not hold the saved transcript, got " + hist.size() + " msgs");
            tk.close();
        }
    }

    // ---- MCP-server helper: serve a toolkit exposing tools a,b,c as streamable-HTTP MCP ----

    private static A2AServer.ServeHandle startMCP() {
        Toolkit serverTk = Toolkit.create(new Toolkit.Options().builtins(false)
                .extraTools(mkTool("a"), mkTool("b"), mkTool("c")));
        return serverTk.serve("127.0.0.1:0", new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig()));
    }

    // ---- Gap 7: per-server tool allowlist (keyed on ORIGINAL name) ----

    @Test
    void gap7PerServerToolAllowlist() {
        A2AServer.ServeHandle srv = startMCP();
        String url = srv.url() + "/mcp";
        try {
            McpSource allow = McpSource.load(m("srv", m("url", url, "tools", m("a", true, "b", true))));
            List<String> allowNames = names(allow.tools());
            assertEquals(2, allowNames.size(), "allowlist tools = " + allowNames);
            assertTrue(allowNames.contains("srv_a") && allowNames.contains("srv_b"),
                    "allowlist tools = " + allowNames + ", want srv_a,srv_b");
            allow.close();

            McpSource drop = McpSource.load(m("srv", m("url", url, "tools", m("c", false))));
            List<String> dropNames = names(drop.tools());
            assertEquals(2, dropNames.size(), "drop-list tools = " + dropNames);
            assertFalse(dropNames.contains("srv_c"), "drop-list kept srv_c: " + dropNames);
            drop.close();

            McpSource all = McpSource.load(m("srv", m("url", url)));
            assertEquals(3, names(all.tools()).size(), "nil filter should load all 3");
            all.close();
        } finally {
            srv.stop();
        }
    }

    // ---- Gap 6: list-only inventory — UNFILTERED, with per-server status incl. a failed server ----

    @Test
    void gap6ListMcpTools() {
        A2AServer.ServeHandle srv = startMCP();
        String url = srv.url() + "/mcp";
        try {
            McpSource.McpInventory inv = McpSource.listMcpTools(m(
                    "good", m("url", url, "tools", m("a", true)),   // filter IGNORED by the inventory
                    "bad", m("url", "http://127.0.0.1:1/mcp", "timeout", 500)));
            assertEquals(3, inv.tools().get("good").size(),
                    "inventory should be unfiltered (a,b,c)");
            assertEquals("connected", inv.status().get("good"));
            assertEquals("failed", inv.status().get("bad"), "bad server should be failed");
            // original, unprefixed names carried through.
            List<String> good = new ArrayList<>();
            for (McpSource.ToolInfo ti : inv.tools().get("good")) good.add(ti.name());
            assertTrue(good.contains("a") && good.contains("b") && good.contains("c"),
                    "inventory names should be original/unprefixed: " + good);
        } finally {
            srv.stop();
        }
    }

    // ---- Gap 3: hanging server bounded by timeout → failed; cancel aborts the whole load ----

    @Test
    void gap3HangingBoundedAndCancel() throws IOException {
        // A server that accepts but never responds → connect/init bounded by the server timeout.
        HttpServer hang = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch unblock = new CountDownLatch(1);
        hang.createContext("/", ex -> {
            try {
                unblock.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        hang.start();
        String hurl = "http://127.0.0.1:" + hang.getAddress().getPort() + "/mcp";
        try {
            long t0 = System.currentTimeMillis();
            McpSource src = McpSource.load(m("hang", m("url", hurl, "timeout", 500)));
            assertEquals("failed", src.status().get("hang"),
                    "hanging server should be bounded to failed by its timeout");
            assertTrue(System.currentTimeMillis() - t0 < 5000, "not bounded by timeout");
            src.close();

            // An already-signalled cancel aborts the whole load promptly with CancellationException.
            McpSource.CancelSignal cancelled = () -> true;
            long t1 = System.currentTimeMillis();
            assertThrows(CancellationException.class, () ->
                    McpSource.load(m("hang", m("url", hurl, "timeout", 60000)), null, cancelled));
            assertTrue(System.currentTimeMillis() - t1 < 5000, "cancel should abort promptly");
        } finally {
            unblock.countDown();
            hang.stop(0);
        }
    }

    // ---- DisableTools / DisableSkills ----

    @Test
    void disableToolsAndSkills() {
        A2AServer.ServeHandle srv = startMCP();
        String url = srv.url() + "/mcp";
        try {
            Toolkit tk = Toolkit.create(new Toolkit.Options()
                    .builtins(false)
                    .mcpConfig(m("srv", m("url", url)))
                    .disableTools("srv_b"));
            assertNull(tk.get("srv_b"), "DisableTools did not drop srv_b");
            assertNotNull(tk.get("srv_a"), "DisableTools dropped too much (srv_a gone)");
            tk.close();
        } finally {
            srv.stop();
        }

        Toolkit sk = Toolkit.create(new Toolkit.Options()
                .builtins(false)
                .skills(List.of(new SkillSource.SkillDef("keep", "k", "k"),
                        new SkillSource.SkillDef("drop", "d", "d")))
                .disableSkills("drop"));
        String prompt = sk.skillsPrompt();
        assertTrue(prompt.contains("keep"), "DisableSkills dropped the kept skill; prompt:\n" + prompt);
        assertFalse(prompt.contains("**drop**"), "DisableSkills wrong; prompt:\n" + prompt);
        sk.close();
    }
}
