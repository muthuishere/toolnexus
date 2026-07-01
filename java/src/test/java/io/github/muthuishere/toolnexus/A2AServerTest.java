package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inbound A2A "serve" suite — mirrors the JS {@code serve.ts} reference. Uses a
 * real ephemeral {@link HttpServer} as the mock LLM (returns a canned OpenAI-style
 * completion) and drives the served agent both via the OUTBOUND {@link A2A} client
 * (the round-trip) and via raw JSON-RPC. Hermetic: localhost only, no real LLM.
 */
class A2AServerTest {

    private HttpServer llm;
    private String llmBase;
    private final AtomicBoolean fail500 = new AtomicBoolean(false);
    private final AtomicInteger completions = new AtomicInteger();

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeEach
    void startMockLlm() throws IOException {
        llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llm.createContext("/", exchange -> {
            try {
                // Drain the request body.
                exchange.getRequestBody().readAllBytes();
                if (fail500.get()) {
                    respond(exchange, 500, "{\"error\":\"boom\"}");
                    return;
                }
                completions.incrementAndGet();
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "assistant");
                message.put("content", "TRANSCRIBED");
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
                .baseUrl(llmBase).style("openai").model("test-model").apiKey("test-key"));
    }

    private Toolkit skillfulToolkit() {
        return Toolkit.create(new Toolkit.Options()
                .builtins(false)
                .skillsDir(TestFixtures.skillsDir()));
    }

    // ---------------------------------------------------------------------
    // The key test: full inbound<->outbound round-trip.
    // ---------------------------------------------------------------------

    @Test
    void serveRoundTripViaOutboundA2a() throws Exception {
        try (Toolkit tk = skillfulToolkit()) {
            A2AServer.A2AConfig a2a = new A2AServer.A2AConfig()
                    .name("video-desk")
                    .skills(List.of("hello-world"));
            A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().client(client()).a2a(a2a));
            try {
                // Outbound A2A resolves the served card and drives SendMessage + poll.
                List<Tool> tools = A2A.agentTools(
                        A2A.agent(handle.url() + "/.well-known/agent-card.json", null, null, 40L));
                Tool hello = tools.stream()
                        .filter(t -> t.name().equals("video-desk_hello-world"))
                        .findFirst().orElseThrow();
                assertEquals("a2a", hello.source());

                ToolResult r = hello.execute(Map.of("task", "do it"), null);
                assertFalse(r.isError(), "round-trip succeeded: " + r.output());
                assertEquals("TRANSCRIBED", r.output());
                assertEquals("completed", r.metadata().get("state"));
                assertTrue(completions.get() >= 1, "mock LLM was invoked by fulfilment");
            } finally {
                handle.close();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Card advertises skills, not raw tools; streaming is false.
    // ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void cardListsSkillsNotBuiltinsStreamingFalse() throws Exception {
        try (Toolkit tk = skillfulToolkit()) {
            A2AServer.A2AConfig a2a = new A2AServer.A2AConfig().name("video-desk");
            A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().client(client()).a2a(a2a));
            try {
                Map<String, Object> card = getJson(handle.url() + "/.well-known/agent-card.json");
                assertEquals("video-desk", card.get("name"));
                assertEquals("", card.get("description"));
                assertEquals("0.1.0", card.get("version"));
                assertEquals("0.3.0", card.get("protocolVersion"));
                assertEquals(handle.url() + "/", card.get("url"));

                Map<String, Object> caps = (Map<String, Object>) card.get("capabilities");
                assertEquals(Boolean.FALSE, caps.get("streaming"));
                assertEquals(Boolean.FALSE, caps.get("pushNotifications"));
                assertEquals(List.of("text"), card.get("defaultInputModes"));
                assertEquals(List.of("text"), card.get("defaultOutputModes"));

                List<Object> skills = (List<Object>) card.get("skills");
                List<String> ids = new ArrayList<>();
                for (Object s : skills) ids.add(String.valueOf(((Map<String, Object>) s).get("id")));
                assertTrue(ids.contains("hello-world"), "card advertises the skill: " + ids);
                assertFalse(ids.contains("bash"), "card does NOT advertise raw tools");
                assertFalse(ids.contains("read"), "card does NOT advertise raw tools");
                // id == name for skills.
                Map<String, Object> first = (Map<String, Object>) skills.get(0);
                assertEquals(first.get("id"), first.get("name"));
            } finally {
                handle.close();
            }
        }
    }

    // ---------------------------------------------------------------------
    // A2A absent ⇒ no routes (404).
    // ---------------------------------------------------------------------

    @Test
    void a2aAbsentReturns404() throws Exception {
        try (Toolkit tk = skillfulToolkit()) {
            A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().client(client())); // no a2a
            try {
                HttpResponse<String> card = HTTP.send(
                        HttpRequest.newBuilder(URI.create(handle.url() + "/.well-known/agent-card.json")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(404, card.statusCode());

                HttpResponse<String> post = HTTP.send(
                        HttpRequest.newBuilder(URI.create(handle.url() + "/"))
                                .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"GetTask\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(404, post.statusCode());
            } finally {
                handle.close();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Fulfilment error (mock 500) ⇒ failed task; server keeps serving.
    // ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fulfilmentErrorBecomesFailedTaskServerSurvives() throws Exception {
        try (Toolkit tk = skillfulToolkit()) {
            A2AServer.A2AConfig a2a = new A2AServer.A2AConfig().name("video-desk");
            A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().client(client()).a2a(a2a));
            try {
                fail500.set(true); // make client.run throw during fulfilment
                Map<String, Object> submitted = rpc(handle.url() + "/", "SendMessage", sendParams("break it"));
                String id = String.valueOf(submitted.get("id"));
                assertEquals("submitted", state(submitted));

                Map<String, Object> task = pollUntilTerminal(handle.url() + "/", id);
                assertEquals("failed", state(task));
                Map<String, Object> status = (Map<String, Object>) task.get("status");
                Map<String, Object> msg = (Map<String, Object>) status.get("message");
                assertEquals("agent", msg.get("role"));
                assertNotNull(msg.get("parts"));

                // Unknown task id ⇒ JSON-RPC error -32001.
                Map<String, Object> err = rpcRaw(handle.url() + "/", "GetTask", Map.of("id", "nope"));
                Map<String, Object> errObj = (Map<String, Object>) err.get("error");
                assertEquals(-32001, ((Number) errObj.get("code")).intValue());

                // Server survives: the card still serves.
                fail500.set(false);
                Map<String, Object> card = getJson(handle.url() + "/.well-known/agent-card.json");
                assertEquals("video-desk", card.get("name"));
            } finally {
                handle.close();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Custom TaskStore is used as-is.
    // ---------------------------------------------------------------------

    @Test
    void customTaskStoreIsUsed() throws Exception {
        Map<String, Map<String, Object>> backing = new ConcurrentHashMap<>();
        AtomicInteger saves = new AtomicInteger();
        A2AServer.TaskStore custom = new A2AServer.TaskStore() {
            @Override
            public Map<String, Object> get(String id) {
                return backing.get(id);
            }

            @Override
            public void save(Map<String, Object> task) {
                saves.incrementAndGet();
                backing.put(String.valueOf(task.get("id")), task);
            }
        };
        try (Toolkit tk = skillfulToolkit()) {
            A2AServer.A2AConfig a2a = new A2AServer.A2AConfig().name("video-desk").store(custom);
            A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().client(client()).a2a(a2a));
            try {
                Map<String, Object> submitted = rpc(handle.url() + "/", "SendMessage", sendParams("go"));
                String id = String.valueOf(submitted.get("id"));
                Map<String, Object> task = pollUntilTerminal(handle.url() + "/", id);
                assertEquals("completed", state(task));
                // submitted + working + completed all went through the custom store.
                assertTrue(saves.get() >= 3, "custom store received all saves: " + saves.get());
                assertNotNull(backing.get(id), "task read back from the custom store");
            } finally {
                handle.close();
            }
        }
    }

    // ---------------------------------------------------------------------
    // FileTaskStore round-trips in a temp dir.
    // ---------------------------------------------------------------------

    @Test
    void fileTaskStoreRoundTrips(@TempDir Path tmp) throws Exception {
        try (Toolkit tk = skillfulToolkit()) {
            A2AServer.A2AConfig a2a = new A2AServer.A2AConfig()
                    .name("video-desk")
                    .store("file:" + tmp);
            A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().client(client()).a2a(a2a));
            try {
                Map<String, Object> submitted = rpc(handle.url() + "/", "SendMessage", sendParams("write me"));
                String id = String.valueOf(submitted.get("id"));
                Map<String, Object> task = pollUntilTerminal(handle.url() + "/", id);
                assertEquals("completed", state(task));

                Path file = tmp.resolve(Tool.sanitize(id) + ".json");
                assertTrue(Files.exists(file), "task persisted to a file: " + file);
                Map<String, Object> onDisk = Json.toMap(Files.readString(file));
                assertEquals("completed", state(onDisk));
            } finally {
                handle.close();
            }
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static Map<String, Object> sendParams(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("kind", "text");
        part.put("text", text);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("parts", List.of(part));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        return params;
    }

    @SuppressWarnings("unchecked")
    private static String state(Map<String, Object> task) {
        Map<String, Object> status = (Map<String, Object>) task.get("status");
        return status == null ? null : String.valueOf(status.get("state"));
    }

    /** POST a JSON-RPC request and return the {@code result} map (fails if it carried an error). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> rpc(String endpoint, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> env = rpcRaw(endpoint, method, params);
        assertFalse(env.containsKey("error"), "unexpected JSON-RPC error: " + env.get("error"));
        return (Map<String, Object>) env.get("result");
    }

    /** POST a JSON-RPC request and return the full envelope (result or error). */
    private static Map<String, Object> rpcRaw(String endpoint, String method, Map<String, Object> params)
            throws Exception {
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
        return Json.toMap(res.body());
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

    private static Map<String, Object> getJson(String url) throws Exception {
        HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        return Json.toMap(res.body());
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
