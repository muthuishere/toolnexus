package io.github.muthuishere.toolnexus.agents;

import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.Answer;
import io.github.muthuishere.toolnexus.Compaction;
import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Request;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolContext;
import io.github.muthuishere.toolnexus.ToolResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared fixture: {@code examples/agent-hooks/fixture.json} (scenarios H1-H6). */
/**
 * SPEC §7D "The §8 seams on an agent run" — {@code hooks} / {@code onMetric} on
 * {@link RuntimeOptions} and {@link AgentDef}: forwarded verbatim into the client the runtime
 * builds, resolved def-over-runtime (REPLACE, never merge), each field independently.
 *
 * <p>Hermetic: a localhost recording HTTP server stands in for the provider, so the exact
 * transcript that reached the wire is assertable.
 */
class AgentHooksTest {

    // ---------- recording mock LLM (records the wire transcript per model) ----------

    /** A scripted localhost provider that records every request body, keyed by {@code model}. */
    static final class RecordingLlm implements AutoCloseable {
        private final HttpServer server;
        final String base;
        /** model → the list of `messages` arrays seen, in order. */
        final Map<String, List<List<Object>>> sent = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        RecordingLlm() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> req = Json.toMap(body);
                String model = String.valueOf(req.get("model"));
                List<Object> msgs = req.get("messages") instanceof List<?> l
                        ? new ArrayList<>((List<Object>) l) : List.of();
                sent.computeIfAbsent(model, k -> Collections.synchronizedList(new ArrayList<>())).add(msgs);

                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "assistant");
                message.put("content", "ok");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("choices", List.of(Map.of("message", message)));
                payload.put("usage", Map.of("prompt_tokens", 30, "completion_tokens", 10, "total_tokens", 40));
                byte[] out = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<Object> last(String model) {
            List<List<Object>> runs = sent.get(model);
            if (runs == null || runs.isEmpty()) return List.of();
            return runs.get(runs.size() - 1);
        }

