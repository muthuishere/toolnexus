package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC.md §8 {@code afterRound} (change add-round-boundary-hook, #192). Shared fixture:
 * examples/round-boundary/fixture.json (R1-R6), in every loop (run/stream x openai/anthropic)
 * and the §7D agent run. Hermetic: a scripted local HTTP stub.
 */
class AfterRoundTest {

    /** Replays scripted replies: {@code "@call"} = an echo tool call, anything else = answer text. */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();

        Stub(List<String> replies) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                Map<String, Object> body = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                bodies.add(body);
                String reply = replies.get(Math.min(bodies.size() - 1, replies.size() - 1));
                boolean call = "@call".equals(reply);
                boolean anthropic = ex.getRequestURI().getPath().endsWith("/messages");
                boolean stream = Boolean.TRUE.equals(body.get("stream"));
                String out;
                if (stream) {
                    out = anthropic ? anthropicSse(call, reply) : openaiSse(call, reply);
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                } else {
                    out = anthropic ? anthropicJson(call, reply) : openaiJson(call, reply);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                }
                byte[] b = out.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            });
            server.start();
        }

        static final String ARGS = "{\"text\":\"hi\"}";

        static String openaiJson(boolean call, String text) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "assistant");
            if (call) {
                msg.put("content", null);
                msg.put("tool_calls", List.of(Map.of("id", "c1", "type", "function",
                        "function", Map.of("name", "echo", "arguments", ARGS))));
            } else msg.put("content", text);
            return Json.stringify(Map.of("choices", List.of(Map.of("message", msg,
                    "finish_reason", call ? "tool_calls" : "stop"))));
        }

        static String anthropicJson(boolean call, String text) {
            Object block = call ? Map.of("type", "tool_use", "id", "c1", "name", "echo", "input", Map.of("text", "hi"))
                    : Map.of("type", "text", "text", text);
            return Json.stringify(Map.of("content", List.of(block), "stop_reason", call ? "tool_use" : "end_turn"));
        }

        static String openaiSse(boolean call, String text) {
            Object delta = call
                    ? Map.of("tool_calls", List.of(Map.of("index", 0, "id", "c1",
                            "function", Map.of("name", "echo", "arguments", ARGS))))
                    : Map.of("content", text);
            return "data: " + Json.stringify(Map.of("choices", List.of(Map.of("delta", delta)))) + "\n\ndata: [DONE]\n\n";
        }

        static String anthropicSse(boolean call, String text) {
            StringBuilder s = new StringBuilder();
            if (call) {
                s.append("data: ").append(Json.stringify(Map.of("type", "content_block_start", "index", 0,
                        "content_block", Map.of("type", "tool_use", "id", "c1", "name", "echo")))).append("\n\n");
                s.append("data: ").append(Json.stringify(Map.of("type", "content_block_delta", "index", 0,
                        "delta", Map.of("type", "input_json_delta", "partial_json", ARGS)))).append("\n\n");
                s.append("data: ").append(Json.stringify(Map.of("type", "message_delta",
                        "delta", Map.of("stop_reason", "tool_use")))).append("\n\n");
            } else {
                s.append("data: ").append(Json.stringify(Map.of("type", "content_block_start", "index", 0,
                        "content_block", Map.of("type", "text", "text", "")))).append("\n\n");
                s.append("data: ").append(Json.stringify(Map.of("type", "content_block_delta", "index", 0,
                        "delta", Map.of("type", "text_delta", "text", text)))).append("\n\n");
                s.append("data: ").append(Json.stringify(Map.of("type", "message_delta",
                        "delta", Map.of("stop_reason", "end_turn")))).append("\n\n");
            }
            s.append("data: {\"type\":\"message_stop\"}\n\n");
            return s.toString();
        }

        LlmClient.Options options(String style, LlmClient.Hooks hooks, int maxTurns) {
            return new LlmClient.Options().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .style(style).model("m").apiKey("k").maxTurns(maxTurns).retries(0).hooks(hooks);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentMessages(int i) {
            return (List<Map<String, Object>>) bodies.get(i).get("messages");
        }

        @Override public void close() { server.stop(0); }
    }

    static Toolkit toolkit() {
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        tk.register(new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "echo"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return ToolResult.ok(String.valueOf(args.get("text")));
            }
        });
        return tk;
    }

    enum Entry { RUN, STREAM, AGENT }

    static LlmClient.RunResult go(Entry e, LlmClient.Options o, Toolkit tk) {
        return switch (e) {
            case RUN -> LlmClient.create(o).run("go", tk);
            case STREAM -> LlmClient.create(o).stream("go", tk, ev -> {});
            case AGENT -> new Loop(o, tk, null, null, null, null).run("go").result;
        };
    }

    record Call(int turn, boolean fin, String text) {}

    static Map<String, Object> last(List<Map<String, Object>> msgs, int fromEnd) {
        return msgs.get(msgs.size() - fromEnd);
    }

    @Test
    void r1InjectAfterToolResults() throws Exception {
        for (String style : List.of("openai", "anthropic")) for (Entry e : Entry.values()) {
            String label = style + "/" + e;
            try (Stub s = new Stub(List.of("@call", "done")); Toolkit tk = toolkit()) {
                List<Call> calls = new CopyOnWriteArrayList<>();
                var h = new LlmClient.Hooks().afterRound(ev -> {
                    calls.add(new Call(ev.turn(), ev.isFinal(), ev.text()));
                    return ev.isFinal() ? null : LlmClient.RoundInjection.of("todo: 2 open");
                });
                LlmClient.RunResult r = go(e, s.options(style, h, 10), tk);
                assertEquals(2, s.bodies.size(), label);
                assertEquals("done", r.text, label);
                var tail = last(s.sentMessages(1), 1);
                assertEquals("user", tail.get("role"), label);
                assertEquals("todo: 2 open", tail.get("content"), label);
                assertEquals(List.of(new Call(0, false, ""), new Call(1, true, "done")), calls, label);
            }
        }
    }

    @Test
    void r2ContinueWhenModelStops() throws Exception {
        for (String style : List.of("openai", "anthropic")) for (Entry e : Entry.values()) {
            String label = style + "/" + e;
            try (Stub s = new Stub(List.of("first", "second")); Toolkit tk = toolkit()) {
                List<Call> calls = new CopyOnWriteArrayList<>();
                var h = new LlmClient.Hooks().afterRound(ev -> {
                    calls.add(new Call(ev.turn(), ev.isFinal(), ev.text()));
                    return ev.isFinal() && ev.turn() == 0 ? LlmClient.RoundInjection.of("keep going") : null;
                });
                LlmClient.RunResult r = go(e, s.options(style, h, 10), tk);
                assertEquals(2, s.bodies.size(), label);
                assertEquals("second", r.text, label);
                var tail = last(s.sentMessages(1), 1);
                assertEquals("user", tail.get("role"), label);
                assertEquals("keep going", tail.get("content"), label);
                assertEquals("assistant", last(s.sentMessages(1), 2).get("role"), label);
                assertEquals(List.of(new Call(0, true, "first"), new Call(1, true, "second")), calls, label);
            }
        }
    }

    @Test
    void r3EmptyInjectionEndsNormally() throws Exception {
        for (Entry e : Entry.values()) {
            try (Stub s = new Stub(List.of("only")); Toolkit tk = toolkit()) {
                int[] n = {0};
                var h = new LlmClient.Hooks().afterRound(ev -> { n[0]++; return LlmClient.RoundInjection.of(""); });
                LlmClient.RunResult r = go(e, s.options("openai", h, 10), tk);
                assertEquals(1, s.bodies.size(), "" + e);
                assertEquals("only", r.text);
                assertEquals(1, n[0]);
            }
        }
    }

    @Test
    void r4NoContinuationPastMaxTurns() throws Exception {
        for (Entry e : Entry.values()) {
            try (Stub s = new Stub(List.of("only")); Toolkit tk = toolkit()) {
                int[] n = {0};
                var h = new LlmClient.Hooks().afterRound(ev -> { n[0]++; return LlmClient.RoundInjection.of("again"); });
                LlmClient.RunResult r = go(e, s.options("openai", h, 1), tk);
                assertEquals(1, s.bodies.size(), "" + e);
                assertEquals("only", r.text);
                assertEquals(0, n[0]);
            }
        }
    }

    static final class Boom extends RuntimeException { Boom() { super("round-boom"); } }

    @Test
    void r5FailingHookStopsTheRun() throws Exception {
        for (Entry e : Entry.values()) {
            try (Stub s = new Stub(List.of("@call", "never")); Toolkit tk = toolkit()) {
                var h = new LlmClient.Hooks().afterRound(ev -> { throw new Boom(); });
                assertThrows(Boom.class, () -> go(e, s.options("openai", h, 10), tk), "" + e);
                assertEquals(1, s.bodies.size(), "" + e);
            }
        }
    }

    @Test
    void absentHookIsByteIdentical() throws Exception {
        for (Entry e : Entry.values()) {
            try (Stub a = new Stub(List.of("@call", "done")); Stub b = new Stub(List.of("@call", "done"));
                 Toolkit tk = toolkit()) {
                go(e, a.options("openai", null, 10), tk);
                go(e, b.options("openai", new LlmClient.Hooks().afterRound(ev -> null), 10), tk);
                assertEquals(a.bodies, b.bodies, "" + e);
            }
        }
    }
}
