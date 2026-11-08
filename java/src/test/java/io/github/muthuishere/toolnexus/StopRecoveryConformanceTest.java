package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC §8 "Stop-reason recovery": replays {@code conformance/stop_recovery/cases.json} in both
 * styles (openai, anthropic) and both loops (run, stream) against a scripted local provider.
 */
@SuppressWarnings("unchecked")
class StopRecoveryConformanceTest {

    static final Map<String, Object> FIXTURE;
    static {
        try {
            Path file = Path.of(TestFixtures.fixture("")).getParent().resolve("conformance/stop_recovery/cases.json");
            FIXTURE = Json.toMap(Files.readString(file));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static final Map<String, String> STOP_OPENAI = Map.of("end", "stop", "truncated", "length", "tool", "tool_calls");
    static final Map<String, String> STOP_ANTHROPIC = Map.of("end", "end_turn", "truncated", "max_tokens", "tool", "tool_use");

    /** Serves the case's responses in order, recording every request body. */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();

        Stub(List<Object> responses) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                Map<String, Object> body = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                bodies.add(body);
                Map<String, Object> r = (Map<String, Object>) responses.get(Math.min(bodies.size() - 1, responses.size() - 1));
                boolean anthropic = ex.getRequestURI().getPath().endsWith("/messages");
                boolean stream = Boolean.TRUE.equals(body.get("stream"));
                String out = stream ? (anthropic ? anthropicSse(r) : openaiSse(r))
                        : (anthropic ? anthropicJson(r) : openaiJson(r));
                ex.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                byte[] b = out.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override public void close() { server.stop(0); }
    }

    static List<Map<String, Object>> calls(Map<String, Object> r) {
        Object c = r.get("toolCalls");
        return c == null ? List.of() : (List<Map<String, Object>>) c;
    }

    static String text(Map<String, Object> r) { return r.get("text") == null ? "" : (String) r.get("text"); }

    static String openaiJson(Map<String, Object> r) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", text(r));
        if (r.get("thinking") != null) msg.put("reasoning_content", r.get("thinking"));
        List<Object> tcs = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> c : calls(r)) {
            tcs.add(Map.of("id", "c" + i++, "type", "function",
                    "function", Map.of("name", c.get("name"), "arguments", c.get("arguments"))));
        }
        if (!tcs.isEmpty()) msg.put("tool_calls", tcs);
        return Json.stringify(Map.of("choices", List.of(Map.of("message", msg,
                "finish_reason", STOP_OPENAI.get(r.get("stop"))))));
    }

    static String anthropicJson(Map<String, Object> r) {
        List<Object> content = new ArrayList<>();
        if (r.get("thinking") != null)
            content.add(Map.of("type", "thinking", "thinking", r.get("thinking"), "signature", "sig"));
        if (!text(r).isEmpty() || calls(r).isEmpty()) content.add(Map.of("type", "text", "text", text(r)));
        int i = 0;
        for (Map<String, Object> c : calls(r)) {
            String raw = (String) c.get("arguments");
            Object parsed = Json.parseLoose(raw);
            content.add(Map.of("type", "tool_use", "id", "c" + i++, "name", c.get("name"),
                    "input", parsed instanceof Map ? parsed : raw));
        }
        return Json.stringify(Map.of("content", content, "stop_reason", STOP_ANTHROPIC.get(r.get("stop"))));
    }

    static String data(Object o) { return "data: " + Json.stringify(o) + "\n\n"; }

    static String openaiSse(Map<String, Object> r) {
        StringBuilder s = new StringBuilder();
        if (r.get("thinking") != null)
            s.append(data(Map.of("choices", List.of(Map.of("delta", Map.of("reasoning_content", r.get("thinking")))))));
        if (!text(r).isEmpty())
            s.append(data(Map.of("choices", List.of(Map.of("delta", Map.of("content", text(r)))))));
        int i = 0;
        for (Map<String, Object> c : calls(r)) {
            s.append(data(Map.of("choices", List.of(Map.of("delta", Map.of("tool_calls", List.of(Map.of(
                    "index", i, "id", "c" + i, "function", Map.of("name", c.get("name"), "arguments", c.get("arguments"))))))))));
            i++;
        }
        s.append(data(Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", STOP_OPENAI.get(r.get("stop")))))));
        return s.append("data: [DONE]\n\n").toString();
    }

    static String anthropicSse(Map<String, Object> r) {
        StringBuilder s = new StringBuilder();
        int idx = 0;
        if (r.get("thinking") != null) {
            s.append(data(Map.of("type", "content_block_start", "index", idx,
                    "content_block", Map.of("type", "thinking", "thinking", ""))));
            s.append(data(Map.of("type", "content_block_delta", "index", idx,
                    "delta", Map.of("type", "thinking_delta", "thinking", r.get("thinking")))));
            idx++;
        }
        if (!text(r).isEmpty()) {
            s.append(data(Map.of("type", "content_block_start", "index", idx,
                    "content_block", Map.of("type", "text", "text", ""))));
            s.append(data(Map.of("type", "content_block_delta", "index", idx,
                    "delta", Map.of("type", "text_delta", "text", text(r)))));
            idx++;
        }
        for (Map<String, Object> c : calls(r)) {
            s.append(data(Map.of("type", "content_block_start", "index", idx,
                    "content_block", Map.of("type", "tool_use", "id", "c" + idx, "name", c.get("name")))));
            s.append(data(Map.of("type", "content_block_delta", "index", idx,
                    "delta", Map.of("type", "input_json_delta", "partial_json", c.get("arguments")))));
            idx++;
        }
        s.append(data(Map.of("type", "message_delta", "delta", Map.of("stop_reason", STOP_ANTHROPIC.get(r.get("stop"))))));
        return s.append("data: {\"type\":\"message_stop\"}\n\n").toString();
    }

    @Test
    void pinnedTextsMatchFixture() {
        Map<String, Object> m = (Map<String, Object>) FIXTURE.get("messages");
        assertEquals(m.get("NUDGE"), StopRecovery.NUDGE);
        assertEquals(m.get("CONTINUE"), StopRecovery.CONTINUE);
        assertEquals(m.get("CORRECTIVE"), StopRecovery.CORRECTIVE);
    }

    @Test
    void replaysEveryCaseInBothStylesAndLoops() throws Exception {
        Map<String, Object> texts = (Map<String, Object>) FIXTURE.get("messages");
        List<Object> cases = (List<Object>) FIXTURE.get("cases");
        assertFalse(cases.isEmpty());
        for (Object co : cases) {
            Map<String, Object> c = (Map<String, Object>) co;
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            for (String style : List.of("openai", "anthropic")) {
                for (boolean stream : List.of(false, true)) {
                    String label = c.get("name") + "/" + style + "/" + (stream ? "stream" : "run");
                    List<Map<String, Object>> runs = new CopyOnWriteArrayList<>();
                    try (Stub s = new Stub((List<Object>) c.get("responses"));
                         Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                        tk.register(new Tool() {
                            @Override public String name() { return "echo"; }
                            @Override public String description() { return "echo"; }
                            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
                            @Override public String source() { return "custom"; }
                            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                                runs.add(args);
                                return ToolResult.ok("ok");
                            }
                        });
                        LlmClient.Options o = new LlmClient.Options().baseUrl(s.url()).style(style).model("m")
                                .apiKey("k").retries(0).stopRecovery(Boolean.TRUE.equals(c.get("stopRecovery")));
                        if (c.get("maxTurns") instanceof Number n) o.maxTurns(n.intValue());
                        LlmClient client = LlmClient.create(o);
                        LlmClient.RunResult r = stream ? client.stream("go", tk, ev -> {}) : client.run("go", tk);

                        assertEquals(((Number) expect.get("turns")).intValue(), r.turns, label + ": turns");
                        assertEquals(r.turns, s.bodies.size(), label + ": provider calls");
                        assertEquals(expect.get("text"), r.text, label + ": text");

                        // Recoveries: each body whose last message is a pinned user message, in order.
                        List<String> got = new ArrayList<>();
                        for (int i = 1; i < s.bodies.size(); i++) {
                            List<Object> msgs = (List<Object>) s.bodies.get(i).get("messages");
                            Map<String, Object> last = (Map<String, Object>) msgs.get(msgs.size() - 1);
                            for (var t : texts.entrySet()) {
                                if (Map.of("role", "user", "content", t.getValue()).equals(last)) got.add(t.getKey());
                            }
                        }
                        assertEquals(expect.get("recoveries"), got, label + ": recoveries");
                        assertEquals(expect.get("toolRuns"), new ArrayList<>(runs), label + ": toolRuns");
                    }
                }
            }
        }
    }
}
