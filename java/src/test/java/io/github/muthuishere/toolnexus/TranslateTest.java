package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single-turn translation suite (SPEC.md §11, ADR-0011). Ports {@code
 * golang/translate_test.go} — Go's assertions are the cross-port oracle. Hermetic: a
 * localhost {@code HttpServer} stands in for the provider and records what it was sent.
 */
class TranslateTest {

    /** A provider stand-in that records the request body and replies with a canned response. */
    private static final class Upstream implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<Map<String, Object>> body = new AtomicReference<>();

        Upstream(Map<String, Object> reply) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body.set(Json.toMap(raw));
                respond(exchange, 200, Json.stringify(reply), "application/json");
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        Map<String, Object> sent() {
            return body.get();
        }

        String sentJson() {
            return Json.stringify(body.get());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int code, String payload, String type) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static LlmClient client(String base, String style) {
        return LlmClient.create(new LlmClient.Options()
                .baseUrl(base).style(style).model("stub").apiKey("k"));
    }

    /** The OpenAI {@code tools} array a client sends, verbatim. */
    private static List<Object> openAITools() {
        return List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_weather",
                        "description", "Get the weather",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of("city", Map.of("type", "string")),
                                "required", List.of("city")))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocksOf(Map<String, Object> sent) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object msgs = sent.get("messages");
        if (!(msgs instanceof List<?> list)) return out;
        for (Object m : list) {
            if (!(m instanceof Map<?, ?> mm)) continue;
            if (mm.get("content") instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm) out.add((Map<String, Object>) bm);
                }
            }
        }
        return out;
    }

    // ---- Anthropic upstream: the real translation ----

    @Test
    void toolUseComesBackAsAnOpenAiToolCall() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "tool_use", "id", "toolu_1",
                        "name", "get_weather", "input", Map.of("city", "Chennai"))),
                "stop_reason", "tool_use",
                "usage", Map.of("input_tokens", 10, "output_tokens", 5));
        try (Upstream up = new Upstream(reply)) {
            Translate.Result res = client(up.base(), "anthropic").translate(new Translate.Request()
                    .messages(List.of(Map.of("role", "user", "content", "weather in Chennai?")))
                    .tools(openAITools()));

            assertEquals("tool_calls", res.finishReason);
            assertEquals(1, res.toolCalls.size());
            assertEquals("toolu_1", res.toolCalls.get(0).id());
            assertEquals("get_weather", res.toolCalls.get(0).name());
            // arguments must be a JSON STRING (the OpenAI wire shape), not an object
            Map<String, Object> parsed = Json.toMap(res.toolCalls.get(0).arguments());
            assertEquals("Chennai", parsed.get("city"));
            assertTrue(res.usage.totalTokens > 0, "usage not reported");

            String sent = up.sentJson();
            assertTrue(sent.contains("input_schema"), sent);
            assertTrue(sent.contains("get_weather"), sent);
            assertFalse(sent.contains("\"parameters\""), "OpenAI-shaped 'parameters' leaked upstream: " + sent);
        }
    }

    /** The case a text-flattening translator gets wrong. */
    @Test
    void multiTurnToolExchangeSurvives() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "text", "text", "It is 31C in Chennai.")),
                "stop_reason", "end_turn",
                "usage", Map.of("input_tokens", 20, "output_tokens", 8));
        try (Upstream up = new Upstream(reply)) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("tool_calls", List.of(Map.of(
                    "id", "call_abc", "type", "function",
                    "function", Map.of("name", "get_weather", "arguments", "{\"city\":\"Chennai\"}"))));

            Translate.Result res = client(up.base(), "anthropic").translate(new Translate.Request()
                    .tools(openAITools())
                    .messages(List.of(
                            Map.of("role", "system", "content", "Be terse."),
                            Map.of("role", "user", "content", "weather in Chennai?"),
                            assistant,
                            Map.of("role", "tool", "tool_call_id", "call_abc", "content", "31C, clear"))));

            assertEquals("stop", res.finishReason);
            assertEquals("It is 31C in Chennai.", res.text);
            assertEquals("Be terse.", up.sent().get("system"), "system not hoisted out of messages");

            String sent = up.sentJson();
            for (String want : List.of("tool_use", "tool_result", "call_abc", "31C, clear")) {
                assertTrue(sent.contains(want), "multi-turn structure lost " + want + ": " + sent);
            }
            // the tool_use's input is an OBJECT upstream, re-parsed from the JSON string
            Map<String, Object> use = blocksOf(up.sent()).stream()
                    .filter(b -> "tool_use".equals(b.get("type"))).findFirst().orElse(null);
            assertNotNull(use, "no tool_use block reached the provider");
            assertEquals("Chennai", ((Map<?, ?>) use.get("input")).get("city"),
                    "tool_use input not re-parsed to an object");
        }
    }

    @Test
    void threeConsecutiveToolResultsMergeIntoOneUserTurn() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "text", "text", "done")), "stop_reason", "end_turn");
        try (Upstream up = new Upstream(reply)) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("tool_calls", List.of(
                    Map.of("id", "a", "function", Map.of("name", "f", "arguments", "{}")),
                    Map.of("id", "b", "function", Map.of("name", "f", "arguments", "{}")),
                    Map.of("id", "c", "function", Map.of("name", "f", "arguments", "{}"))));

            client(up.base(), "anthropic").translate(new Translate.Request().messages(List.of(
                    Map.of("role", "user", "content", "do three things"),
                    assistant,
                    Map.of("role", "tool", "tool_call_id", "a", "content", "ra"),
                    Map.of("role", "tool", "tool_call_id", "b", "content", "rb"),
                    Map.of("role", "tool", "tool_call_id", "c", "content", "rc"))));

            // exactly ONE user turn carries all three tool_result blocks
            int resultTurns = 0;
            int resultsInTurn = 0;
            Object msgs = up.sent().get("messages");
            for (Object m : (List<?>) msgs) {
                if (!(m instanceof Map<?, ?> mm) || !(mm.get("content") instanceof List<?> blocks)) continue;
                int n = 0;
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm && "tool_result".equals(bm.get("type"))) n++;
                }
                if (n > 0) {
                    resultTurns++;
                    resultsInTurn = n;
                }
            }
            assertEquals(1, resultTurns, "tool results spread over more than one user turn");
            assertEquals(3, resultsInTurn, "merged turn does not carry all three results");

            long uses = blocksOf(up.sent()).stream().filter(b -> "tool_use".equals(b.get("type"))).count();
            assertEquals(3, uses, "want 3 tool_use blocks upstream");
        }
    }

    @Test
    void parallelToolCallsAllReturnedInProviderOrder() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(
                        Map.of("type", "text", "text", "calling three"),
                        Map.of("type", "tool_use", "id", "t1", "name", "alpha", "input", Map.of("n", 1)),
                        Map.of("type", "tool_use", "id", "t2", "name", "beta", "input", Map.of("n", 2)),
                        Map.of("type", "tool_use", "id", "t3", "name", "gamma", "input", Map.of("n", 3))),
                "stop_reason", "tool_use");
        try (Upstream up = new Upstream(reply)) {
            Translate.Result res = client(up.base(), "anthropic").translate(new Translate.Request()
                    .messages(List.of(Map.of("role", "user", "content", "go")))
                    .tools(openAITools()));
            assertEquals(3, res.toolCalls.size());
            assertEquals(List.of("alpha", "beta", "gamma"),
                    res.toolCalls.stream().map(Translate.ToolCall::name).toList());
            assertEquals("calling three", res.text, "text alongside tool calls was lost");
            assertEquals("tool_calls", res.finishReason);
            assertEquals(3, res.toolCallsJson().size());
        }
    }

    @Test
    void executesNothingAndKeepsNoState() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "tool_use", "id", "t1", "name", "danger", "input", Map.of())),
                "stop_reason", "tool_use");
        AtomicInteger ran = new AtomicInteger();
        try (Upstream up = new Upstream(reply)) {
            Tool danger = NativeTool.of("danger", "must not run",
                    Map.of("type", "object", "properties", Map.of()),
                    args -> {
                        ran.incrementAndGet();
                        return "RAN";
                    });
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(List.of(danger)))) {
                LlmClient c = client(up.base(), "anthropic");
                for (int i = 0; i < 3; i++) {
                    Translate.Result res = c.translate(new Translate.Request()
                            .messages(List.of(Map.of("role", "user", "content", "go")))
                            .toolkit(tk));
                    assertEquals(1, res.toolCalls.size());
                    assertEquals("danger", res.toolCalls.get(0).name());
                }
                assertEquals(0, ran.get(), "translate EXECUTED a tool — it must never execute anything");
                // no history accumulated between the three independent calls
                assertEquals(1, ((List<?>) up.sent().get("messages")).size(),
                        "state leaked between translate calls");
            }
        }
    }

    /** The generality case: a real toolkit works, not only OpenAI JSON. */
    @Test
    void toolkitIsDeclaredButNeverExecuted() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "tool_use", "id", "tu_9",
                        "name", "my_native_tool", "input", Map.of("x", 1))),
                "stop_reason", "tool_use");
        AtomicInteger ran = new AtomicInteger();
        try (Upstream up = new Upstream(reply)) {
            Tool t = NativeTool.of("my_native_tool", "an ordinary executable tool",
                    Map.of("type", "object", "properties", Map.of("x", Map.of("type", "number"))),
                    args -> {
                        ran.incrementAndGet();
                        return "SHOULD NOT RUN";
                    });
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(List.of(t)))) {
                Translate.Result res = client(up.base(), "anthropic").translate(new Translate.Request()
                        .messages(List.of(Map.of("role", "user", "content", "use the tool")))
                        .toolkit(tk));
                assertEquals(0, ran.get(), "translate executed a toolkit tool");
                assertEquals("my_native_tool", res.toolCalls.get(0).name());
                assertEquals("tu_9", res.toolCalls.get(0).id());
                String sent = up.sentJson();
                assertTrue(sent.contains("input_schema"), sent);
                assertTrue(sent.contains("my_native_tool"), sent);
            }
        }
    }

    @Test
    void toolkitAndOpenAiToolsCompose() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "text", "text", "ok")), "stop_reason", "end_turn");
        try (Upstream up = new Upstream(reply)) {
            Tool t = NativeTool.of("server_side_tool", "gateway's own",
                    Map.of("type", "object", "properties", Map.of()), args -> "x");
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(List.of(t)))) {
                client(up.base(), "anthropic").translate(new Translate.Request()
                        .messages(List.of(Map.of("role", "user", "content", "go")))
                        .toolkit(tk)
                        .tools(openAITools()));
                String sent = up.sentJson();
                for (String want : List.of("server_side_tool", "get_weather")) {
                    assertTrue(sent.contains(want), "composed declaration missing " + want);
                }
            }
        }
    }

    @Test
    void toolChoiceMapping() throws IOException {
        record Case(Object in, String want) {}
        List<Case> cases = List.of(
                new Case(null, null),
                new Case("auto", null),
                new Case("required", "\"type\":\"any\""),
                new Case("none", "\"type\":\"none\""),
                new Case(Map.of("type", "function", "function", Map.of("name", "get_weather")),
                        "\"name\":\"get_weather\""));
        for (Case tc : cases) {
            Map<String, Object> reply = Map.of(
                    "content", List.of(Map.of("type", "text", "text", "ok")), "stop_reason", "end_turn");
            try (Upstream up = new Upstream(reply)) {
                client(up.base(), "anthropic").translate(new Translate.Request()
                        .messages(List.of(Map.of("role", "user", "content", "go")))
                        .tools(openAITools())
                        .toolChoice(tc.in()));
                Object present = up.sent().get("tool_choice");
                if (tc.want() == null) {
                    assertTrue(present == null, "tool_choice " + tc.in() + " should be omitted");
                } else {
                    assertNotNull(present, "tool_choice " + tc.in() + " missing");
                    String rendered = Json.stringify(present).replace(" ", "");
                    assertTrue(rendered.contains(tc.want()),
                            "tool_choice did not map to " + tc.want() + ": " + rendered);
                }
            }
        }
    }

    @Test
    void finishReasonMapping() throws IOException {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("end_turn", "stop");
        cases.put("max_tokens", "length");
        cases.put("refusal", "content_filter");
        cases.put("stop_sequence", "stop");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            Map<String, Object> reply = Map.of(
                    "content", List.of(Map.of("type", "text", "text", "x")), "stop_reason", e.getKey());
            try (Upstream up = new Upstream(reply)) {
                Translate.Result res = client(up.base(), "anthropic").translate(new Translate.Request()
                        .messages(List.of(Map.of("role", "user", "content", "go"))));
                assertEquals(e.getValue(), res.finishReason, "stop_reason " + e.getKey());
            }
        }
    }

    /** Some clients send {@code arguments} as an object rather than a JSON string. */
    @Test
    void argumentsAcceptedAsAnObjectToo() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "text", "text", "ok")), "stop_reason", "end_turn");
        try (Upstream up = new Upstream(reply)) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("tool_calls", List.of(Map.of("id", "z",
                    "function", Map.of("name", "f", "arguments", Map.of("city", "Madurai")))));

            client(up.base(), "anthropic").translate(new Translate.Request().messages(List.of(
                    Map.of("role", "user", "content", "go"),
                    assistant,
                    Map.of("role", "tool", "tool_call_id", "z", "content", "done"))));

            Map<String, Object> use = blocksOf(up.sent()).stream()
                    .filter(b -> "tool_use".equals(b.get("type"))).findFirst().orElse(null);
            assertNotNull(use, "no tool_use block upstream");
            assertEquals("Madurai", ((Map<?, ?>) use.get("input")).get("city"),
                    "object-form arguments were not carried through");
        }
    }

    @Test
    void contentPartsAreFlattened() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "text", "text", "ok")), "stop_reason", "end_turn");
        try (Upstream up = new Upstream(reply)) {
            client(up.base(), "anthropic").translate(new Translate.Request().messages(List.of(
                    Map.of("role", "user", "content", List.of(
                            Map.of("type", "text", "text", "part one "),
                            Map.of("type", "text", "text", "part two"))))));
            assertTrue(up.sentJson().contains("part one part two"), up.sentJson());
        }
    }

    @Test
    void llmHooksFireOnceAndNoToolHookFires() throws IOException {
        Map<String, Object> reply = Map.of(
                "content", List.of(Map.of("type", "tool_use", "id", "t1", "name", "get_weather", "input", Map.of())),
                "stop_reason", "tool_use");
        try (Upstream up = new Upstream(reply)) {
            AtomicInteger before = new AtomicInteger();
            AtomicInteger after = new AtomicInteger();
            AtomicInteger toolHooks = new AtomicInteger();
            LlmClient.Hooks hooks = new LlmClient.Hooks();
            hooks.beforeLLM = ev -> {
                before.incrementAndGet();
                return null;
            };
            hooks.afterLLM = ev -> after.incrementAndGet();
            hooks.beforeTool = ev -> {
                toolHooks.incrementAndGet();
                return null;
            };
            hooks.afterTool = ev -> {
                toolHooks.incrementAndGet();
                return null;
            };
            LlmClient c = LlmClient.create(new LlmClient.Options()
                    .baseUrl(up.base()).style("anthropic").model("stub").apiKey("k").hooks(hooks));
            c.translate(new Translate.Request()
                    .messages(List.of(Map.of("role", "user", "content", "go")))
                    .tools(openAITools()));
            assertEquals(1, before.get(), "beforeLLM did not fire exactly once");
            assertEquals(1, after.get(), "afterLLM did not fire exactly once");
            assertEquals(0, toolHooks.get(), "a tool hook fired, but no tool runs in translate");
        }
    }

    // ---- OpenAI upstream: near-passthrough ----

    @Test
    void openAiUpstreamPassesToolsAndArgumentsThrough() throws IOException {
        Map<String, Object> reply = Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("content", "", "tool_calls", List.of(Map.of(
                                "id", "call_1", "type", "function",
                                "function", Map.of("name", "get_weather",
                                        "arguments", "{\"city\":\"Madurai\"}")))),
                        "finish_reason", "tool_calls")),
                "usage", Map.of("prompt_tokens", 3, "completion_tokens", 4, "total_tokens", 7));
        try (Upstream up = new Upstream(reply)) {
            Translate.Result res = client(up.base(), "openai").translate(new Translate.Request()
                    .messages(List.of(Map.of("role", "user", "content", "weather?")))
                    .tools(openAITools()));
            assertEquals("tool_calls", res.finishReason);
            assertEquals("{\"city\":\"Madurai\"}", res.toolCalls.get(0).arguments(),
                    "arguments not byte-for-byte");
            assertEquals(7, res.usage.totalTokens);
            assertTrue(up.sentJson().contains("\"parameters\""),
                    "OpenAI tools were altered on an OpenAI upstream");
        }
    }
}
