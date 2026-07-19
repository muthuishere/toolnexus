package io.github.muthuishere.toolnexus.agents;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted mock LLM for the §7D conformance tests — the shared {@code examples/subagent-*}
 * fixtures' {@code mockLLM} scripts, keyed by the request body's {@code model} (openai style).
 * Zero network beyond localhost, zero cost.
 */
final class MockLlm implements AutoCloseable {

    private final HttpServer server;
    final String base;
    /** The {@code m-slow} model blocks on this latch until the scenario releases it. */
    final AtomicReference<CountDownLatch> slowGate = new AtomicReference<>();
    /** The {@code m-heartbeat-gated} model blocks its turn on this latch (coalesce probe). */
    final AtomicReference<CountDownLatch> heartbeatGate = new AtomicReference<>();

    /** Arm the heartbeat gate: the next {@code m-heartbeat-gated} turn blocks until the returned
     * latch is counted down. */
    CountDownLatch gateHeartbeat() {
        CountDownLatch g = new CountDownLatch(1);
        heartbeatGate.set(g);
        return g;
    }

    @SuppressWarnings("unchecked")
    MockLlm() {
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
            List<Object> msgs = req.get("messages") instanceof List<?> l ? (List<Object>) l : List.of();
            List<String> toolMsgs = new ArrayList<>();
            String sys = "";
            for (Object o : msgs) {
                if (o instanceof Map<?, ?> m) {
                    if ("tool".equals(m.get("role"))) toolMsgs.add(String.valueOf(m.get("content")));
                    if ("system".equals(m.get("role"))) sys = String.valueOf(m.get("content"));
                }
            }
            Map<String, Object> message;
            switch (model) {
                case "m-coordinator" -> {
                    if (toolMsgs.isEmpty()) {
                        message = calls(
                                call("c1", "task", Map.of("agent", "explore", "prompt", "find A")),
                                call("c2", "task", Map.of("agent", "explore", "prompt", "find B")));
                    } else {
                        message = text("synthesis: " + String.join(" + ", toolMsgs));
                    }
                }
                case "m-explore" -> {
                    if (toolMsgs.isEmpty()) message = calls(call("e1", "lookup", Map.of("q", "x")));
                    else message = text("found:" + toolMsgs.get(0));
                }
                case "m-approver-parent" -> {
                    if (toolMsgs.isEmpty()) {
                        message = calls(call("p1", "task", Map.of("agent", "asker", "prompt", "get the secret")));
                    } else {
                        message = text("parent-final: " + toolMsgs.get(toolMsgs.size() - 1));
                    }
                }
                case "m-asker" -> {
                    boolean approved = toolMsgs.stream().anyMatch(c -> c.contains("secret-token"));
                    if (!approved) message = calls(call("a1", "check_secret", Map.of()));
                    else message = text("asker-done: secret-token");
                }
                case "m-peer" -> {
                    String last = msgs.isEmpty() ? "" :
                            String.valueOf(((Map<String, Object>) msgs.get(msgs.size() - 1)).get("content"));
                    Matcher m = Pattern.compile("^\\d+\\.", Pattern.MULTILINE).matcher(last);
                    int items = 0;
                    while (m.find()) items++;
                    message = text("processed " + items + " items");
                }
                case "m-loop" -> // never finishes: always another tool call → maxTurns/incomplete
                        message = calls(call("l" + toolMsgs.size(), "lookup", Map.of("q", "again")));
                case "m-slow" -> {
                    CountDownLatch gate = slowGate.get();
                    try {
                        if (gate != null) gate.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    message = text("slow-done");
                }
                case "m-coder" -> {
                    if (toolMsgs.isEmpty()) {
                        message = calls(call("t1", "task", Map.of("agent", "explore", "prompt", "find the bug")));
                    } else {
                        message = text("fixed using: " + toolMsgs.get(0) + " [soul:"
                                + (sys.contains("You are the CODER") ? "loaded" : "missing") + "]");
                    }
                }
                case "m-explore-bug" -> {
                    if (toolMsgs.isEmpty()) message = calls(call("e1", "lookup", Map.of("q", "bug")));
                    else message = text("bug at line 42 (" + toolMsgs.get(0) + ")");
                }
                case "m-echo-soul" -> {
                    // reports which bootstrap sections it can see in the composed soul
                    List<String> present = new ArrayList<>();
                    for (String f : Agents.BOOTSTRAP_ORDER) {
                        if (sys.contains("## " + f)) present.add(f);
                    }
                    message = text("sections:[" + String.join(",", present) + "]");
                }
                case "m-remember" -> {
                    // first turn: write a memory; then confirm what the tool returned
                    if (toolMsgs.isEmpty()) {
                        message = calls(call("w1", "memory",
                                Map.of("action", "add", "target", "user", "text", "Prefers dark roast")));
                    } else {
                        message = text("saved: " + toolMsgs.get(0));
                    }
                }
                case "m-recall" -> // proves the next-session snapshot carries prior USER.md content
                        message = text(sys.contains("Prefers dark roast") ? "I recall: dark roast" : "no memory");
                case "m-heartbeat" -> {
                    // speaks only when the HEARTBEAT.md rule fires on a heartbeat wake
                    String last = msgs.isEmpty() ? "" :
                            String.valueOf(((Map<String, Object>) msgs.get(msgs.size() - 1)).get("content"));
                    message = text(sys.contains("remind about the 3pm sync") && last.contains("Heartbeat")
                            ? "Reminder: 3pm sync 🔔" : Agents.HEARTBEAT_OK);
                }
                case "m-heartbeat-gated" -> {
                    // a due heartbeat whose turn blocks on the gate — the coalesce probe
                    CountDownLatch g = heartbeatGate.get();
                    try {
                        if (g != null) g.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    message = text("Reminder: 3pm sync 🔔");
                }
                case "m-old-api" -> {
                    if (toolMsgs.isEmpty()) message = calls(call("b1", "explore", Map.of("prompt", "scan the repo")));
                    else message = text("old-api summary: " + toolMsgs.get(0));
                }
                default -> message = text("ok");
            }
            respond(exchange, message);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        CountDownLatch gate = slowGate.get();
        if (gate != null) gate.countDown();
        CountDownLatch hb = heartbeatGate.get();
        if (hb != null) hb.countDown();
        server.stop(0);
    }

    private static Map<String, Object> text(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> call(String id, String name, Map<String, Object> args) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("arguments", Json.stringify(args));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("type", "function");
        c.put("function", fn);
        return c;
    }

    @SafeVarargs
    private static Map<String, Object> calls(Map<String, Object>... callObjs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", null);
        m.put("tool_calls", List.of(callObjs));
        return m;
    }

    private static void respond(HttpExchange exchange, Map<String, Object> message) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("choices", List.of(Map.of("message", message)));
        // The shared fixtures' usagePerCall: 30 prompt + 10 completion = 40 total per LLM call.
        payload.put("usage", Map.of("prompt_tokens", 30, "completion_tokens", 10, "total_tokens", 40));
        byte[] out = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
    }
}
