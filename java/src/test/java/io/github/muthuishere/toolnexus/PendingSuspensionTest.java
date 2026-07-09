package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
