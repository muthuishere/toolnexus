package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors the JS "a2a (outbound)" suite ({@code js/test/unit.test.ts}) against a
 * real ephemeral {@link HttpServer} fake A2A agent — no external network.
 *
 * <p>The stub serves the Agent Card at {@code /.well-known/agent-card.json}
 * (skills review/plan/fail, {@code url} → itself) and a JSON-RPC POST endpoint
 * ({@code SendMessage} → submitted Task; {@code GetTask} → "working" once, then
 * terminal). Behavior is keyed on the message text: "fail" → failed, "slow" →
 * stays working, else → completed(REVIEWED).
 */
class A2ATest {

    private HttpServer server;
    private String cardUrl;
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicInteger sendMessageCalls = new AtomicInteger();
    private final AtomicInteger getTaskCalls = new AtomicInteger();

    // An env var that exists in basically every environment, used to prove
    // ${ENV} header expansion reaches the server (same approach as HttpToolTest).
    private static final String ENV_NAME = System.getenv("PATH") != null ? "PATH" : "HOME";
    private static final String ENV_VALUE = System.getenv(ENV_NAME);

    private record TaskState(AtomicInteger polls, String mode) {}

    @BeforeEach
    @SuppressWarnings("unchecked")
    void startServer() throws IOException {
        Map<String, TaskState> tasks = new ConcurrentHashMap<>();
        AtomicInteger counter = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            int port = server.getAddress().getPort();
            try {
                if ("GET".equals(method) && "/.well-known/agent-card.json".equals(path)) {
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("name", "reviewer");
                    card.put("description", "a code reviewer");
                    card.put("version", "1.0.0");
                    card.put("protocolVersion", "0.3.0");
                    card.put("capabilities", Map.of("streaming", false, "pushNotifications", false));
                    card.put("defaultInputModes", List.of("text/plain"));
                    card.put("defaultOutputModes", List.of("text/plain"));
                    card.put("url", "http://127.0.0.1:" + port + "/");
                    card.put("skills", List.of(
                            Map.of("id", "review", "name", "Review", "description", "Review some code"),
                            Map.of("id", "plan", "name", "Plan", "description", "Plan a task"),
                            Map.of("id", "fail", "name", "Fail", "description", "Always fails")));
                    respond(exchange, 200, Json.stringify(card));
                    return;
                }
                if ("POST".equals(method)) {
                    List<String> auth = exchange.getRequestHeaders().get("Authorization");
                    seenAuth.set(auth == null || auth.isEmpty() ? null : auth.get(0));
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    Map<String, Object> rpc = Json.toMap(body);
                    Object id = rpc.get("id");
                    String rpcMethod = String.valueOf(rpc.get("method"));
                    Map<String, Object> params = (Map<String, Object>) rpc.get("params");

                    if ("SendMessage".equals(rpcMethod)) {
                        sendMessageCalls.incrementAndGet();
                        Map<String, Object> message = (Map<String, Object>) params.get("message");
                        List<Object> parts = (List<Object>) message.get("parts");
                        StringBuilder text = new StringBuilder();
                        for (Object p : parts) text.append(String.valueOf(((Map<String, Object>) p).get("text")));
                        String t = text.toString();
                        String mode = t.contains("fail") ? "failed" : t.contains("slow") ? "slow" : "completed";
                        String taskId = "t" + counter.incrementAndGet();
                        tasks.put(taskId, new TaskState(new AtomicInteger(0), mode));
                        respond(exchange, 200, rpcResult(id, Map.of("id", taskId, "status", Map.of("state", "submitted"))));
                    } else if ("GetTask".equals(rpcMethod)) {
                        getTaskCalls.incrementAndGet();
                        String taskId = String.valueOf(params.get("id"));
                        TaskState st = tasks.get(taskId);
                        int polls = st.polls().incrementAndGet();
                        Map<String, Object> result;
                        if ("slow".equals(st.mode()) || polls < 2) {
                            result = Map.of("id", taskId, "status", Map.of("state", "working"));
                        } else if ("failed".equals(st.mode())) {
                            result = Map.of("id", taskId, "status", Map.of(
                                    "state", "failed",
                                    "message", Map.of("role", "agent",
                                            "parts", List.of(Map.of("kind", "text", "text", "boom")))));
                        } else {
                            result = Map.of("id", taskId,
                                    "status", Map.of("state", "completed"),
                                    "artifacts", List.of(Map.of("parts",
                                            List.of(Map.of("kind", "text", "text", "REVIEWED")))));
                        }
                        respond(exchange, 200, rpcResult(id, result));
                    } else {
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("jsonrpc", "2.0");
                        err.put("id", id);
                        err.put("error", Map.of("code", -32601, "message", "unknown method"));
                        respond(exchange, 200, Json.stringify(err));
                    }
                    return;
                }
                respond(exchange, 404, "nope");
            } catch (Exception e) {
                respond(exchange, 500, "err: " + e.getMessage());
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        cardUrl = "http://127.0.0.1:" + port + "/.well-known/agent-card.json";
    }

    private static String rpcResult(Object id, Object result) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.put("result", result);
        return Json.stringify(env);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
        }
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void agentSkillsBecomeA2aToolsSuccessRoundTripEnvHeader() {
        assertNotNull(ENV_VALUE, "need a real env var to prove header expansion");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer ${" + ENV_NAME + "}");
        Toolkit.Options opts = new Toolkit.Options()
                .builtins(false)
                .agents(A2A.agent(cardUrl, headers, null, 5L));
        try (Toolkit tk = Toolkit.create(opts)) {
            // skills → tools, source:"a2a"
            Tool review = tk.get("reviewer_review");
            assertNotNull(review, "reviewer_review present");
            assertEquals("a2a", review.source());
            assertNotNull(tk.get("reviewer_plan"), "reviewer_plan present");

            // in the provider schema
            List<String> names = new java.util.ArrayList<>();
            for (Map<String, Object> f : tk.toOpenAI()) {
                names.add((String) ((Map<?, ?>) f.get("function")).get("name"));
            }
            assertTrue(names.contains("reviewer_review") && names.contains("reviewer_plan"),
                    "a2a tools in provider schema: " + names);

            // execute → SendMessage once → poll GetTask → completed → "REVIEWED"
            ToolResult r = tk.execute("reviewer_review", Map.of("task", "please review"));
            assertFalse(r.isError());
            assertEquals("REVIEWED", r.output());
            assertEquals(1, sendMessageCalls.get(), "SendMessage called exactly once");
            assertTrue(getTaskCalls.get() >= 2, "polled GetTask until completed");
            assertEquals("Bearer " + ENV_VALUE, seenAuth.get(), "env header expanded at call time");
            assertEquals("completed", r.metadata().get("state"));
            assertEquals("reviewer", r.metadata().get("agent"));
            assertNotNull(r.metadata().get("taskId"));
            assertTrue(String.valueOf(r.metadata().get("taskId")).length() > 0);
        }
    }

