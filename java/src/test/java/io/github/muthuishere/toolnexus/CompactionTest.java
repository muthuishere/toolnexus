package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context compaction (SPEC §7F) — C1–C6 (11 checks), driven by the shared cross-language
 * fixture {@code examples/compaction/fixture.json}. Proves: no-op below budget (byte-identical),
 * summarize-above-budget, tool-pair safety, tail preservation, system-prompt preservation, and
 * end-to-end through the SHIPPED {@link LlmClient} loop via the {@code beforeLLM} seam. Hermetic:
 * a localhost mock LLM, no network, no live model.
 */
class CompactionTest {

    // -- fixture ----------------------------------------------------------------

    private final String systemPrompt;
    private final int userTurns;
    private final int maxTokens;
    private final int keepTail;

    @SuppressWarnings("unchecked")
    CompactionTest() throws IOException {
        Path fixture = Path.of(TestFixtures.fixture("compaction/fixture.json"));
        Map<String, Object> f = Json.toMap(Files.readString(fixture));
        Map<String, Object> input = (Map<String, Object>) f.get("input");
        Map<String, Object> generator = (Map<String, Object>) input.get("generator");
        Map<String, Object> options = (Map<String, Object>) f.get("options");
        this.systemPrompt = (String) input.get("systemPrompt");
        this.userTurns = ((Number) generator.get("userTurns")).intValue();
        this.maxTokens = ((Number) options.get("maxTokens")).intValue();
        this.keepTail = ((Number) options.get("keepTail")).intValue();
    }

