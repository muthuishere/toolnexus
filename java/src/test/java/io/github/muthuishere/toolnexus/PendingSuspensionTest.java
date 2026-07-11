package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §10 Suspension — Pending / waitFor. Mirrors {@code js/examples/pending.ts}: a
 * {@code get_balance} tool that returns {@code authRequired(...)} until the session is authed,
 * driven through the REAL client loop against a localhost stub LLM (no network, no key). Login
 * is just the {@code kind:"authorization"} case — no auth subsystem, just data + one function.
 *
 * <p>Exercises both host postures:
 * <ul>
 *   <li>(A) {@code waitFor} provided → the engine resolves + retries transparently; the model
 *       gets the answer, {@code status == "done"}.</li>
 *   <li>(B) no {@code waitFor} → {@code run} halts with {@code status:"pending"} + the request to
 *       resume later.</li>
 * </ul>
 */
class PendingSuspensionTest {

    // A fake OpenAI endpoint: turn 1 calls get_balance; turn 2 (after it sees a `tool` message)
    // returns a final answer. Lets us exercise the real client loop with no network.
    @SuppressWarnings("unchecked")
    private static HttpServer stubLLM() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = Json.toMap(body);
            List<Object> messages = (List<Object>) req.get("messages");
            boolean sawToolResult = messages != null && messages.stream()
                    .anyMatch(m -> m instanceof Map && "tool".equals(((Map<String, Object>) m).get("role")));
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("role", "assistant");
            if (sawToolResult) {
                message.put("content", "Done — your balance is ₹67,417.");
            } else {
                message.put("content", null);
                message.put("tool_calls", List.of(Map.of(
                        "id", "c1", "type", "function",
                        "function", Map.of("name", "get_balance", "arguments", "{}"))));
            }
            String json = Json.stringify(Map.of(
                    "choices", List.of(Map.of("message", message)),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
        return server;
    }

    // get_balance: requires login the first time, then succeeds. authed flips out-of-band.
    private static Toolkit balanceToolkit(AtomicBoolean authed) {
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        tk.register(new Tool() {
            @Override public String name() { return "get_balance"; }
            @Override public String description() { return "Return the account balance. Requires login first."; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
            }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                if (!authed.get()) {
                    return ToolResult.authRequired("https://example.com/login?token=abc",
                            "Log in to view your balance");
                }
                String answerId = ctx != null && ctx.answer() != null ? ctx.answer().id() : "none";
                return ToolResult.ok("balance: ₹67,417 (resolved via answer " + answerId + ")");
            }
        });
        return tk;
    }

    // ── A) waitFor provided → resolve + retry transparently → status "done" ────────
    @Test
    void waitForResolvesAndRetriesToDone() throws IOException {
        HttpServer server = stubLLM();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AtomicBoolean authed = new AtomicBoolean(false);
        AtomicReference<String> linkSeen = new AtomicReference<>();
        try (Toolkit tk = balanceToolkit(authed)) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("stub").apiKey("k")
                    // waitFor is the ONLY behavior the host supplies — here it simulates the human
                    // completing login. In real life: open a browser, text the link, or forward over A2A.
                    .waitFor(request -> {
                        linkSeen.set(request.url());
                        authed.set(true); // the world changed out-of-band
                        return new Answer(request.id(), true);
                    }));
            LlmClient.RunResult a = client.run("what is my balance?", tk);

