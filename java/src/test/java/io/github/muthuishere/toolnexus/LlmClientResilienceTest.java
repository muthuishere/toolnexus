package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic resilience tests for {@link LlmClient}, mirroring the JS reference
 * ({@code js/test/unit.test.ts}: "retries on 503 then succeeds" + "run-level timeout aborts").
 * Uses a real ephemeral {@link HttpServer} — no network, no LLM key.
 */
class LlmClientResilienceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private int start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Test
    void retriesOn503ThenSucceeds() throws IOException {
        AtomicInteger hits = new AtomicInteger(0);
        int port = start(ex -> {
            int n = hits.incrementAndGet();
            if (n < 3) {
                respond(ex, 503, "busy");
                return;
            }
            respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
        });

        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .style("openai")
                .model("x")
                .apiKey("k")
                .retries(3)
                .retryBaseMs(5));

        LlmClient.RunResult res = client.run("hi", tk);
        assertEquals("ok", res.text);
        assertEquals(3, hits.get(), "two 503s retried, third succeeded");
        tk.close();
    }

    @Test
    void runLevelTimeoutAborts() throws IOException {
        int port = start(ex -> {
            try {
                Thread.sleep(800); // far longer than the run deadline
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
        });

        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .style("openai")
                .model("x")
                .apiKey("k")
                .retries(0)
                .timeoutMs(60));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.run("hi", tk));
        assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().matches(".*(timeout|abort).*"),
                "expected timeout/abort message, got: " + ex.getMessage());
        tk.close();
    }
}
