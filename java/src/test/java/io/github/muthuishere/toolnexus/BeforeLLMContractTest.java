package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC.md §8 beforeLLM (add-judge-batteries): a failing hook stops the call (no provider request)
 * and a {@code model} override is transmitted AND reported — in every loop (run/stream x
 * openai/anthropic), the §7D agent run, and translate.
 */
class BeforeLLMContractTest {

    /** Turn 1 calls {@code deploy}; later turns answer "done". Speaks both styles, JSON and SSE. */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();
        final boolean singleTurn;

        Stub(boolean singleTurn) throws Exception {
            this.singleTurn = singleTurn;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                Map<String, Object> body = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                bodies.add(body);
                boolean call = !singleTurn && bodies.size() == 1;
                boolean anthropic = ex.getRequestURI().getPath().endsWith("/messages");
                boolean stream = Boolean.TRUE.equals(body.get("stream"));
                String out;
                if (stream) {
                    out = anthropic ? anthropicSse(call) : openaiSse(call);
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                } else {
                    out = anthropic ? anthropicJson(call) : openaiJson(call);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                }
                byte[] b = out.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            });
            server.start();
        }

        static String openaiJson(boolean call) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "assistant");
            if (call) {
                msg.put("content", null);
                msg.put("tool_calls", List.of(Map.of("id", "c1", "type", "function",
                        "function", Map.of("name", "deploy", "arguments", "{}"))));
            } else msg.put("content", "done");
            return Json.stringify(Map.of("choices", List.of(Map.of("message", msg,
                    "finish_reason", call ? "tool_calls" : "stop"))));
        }

        static String anthropicJson(boolean call) {
            Object block = call ? Map.of("type", "tool_use", "id", "c1", "name", "deploy", "input", Map.of())
                    : Map.of("type", "text", "text", "done");
            return Json.stringify(Map.of("content", List.of(block), "stop_reason", call ? "tool_use" : "end_turn"));
        }

        static String openaiSse(boolean call) {
            Object delta = call
                    ? Map.of("tool_calls", List.of(Map.of("index", 0, "id", "c1",
                            "function", Map.of("name", "deploy", "arguments", "{}"))))
                    : Map.of("content", "done");
            return "data: " + Json.stringify(Map.of("choices", List.of(Map.of("delta", delta)))) + "\n\ndata: [DONE]\n\n";
        }

        static String anthropicSse(boolean call) {
            StringBuilder s = new StringBuilder();
            if (call) {
                s.append("data: ").append(Json.stringify(Map.of("type", "content_block_start", "index", 0,
                        "content_block", Map.of("type", "tool_use", "id", "c1", "name", "deploy")))).append("\n\n");
                s.append("data: ").append(Json.stringify(Map.of("type", "content_block_delta", "index", 0,
                        "delta", Map.of("type", "input_json_delta", "partial_json", "{}")))).append("\n\n");
                s.append("data: ").append(Json.stringify(Map.of("type", "message_delta",
                        "delta", Map.of("stop_reason", "tool_use")))).append("\n\n");
            } else {
                s.append("data: ").append(Json.stringify(Map.of("type", "content_block_start", "index", 0,
                        "content_block", Map.of("type", "text", "text", "")))).append("\n\n");
                s.append("data: ").append(Json.stringify(Map.of("type", "content_block_delta", "index", 0,
                        "delta", Map.of("type", "text_delta", "text", "done")))).append("\n\n");
                s.append("data: ").append(Json.stringify(Map.of("type", "message_delta",
                        "delta", Map.of("stop_reason", "end_turn")))).append("\n\n");
            }
            s.append("data: {\"type\":\"message_stop\"}\n\n");
            return s.toString();
        }

        LlmClient.Options options(String style, LlmClient.Hooks hooks, List<LlmClient.MetricEvent> metrics) {
            return new LlmClient.Options().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .style(style).model("configured").apiKey("k").maxTurns(4).retries(0)
                    .hooks(hooks).onMetric(metrics::add);
        }

        @Override public void close() { server.stop(0); }
    }

    static Toolkit toolkit() {
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        tk.register(new Tool() {
            @Override public String name() { return "deploy"; }
            @Override public String description() { return "deploy"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return ToolResult.ok("D"); }
        });
        return tk;
    }

    enum Entry { RUN, STREAM, AGENT }

    static LlmClient.RunResult go(Entry e, LlmClient.Options o, Toolkit tk) {
        return switch (e) {
            case RUN -> LlmClient.create(o).run("ship it", tk);
            case STREAM -> LlmClient.create(o).stream("ship it", tk, ev -> {});
            case AGENT -> new Loop(o, tk, null, null, null, null).run("ship it").result;
        };
    }

    static final class Boom extends RuntimeException { Boom() { super("hook failed"); } }

    private static List<String> llmModels(List<LlmClient.MetricEvent> ms) {
        List<String> out = new ArrayList<>();
        for (var m : ms) if (m instanceof LlmClient.MetricEvent.Llm l) out.add(l.model());
        return out;
    }

    private static LlmClient.MetricEvent.Run runEvent(List<LlmClient.MetricEvent> ms) {
        LlmClient.MetricEvent.Run r = null;
        for (var m : ms) if (m instanceof LlmClient.MetricEvent.Run x) r = x;
        return r;
    }

    @Test
    void failingBeforeLLMStopsEveryLoop() throws Exception {
        for (String style : List.of("openai", "anthropic")) {
            for (Entry e : Entry.values()) {
                String label = style + "/" + e;
                // turn 0 fails: nothing is sent.
                try (Stub s = new Stub(false); Toolkit tk = toolkit()) {
                    var o = s.options(style, new LlmClient.Hooks().beforeLLM(ev -> { throw new Boom(); }), new ArrayList<>());
                    assertThrows(Boom.class, () -> go(e, o, tk), label);
                    assertEquals(0, s.bodies.size(), label + ": no provider request");
                }
                // turn 1 fails: only turn 0's request was sent; the failure is not swallowed.
                try (Stub s = new Stub(false); Toolkit tk = toolkit()) {
                    List<LlmClient.MetricEvent> ms = new CopyOnWriteArrayList<>();
                    var o = s.options(style, new LlmClient.Hooks().beforeLLM(ev -> {
                        if (ev.turn() == 1) throw new Boom();
                        return LlmClient.LLMOverride.withModel("t0");
                    }), ms);
                    assertThrows(Boom.class, () -> go(e, o, tk), label);
                    assertEquals(1, s.bodies.size(), label + ": turn 1 never sent");
                    // a failed run reports the last model call's model.
                    assertEquals("t0", runEvent(ms).model(), label + ": failed-run model");
                    assertNotNull(runEvent(ms).error(), label);
                }
            }
        }
    }

    @Test
    void modelOverrideIsTransmittedAndReportedInEveryLoop() throws Exception {
        for (String style : List.of("openai", "anthropic")) {
            for (Entry e : Entry.values()) {
                String label = style + "/" + e;
                try (Stub s = new Stub(false); Toolkit tk = toolkit()) {
                    List<LlmClient.MetricEvent> ms = new CopyOnWriteArrayList<>();
                    List<String> after = new CopyOnWriteArrayList<>();
                    var o = s.options(style, new LlmClient.Hooks()
                            .beforeLLM(ev -> LlmClient.LLMOverride.withModel("t" + ev.turn()))
                            .afterLLM(ev -> after.add(ev.model())), ms);
                    LlmClient.RunResult r = go(e, o, tk);
                    assertEquals(2, s.bodies.size(), label);
                    assertEquals("t0", s.bodies.get(0).get("model"), label);
                    assertEquals("t1", s.bodies.get(1).get("model"), label);
                    assertEquals(List.of("t0", "t1"), after, label);
                    assertEquals(List.of("t0", "t1"), llmModels(ms), label + ": llm events");
                    assertEquals("t1", r.model, label + ": RunResult.model = last call");
                    assertEquals("t1", runEvent(ms).model(), label + ": run event");
                }
                // override on turn 0 only: the last call used the configured model.
                try (Stub s = new Stub(false); Toolkit tk = toolkit()) {
                    List<LlmClient.MetricEvent> ms = new CopyOnWriteArrayList<>();
                    var o = s.options(style, new LlmClient.Hooks()
                            .beforeLLM(ev -> ev.turn() == 0 ? LlmClient.LLMOverride.withModel("small") : null), ms);
                    LlmClient.RunResult r = go(e, o, tk);
                    assertEquals("small", s.bodies.get(0).get("model"), label);
                    assertEquals("configured", s.bodies.get(1).get("model"), label);
                    assertEquals(List.of("small", "configured"), llmModels(ms), label);
                    assertEquals("configured", r.model, label);
                    assertEquals("configured", runEvent(ms).model(), label);
                }
                // absent / empty override: configured, verbatim, everywhere.
                try (Stub s = new Stub(false); Toolkit tk = toolkit()) {
                    List<LlmClient.MetricEvent> ms = new CopyOnWriteArrayList<>();
                    var o = s.options(style, new LlmClient.Hooks().beforeLLM(ev -> LlmClient.LLMOverride.withModel("")), ms);
                    LlmClient.RunResult r = go(e, o, tk);
                    for (var b : s.bodies) assertEquals("configured", b.get("model"), label);
                    assertEquals(List.of("configured", "configured"), llmModels(ms), label);
                    assertEquals("configured", r.model, label);
                }
            }
        }
    }

    private static Translate.Request treq() {
        return new Translate.Request().messages(List.of(Map.of("role", "user", "content", "hi")));
    }

    @Test
    void translateBeforeLLMContract() throws Exception {
        for (String style : List.of("openai", "anthropic")) {
            try (Stub s = new Stub(true)) {
                var o = s.options(style, new LlmClient.Hooks().beforeLLM(ev -> { throw new Boom(); }), new ArrayList<>());
                assertThrows(Boom.class, () -> LlmClient.create(o).translate(treq()), style);
                assertEquals(0, s.bodies.size(), style + ": no provider request");
            }
            try (Stub s = new Stub(true)) {
                List<LlmClient.MetricEvent> ms = new CopyOnWriteArrayList<>();
                var o = s.options(style, new LlmClient.Hooks().beforeLLM(ev -> LlmClient.LLMOverride.withModel("small")), ms);
                Translate.Result r = LlmClient.create(o).translate(treq());
                assertEquals("small", s.bodies.get(0).get("model"), style);
                assertEquals("small", r.model, style);
                assertEquals(List.of("small"), llmModels(ms), style);
            }
            try (Stub s = new Stub(true)) {
                var o = s.options(style, new LlmClient.Hooks().beforeLLM(ev -> null), new ArrayList<>());
                Translate.Result r = LlmClient.create(o).translate(treq());
                assertEquals("configured", s.bodies.get(0).get("model"), style);
                assertEquals("configured", r.model, style);
            }
        }
    }
}
