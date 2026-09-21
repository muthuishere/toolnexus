package io.github.muthuishere.toolnexus.acp;

import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Toolkit;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for {@link AcpClient} against {@link FakeAcpServer}, spawned as a real child
 * process over real OS pipes — no network, no real {@code devin}, no MCP SDK. Mirrors the six
 * ADR 0031 / {@code openspec/changes/add-acp-model-source} spec scenarios (see
 * {@code spikes/acp/SPIKE.md} for the Go reference this was built from).
 */
class AcpClientTest {

    private static List<String> fakeServerCommand(String scenario) {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        return List.of(javaBin, "-cp", classpath, FakeAcpServer.class.getName(), "--scenario=" + scenario);
    }

    private static AcpClient startClient(String scenario) throws Exception {
        AcpClient client = AcpClient.start(fakeServerCommand(scenario), 5_000);
        return client;
    }

    private static Map<String, Object> userMessage(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", text);
        return m;
    }

    private static Map<String, Object> assistantMessage(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", text);
        return m;
    }

    // ---- warm session reuse ------------------------------------------------------------

    @Test
    void warmSessionReuse_oneSessionAcrossManyTurns() throws Exception {
        try (AcpClient client = startClient("warm")) {
            String sid = client.sessionId();
            assertNotNull(sid);

            LlmClient llm = InProcess.createClient(new InProcess.Options()
                    .model("acp-test")
                    .generate(client));
            try (Toolkit toolkit = Toolkit.create(new Toolkit.Options())) {
                LlmClient.RunResult r1 = llm.run("hello", toolkit);
                LlmClient.RunResult r2 = llm.run("again", toolkit);
                LlmClient.RunResult r3 = llm.run("more", toolkit);

                assertEquals("done", r1.status);
                assertEquals("done", r2.status);
                assertEquals("done", r3.status);

                // session/new is only ever called once, by AcpClient.start — never per turn.
                assertEquals(sid, client.sessionId());
                assertTrue(r1.text.contains("sessionNewCount=1"), r1.text);
                assertTrue(r2.text.contains("sessionNewCount=1"), r2.text);
                assertTrue(r3.text.contains("sessionNewCount=1"), r3.text);
                assertTrue(r1.text.contains("echo:") && r1.text.contains("hello"), r1.text);
            }
        }
    }

    // ---- thought/tool narration filtered -------------------------------------------------

    @Test
    void thoughtAndToolNarrationFiltered_onlyMessageChunksAccumulated() throws Exception {
        try (AcpClient client = startClient("noisy")) {
            InProcess.Response resp = client.generate(request(List.of(userMessage("77"))));
            // FakeAcpServer's "noisy" scenario echoes the full text it received (AcpClient's
            // assembled prompt: the formatted message plus the supersedes marker) back inside
            // agent_thought_chunk and tool_call/tool_call_update notifications interleaved
            // around two agent_message_chunk halves of a JSON object. Only the message-chunk
            // halves may appear in the result: no thought/tool narration, and the result must
            // parse as valid JSON (the exact corruption ADR 0031 warns about).
            String expectedUserText = AcpClient.assemblePrompt(request(List.of(userMessage("77"))));
            assertEquals("{\"answer\":\"" + expectedUserText + "\"}", resp.content);
            assertTrue(!resp.content.contains("Let me think"), resp.content);
            assertTrue(!resp.content.contains("double-checking"), resp.content);
        }
    }

    // ---- permission answered, not awaited -------------------------------------------------

    @Test
    void permissionAnsweredNotAwaited_turnCompletesQuickly() throws Exception {
        try (AcpClient client = startClient("permission")) {
            long start = System.nanoTime();
            List<Object> messages = List.of(userMessage("delete the database"));
            InProcess.Response resp = client.generate(request(messages));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals("PERMITTED:" + AcpClient.assemblePrompt(request(messages)), resp.content);
            // Well under the client's 5s timeout — the request_permission round trip is
            // answered inline by the reader thread, not awaited by the main turn.
            assertTrue(elapsedMs < 2_000, "turn took " + elapsedMs + "ms, expected well under 2000ms");
        }
    }