            assertEquals("done", a.status, "waitFor path completes");
            assertNull(a.pending, "no pending request when resolved");
            assertTrue(a.text.contains("67,417"), "final answer carries the balance: " + a.text);
            assertNotNull(linkSeen.get(), "the login link was delivered to the host");
            assertTrue(linkSeen.get().contains("example.com/login"), "link is the authorize URL");
            // the tool call output reflects the resolved (post-auth) execution
            assertTrue(a.toolCalls.get(a.toolCalls.size() - 1).output.contains("67,417"));
        } finally {
            server.stop(0);
        }
    }

    // ── B) no waitFor → durable: run() halts with the request to resume later ──────
    @Test
    void noWaitForHaltsWithPending() throws IOException {
        HttpServer server = stubLLM();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AtomicBoolean authed = new AtomicBoolean(false);
        try (Toolkit tk = balanceToolkit(authed)) {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("stub").apiKey("k"));
            LlmClient.RunResult b = client.run("what is my balance?", tk);

            assertEquals("pending", b.status, "durable posture halts, never hangs");
            assertNotNull(b.pending, "the unresolved request is surfaced");
            assertEquals("authorization", b.pending.kind(), "login is kind=authorization");
            assertNotNull(b.pending.url());
            assertTrue(b.pending.url().contains("example.com/login"), "carries the authorize URL");
        } finally {
            server.stop(0);
        }
    }

    // A fake OpenAI endpoint that always calls the `question` builtin. With no waitFor the loop
    // halts durably (§10) — the question suspension surfaces as status:"pending".
    private static HttpServer questionLLM() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", null);
            String argsJson = Json.stringify(Map.of("questions",
                    List.of(Map.of("question", "Pick a color?", "options", List.of("red", "green")))));
            message.put("tool_calls", List.of(Map.of(
                    "id", "c1", "type", "function",
                    "function", Map.of("name", "question", "arguments", argsJson))));
            String json = Json.stringify(Map.of(
                    "choices", List.of(Map.of("message", message)),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
        return server;
    }

    // ── a question suspension with no waitFor halts the run (status pending) ────────
    @Test
    void questionSuspensionNoWaitForHaltsPending() throws IOException {
        HttpServer server = questionLLM();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options())) { // builtins on → `question` exists
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("stub").apiKey("k")); // no waitFor
            LlmClient.RunResult res = client.run("ask me", tk);

            assertEquals("pending", res.status, "no waitFor ⇒ the run halts pending, does not loop forever");
            assertNotNull(res.pending);
            assertEquals("question", res.pending.kind());
            assertEquals("Pick a color? (options: red, green)", res.pending.prompt(), "byte-exact rendered prompt");
        } finally {
            server.stop(0);
        }
    }

    // ── helpers for the G2/G3 metric + concurrency tests ──────────────────────────

    private static void writeJson(HttpExchange exchange, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
    }

    // Calls the named tool (with fixed args) on turn 1, then returns `content` once it sees a
    // tool result. Lets a single tool call round-trip to completion through the real loop.
    @SuppressWarnings("unchecked")
    private static HttpServer callToolThenDoneLLM(String toolName, String argsJson, String content) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = Json.toMap(body);
            List<Object> messages = (List<Object>) req.get("messages");
            boolean sawToolResult = messages != null && messages.stream()
                    .anyMatch(m -> m instanceof Map && "tool".equals(((Map<String, Object>) m).get("role")));
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("role", "assistant");
            if (sawToolResult) {
                message.put("content", content);
            } else {
                message.put("content", null);
                message.put("tool_calls", List.of(Map.of(
                        "id", "c1", "type", "function",
                        "function", Map.of("name", toolName, "arguments", argsJson))));
            }
            writeJson(exchange, Json.stringify(Map.of(
                    "choices", List.of(Map.of("message", message)),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2))));
        });
        server.start();
        return server;
    }

    // ── G2) a suspension is pending, not a tool error; afterTool sees only the resolved result ──
    @Test
    void suspensionIsPendingNotErrorAfterToolSeesResolved() throws IOException {
        String argsJson = Json.stringify(Map.of("questions",
                List.of(Map.of("question", "Pick?", "options", List.of("a", "b")))));
        HttpServer server = callToolThenDoneLLM("question", argsJson, "done");
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        List<LlmClient.MetricEvent> events = new CopyOnWriteArrayList<>();
        List<ToolResult> afterToolSaw = new CopyOnWriteArrayList<>();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options())) { // builtins on → `question`
            LlmClient.Hooks hooks = new LlmClient.Hooks()
                    .afterTool(ev -> { afterToolSaw.add(ev.result()); return null; });
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("x").apiKey("k")
                    .waitFor(r -> new Answer(r.id(), true, Map.of("answers", List.of("a"))))
                    .hooks(hooks)
                    .onMetric(events::add));
            LlmClient.RunResult res = client.run("ask", tk);

            assertEquals("done", res.status, "waitFor resolved the suspension and the run finished");
            LlmClient.MetricEvent.Tool suspend = events.stream()
                    .filter(e -> e instanceof LlmClient.MetricEvent.Tool).map(e -> (LlmClient.MetricEvent.Tool) e)
                    .filter(e -> "question".equals(e.tool()) && e.pending())
                    .findFirst().orElse(null);
            assertNotNull(suspend, "the suspension emitted a tool event tagged pending");
            assertFalse(suspend.isError(), "a suspension is not a tool error");
            assertEquals(1, afterToolSaw.size(), "afterTool ran exactly once — not on the suspension");
            assertNull(ToolResult.pendingOf(afterToolSaw.get(0)),
                    "afterTool saw the resolved result, never the suspension");
        } finally {
            server.stop(0);
        }
    }

    // ── path B) a beforeTool guard-raised pending is honored, counted pending not error ──
    @Test
    void beforeToolGuardRaisedPendingIsHonoredAndCountedPending() throws IOException {
        HttpServer server = callToolThenDoneLLM("add", Json.stringify(Map.of("a", 1, "b", 2)), "3");
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        List<LlmClient.MetricEvent> events = new CopyOnWriteArrayList<>();
        AtomicBoolean asked = new AtomicBoolean(false);
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            tk.register(new Tool() {
                @Override public String name() { return "add"; }
                @Override public String description() { return "add two numbers"; }
                @Override public Map<String, Object> inputSchema() {
                    return Map.of("type", "object", "properties", Map.of(
                            "a", Map.of("type", "number"), "b", Map.of("type", "number")),
                            "required", List.of("a", "b"));
                }
                @Override public String source() { return "custom"; }
                @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                    long a = ((Number) args.get("a")).longValue();
                    long b = ((Number) args.get("b")).longValue();
                    return ToolResult.ok(String.valueOf(a + b));
                }
            });
            // The guard raises a suspension with NO tool code running (path B), just once.
            LlmClient.Hooks hooks = new LlmClient.Hooks().beforeTool(ev ->
                    ("add".equals(ev.name()) && !asked.get())
                            ? LlmClient.ToolOverride.withResult(
                                    ToolResult.pending(new Request("appr1", "approval", "approve add?")))
                            : null);
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("x").apiKey("k")
                    .hooks(hooks)
                    .waitFor(r -> { asked.set(true); return new Answer(r.id(), true); })
                    .onMetric(events::add));
            LlmClient.RunResult res = client.run("add them", tk);

            assertEquals("done", res.status, "on approval the real tool ran and the run finished");
            LlmClient.MetricEvent.Tool suspend = events.stream()
                    .filter(e -> e instanceof LlmClient.MetricEvent.Tool).map(e -> (LlmClient.MetricEvent.Tool) e)
                    .filter(e -> "add".equals(e.tool()) && e.pending())
                    .findFirst().orElse(null);
            assertNotNull(suspend, "the guard-raised pending emitted a tool event tagged pending");
            assertFalse(suspend.isError(), "a guard-raised suspension is not a tool error");
            assertTrue(res.toolCalls.stream().anyMatch(t -> "add".equals(t.name) && "3".equals(t.output)),
                    "the real tool ran after approval");
        } finally {
            server.stop(0);
        }
    }

    // A fake OpenAI endpoint that emits TWO `question` tool_calls in one turn.
    private static HttpServer twoQuestionsLLM() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", null);
            String first = Json.stringify(Map.of("questions", List.of(Map.of("question", "First?"))));
            String second = Json.stringify(Map.of("questions", List.of(Map.of("question", "Second?"))));
            message.put("tool_calls", List.of(
                    Map.of("id", "c1", "type", "function", "function", Map.of("name", "question", "arguments", first)),
                    Map.of("id", "c2", "type", "function", "function", Map.of("name", "question", "arguments", second))));
            writeJson(exchange, Json.stringify(Map.of(
                    "choices", List.of(Map.of("message", message)),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2))));
        });
        server.start();
        return server;
    }

    // ── G3) two concurrent suspensions with no waitFor surface the FIRST, not the last ──
    @Test
    @SuppressWarnings("unchecked")
    void twoConcurrentSuspensionsSurfaceTheFirst() throws IOException {
        HttpServer server = twoQuestionsLLM();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options())) { // builtins on, no waitFor
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("x").apiKey("k"));
            LlmClient.RunResult res = client.run("ask two", tk);

            assertEquals("pending", res.status);
            assertNotNull(res.pending);
            assertEquals("First?", res.pending.prompt(),
                    "the FIRST suspension in call order is surfaced, deterministically");
            boolean c2Present = res.messages.stream().anyMatch(m ->
                    m instanceof Map && "c2".equals(((Map<String, Object>) m).get("tool_call_id")));
            assertFalse(c2Present, "the second concurrent suspension does not pollute the transcript");
        } finally {
            server.stop(0);
        }
    }
}