        @Override public void close() { server.stop(0); }
    }

    private static RecordingLlm mock;

    @BeforeAll
    static void start() { mock = new RecordingLlm(); }

    @AfterAll
    static void stop() { mock.close(); }

    // ---------- helpers ----------

    private static Map<String, AgentDef> reg(AgentDef... defs) {
        Map<String, AgentDef> m = new LinkedHashMap<>();
        for (AgentDef d : defs) m.put(d.name, d);
        return m;
    }

    private static RuntimeOptions opts(Map<String, AgentDef> registry) {
        return new RuntimeOptions().baseUrl(mock.base).apiKey("test").registry(registry);
    }

    private static LlmClient.Hooks injecting(String marker, AtomicInteger counter) {
        return new LlmClient.Hooks().beforeLLM(ev -> {
            counter.incrementAndGet();
            List<Object> msgs = new ArrayList<>(ev.messages());
            msgs.add(Map.of("role", "system", "content", marker));
            return new LlmClient.LLMOverride(msgs, null);
        });
    }

    private static boolean containsContent(List<Object> msgs, String content) {
        for (Object o : msgs) {
            if (o instanceof Map<?, ?> m && content.equals(m.get("content"))) return true;
        }
        return false;
    }

    /** Seed a handle's stored transcript with a leading system message + {@code pairs} exchanges. */
    private static void seed(LlmClient.ConversationStore store, String id, int pairs) {
        List<Object> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", "SOUL-VERBATIM"));
        for (int i = 0; i < pairs; i++) {
            msgs.add(Map.of("role", "user", "content", "q".repeat(200)));
            msgs.add(Map.of("role", "assistant", "content", "a".repeat(200)));
        }
        store.save(id, msgs);
    }

    // ---------- 1. a runtime-level beforeLLM hook runs inside an agent turn ----------

    @Test
    void runtimeHooks_runInsideAnAgentTurn_andReachTheWire() {
        AtomicInteger fired = new AtomicInteger();
        Map<String, AgentDef> registry = reg(new AgentDef("a", "x", "soul-a", "m-hooks-1"));
        AgentRuntime rt = new AgentRuntime(opts(registry).hooks(injecting("INJECTED-BY-HOOK", fired)));
        try {
            Handle h = rt.spawn(rt.root, "a").handle();
            TaskResult r = rt.runTurn(h, "hello");
            assertTrue(!r.isError(), r.text());
            assertTrue(fired.get() > 0, "beforeLLM never ran inside the agent turn");
            assertTrue(containsContent(mock.last("m-hooks-1"), "INJECTED-BY-HOOK"),
                    "hook rewrite did not reach the wire: " + mock.last("m-hooks-1"));
        } finally {
            rt.close(rt.root);
        }
    }

    // ---------- 2. a runtime-level onMetric receives the §8 events ----------

    @Test
    void runtimeOnMetric_receivesLlmAndRunEvents() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Map<String, AgentDef> registry = reg(new AgentDef("a", "x", "", "m-hooks-2"));
        AgentRuntime rt = new AgentRuntime(opts(registry).onMetric(ev -> events.add(ev.event())));
        try {
            Handle h = rt.spawn(rt.root, "a").handle();
            TaskResult r = rt.runTurn(h, "hi");
            assertTrue(!r.isError(), r.text());
            assertTrue(events.contains("llm"), "expected an llm event, got " + events);
            assertTrue(events.contains("run"), "expected a run event, got " + events);
        } finally {
            rt.close(rt.root);
        }
    }

    // ---------- 3. def-level REPLACES runtime-level, for that agent only ----------

    @Test
    void defHooks_replaceRuntimeHooks_perAgent() {
        AtomicInteger runtimeFired = new AtomicInteger();
        AtomicInteger defAFired = new AtomicInteger();
        AgentDef a = new AgentDef("a", "x", "", "m-hooks-3a").hooks(injecting("DEF-A", defAFired));
        AgentDef b = new AgentDef("b", "x", "", "m-hooks-3b");
        AgentRuntime rt = new AgentRuntime(
                opts(reg(a, b)).hooks(injecting("RUNTIME", runtimeFired)));
        try {
            Handle ha = rt.spawn(rt.root, "a").handle();
            Handle hb = rt.spawn(rt.root, "b").handle();
            assertTrue(!rt.runTurn(ha, "hi").isError());
            assertTrue(!rt.runTurn(hb, "hi").isError());

            assertEquals(1, defAFired.get(), "agent A runs its OWN hook once");
            assertEquals(1, runtimeFired.get(),
                    "the runtime hook runs ONLY for B — a def that sets hooks REPLACES, never merges");
            assertTrue(containsContent(mock.last("m-hooks-3a"), "DEF-A"), "A's own hook reached the wire");
            assertTrue(!containsContent(mock.last("m-hooks-3a"), "RUNTIME"),
                    "the runtime hook must NOT also run for A");
            assertTrue(containsContent(mock.last("m-hooks-3b"), "RUNTIME"), "B inherits the runtime hook");
        } finally {
            rt.close(rt.root);
        }
    }

    // ---------- 4. hooks and onMetric resolve INDEPENDENTLY ----------

    @Test
    void hooksAndOnMetric_resolveIndependently() {
        AtomicInteger defAFired = new AtomicInteger();
        List<String> runtimeMetrics = Collections.synchronizedList(new ArrayList<>());
        AgentDef a = new AgentDef("a", "x", "", "m-hooks-4").hooks(injecting("DEF-A-ONLY", defAFired));
        AgentRuntime rt = new AgentRuntime(opts(reg(a))
                .hooks(injecting("RUNTIME", new AtomicInteger()))
                .onMetric(ev -> runtimeMetrics.add(ev.event())));
        try {
            Handle h = rt.spawn(rt.root, "a").handle();
            assertTrue(!rt.runTurn(h, "hi").isError());
            assertEquals(1, defAFired.get(), "the def's hooks won");
            assertTrue(runtimeMetrics.contains("llm") && runtimeMetrics.contains("run"),
                    "overriding hooks must NOT drop the inherited runtime onMetric, got " + runtimeMetrics);
        } finally {
            rt.close(rt.root);
        }
    }

    // ---------- 5. a real §7F compactor attached per-agent compacts an agent run ----------

    @Test
    void compactorAttachedToADef_compactsTheAgentRun() {
        LlmClient.ConversationStore store = new LlmClient.InMemoryConversationStore();
        var compactor = Compaction.compactor(new Compaction.Options()
                .maxTokens(200)
                .keepTail(80)
                .summarize(older -> "SUMMARY-OF-HEAD"));
        AgentDef d = new AgentDef("long", "x", "SOUL-VERBATIM", "m-hooks-5")
                .hooks(new LlmClient.Hooks().beforeLLM(compactor));
        AgentRuntime rt = new AgentRuntime(opts(reg(d)).store(store));
        try {
            Handle h = rt.spawn(rt.root, "long").handle();
            seed(store, h.id, 40);
            List<Object> pre = store.get(h.id);
            assertEquals(81, pre.size(), "seed landed on the handle's conversation id");

            TaskResult r = rt.runTurn(h, "next question");
            assertTrue(!r.isError(), r.text());

            List<Object> wire = mock.last("m-hooks-5");
            assertTrue(wire.size() < pre.size(),
                    "compaction did not shrink the transcript: pre=" + pre.size() + " wire=" + wire.size());
            assertTrue(wire.get(0) instanceof Map<?, ?> m0 && "SOUL-VERBATIM".equals(m0.get("content")),
                    "leading system prompt not preserved verbatim: " + wire.get(0));
            assertTrue(Json.stringify(wire).contains("SUMMARY-OF-HEAD"),
                    "summary system message missing from the compacted transcript");
        } finally {
            rt.close(rt.root);
        }
    }

    // ---------- 6. §10: a turn that compacts and THEN suspends is rewound in FULL ----------

    @Test
    void compactionThenDurablePending_rewindsToTheFullPreTurnTranscript() {
        LlmClient.ConversationStore store = new LlmClient.InMemoryConversationStore();
        var compactor = Compaction.compactor(new Compaction.Options()
                .maxTokens(200)
                .keepTail(80)
                .summarize(older -> "SUMMARY-OF-HEAD"));
        Tool needsApproval = new Tool() {
            @Override public String name() { return "check_secret"; }
            @Override public String description() { return "needs human approval"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                if (ctx != null && ctx.answer() != null && ctx.answer().ok()) return ToolResult.ok("secret-token");
                return ToolResult.pending(new Request(null, "approval", "approve secret access?"));
            }
        };
        // A dedicated scripted provider: this agent always calls the pending tool → durable pending
        // (no interpreter anywhere in the tree).
        try (MockLlm asker = new MockLlm()) {
            AgentDef d = new AgentDef("asker", "needs approvals", "", "m-asker")
                    .tools(List.of(needsApproval))
                    .hooks(new LlmClient.Hooks().beforeLLM(compactor));
            AgentRuntime rt = new AgentRuntime(new RuntimeOptions()
                    .baseUrl(asker.base).apiKey("test").registry(reg(d)).store(store));
            try {
                Handle h = rt.spawn(rt.root, "asker").handle();
                seed(store, h.id, 40);
                List<Object> pre = store.get(h.id);
                assertEquals(81, pre.size());

                TaskResult r = rt.runTurn(h, "get the secret");
                assertEquals("pending", r.status(), "the fixture must suspend for this check to mean anything");

                List<Object> post = store.get(h.id);
                assertNotNull(post, "the pre-turn checkpoint was restored");
                assertEquals(pre.size(), post.size(),
                        "a compacted turn that suspends must be rewound to its FULL pre-turn transcript "
                                + "(compaction discarded), got " + post.size());
                assertEquals(pre, post, "the restored transcript is the pre-turn one, verbatim");
            } finally {
                rt.close(rt.root);
            }
        }
    }
}
