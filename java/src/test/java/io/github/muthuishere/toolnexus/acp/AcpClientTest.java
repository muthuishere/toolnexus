package io.github.muthuishere.toolnexus.acp;

import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.Toolkit;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hermetic tests for {@link AcpClient} against {@link FakeAcpServer}, spawned as a real child
 * process over real OS pipes — no network, no real {@code devin}, no MCP SDK. Mirrors the six
 * ADR 0031 / {@code openspec/changes/add-acp-model-source} spec scenarios (see
 * {@code spikes/acp/SPIKE.md} for the Go reference this was built from), plus the
 * {@code openspec/changes/add-acp-tool-calling} ones: the tool-calling loop end to end, the
 * pinned prompt shape, the reply parser table, and reject-by-default permissions.
 */
class AcpClientTest {

    private static List<String> fakeServerCommand(String scenario, String... extra) {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-cp", classpath, FakeAcpServer.class.getName(),
                "--scenario=" + scenario));
        cmd.addAll(List.of(extra));
        return cmd;
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
            // FakeAcpServer's "noisy" scenario interleaves agent_thought_chunk and
            // tool_call/tool_call_update notifications around two agent_message_chunk halves of
            // {"answer":true}. Only the message-chunk halves may appear in the result: no
            // thought/tool narration, and the result must be the intact JSON (the exact
            // corruption ADR 0031 warns about). Not a reply envelope, so the parser hands the
            // original text back untouched as content (structured output the host asked for).
            assertEquals("{\"answer\":true}", resp.content);
            assertEquals(null, resp.toolCalls);
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

            // Default: the first reject-kind option — toolnexus executes tools, the agent must not.
            assertEquals("PERMITTED:reject", resp.content);
            // Well under the client's 5s timeout — the request_permission round trip is
            // answered inline by the reader thread, not awaited by the main turn.
            assertTrue(elapsedMs < 2_000, "turn took " + elapsedMs + "ms, expected well under 2000ms");
        }
    }

    @Test
    void permissionAllowedOnOptIn_selectsFirstAllowOption() throws Exception {
        try (AcpClient client = startClient("permission")) {
            client.allowAgentTools = true;
            long start = System.nanoTime();
            InProcess.Response resp = client.generate(request(List.of(userMessage("go"))));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals("PERMITTED:allow-once", resp.content);
            assertTrue(elapsedMs < 2_000, "permission was awaited, not answered: " + elapsedMs + "ms");
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

    // ---- ACP as a real tool-calling model (SPEC §8, add-acp-tool-calling) -------------------

    private static Tool addTool() {
        return NativeTool.of("add", "Add two numbers.",
                Map.of("type", "object",
                        "properties", Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number")),
                        "required", List.of("a", "b")),
                args -> String.valueOf((long) (((Number) args.get("a")).doubleValue()
                        + ((Number) args.get("b")).doubleValue())));
    }

    @Test
    void toolCallingLoopEndToEnd() throws Exception {
        Path events = Files.createTempFile("acp-events", ".ndjson");
        try (AcpClient client = AcpClient.start(fakeServerCommand("toolloop", "--events=" + events), 5_000);
             Toolkit toolkit = Toolkit.create(new Toolkit.Options())) {
            toolkit.register(addTool());
            LlmClient llm = InProcess.createClient(new InProcess.Options().model("acp").generate(client));
            LlmClient.RunResult r = llm.run("What is 2 + 3?", toolkit);

            assertEquals("The answer is 5.", r.text);
            assertEquals(1, r.toolCalls.size());
            assertEquals("add", r.toolCalls.get(0).name);
            assertEquals("5", r.toolCalls.get(0).output);
        } finally {
            List<Map<String, Object>> requests = new ArrayList<>();
            for (String line : Files.readAllLines(events)) {
                if (!line.isBlank()) requests.add(Json.toMap(line));
            }
            Files.deleteIfExists(events);
            assertEquals(2, requests.size(), "expected 2 prompts (ask, then answer)");

            // Turn 1: the tool schema reached the agent, OpenAI-shaped.
            boolean sawAdd = false;
            for (Object t : (List<?>) requests.get(0).get("tools")) {
                if (((Map<?, ?>) t).get("function") instanceof Map<?, ?> fn
                        && "add".equals(fn.get("name")) && fn.get("parameters") != null) {
                    sawAdd = true;
                }
            }
            assertTrue(sawAdd, "turn 1 tools did not carry add's schema: " + requests.get(0).get("tools"));

            // Turn 2: the assistant tool_calls message and the tool result are both there.
            boolean sawCall = false;
            boolean sawResult = false;
            for (Object m : (List<?>) requests.get(1).get("messages")) {
                Map<?, ?> mm = (Map<?, ?>) m;
                if ("assistant".equals(mm.get("role")) && mm.get("tool_calls") != null) sawCall = true;
                if ("tool".equals(mm.get("role")) && "c1".equals(mm.get("tool_call_id"))
                        && "5".equals(mm.get("content"))) sawResult = true;
            }
            assertTrue(sawCall && sawResult, "turn 2 messages missing the call (" + sawCall
                    + ") or its result (" + sawResult + "): " + requests.get(1).get("messages"));
        }
    }

    @Test
    void promptShape() {
        List<Object> parts = List.of(
                Map.of("type", "text", "text", "a <b> & c"),
                Map.of("type", "image_url"),
                Map.of("type", "text", "text", "d"));
        String p = AcpClient.assemblePrompt(request(List.of(
                Map.of("role", "system", "content", "be terse"),
                Map.of("role", "user", "content", parts))));

        int i = p.indexOf("\nREQUEST:\n");
        assertTrue(i >= 0, p);
        assertEquals(AcpClient.PREAMBLE, p.substring(0, i), "preamble drifted");
        Map<String, Object> body = FakeAcpServer.splitRequest(p);
        assertNotNull(body, "prompt does not split: " + p);
        assertTrue(p.endsWith("\n\nSUPERSEDES-ALL-PRIOR: a <b> & c d"), p);
        assertFalse(p.contains("\\u003c"), "REQUEST JSON is HTML-escaped");
        assertEquals(List.of(), body.get("tools"), "absent tools must render as []");
        assertTrue(p.contains("\nREQUEST:\n{\"messages\":"), "REQUEST JSON must lead with messages");
    }

    /** The preamble is byte-pinned by SPEC.md §8; read it back from the spec so the constant
     * cannot drift from the contract every port is held to. */
    @Test
    void preambleMatchesSpec() throws Exception {
        Path spec = Path.of("..", "SPEC.md");
        assumeTrue(Files.isReadable(spec), "SPEC.md not reachable");
        String s = Files.readString(spec);
        int i = s.indexOf("`PREAMBLE` is these seven lines");
        assertTrue(i >= 0, "SPEC.md has no ACP preamble block");
        s = s.substring(i);
        int start = s.indexOf("```\n") + "```\n".length();
        int end = s.indexOf("```", start);
        assertEquals(s.substring(start, end), AcpClient.PREAMBLE);
    }

    @Test
    void parseReply() {
        Map<String, Object> ab = Map.of("a", 2, "b", 3);
        String abStr = "{\"a\":2,\"b\":3}";
        // {name, input, expected calls as [id, name, args] triples (null = content), expected content}
        Object[][] cases = {
                {"plain prose passes through", "just text {not json", null, "just text {not json"},
                {"content envelope", "{\"content\":\"The answer is 5.\"}", null, "The answer is 5."},
                {"null content", "{\"content\":null}", null, ""},
                {"non-string content encodes", "{\"content\":{\"x\":1}}", null, "{\"x\":1}"},
                {"string arguments pre-encoded",
                        "{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"add\",\"arguments\":\"{\\\"a\\\":2,\\\"b\\\":3}\"}}]}",
                        List.of(Arrays.asList("c1", "add", abStr)), null},
                {"object arguments",
                        "{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]}",
                        List.of(Arrays.asList("c1", "add", ab)), null},
                {"fenced",
                        "```json\n{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]}\n```",
                        List.of(Arrays.asList("c1", "add", ab)), null},
                {"prose around",
                        "Calling now: {\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]} done",
                        List.of(Arrays.asList("c1", "add", ab)), null},
                {"choices envelope",
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":3}}}]}}]}",
                        List.of(Arrays.asList("c1", "add", ab)), null},
                {"message envelope", "{\"message\":{\"content\":\"hi\"}}", null, "hi"},
                {"flat call, no id, no arguments", "{\"tool_calls\":[{\"name\":\"ping\"}]}",
                        List.of(Arrays.asList(null, "ping", Map.of())), null},
                {"nameless call skipped, falls to content",
                        "{\"tool_calls\":[{\"function\":{\"arguments\":\"{}\"}}],\"content\":\"fallback\"}", null, "fallback"},
                {"structured output passes through", " {\"answer\":true} ", null, " {\"answer\":true} "},
        };
        for (Object[] tc : cases) {
            String name = (String) tc[0];
            InProcess.Response got = AcpClient.parseReply((String) tc[1]);
            List<?> wantCalls = (List<?>) tc[2];
            if (wantCalls == null) {
                assertNull(got.toolCalls, name);
                assertEquals(tc[3], got.content, name);
            } else {
                assertNull(got.content, name);
                assertNotNull(got.toolCalls, name);
                List<List<Object>> gotCalls = new ArrayList<>();
                for (InProcess.ToolCall c : got.toolCalls) gotCalls.add(Arrays.asList(c.id, c.name, c.arguments));
                assertEquals(wantCalls, gotCalls, name);
            }
        }
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
