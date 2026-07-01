package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conversation memory suite (§8 "Conversation memory") — mirrors the three JS tests
 * (ask-remembers-by-id, custom ConversationStore get/save, a2a serve contextId memory)
 * in {@code js/test/unit.test.ts}. Hermetic: a localhost mock LLM whose reply is the
 * number of messages it received, so the counts prove history is loaded/saved.
 */
class ConversationMemoryTest {

    private HttpServer llm;
    private String llmBase;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Mock LLM: OpenAI-style reply whose content is the count of messages it received. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void startMockLlm() throws IOException {
        llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llm.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> req = Json.toMap(body);
                List<Object> messages = (List<Object>) req.get("messages");
                int count = messages == null ? 0 : messages.size();

                Map<String, Object> message = new LinkedHashMap<>();
                message.put("content", String.valueOf(count));
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("message", message);
                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("prompt_tokens", 1);
                usage.put("completion_tokens", 1);
                usage.put("total_tokens", 2);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("choices", List.of(choice));
                data.put("usage", usage);
                respond(exchange, 200, Json.stringify(data));
            } catch (Exception e) {
                try {
                    respond(exchange, 500, "err");
                } catch (Exception ignored) {
                }
            }
        });
        llm.start();
        llmBase = "http://127.0.0.1:" + llm.getAddress().getPort();
    }

    @AfterEach
    void stopMockLlm() {
        if (llm != null) llm.stop(0);
    }

    private LlmClient client() {
        return LlmClient.create(new LlmClient.Options()
                .baseUrl(llmBase).style("openai").model("x").apiKey("k"));
    }

    private LlmClient client(LlmClient.ConversationStore store) {
        return LlmClient.create(new LlmClient.Options()
                .baseUrl(llmBase).style("openai").model("x").apiKey("k").store(store));
    }

    /** No skills + no builtins ⇒ no system-prompt message, so counts are exactly the turns. */
    private Toolkit bareToolkit() {
        return Toolkit.create(new Toolkit.Options().builtins(false));
    }

    // ---------------------------------------------------------------------
    // ask() remembers by id via the (default in-memory) conversation store.
    // ---------------------------------------------------------------------

    @Test
    void askRemembersById() {
        try (Toolkit tk = bareToolkit()) {
            LlmClient client = client();

            LlmClient.RunResult solo = client.ask("hi", tk, null);
            assertEquals("1", solo.text, "no id ⇒ stateless one-shot (just the user turn)");

            LlmClient.RunResult a = client.ask("first", tk, "c1");
            assertEquals("1", a.text, "first turn: 1 message");
            LlmClient.RunResult b = client.ask("second", tk, "c1");
            assertEquals("3", b.text, "same id remembers: user+assistant+user = 3");

            LlmClient.RunResult c = client.ask("other", tk, "c2");
            assertEquals("1", c.text, "a different id is an independent conversation");
        }
    }

    // ---------------------------------------------------------------------
    // A custom ConversationStore is used (get then save).
    // ---------------------------------------------------------------------

    @Test
    void customConversationStoreIsUsed() {
        List<String> calls = new CopyOnWriteArrayList<>();
        Map<String, List<Object>> backing = new ConcurrentHashMap<>();
        LlmClient.ConversationStore store = new LlmClient.ConversationStore() {
            @Override
            public List<Object> get(String id) {
                calls.add("get:" + id);
                return backing.get(id);
            }

            @Override
            public void save(String id, List<Object> messages) {
                calls.add("save:" + id);
                backing.put(id, messages);
            }
        };
        try (Toolkit tk = bareToolkit()) {
            LlmClient client = client(store);
            client.ask("hi", tk, "u1");
            assertEquals(List.of("get:u1", "save:u1"), new ArrayList<>(calls), "custom store: get then save");
            assertTrue(backing.containsKey("u1"), "custom store persisted the transcript");
        }
    }

    // ---------------------------------------------------------------------
    // a2a serve remembers a peer's turns by contextId (ask + store).
    // ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void a2aServeRemembersByContextId() throws Exception {
        try (Toolkit tk = bareToolkit()) {
            A2AServer.A2AConfig a2a = new A2AServer.A2AConfig().name("mem-desk");
            A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().client(client()).a2a(a2a));
            try {
                Map<String, Object> t1 = send(handle.url() + "/", "first", "ctxA");
                assertEquals("1", artifactText(t1), "first served turn: 1 message");
                Map<String, Object> t2 = send(handle.url() + "/", "second", "ctxA");
                assertEquals("3", artifactText(t2), "same contextId remembers: user+assistant+user = 3");
                Map<String, Object> t3 = send(handle.url() + "/", "other", "ctxB");
                assertEquals("1", artifactText(t3), "a different contextId is independent");
            } finally {
                handle.close();
            }
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** SendMessage with a contextId, then poll GetTask until terminal; returns the terminal task. */
    private static Map<String, Object> send(String endpoint, String text, String contextId) throws Exception {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("kind", "text");
        part.put("text", text);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("contextId", contextId);
        message.put("parts", List.of(part));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);

        Map<String, Object> submitted = rpc(endpoint, "SendMessage", params);
        String id = String.valueOf(submitted.get("id"));
        return pollUntilTerminal(endpoint, id);
    }

    @SuppressWarnings("unchecked")
    private static String artifactText(Map<String, Object> task) {
        List<Object> artifacts = (List<Object>) task.get("artifacts");
        assertNotNull(artifacts, "task has artifacts: " + task);
        Map<String, Object> artifact = (Map<String, Object>) artifacts.get(0);
        List<Object> parts = (List<Object>) artifact.get("parts");
        Map<String, Object> firstPart = (Map<String, Object>) parts.get(0);
        return String.valueOf(firstPart.get("text"));
    }

    @SuppressWarnings("unchecked")
    private static String state(Map<String, Object> task) {
        Map<String, Object> status = (Map<String, Object>) task.get("status");
        return status == null ? null : String.valueOf(status.get("state"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rpc(String endpoint, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", "test-id");
        req.put("method", method);
        req.put("params", params);
        HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(req)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "JSON-RPC HTTP 200");
        Map<String, Object> env = Json.toMap(res.body());
        return (Map<String, Object>) env.get("result");
    }

    private static Map<String, Object> pollUntilTerminal(String endpoint, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        Map<String, Object> task = null;
        while (System.currentTimeMillis() < deadline) {
            task = rpc(endpoint, "GetTask", Map.of("id", id));
            String st = state(task);
            if ("completed".equals(st) || "failed".equals(st) || "canceled".equals(st)) return task;
            Thread.sleep(25);
        }
        return task;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