    /**
     * Build the transcript deterministically per the fixture generator: the systemPrompt, then
     * {@code userTurns} groups of exactly four messages (user / assistant+tool_calls / tool /
     * assistant), padded so it overflows a small budget.
     */
    private List<Object> transcript(int turns) {
        List<Object> m = new ArrayList<>();
        m.add(msg("system", systemPrompt));
        for (int i = 0; i < turns; i++) {
            m.add(msg("user", "question " + i + " " + "pad ".repeat(40)));

            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", "c" + i);
            call.put("type", "function");
            call.put("function", Map.of("name", "lookup", "arguments", "{}"));
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", null);
            assistant.put("tool_calls", List.of(call));
            m.add(assistant);

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("role", "tool");
            tool.put("tool_call_id", "c" + i);
            tool.put("content", "result " + i + " " + "data ".repeat(40));
            m.add(tool);

            m.add(msg("assistant", "answer " + i));
        }
        return m;
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** The deterministic summarizer of the fixture: "summarized <N> messages". */
    private static final Function<List<Object>, String> SUMMARIZE =
            older -> "summarized " + older.size() + " messages";

    @SuppressWarnings("unchecked")
    private static String role(Object m) {
        return (String) ((Map<String, Object>) m).get("role");
    }

    @SuppressWarnings("unchecked")
    private static Object field(Object m, String k) {
        return ((Map<String, Object>) m).get(k);
    }

    private static LlmClient.BeforeLLMEvent ev(List<Object> messages) {
        return new LlmClient.BeforeLLMEvent(messages, List.of(), "m", 0);
    }

    // -- C1: no-op below budget -------------------------------------------------

    @Test
    void c1_belowBudgetIsByteIdenticalNoOp() {
        var hook = Compaction.compactor(new Compaction.Options().maxTokens(100_000).summarize(SUMMARIZE));
        List<Object> msgs = transcript(3);
        LlmClient.LLMOverride out = hook.apply(ev(msgs));
        assertNull(out, "returns nothing (no override) when under budget");
    }

    // -- C2: compacts above budget ---------------------------------------------

    @Test
    void c2_aboveBudgetSummarizesHeadKeepsTail() {
        List<Object> msgs = transcript(userTurns);
        int before = Compaction.estimateTokens(msgs);
        var hook = Compaction.compactor(new Compaction.Options()
                .maxTokens(maxTokens).keepTail(keepTail).summarize(SUMMARIZE));
        LlmClient.LLMOverride out = hook.apply(ev(msgs));

        assertNotNull(out, "compacts when over budget");
        assertTrue(Compaction.estimateTokens(out.messages()) < before, "result is smaller than the original");
        boolean hasSummary = out.messages().stream()
                .anyMatch(m -> String.valueOf(field(m, "content")).startsWith("[Summary of earlier conversation]"));
        assertTrue(hasSummary, "a summary system message is inserted");
    }

    // -- C3: tool-pair safety ---------------------------------------------------

    @Test
    void c3_toolPairSafetyNoOrphanedToolResult() {
        List<Object> msgs = transcript(userTurns);
        var hook = Compaction.compactor(new Compaction.Options()
                .maxTokens(maxTokens).keepTail(keepTail).summarize(SUMMARIZE));
        List<Object> t = hook.apply(ev(msgs)).messages();

        boolean safe = true;
        for (int i = 0; i < t.size(); i++) {
            if ("tool".equals(role(t.get(i)))) {
                Object id = field(t.get(i), "tool_call_id");
                boolean hasParent = false;
                for (int j = 0; j < i; j++) {
                    if ("assistant".equals(role(t.get(j)))) {
                        Object calls = field(t.get(j), "tool_calls");
                        if (calls instanceof List<?> l) {
                            for (Object c : l) {
                                if (c instanceof Map<?, ?> cm && java.util.Objects.equals(cm.get("id"), id)) {
                                    hasParent = true;
                                }
                            }
                        }
                    }
                }
                if (!hasParent) safe = false;
            }
        }
        assertTrue(safe, "no tool message is orphaned from its tool_calls");

        Object firstNonSystem = t.stream().filter(m -> !"system".equals(role(m))).findFirst().orElse(null);
        assertNotNull(firstNonSystem);
        assertEquals("user", role(firstNonSystem), "the tail begins at a clean user turn");
    }

    // -- C4: system prompt preserved -------------------------------------------

    @Test
    void c4_leadingSystemPromptPreservedVerbatim() {
        List<Object> msgs = transcript(userTurns);
        var hook = Compaction.compactor(new Compaction.Options().maxTokens(maxTokens).summarize(SUMMARIZE));
        List<Object> t = hook.apply(ev(msgs)).messages();
        assertEquals("system", role(t.get(0)), "first message is still system");
        assertTrue(String.valueOf(field(t.get(0), "content")).contains("SOUL"),
                "original SOUL system prompt is still first, verbatim");
    }

    // -- C5: flush-to-memory nudge ---------------------------------------------

    @Test
    void c5_flushToMemoryInjectsReminder() {
        List<Object> msgs = transcript(userTurns);
        var hook = Compaction.compactor(new Compaction.Options()
                .maxTokens(maxTokens).summarize(SUMMARIZE).flushToMemory(true));
        List<Object> t = hook.apply(ev(msgs)).messages();
        boolean hasReminder = t.stream()
                .anyMatch(m -> String.valueOf(field(m, "content")).contains("save it with the memory tool"));
        assertTrue(hasReminder, "a 'save with the memory tool' reminder is present");
    }

    // -- C6: end-to-end through the SHIPPED client loop -------------------------

    @Test
    void c6_wiredViaBeforeLLMLoopKeepsGoingPastCompaction() throws IOException {
        // Mock LLM: makes 6 tool calls (padding the transcript), then a final answer that reports
        // whether it saw a compacted transcript ([Summary ...]).
        AtomicInteger calls = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = Json.toMap(body);
            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) req.get("messages");
            boolean compacted = messages != null && messages.stream()
                    .anyMatch(m -> String.valueOf(field(m, "content")).startsWith("[Summary"));
            int c = calls.get();
            boolean done = c >= 6;
            Map<String, Object> message;
            if (done) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("role", "assistant");
                mm.put("content", "final (compacted=" + compacted + ")");
                message = mm;
            } else {
                calls.incrementAndGet();
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", "c" + c);
                call.put("type", "function");
                call.put("function", Map.of("name", "pad", "arguments", "{}"));
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("role", "assistant");
                mm.put("content", null);
                mm.put("tool_calls", List.of(call));
                message = mm;
            }
            Map<String, Object> data = Map.of(
                    "choices", List.of(Map.of("message", message)),
                    "usage", Map.of("prompt_tokens", 50, "completion_tokens", 10, "total_tokens", 60));
            respond(exchange, 200, Json.stringify(data));
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        Tool pad = NativeTool.of("pad", "pads", null, args -> "x ".repeat(600));
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(pad))) {
            LlmClient.Hooks hooks = new LlmClient.Hooks().beforeLLM(
                    Compaction.compactor(new Compaction.Options().maxTokens(600).keepTail(250).summarize(SUMMARIZE)));
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("m").apiKey("spike").maxTurns(20)
                    .systemPrompt(systemPrompt).hooks(hooks));

            LlmClient.RunResult r = client.run("start", tk);
            assertEquals("done", r.status, "run completes to a final answer despite mid-run compaction");
            assertTrue(r.text.startsWith("final"), "final answer reached: " + r.text);
            assertTrue(r.text.contains("compacted=true"), "compaction actually fired during the run: " + r.text);
            assertTrue(Compaction.estimateTokens(r.messages) < 4000,
                    "final transcript is bounded, not full raw history: " + Compaction.estimateTokens(r.messages));
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
