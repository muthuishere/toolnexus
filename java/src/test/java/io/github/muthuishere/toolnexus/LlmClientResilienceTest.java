package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    private static final String OK_BODY =
            "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    // ---- §8 onError classifier (mirrors js/test/unit.test.ts "onError" cases) ----

    /** onError -> FAIL on a normally-retryable 429 surfaces immediately: exactly one request. */
    @Test
    void onErrorFailOn429SurfacesWithoutRetry() throws IOException {
        AtomicInteger hits = new AtomicInteger(0);
        int port = start(ex -> {
            hits.incrementAndGet();
            respond(ex, 429, "rate limited");
        });

        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .style("openai")
                .model("x")
                .apiKey("k")
                .retries(3)
                .retryBaseMs(5)
                .onError(info -> LlmClient.Tier.FAIL));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.run("hi", tk));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("429"),
                "expected surfaced 429, got: " + ex.getMessage());
        assertEquals(1, hits.get(), "FAIL classifier skips all retries");
        tk.close();
    }

    /** onError -> RETRY on a normally-terminal 400 retries to the full budget: 1 + retries requests. */
    @Test
    void onErrorRetryOn400RetriesToBudget() throws IOException {
        AtomicInteger hits = new AtomicInteger(0);
        int port = start(ex -> {
            hits.incrementAndGet();
            respond(ex, 400, "bad request");
        });

        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .style("openai")
                .model("x")
                .apiKey("k")
                .retries(2)
                .retryBaseMs(5)
                .onError(info -> LlmClient.Tier.RETRY));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.run("hi", tk));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("400"),
                "expected surfaced 400 after budget exhausted, got: " + ex.getMessage());
        assertEquals(3, hits.get(), "RETRY classifier retries a 400 to the full budget (1 + 2)");
        tk.close();
    }

    /** Absent onError, the default classifier is byte-identical: 429 then 200 succeeds. */
    @Test
    void defaultClassifierRetries429ThenSucceeds() throws IOException {
        AtomicInteger hits = new AtomicInteger(0);
        int port = start(ex -> {
            int n = hits.incrementAndGet();
            if (n < 2) {
                respond(ex, 429, "rate limited");
                return;
            }
            respond(ex, 200, OK_BODY);
        });

        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .style("openai")
                .model("x")
                .apiKey("k")
                .retries(2)
                .retryBaseMs(5)); // no onError

        LlmClient.RunResult res = client.run("hi", tk);
        assertEquals("ok", res.text);
        assertEquals(2, hits.get(), "default retries the 429, then succeeds");
        tk.close();
    }

    /** Absent onError, the default classifier fails a non-retryable 400 immediately: one request. */
    @Test
    void defaultClassifierFailsOn400Immediately() throws IOException {
        AtomicInteger hits = new AtomicInteger(0);
        int port = start(ex -> {
            hits.incrementAndGet();
            respond(ex, 400, "bad request");
        });

        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .style("openai")
                .model("x")
                .apiKey("k")
                .retries(2)
                .retryBaseMs(5)); // no onError

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.run("hi", tk));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("400"),
                "expected surfaced 400, got: " + ex.getMessage());
        assertEquals(1, hits.get(), "default fails a non-retryable 400 without retry");
        tk.close();
    }
    // Retry-After is parsed identically in all seven ports: the delay-seconds form
    // only (RFC 9110 §10.2.3) — ASCII digits in 0…2147483647. The parser is private,
    // so the rule is asserted where it is observable: how long the client actually
    // waits. This port's shape filter was already right; its range was not —
    // Long.parseLong threw NumberFormatException from inside the retry path on an
    // absurd digit string, killing the run instead of ignoring the header.
    private long retryDelayMs(String retryAfter, int retryBaseMs) throws IOException {
        AtomicInteger hits = new AtomicInteger(0);
        int port = start(ex -> {
            if (hits.incrementAndGet() < 2) {
                ex.getResponseHeaders().add("Retry-After", retryAfter);
                respond(ex, 429, "slow down");
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
                .retryBaseMs(retryBaseMs));

        long t0 = System.nanoTime();
        LlmClient.RunResult res = client.run("hi", tk);
        long elapsed = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals("ok", res.text);
        tk.close();
        return elapsed;
    }

    @Test
    void outOfRangeRetryAfterIsIgnoredRatherThanThrowing() throws IOException {
        // Before this change: NumberFormatException out of the retry path.
        assertTrue(retryDelayMs("99999999999999999999", 5) < 400);
    }

    @Test
    void fractionalRetryAfterFallsBackToBackoff() throws IOException {
        assertTrue(retryDelayMs("0.5", 5) < 400);
    }

    @Test
    void negativeRetryAfterFallsBackToBackoff() throws IOException {
        assertTrue(retryDelayMs("-5", 400) >= 300);
    }

    @Test
    void zeroRetryAfterMeansRetryNow() throws IOException {
        assertTrue(retryDelayMs("0", 700) < 450);
    }

    @Test
    void httpDateRetryAfterFallsBackToBackoff() throws IOException {
        assertTrue(retryDelayMs("Wed, 21 Oct 2015 07:28:00 GMT", 5) < 400);
    }

    @Test
    void integerRetryAfterIsHonoredOverBackoff() throws IOException {
        assertTrue(retryDelayMs("1", 5) >= 900);
    }


    // ------------------------------------------------------------------ the retryable set

    /** One server that answers {@code status} once, then succeeds; returns the attempt count. */
    private int attemptsFor(int status, List<Integer> retryableStatuses) throws IOException {
        AtomicInteger hits = new AtomicInteger(0);
        int port = start(ex -> {
            if (hits.incrementAndGet() < 2) {
                respond(ex, status, "transient");
                return;
            }
            respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{}}");
        });
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .style("openai")
                .model("x")
                .apiKey("k")
                .retries(2)
                .retryBaseMs(5)
                .retryableStatuses(retryableStatuses));
        try {
            client.run("hi", tk);
        } catch (RuntimeException ignored) {
            // a terminal status surfaces; the attempt count is what this asserts
        }
        tk.close();
        stopServer();
        server = null;
        return hits.get();
    }

    /**
     * TypeSafe documents {@code 529 Overloaded} as "retry with backoff", so it is in the defaults.
     * The set stays an ENUMERATION, so {@code 520}–{@code 527} and {@code 501} remain terminal
     * until a host opts in — which is what {@code retryableStatuses} is for, additively.
     */
    @Test
    void fiveTwentyNineRetriesByDefaultAndRetryableStatusesOnlyWidensTheSet() throws IOException {
        assertEquals(2, attemptsFor(529, null), "529 Overloaded is retryable by default");
        assertEquals(1, attemptsFor(520, null), "an unlisted 5xx is terminal by default");
        assertEquals(1, attemptsFor(501, null), "a permanent 5xx is terminal by default");
        assertEquals(1, attemptsFor(422, null), "a non-429 4xx stays terminal");

        List<Integer> cloudflare = List.of(520, 521, 522, 523, 524, 525, 526, 527);
        assertEquals(2, attemptsFor(520, cloudflare), "an opted-in status retries");
        assertEquals(2, attemptsFor(429, cloudflare), "429 still retries, so Retry-After still applies");
        assertEquals(1, attemptsFor(501, cloudflare), "a status in neither set stays terminal");
    }

    /** The option sets the default classification only; {@code onError} has the final say. */
    @Test
    void onErrorHasTheFinalSayOverRetryableStatuses() throws IOException {
        AtomicInteger hits = new AtomicInteger(0);
        int port = start(ex -> {
            hits.incrementAndGet();
            respond(ex, 520, "cloudflare");
        });
        Toolkit tk = Toolkit.create(new Toolkit.Options());
        LlmClient client = LlmClient.create(new LlmClient.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .style("openai")
                .model("x")
                .apiKey("k")
                .retries(3)
                .retryBaseMs(5)
                .retryableStatuses(List.of(520))
                .onError(info -> LlmClient.Tier.FAIL));
        try {
            client.run("hi", tk);
        } catch (RuntimeException ignored) {
            // terminal either way
        }
        assertEquals(1, hits.get(), "onError:fail overrode a status the host itself listed");
        tk.close();
    }

}