    @Test
    void failedTaskMapsToIsError() {
        Toolkit.Options opts = new Toolkit.Options().builtins(false).agents(A2A.agent(cardUrl, null, null, 5L));
        try (Toolkit tk = Toolkit.create(opts)) {
            ToolResult r = tk.execute("reviewer_fail", Map.of("task", "please fail"));
            assertTrue(r.isError());
            assertTrue(r.output().contains("failed"), "output: " + r.output());
            assertTrue(r.output().contains("boom"), "carries the status message text: " + r.output());
            assertEquals("failed", r.metadata().get("state"));
        }
    }

    @Test
    void ctxCancelMidPollStopsFurtherGetTaskCalls() throws Exception {
        Toolkit.Options opts = new Toolkit.Options().builtins(false).agents(A2A.agent(cardUrl, null, null, 10L));
        try (Toolkit tk = Toolkit.create(opts)) {
            Tool review = tk.get("reviewer_review");
            assertNotNull(review);
            ToolContext ctx = new ToolContext();
            AtomicReference<ToolResult> holder = new AtomicReference<>();
            Thread runner = new Thread(() -> holder.set(review.execute(Map.of("task", "slow one"), ctx)));
            runner.start();
            Thread.sleep(35);
            ctx.cancel();
            runner.join(2000);
            ToolResult r = holder.get();
            assertNotNull(r, "execute returned");
            assertTrue(r.isError());
            assertEquals("canceled", r.metadata().get("state"));
            int afterAbort = getTaskCalls.get();
            Thread.sleep(60);
            assertEquals(afterAbort, getTaskCalls.get(), "no GetTask calls after abort");
        }
    }

    @Test
    void configAgentsBlockEnabledFalseSkippedEnabledResolves() {
        // disabled agent skipped
        Map<String, Object> disabledCfg = Map.of("agents", Map.of("rev", Map.of("card", cardUrl, "disabled", true)));
        try (Toolkit off = Toolkit.create(new Toolkit.Options().builtins(false).mcpConfig(disabledCfg))) {
            assertNull(off.get("reviewer_review"), "disabled agent skipped");
        }
        // enabled agent resolved from config
        Map<String, Object> enabledCfg = Map.of("agents", Map.of("rev", Map.of("card", cardUrl, "pollEvery", 5)));
        try (Toolkit on = Toolkit.create(new Toolkit.Options().builtins(false).mcpConfig(enabledCfg))) {
            assertNotNull(on.get("reviewer_review"), "enabled agent resolved from config");
        }
    }

    @Test
    void addAgentRegistersToolsAtRuntime() {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            assertNull(tk.get("reviewer_review"), "absent before addAgent");
            tk.addAgent(A2A.agent(cardUrl, null, null, 5L));
            assertNotNull(tk.get("reviewer_review"), "present after addAgent");
            assertNotNull(tk.get("reviewer_plan"));
            // bare card URL form also works (dedupe keeps first)
            tk.addAgent(cardUrl);
        }
    }

    @Test
    void parseAgentsConfigHonorsEnabledDisabledPrecedence() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("a", Map.of("card", "http://x/1"));
        block.put("b", Map.of("card", "http://x/2", "disabled", true));
        block.put("c", Map.of("card", "http://x/3", "enabled", false));
        block.put("d", Map.of("card", "http://x/4", "enabled", true, "disabled", true));
        List<A2A.Agent> parsed = A2A.parseAgentsConfig(block);
        assertEquals(1, parsed.size());
        assertEquals("http://x/1", parsed.get(0).card());
    }
}
