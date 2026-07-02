package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client observability + streaming-memory suite (§8) — mirrors the JS tests in
 * {@code js/test/unit.test.ts}: stream({id}) remembers across consumptions, ask({onText})
 * forwards deltas AND returns the RunResult, onMetric fires llm/tool/run, and metrics()
 * renders well-formed (byte-parity) Prometheus text. Hermetic: localhost mock LLMs.
 */
class ClientObservabilityTest {

    private static Toolkit bareToolkit() {
        return Toolkit.create(new Toolkit.Options().builtins(false));
    }

    // ---------------------------------------------------------------------
    // stream({ id }) is stateful: history loaded before, saved on done.
    // ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void streamWithIdRemembersAcrossConsumptions() throws IOException {
        // Mock LLM streams, as one text delta, the count of messages it received.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = Json.toMap(body);
            List<Object> messages = (List<Object>) req.get("messages");
            int count = messages == null ? 0 : messages.size();
            StringBuilder sse = new StringBuilder();
            sse.append("data: ").append(Json.stringify(Map.of(
                    "choices", List.of(Map.of("delta", Map.of("content", String.valueOf(count))))))).append("\n\n");
            sse.append("data: ").append(Json.stringify(Map.of(
                    "choices", List.of(Map.of("delta", Map.of())),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)))).append("\n\n");
            sse.append("data: [DONE]\n\n");
            respond(exchange, 200, sse.toString(), "text/event-stream");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (Toolkit tk = bareToolkit()) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("x").apiKey("k"));

            StringBuilder text1 = new StringBuilder();
            LlmClient.RunResult first = client.stream("first", tk,
                    ev -> { if (ev.type() == LlmClient.StreamEvent.Kind.TEXT) text1.append(ev.delta()); }, "c1");
            assertEquals("1", text1.toString(), "first turn: 1 message");
            assertEquals("1", first.text);

            StringBuilder text2 = new StringBuilder();
            client.stream("second", tk,
                    ev -> { if (ev.type() == LlmClient.StreamEvent.Kind.TEXT) text2.append(ev.delta()); }, "c1");
            assertEquals("3", text2.toString(), "same id remembers: user+assistant+user = 3");

            StringBuilder text3 = new StringBuilder();
            client.stream("other", tk,
                    ev -> { if (ev.type() == LlmClient.StreamEvent.Kind.TEXT) text3.append(ev.delta()); }, "c2");
            assertEquals("1", text3.toString(), "a different id is an independent conversation");
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------
    // ask({ onText }) streams deltas AND still returns the full RunResult.
    // ---------------------------------------------------------------------

    @Test
    void askWithOnTextForwardsDeltasAndReturnsRunResult() throws IOException {
        // SSE when stream:true, plain JSON otherwise.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = Json.toMap(body);
            if (Boolean.TRUE.equals(req.get("stream"))) {
                StringBuilder sse = new StringBuilder();
                sse.append("data: ").append(Json.stringify(Map.of(
                        "choices", List.of(Map.of("delta", Map.of("content", "Hel")))))).append("\n\n");
                sse.append("data: ").append(Json.stringify(Map.of(
                        "choices", List.of(Map.of("delta", Map.of("content", "lo")))))).append("\n\n");
                sse.append("data: ").append(Json.stringify(Map.of(
                        "choices", List.of(Map.of("delta", Map.of())),
                        "usage", Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5)))).append("\n\n");
                sse.append("data: [DONE]\n\n");
                respond(exchange, 200, sse.toString(), "text/event-stream");
            } else {
                Map<String, Object> data = Map.of(
                        "choices", List.of(Map.of("message", Map.of("content", "Hello"))),
                        "usage", Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5));
                respond(exchange, 200, Json.stringify(data), "application/json");
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (Toolkit tk = bareToolkit()) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("x").apiKey("k"));

            List<String> deltas = new CopyOnWriteArrayList<>();
            LlmClient.RunResult streamed = client.ask("hi", tk, null, deltas::add);
            assertEquals(List.of("Hel", "lo"), deltas, "onText receives each text delta");
            assertEquals("Hello", streamed.text, "ask still returns the full RunResult");
            assertEquals(5, streamed.usage.totalTokens);

            LlmClient.RunResult plain = client.ask("hi", tk, null);
            assertEquals("Hello", plain.text, "without onText: non-streaming path still works");
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------
    // onMetric fires llm, tool, and a final aggregated run event.
    // ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void onMetricFiresLlmToolAndRun() throws IOException {
        // first request (no tool result yet) → a tool call; second → final text.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = Json.toMap(body);
            List<Object> messages = (List<Object>) req.get("messages");
            boolean hasToolResult = messages != null && messages.stream()
                    .anyMatch(m -> m instanceof Map && "tool".equals(((Map<?, ?>) m).get("role")));
            Map<String, Object> data;
            if (!hasToolResult) {
                Map<String, Object> fn = Map.of("name", "add", "arguments", Json.stringify(Map.of("a", 2, "b", 3)));
                Map<String, Object> call = Map.of("id", "c1", "type", "function", "function", fn);
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("content", null);
                message.put("tool_calls", List.of(call));
                data = Map.of("choices", List.of(Map.of("message", message)),
                        "usage", Map.of("prompt_tokens", 5, "completion_tokens", 4, "total_tokens", 9));
            } else {
                data = Map.of("choices", List.of(Map.of("message", Map.of("content", "5"))),
                        "usage", Map.of("prompt_tokens", 6, "completion_tokens", 1, "total_tokens", 7));
            }
            respond(exchange, 200, Json.stringify(data), "application/json");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (Toolkit tk = bareToolkit()) {
            tk.register(NativeTool.of("add", "add",
                    Map.of("type", "object",
                            "properties", Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number")),
                            "required", List.of("a", "b")),
                    args -> "5"));
            List<LlmClient.MetricEvent> events = new CopyOnWriteArrayList<>();
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("gpt-x").apiKey("k").onMetric(events::add));
            client.run("add them", tk);

            LlmClient.MetricEvent.Llm llm = events.stream()
                    .filter(e -> e instanceof LlmClient.MetricEvent.Llm).map(e -> (LlmClient.MetricEvent.Llm) e)
                    .findFirst().orElse(null);
            assertNotNull(llm, "an llm event fired");
            assertEquals("gpt-x", llm.model());
            assertEquals("ok", llm.status());

            LlmClient.MetricEvent.Tool tool = events.stream()
                    .filter(e -> e instanceof LlmClient.MetricEvent.Tool).map(e -> (LlmClient.MetricEvent.Tool) e)
                    .findFirst().orElse(null);
            assertNotNull(tool, "a tool event fired");
            assertEquals("add", tool.tool());
            assertEquals("native", tool.source());
            assertFalse(tool.isError());

            LlmClient.MetricEvent last = events.get(events.size() - 1);
            assertTrue(last instanceof LlmClient.MetricEvent.Run, "run event is last");
            LlmClient.MetricEvent.Run run = (LlmClient.MetricEvent.Run) last;
            assertEquals("gpt-x", run.model());
            assertEquals(1, run.toolCalls(), "one tool call aggregated");
            assertEquals(2, run.turns(), "two LLM round trips");
            assertEquals(16, run.totalTokens(), "9 + 7 summed");
            assertEquals("run", run.event());
            long llmCount = events.stream().filter(e -> e instanceof LlmClient.MetricEvent.Llm).count();
            assertEquals(2, llmCount, "one llm event per call");
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------
    // metrics() renders well-formed, byte-parity Prometheus text after a run.
    // ---------------------------------------------------------------------

    @Test
    void metricsRendersPrometheusText() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, Object> data = Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", "ok"))),
                    "usage", Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5));
            respond(exchange, 200, Json.stringify(data), "application/json");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (Toolkit tk = bareToolkit()) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("gpt-x").apiKey("k"));

            String before = client.metrics();
            assertTrue(before.contains("# TYPE toolnexus_llm_requests_total counter"),
                    "empty-but-valid before activity");
            assertTrue(before.endsWith("\n"), "trailing newline");

            client.run("hi", tk);
            String text = client.metrics();

            // counters present with labels (exact bytes)
            assertTrue(text.contains("toolnexus_llm_requests_total{model=\"gpt-x\",status=\"ok\"} 1"), text);
            assertTrue(text.contains("toolnexus_llm_tokens_total{type=\"prompt\"} 3"), text);
            assertTrue(text.contains("toolnexus_llm_tokens_total{type=\"completion\"} 2"), text);
            // histogram HELP/TYPE + bucket le formatting (no trailing .0)
            assertTrue(text.contains("# TYPE toolnexus_llm_request_duration_seconds histogram"), text);
            assertTrue(text.contains("toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"0.05\"} "), text);
            assertTrue(text.contains("toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"0.1\"} "), text);
            assertTrue(text.contains("toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"2.5\"} "), text);
            assertTrue(text.contains("toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"1\"} "),
                    "integer bucket rendered as 1, not 1.0");
            assertFalse(text.contains("le=\"1.0\""), "no Java-style trailing .0 on bucket labels");
            assertFalse(text.contains("le=\"60.0\""), "no Java-style trailing .0 on bucket labels");
            assertTrue(text.contains("toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"+Inf\"} "), text);
            assertTrue(text.contains("toolnexus_llm_request_duration_seconds_count{model=\"gpt-x\"} 1"), text);
            assertTrue(text.endsWith("\n"), "trailing newline");

            // every non-empty line is a comment or a well-formed metric sample
            for (String line : text.split("\n")) {
                if (line.isEmpty()) continue;
                boolean ok = line.startsWith("#")
                        || line.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\{[^}]*\\})? -?[0-9.eE+]+$");
                assertTrue(ok, "well-formed line: " + line);
            }
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
