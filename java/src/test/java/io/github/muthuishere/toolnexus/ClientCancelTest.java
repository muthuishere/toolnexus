package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §7D Java cancellation seam (task 1.3) + the {@code "incomplete"} RunStatus (task 1.5):
 * <ul>
 *   <li>a {@link LlmClient.CancelToken} aborts an in-flight {@code run}/{@code ask} with
 *       {@link LlmClient.CancelledException} (classified by token state, never retried);</li>
 *   <li>a pre-cancelled token aborts before the first attempt (the between-attempts minimum
 *       guarantee);</li>
 *   <li>a run that hits {@code maxTurns} while the model still emits tool calls returns
 *       {@code status:"incomplete"} — never a silent {@code "done"}.</li>
 * </ul>
 */
class ClientCancelTest {

    private static HttpServer server;
    private static String base;
    /** When set, the mock blocks the response until released (for mid-flight cancellation). */
    private static final AtomicReference<CountDownLatch> gate = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CountDownLatch g = gate.get();
            if (g != null) {
                try { g.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            if (body.contains("\"model\":\"m-looper\"")) {
                // Always another tool call — the run can only end at the turn cap.
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", "noop");
                fn.put("arguments", "{}");
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", "c1");
                call.put("type", "function");
                call.put("function", fn);
                message.put("content", null);
                message.put("tool_calls", List.of(call));
            } else {
                message.put("content", "ok");
            }
            respond(exchange, message);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        CountDownLatch g = gate.get();
        if (g != null) g.countDown();
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, Map<String, Object> message) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("choices", List.of(Map.of("message", message)));
        payload.put("usage", Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5));
        byte[] out = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
    }

    private static Tool noopTool() {
        return new Tool() {
            @Override public String name() { return "noop"; }
            @Override public String description() { return "does nothing"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return ToolResult.ok("noop");
            }
        };
    }

    private static LlmClient client(String model) {
        return LlmClient.create(new LlmClient.Options()
                .baseUrl(base).style("openai").model(model).apiKey("test").retries(0));
    }

    @Test
    void cancelToken_abortsInFlightRun_asCancelledException() throws Exception {
        gate.set(new CountDownLatch(1));
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            LlmClient.CancelToken cancel = new LlmClient.CancelToken();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(1);
            Thread t = Thread.startVirtualThread(() -> {
                try {
                    client("m-ok").run("hi", tk, null, cancel);
                } catch (Throwable e) {
                    thrown.set(e);
                } finally {
                    finished.countDown();
                }
            });
            Thread.sleep(100); // let the request get in flight (blocked on the gate)
            cancel.cancel();
            assertTrue(finished.await(5, TimeUnit.SECONDS), "cancel aborts the in-flight run promptly");
            assertNotNull(thrown.get(), "the cancelled run throws");
            assertTrue(thrown.get() instanceof LlmClient.CancelledException,
                    "classified by token state as CancelledException, got " + thrown.get());
            t.join(1000);
        } finally {
            gate.get().countDown();
            gate.set(null);
        }
    }

    @Test
    void preCancelledToken_abortsBeforeFirstAttempt() throws Exception {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            LlmClient.CancelToken cancel = new LlmClient.CancelToken();
            cancel.cancel();
            assertThrows(LlmClient.CancelledException.class,
                    () -> client("m-ok").run("hi", tk, null, cancel),
                    "between-attempts minimum: a cancelled token never reaches the endpoint");
            assertFalse(Thread.currentThread().isInterrupted(),
                    "the interrupt flag does not leak to the caller");
        }
    }

    @Test
    void askWithCancelToken_completesNormallyWhenNotCancelled() throws Exception {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
            LlmClient c = client("m-ok");
            LlmClient.CancelToken cancel = new LlmClient.CancelToken();
            LlmClient.RunResult r = c.ask("hi", tk, "conv-1", cancel);
            assertEquals("done", r.status);
            assertNotNull(c.conversationStore().get("conv-1"), "the transcript was persisted");
        }
    }

    @Test
    void maxTurnsWithToolCalls_isIncomplete_notSilentDone() throws Exception {
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(noopTool()))) {
            LlmClient c = LlmClient.create(new LlmClient.Options()
                    .baseUrl(base).style("openai").model("m-looper").apiKey("test")
                    .retries(0).maxTurns(3));
            LlmClient.RunResult r = c.run("loop", tk);
            assertEquals("incomplete", r.status, "a §7D limit stop is loud, never a silent done");
            assertEquals(3, r.turns);
        }
    }
}