    @Test
    void unansweredPermission_hangsUntilTimeout() throws Exception {
        try (AcpClient client = startClient("hang")) {
            client.autoAnswerPermission = false;
            client.timeoutMs = 1_000;
            long start = System.nanoTime();
            Exception ex = null;
            try {
                client.generate(request(List.of(userMessage("delete everything"))));
            } catch (Exception e) {
                ex = e;
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertNotNull(ex, "expected the turn to time out rather than complete");
            assertTrue(elapsedMs >= 900, "expected the client's own timeout to fire, took " + elapsedMs + "ms");
        }
    }

    // ---- stale-answer prevented by the supersedes marker ----------------------------------

    @Test
    void staleAnswerPreventedBySupersedesMarker() throws Exception {
        try (AcpClient client = startClient("stale")) {
            LlmClient llm = InProcess.createClient(new InProcess.Options()
                    .model("acp-test")
                    .generate(client));
            try (Toolkit toolkit = Toolkit.create(new Toolkit.Options())) {
                LlmClient.RunResult r1 = llm.run("What is the capital of France?", toolkit);
                assertEquals("done", r1.status);
                assertTrue(r1.text.contains("France"), r1.text);

                List<Object> history = new ArrayList<>();
                history.add(userMessage("What is the capital of France?"));
                history.add(assistantMessage(r1.text));
                LlmClient.RunResult r2 = llm.run("What is the capital of Japan?", toolkit, history);

                assertEquals("done", r2.status);
                // Turn 2's full assembled request contains turn 1's question verbatim as a
                // prefix — the exact shape that makes a naive stateful agent answer the FIRST
                // (stale) question. The supersedes marker, assembled by AcpClient itself, is
                // what makes it answer the SECOND (fresh) one instead.
                assertTrue(r2.text.startsWith("FRESH-ANSWER-TO:"), r2.text);
                assertTrue(r2.text.contains("Japan"), r2.text);
                assertTrue(!r2.text.contains("STALE"), r2.text);
            }
        }
    }

    // ---- turns on one session are serialised -----------------------------------------------

    @Test
    void turnsSerialized_concurrentCallsDoNotCorrupt() throws Exception {
        try (AcpClient client = startClient("warm")) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CompletableFuture<InProcess.Response> f1 = CompletableFuture.supplyAsync(
                        () -> client.apply(request(List.of(userMessage("alpha")))), pool);
                CompletableFuture<InProcess.Response> f2 = CompletableFuture.supplyAsync(
                        () -> client.apply(request(List.of(userMessage("beta")))), pool);

                InProcess.Response r1 = f1.get(10, TimeUnit.SECONDS);
                InProcess.Response r2 = f2.get(10, TimeUnit.SECONDS);

                // Each caller gets its OWN correct answer, matched by content — proof that the
                // two turns did not interleave into a corrupted accumulation, regardless of
                // which one the fake server happened to service first.
                assertTrue(r1.content.contains("alpha") ^ r2.content.contains("alpha"),
                        "exactly one reply should contain 'alpha': " + r1.content + " | " + r2.content);
                assertTrue(r1.content.contains("beta") ^ r2.content.contains("beta"),
                        "exactly one reply should contain 'beta': " + r1.content + " | " + r2.content);
                assertTrue(!(r1.content.contains("alpha") && r1.content.contains("beta")), r1.content);
                assertTrue(!(r2.content.contains("alpha") && r2.content.contains("beta")), r2.content);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ---- idempotent close --------------------------------------------------------------

    @Test
    void closeIsIdempotent() throws Exception {
        AcpClient client = startClient("warm");
        client.close();
        assertDoesNotThrow(client::close);
    }

    // ---- helper: build an InProcess.Request without a public constructor -----------------

    /** {@link InProcess.Request} has no public constructor (ADR 0030) — the seam is only meant
     * to be reached via {@link InProcess#createClient} / the sub-agent runtime. For a direct,
     * single-call unit test we drive the SAME wire shape those callers build (an OpenAI-style
     * {@code messages} array) through {@link InProcess.GenerateBackedHttpClient}, which is the
     * one public adapter that turns it into a real {@link InProcess.Request}. */
    private static InProcess.Request request(List<Object> messages) {
        InProcess.Request[] captured = new InProcess.Request[1];
        InProcess.GenerateBackedHttpClient shim = new InProcess.GenerateBackedHttpClient(req -> {
            captured[0] = req;
            return InProcess.Response.content("");
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "acp-test");
        body.put("messages", messages);
        try {
            shim.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(InProcess.BASE_URL))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    io.github.muthuishere.toolnexus.Json.stringify(body)))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return captured[0];
    }
}
