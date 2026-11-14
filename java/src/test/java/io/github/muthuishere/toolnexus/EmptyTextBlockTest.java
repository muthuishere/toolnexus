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
 * Issue #235 / SPEC §8.x: an empty anthropic text block beside tool_use must never be stored in
 * the assistant message, or the next request is rejected with a 400.
 */
@SuppressWarnings("unchecked")
class EmptyTextBlockTest {

    static String data(Object o) { return "data: " + Json.stringify(o) + "\n\n"; }

    static String turn1(boolean stream) {
        if (!stream) {
            return Json.stringify(Map.of("content", List.of(
                    Map.of("type", "text", "text", ""),
                    Map.of("type", "tool_use", "id", "toolu_0", "name", "echo", "input", Map.of())),
                    "stop_reason", "tool_use"));
        }
        return data(Map.of("type", "content_block_start", "index", 0, "content_block", Map.of("type", "text", "text", "")))
                + data(Map.of("type", "content_block_stop", "index", 0))
                + data(Map.of("type", "content_block_start", "index", 1,
                        "content_block", Map.of("type", "tool_use", "id", "toolu_0", "name", "echo", "input", Map.of())))
                + data(Map.of("type", "content_block_delta", "index", 1, "delta", Map.of("type", "input_json_delta", "partial_json", "{}")))
                + data(Map.of("type", "content_block_stop", "index", 1))
                + data(Map.of("type", "message_delta", "delta", Map.of("stop_reason", "tool_use")))
                + "data: {\"type\":\"message_stop\"}\n\n";
    }

    static String turn2(boolean stream) {
        if (!stream) {
            return Json.stringify(Map.of("content", List.of(Map.of("type", "text", "text", "done")), "stop_reason", "end_turn"));
        }
        return data(Map.of("type", "content_block_start", "index", 0, "content_block", Map.of("type", "text", "text", "")))
                + data(Map.of("type", "content_block_delta", "index", 0, "delta", Map.of("type", "text_delta", "text", "done")))
                + data(Map.of("type", "content_block_stop", "index", 0))
                + data(Map.of("type", "message_delta", "delta", Map.of("stop_reason", "end_turn")))
                + "data: {\"type\":\"message_stop\"}\n\n";
    }

    @Test
    void anthropicEmptyTextBlockIsNotStored() throws Exception {
        for (boolean stream : List.of(false, true)) {
            String label = stream ? "stream" : "run";
            List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                Map<String, Object> body = Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                bodies.add(body);
                String out = bodies.size() == 1 ? turn1(stream) : turn2(stream);
                ex.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                byte[] b = out.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            });
            server.start();
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                tk.register(new Tool() {
                    @Override public String name() { return "echo"; }
                    @Override public String description() { return "echo"; }
                    @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
                    @Override public String source() { return "custom"; }
                    @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) { return ToolResult.ok("ok"); }
                });
                LlmClient client = LlmClient.create(new LlmClient.Options()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .style("anthropic").model("m").apiKey("k").retries(0));
                LlmClient.RunResult r = stream ? client.stream("go", tk, ev -> {}) : client.run("go", tk);
                assertEquals("done", r.text, label);
                assertEquals(2, bodies.size(), label);
                for (Object mo : (List<Object>) bodies.get(1).get("messages")) {
                    Map<String, Object> m = (Map<String, Object>) mo;
                    if (!(m.get("content") instanceof List<?> blocks)) continue;
                    for (Object bo : blocks) {
                        Map<String, Object> blk = (Map<String, Object>) bo;
                        assertFalse("text".equals(blk.get("type")) && "".equals(blk.get("text")),
                                label + ": empty text block sent: " + m);
                    }
                }
            } finally {
                server.stop(0);
            }
        }
    }
}
