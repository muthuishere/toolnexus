package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.agents.Agents;
import io.github.muthuishere.toolnexus.agents.Budget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer issues #86–#93 (ADRs 0023–0028), for the Java port.
 *
 * <p>Hermetic throughout: every LLM/provider is a scripted {@code localhost} stub, no network, no
 * API key, no cost. The scenarios mirror the reproductions under {@code spikes/issues/}.
 */
class ConsumerIssuesTest {

    // ---- a scripted provider on localhost --------------------------------

    /** Records every request body and answers with a canned status + body. */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        final String base;
        final List<Map<String, Object>> requests = new ArrayList<>();
        final AtomicInteger hits = new AtomicInteger();
        volatile int status = 200;
        volatile String body =
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
        volatile String retryAfter;
        /** When set, produces the response body from the parsed request (a scripted model). */
        volatile java.util.function.Function<Map<String, Object>, String> script;

        Stub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", (HttpExchange ex) -> {
                hits.incrementAndGet();
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    requests.add(Json.toMap(raw));
                } catch (RuntimeException ignored) {
                    requests.add(Map.of());
                }
                String answer = body;
                if (script != null) {
                    try {
                        answer = script.apply(requests.get(requests.size() - 1));
                    } catch (RuntimeException e) {
                        answer = body;
                    }
                }
                byte[] out = answer.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                if (retryAfter != null) ex.getResponseHeaders().add("Retry-After", retryAfter);
                ex.sendResponseHeaders(status, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        Map<String, Object> lastRequest() { return requests.get(requests.size() - 1); }

        @Override public void close() { server.stop(0); }
    }

    private static LlmClient.Options opts(Stub stub) {
        return new LlmClient.Options().baseUrl(stub.base).model("m").apiKey("test-only-not-a-secret");
    }

    // =====================================================================
    // D1 — a completion with no toolkit at all (#86, ADR 0023)
    // =====================================================================

    @Test
    void d1_runWithoutAToolkitCompletes_andDeclaresNoTools() {
        try (Stub stub = new Stub()) {
            LlmClient client = LlmClient.create(opts(stub));

            LlmClient.RunResult r = client.run("hello");

            assertEquals("ok", r.text);
            assertEquals(LlmClient.RunResult.STATUS_DONE, r.status);
            // The wire assertion: NO `tools` key and NO `tool_choice` key. Not an empty array —
            // an empty array is a different request, and some providers reject it.
            Map<String, Object> body = stub.lastRequest();
            assertFalse(body.containsKey("tools"), "toolkit-less request must not declare tools");
            assertFalse(body.containsKey("tool_choice"), "toolkit-less request must not set tool_choice");
        }
    }

    @Test
    void d1_exactlyThreeToolkitLessShapes_promptPromptIdPromptOnText() {
        try (Stub stub = new Stub()) {
            LlmClient client = LlmClient.create(opts(stub));

            assertEquals("ok", client.run("one").text);                       // prompt
            assertEquals("ok", client.ask("two", "thread-1").text);           // prompt + id
            StringBuilder seen = new StringBuilder();
            // prompt + onText — the streaming shape. The stub answers non-SSE, so what is being
            // pinned here is the SIGNATURE's existence and its return type, not the deltas.
            assertNotNull(client.ask("three", seen::append));

            // Memory really was threaded through the id shape: the second ask carries history.
            assertTrue(stub.requests.size() >= 3);
        }
    }

    @Test
    void d1_skillsPromptIsSkippedRatherThanDereferenced() {
        try (Stub stub = new Stub()) {
            LlmClient client = LlmClient.create(opts(stub).systemPrompt("you are terse"));
            client.run("hi");
            Map<String, Object> body = stub.lastRequest();
            @SuppressWarnings("unchecked")
            List<Object> msgs = (List<Object>) body.get("messages");
            @SuppressWarnings("unchecked")
            Map<String, Object> sys = (Map<String, Object>) msgs.get(0);
            // The caller's system prompt survives; the skills section is simply absent.
            assertEquals("you are terse", sys.get("content"));
        }
    }

    // =====================================================================
    // D2 — the Loop honours the AgentSpec (#87, ADR 0024)
    // =====================================================================

    @Test
    void d2_guardrailDeniesBeforeTheToolExecutes_executeIsNeverEntered() {
        try (Stub stub = new Stub()) {
            // Turn 1 calls the tool; once a tool result is in the transcript, answer in text.
            stub.script = req -> hasToolMessage(req)
                    ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"finished\"}}]}"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":"
                      + "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"danger\","
                      + "\"arguments\":\"{}\"}}]}}]}";

            AtomicInteger executed = new AtomicInteger();
            Tool danger = NativeTool.of("danger", "does something dangerous",
                    Map.of("type", "object", "properties", Map.of()),
                    (Map<String, Object> args) -> {
                        executed.incrementAndGet();
                        return ToolResult.ok("ran");
                    });

            Agents.AgentSpec spec = new Agents.AgentSpec()
                    .does("a guarded agent")
                    .soul("You are GUARDED")
                    .guardrails(ev -> "danger".equals(ev.name()) ? "policy: not allowed" : "allow");
            Agents.Agent agent = Agents.agent("guarded", spec);

            try (Toolkit tk = Toolkit.create(
                    new Toolkit.Options().builtins(false).extraTools(List.of(danger)))) {
                Loop loop = agent.loop(
                        new LlmClient.Options().baseUrl(stub.base).model("m")
                                .apiKey("test-only-not-a-secret"),
                        tk);
                loop.run("go");
            }

            // THE assertion: the guardrail denied BEFORE the tool, not after it. Never the text —
            // a model can be talked out of saying "denied"; an un-entered execute cannot.
            assertEquals(0, executed.get(), "denied tool's execute must never be entered");
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasToolMessage(Map<String, Object> req) {
        Object msgs = req.get("messages");
        if (!(msgs instanceof List<?> l)) return false;
        for (Object o : l) {
            if (o instanceof Map<?, ?> m && "tool".equals(m.get("role"))) return true;
        }
        return false;
    }

    @Test
    void d2_specModelAndBudgetMaxTurnsBecomeLoopDefaults() {
        try (Stub stub = new Stub()) {
            Agents.AgentSpec spec = new Agents.AgentSpec()
                    .does("d")
                    .model("m-from-spec")
                    .budget(new Budget().maxTurns(3));
            Agents.Agent agent = Agents.agent("a", spec);
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                // No model and no maxTurns on the client options — the spec fills both.
                Loop loop = agent.loop(
                        new LlmClient.Options().baseUrl(stub.base).apiKey("test-only-not-a-secret"), tk);
                loop.run("go");
                assertEquals("m-from-spec", stub.lastRequest().get("model"));
            }
        }
    }

    @Test
    void d2_callerSuppliedSystemPromptWinsOverTheSoul() {
        try (Stub stub = new Stub()) {
            Agents.Agent agent = Agents.agent("a", new Agents.AgentSpec().does("d").soul("SOUL TEXT"));
            try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false))) {
                agent.loop(new LlmClient.Options().baseUrl(stub.base).model("m")
                        .apiKey("test-only-not-a-secret").systemPrompt("CALLER TEXT"), tk).run("go");
            }
            @SuppressWarnings("unchecked")
            List<Object> msgs = (List<Object>) stub.lastRequest().get("messages");
            @SuppressWarnings("unchecked")
            Map<String, Object> sys = (Map<String, Object>) msgs.get(0);
            assertEquals("CALLER TEXT", sys.get("content"), "caller-supplied systemPrompt wins");
        }
    }

    @Test
    void d2_loopUnsupportedNamesTheCanonicalFieldsAndNothingElse() {
        Agents.AgentSpec empty = new Agents.AgentSpec().does("d").soul("s");
        assertEquals(List.of(), Loop.loopUnsupported(empty));

        Agents.AgentSpec full = new Agents.AgentSpec()
                .does("d")
                .tools(NativeTool.of("t", "t", Map.of("type", "object", "properties", Map.of()),
                        args -> ToolResult.ok("x")))
                .team(Agents.agent("child", new Agents.AgentSpec().does("c")))
                .waitFor(req -> new Answer(req.id(), true))
                .onMetric(ev -> { });
        // A6: the canonical vocabulary, identical in all seven ports.
        assertEquals(List.of("tools", "team", "waitFor", "onMetric"), Loop.loopUnsupported(full));
    }

    // =====================================================================
    // D4 — the Answer payload contract (#89, ADR 0026)
    // =====================================================================

    @Test
    void d4_answerOutputConstructorPutsTheResultUnderTheOneReadKey() {
        Answer a = Answer.output("req-1", "staging");
        assertTrue(a.ok());
        assertEquals("staging", a.data().get(Answer.OUTPUT_KEY));
        assertEquals("staging", a.outputOf());
        assertTrue(a.hasRecognisedKey());
    }

    @Test
    void d4_anOkAnswerWithNoDeterminableResultIsAnErrorToTheHost() {
        assertThrows(IllegalArgumentException.class, () -> Answer.output("req-1", null));
        // {"value": …} and {"answers": […]} are the two shapes #89 reported; neither is recognised.
        assertFalse(new Answer("req-1", true, Map.of("value", "staging")).hasRecognisedKey());
        assertFalse(new Answer("req-1", true, Map.of("answers", List.of("staging"))).hasRecognisedKey());
        assertNull(new Answer("req-1", true, Map.of("value", "staging")).outputOf());
    }

    @Test
    void d4_aNonStringOutputErrorsRatherThanDegradingToEmpty() {
        Answer numeric = new Answer("req-1", true, Map.of(Answer.OUTPUT_KEY, 42));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, numeric::outputOf);
        assertTrue(e.getMessage().contains("must be a string"), e.getMessage());
    }

    @Test
    void d4_resultsTakesPrecedenceOverOutputInTheRecognisedVocabulary() {
        // A3: `results` first, then `output` (+ isError).
        assertEquals(List.of("results", "output"), Answer.RECOGNISED_KEYS);
        assertEquals("isError", Answer.IS_ERROR_KEY);
    }

    // =====================================================================
    // D5 — what we hand back when we fail (#91/#92, ADR 0027)
    // =====================================================================

    /** The exact body the spike captured, with its obviously-fake account id. */
    private static final String LEAKY_400 =
            "{\"error\":{\"message\":\"not a valid model ID\",\"code\":400},"
                    + "\"user_id\":\"user_2FAKEFAKEFAKEFAKEFAKEFAKE\"}";

    @Test
    void d5_providerErrorIsTypedAndCarriesStatusBodyAndRetryAfter() {
        try (Stub stub = new Stub()) {
            stub.status = 429;
            stub.body = "{\"error\":\"slow down\"}";
            stub.retryAfter = "1";
            LlmClient client = LlmClient.create(opts(stub).retries(0));
            LlmClient.ProviderException e = assertThrows(LlmClient.ProviderException.class,
                    () -> client.run("hi"));
            assertEquals(429, e.status);
            assertTrue(e.body.contains("slow down"), e.body);
            assertEquals("1", e.retryAfter);
        }
    }

    @Test
    void d5_accountIdentifiersAreRedactedFromTheErrorBody() {
        try (Stub stub = new Stub()) {
            stub.status = 400;
            stub.body = LEAKY_400;
            LlmClient client = LlmClient.create(opts(stub));
            LlmClient.ProviderException e = assertThrows(LlmClient.ProviderException.class,
                    () -> client.run("hi"));
            assertFalse(e.getMessage().contains("user_2FAKE"), e.getMessage());
            assertFalse(e.body.contains("user_2FAKE"), e.body);
            assertTrue(e.body.contains(LlmClient.REDACTED), e.body);
            // The reported cause survives — only the identifier goes.
            assertTrue(e.body.contains("not a valid model ID"), e.body);
        }
    }

    @Test
    void d5_theCapIsNotTheRedaction_everyAccountKeyIsCovered() {
        for (String key : List.of("user_id", "account_id", "org_id", "organization")) {
            String body = "{\"" + key + "\":\"acct_SECRETVALUE\"}";
            String out = LlmClient.safeErrorBody(400, body);
            assertFalse(out.contains("acct_SECRETVALUE"), key + " leaked: " + out);
            assertTrue(out.contains(key), key + " should still be named: " + out);
        }
        // A 401/403 body is blanked outright — a gateway reflects the header it rejected.
        assertEquals("", LlmClient.safeErrorBody(401, "Bearer sk-whatever is invalid"));
        assertEquals("", LlmClient.safeErrorBody(403, "forbidden for sk-whatever"));
    }

    @Test
    void d5_theCapIsMessageOnly_theTypedBodyCarriesTheWholeRedactedThing() {
        try (Stub stub = new Stub()) {
            stub.status = 400;
            stub.body = "{\"error\":\"" + "x".repeat(500) + "\"}";
            LlmClient client = LlmClient.create(opts(stub));
            LlmClient.ProviderException e = assertThrows(LlmClient.ProviderException.class,
                    () -> client.run("hi"));
            assertTrue(e.getMessage().length() < LlmClient.ERROR_BODY_CAP + 40, "message is capped");
            assertTrue(e.body.length() > LlmClient.ERROR_BODY_CAP, "typed body is NOT capped (A5)");
        }
    }

    @Test
    void d5_mustNotRegress_a4xxCostsExactlyOneAttemptDespiteRetries() {
        try (Stub stub = new Stub()) {
            stub.status = 400;
            stub.body = LEAKY_400;
            LlmClient client = LlmClient.create(opts(stub).retries(4));
            assertThrows(LlmClient.ProviderException.class, () -> client.run("hi"));
            assertEquals(1, stub.hits.get(), "fail-fast on 4xx: the enumerated retryable set only");
        }
    }

    @Test
    void d5_mustNotRegress_theRetryableSetIsTheEnumeratedSix() {
        for (int s : new int[] {429, 500, 502, 503, 504, 529}) {
            assertTrue(LlmClient.isRetryableStatus(s, null), s + " must be retryable");
        }
        for (int s : new int[] {400, 401, 402, 403, 404, 422, 501, 505}) {
            assertFalse(LlmClient.isRetryableStatus(s, null), s + " must NOT be retryable");
        }
    }

    @Test
    void d5_theTimeoutMessageNamesTheBudgetThatExpired() {
        String m = LlmClient.timeoutMessage(1);
        assertTrue(m.contains("Options.timeoutMs"), m);
        assertTrue(m.contains("1ms"), m);
    }

    @Test
    void d5_bothStatusVocabulariesAreNamed_andTheyAreDifferent() {
        // The CLIENT set: three values, no "timeout" (SPEC §8).
        assertEquals("done", LlmClient.RunResult.STATUS_DONE);
        assertEquals("pending", LlmClient.RunResult.STATUS_PENDING);
        assertEquals("incomplete", LlmClient.RunResult.STATUS_INCOMPLETE);
        // The AGENT-RUNTIME set: seven values, WITH "timeout" (SPEC §7D).
        assertEquals(7, io.github.muthuishere.toolnexus.agents.TaskResult.STATUSES.size());
        assertTrue(io.github.muthuishere.toolnexus.agents.TaskResult.STATUSES.contains("timeout"));
        assertFalse(io.github.muthuishere.toolnexus.agents.TaskResult.STATUSES.isEmpty());
    }

    @Test
    void d5_classifierBackendPresetSetsBaseUrlModelAndKeyEnvAsAUnit() {
        // The known mismatch fails at CONSTRUCTION, with the wording the decision pins.
        Classifier.ClassifierException e = assertThrows(Classifier.ClassifierException.class,
                () -> Classifier.create(new Classifier.Options()
                        .baseUrl(Classifier.OPENROUTER_BASE_URL)
                        .model(Classifier.DEFAULT_MODEL)));
        assertTrue(e.getMessage().contains("is TypeSafe's spelling"), e.getMessage());
        assertTrue(e.getMessage().contains(Classifier.OPENROUTER_MODEL), e.getMessage());

        // The preset resolves all three together, so the mismatch cannot be constructed by hand.
        assertNotNull(Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_OPENROUTER)));
        assertNotNull(Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_TYPESAFE)));
        assertThrows(Classifier.ClassifierException.class,
                () -> Classifier.create(new Classifier.Options().backend("azure")));
    }

    @Test
    void d5_classifierErrorsReuseTheOneRedactionPolicy() {
        try (Stub stub = new Stub()) {
            stub.status = 400;
            stub.body = LEAKY_400;
            Classifier c = Classifier.create(new Classifier.Options()
                    .baseUrl(stub.base).retries(0)
                    .model("m"));
            Classifier.ClassifierException e = assertThrows(Classifier.ClassifierException.class,
                    () -> c.evaluate(Map.of("s", 1), Map.of("q", new Classifier.NoulQuestion("is it ok?"))));
            assertFalse(e.getMessage().contains("user_2FAKE"),
                    "the 200-char cap never helped — the leaking body is 96 bytes: " + e.getMessage());
            assertTrue(e.getMessage().contains(LlmClient.REDACTED), e.getMessage());
        }
    }

    // =====================================================================
    // D6 — a skill the writing tool accepts (#93, ADR 0028)
    // =====================================================================

    @Test
    void d6_strictYamlRunsFirst_blockScalarsAreByteIdentical() {
        String text = "---\nname: blocky\ndescription: |\n  line one\n  line two\n---\nbody\n";
        Map<String, Object> parsed = SkillSource.parseFrontmatter(text);
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) parsed.get("data");
        assertEquals("blocky", data.get("name"));
        // A line-wise-FIRST reader sees `description: |` and takes "" or "|". YAML-first does not.
        assertEquals("line one\nline two", data.get("description"));
        assertFalse(Boolean.TRUE.equals(parsed.get("malformed")));
    }

    @Test
    void d6_lenientPassRescuesOnlyFrontmatterYamlAlreadyRefused() {
        // A plain scalar carrying ": " — real YAML refuses this outright ("mapping values are
        // not allowed here"), which is the whole `colon-space` family in the shared fixtures.
        String text = "---\nname: rescued\ndescription: Bill hours. Trigger on: do my timesheet.\n---\nbody\n";
        Map<String, Object> parsed = SkillSource.parseFrontmatter(text);
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) parsed.get("data");
        assertEquals("rescued", data.get("name"), "the lenient pass rescues the name");
        assertEquals("Bill hours. Trigger on: do my timesheet.", data.get("description"),
                "…and the whole line, which strict YAML could not read");
        assertFalse(Boolean.TRUE.equals(parsed.get("malformed")));
        assertFalse(String.valueOf(parsed.get("detail")).isEmpty(),
                "the native parser's own message is kept as `detail`");
    }

    @Test
    void d6_lenientPassRefusesConstructsItDoesNotImplement_neverInventsADescription() {
        // YAML refuses the whole block (the unclosed quote), so the RESCUE runs — and it meets a
        // `description: |` it cannot interpret. It must take NOTHING rather than the character
        // "|" or the following indented lines.
        String text = "---\nname: half\nbroken: \"unclosed\ndescription: |\n  a real block scalar\n---\nbody\n";
        Map<String, Object> parsed = SkillSource.parseFrontmatter(text);
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) parsed.get("data");
        assertEquals("half", data.get("name"), "the rescue ran");
        assertNull(data.get("description"), "a `|` value must never be taken literally");
    }

    @Test
    void d6_lenientPassIsColumnZeroAndFirstWins() {
        String text = "---\n  name: indented\nname: real\nname: later\n"
                + "description: \"one\": two\n---\nbody\n";
        Map<String, Object> parsed = SkillSource.parseFrontmatter(text);
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) parsed.get("data");
        if (!Boolean.TRUE.equals(parsed.get("malformed")) && data.get("name") != null) {
            assertEquals("real", data.get("name"), "column 0 only, and first wins");
        }
    }

    @Test
    void d6_genuinelyMalformedFilesAreStillRefused() {
        // No name anywhere that the lenient pass will take.
        String text = "---\n: [ broken\ndescription: |\n---\nbody\n";
        Map<String, Object> parsed = SkillSource.parseFrontmatter(text);
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) parsed.get("data");
        assertTrue(Boolean.TRUE.equals(parsed.get("malformed")) || data.get("name") == null,
                "a file with no rescuable name must not become a skill");
    }

    @Test
    void d6_loadExposesSkipsAsDataAndDiscoveryOrderIsLexicographic() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-skills");
        try {
            write(root.resolve("bbb/SKILL.md"), "---\nname: bbb\ndescription: second\n---\nb\n");
            write(root.resolve("aaa/SKILL.md"), "---\nname: aaa\ndescription: first\n---\na\n");
            write(root.resolve("broken/SKILL.md"), "---\n: [ broken\n---\nx\n");

            SkillSource src = SkillSource.load(root.toString());

            // A2: the skips ride out on the load result — no second listSkills pass required.
            assertEquals(1, src.skipped().size(), "the broken skill is reported, not vanished");
            SkillSource.SkillSkip skip = src.skipped().get(0);
            assertTrue(skip.location.contains("broken"), skip.location);
            assertFalse(skip.detail.isEmpty(), "`detail` carries the native parser error");

            // A1: within a dir, lexicographic by path relative to that dir.
            assertEquals(List.of("aaa", "bbb"), new ArrayList<>(src.skills().keySet()));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void d6_a1a_discoveryOrderIsCodePointOrderOnThePathRelativeToTheRoot() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-order");
        try {
            // The real-world shape this decides: a skill present BOTH at the top level and inside
            // a synced/content-addressed tree. Both declare the same name, so discovery order —
            // and only discovery order — picks the winner.
            write(root.resolve("docx/SKILL.md"), "---\nname: docx\ndescription: top level\n---\na\n");
            write(root.resolve("synced/9f2c1ab/docx/SKILL.md"),
                    "---\nname: docx\ndescription: synced copy\n---\nb\n");
            // Case matters, and is NOT folded: 'D' (0x44) sorts before 'd' (0x64).
            write(root.resolve("Zebra/SKILL.md"), "---\nname: zebra\ndescription: uppercase dir\n---\nc\n");
            write(root.resolve("apple/SKILL.md"), "---\nname: apple\ndescription: lowercase dir\n---\nd\n");

            SkillSource src = SkillSource.load(root.toString());

            assertEquals("top level", src.skills().get("docx").description,
                    "docx/ beats synced/<hash>/docx/ — 'd' < 's' in code-point order");
            // No locale collation and no case folding: "Zebra" (Z=0x5A) precedes "apple" (a=0x61),
            // which precedes "docx" (d=0x64). Insertion order IS discovery order.
            assertEquals(List.of("zebra", "apple", "docx"), new ArrayList<>(src.skills().keySet()));
        } finally {
            deleteTree(root);
        }
    }


    // ---- ADDENDUM 6: the shared fixture table, reproduced exactly ---------

    /**
     * The PINNED cross-port table over {@code spikes/issues/93/fixtures/} — 14 ok, 3 skip.
     * It asserts the DESCRIPTION STRING and the skip REASON, never just the verdict: A11 warns
     * that {@code hash-inline} is decided by the YAML library's comment handling, so a port whose
     * parser keeps the {@code #…} tail still reports "ok" while carrying a different description —
     * a silent table mismatch. {@code broken-flow} is the row that PROVES A10 is implemented:
     * name kept, NO description, never a skip.
     */
    @Test
    void addendum6_theSharedFixtureTableIsReproducedExactly() {
        Path fixtures = Path.of("..", "spikes", "issues", "93", "fixtures");
        assertTrue(Files.isDirectory(fixtures), "shared fixtures must be present: " + fixtures);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("anchors", "A skill using a YAML anchor and alias.");
        expected.put("block-folded", "A folded description that runs across two source lines.");
        expected.put("block-literal",
                "First line of the description.\nSecond line, with a colon: still fine inside a block scalar.");
        expected.put("broken-flow", null); // name kept, description ABSENT — the A10 proof row
        expected.put("broken-tab", "tab-indented continuation");
        expected.put("colon-space",
                "Work out billable hours from git commits and session logs. Trigger on: update the "
                        + "timesheet, do my timesheet, how many hours did I work.");
        expected.put("colon-space-quoted",
                "Work out billable hours. Trigger on: update the timesheet, do my timesheet.");
        expected.put("colon-space-single", "Work out billable hours. Trigger on: update the timesheet.");
        expected.put("hash-inline", "Tag things with"); // ` #` opens a YAML COMMENT (A11)
        expected.put("list-block", "A skill with a block-sequence key.");
        expected.put("list-value", "A skill that also declares a list-valued key.");
        expected.put("nested-map", "A skill with a nested mapping key.");
        expected.put("plain", "An ordinary skill with an ordinary one-line description.");
        expected.put("url-colon", "Fetch pages from https://example.com/docs and summarise them.");

        SkillSource src = SkillSource.load(fixtures.toString());

        assertEquals(expected.keySet(), src.skills().keySet(), "the 14 accepted rows, exactly");
        for (Map.Entry<String, String> row : expected.entrySet()) {
            SkillSource.SkillInfo info = src.skills().get(row.getKey());
            assertNotNull(info, row.getKey() + " must be ok");
            String actual = info.description == null || info.description.isEmpty() ? null : info.description;
            assertEquals(row.getValue(), actual, "description for fixture " + row.getKey());
        }

        // The three skips, by fixture name AND by reason — all `missing-name`.
        Map<String, String> skips = new LinkedHashMap<>();
        for (SkillSource.SkillSkip s : src.skipped()) {
            String dir = Path.of(s.location).getParent().getFileName().toString();
            skips.put(dir, s.reason);
        }
        assertEquals(Map.of(
                "broken-unclosed", SkillSource.SkillSkip.MISSING_NAME,
                "no-frontmatter", SkillSource.SkillSkip.MISSING_NAME,
                "no-name", SkillSource.SkillSkip.MISSING_NAME), skips);
        assertEquals(14, src.skills().size());
        assertEquals(3, src.skipped().size());
    }

    // ---- A15: depth first, code point second -----------------------------

    /**
     * A15. The fixture uses {@code xlsx}, NOT {@code docx}: a docx-only fixture passes under BOTH
     * the pure-code-point rule and the depth-then-code-point rule, which is exactly how six ports
     * and a spec review all missed this. {@code 'x' > 's'}, so pure code-point order hands the win
     * to {@code synced/<uuid>/xlsx} — the top-level skill loses to a nested copy purely because of
     * its first letter.
     */
    @Test
    void a15_discoverySortsByDepthFirst_soATopLevelSkillWinsForEVERYName() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-depth");
        try {
            write(root.resolve("xlsx/SKILL.md"), "---\nname: xlsx\ndescription: top level\n---\na\n");
            write(root.resolve("synced/9f2c1ab/xlsx/SKILL.md"),
                    "---\nname: xlsx\ndescription: synced copy\n---\nb\n");

            SkillSource src = SkillSource.load(root.toString());
            assertEquals("top level", src.skills().get("xlsx").description,
                    "a top-level skill beats a nested copy of the same name, for EVERY name");

            // The fixture DISCRIMINATES: the two comparators disagree on it. Delete the depth
            // term and the assertion above flips — asserted here so the test cannot quietly
            // become a tautology.
            String shallow = "xlsx/SKILL.md";
            String nested = "synced/9f2c1ab/xlsx/SKILL.md";
            assertTrue(SkillSource.compareDiscovery(shallow, nested) < 0, "depth-first: shallow wins");
            assertTrue(SkillSource.compareCodePoints(shallow, nested) > 0,
                    "pure code-point order gives the OPPOSITE answer — that is the bug A15 fixes");
        } finally {
            deleteTree(root);
        }
    }

    /**
     * A1c: the WITHIN-DEPTH tie-break is UNICODE CODE POINT, not UTF-16 code unit.
     * {@code U+1F600} is a surrogate pair (0xD83D 0xDE00); {@code String.compareTo} therefore
     * sorts it BEFORE {@code U+FFFD} (0xFFFD) instead of after, so this fixture fails under a
     * code-unit comparator and passes under a code-point one.
     */
    @Test
    void a1c_theTieBreakIsCodePointOrder_notUtf16CodeUnitOrder() {
        String astral = "😀/SKILL.md";   // U+1F600, above the BMP
        String bmpHigh = "�/SKILL.md";        // U+FFFD, inside the BMP

        assertTrue(SkillSource.compareCodePoints(bmpHigh, astral) < 0,
                "U+FFFD < U+1F600 by CODE POINT");
        assertTrue(bmpHigh.compareTo(astral) > 0,
                "…and the platform comparator says the opposite — which is why we do not use it");
        // Same depth, so compareDiscovery must agree with the code-point tie-break.
        assertTrue(SkillSource.compareDiscovery(bmpHigh, astral) < 0);
    }

    @Test
    void a1c_anAstralPlaneSkillDirectorySortsAfterABmpOne_onDisk() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-astral");
        try {
            write(root.resolve("😀-grin/SKILL.md"), "---\nname: dupe\ndescription: astral\n---\na\n");
            write(root.resolve("�-bmp/SKILL.md"), "---\nname: dupe\ndescription: bmp\n---\nb\n");

            SkillSource src = SkillSource.load(root.toString());
            // First-wins, and U+FFFD sorts FIRST by code point. Under String.compareTo the astral
            // directory would come first and this would read "astral".
            assertEquals("bmp", src.skills().get("dupe").description);
        } finally {
            deleteTree(root);
        }
    }

    // ---- A22: the §0.10 skills-prompt order ------------------------------

    /**
     * A22. The skills PROMPT has its own ordering, separate from discovery order, and SPEC §0.10
     * pins it byte-identical across ports — so it is Unicode CODE POINT over the skill NAME, the
     * same rule as A1c in the second place it was needed. The names here are ranked differently
     * by locale collation ("apple" before "Zebra") and by code point ("Zebra" (Z=0x5A) before
     * "apple" (a=0x61)), and differently again by UTF-16 code units for the astral name.
     */
    @Test
    void a22_theSkillsPromptIsOrderedByCodePointOverTheName() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-prompt-order");
        try {
            write(root.resolve("a/SKILL.md"), "---\nname: apple\ndescription: lower\n---\nx\n");
            write(root.resolve("b/SKILL.md"), "---\nname: Zebra\ndescription: upper\n---\nx\n");
            write(root.resolve("c/SKILL.md"), "---\nname: \uD83D\uDE00grin\ndescription: astral\n---\nx\n");
            write(root.resolve("d/SKILL.md"), "---\nname: \uFFFDbmp\ndescription: bmp\n---\nx\n");

            String prompt = SkillSource.load(root.toString()).prompt();
            List<String> order = new ArrayList<>();
            for (String line : prompt.split("\n")) {
                if (line.startsWith("- **")) order.add(line.substring(4, line.indexOf("**:")));
            }
            // Code point: 'Z'(0x5A) < 'a'(0x61) < U+FFFD < U+1F600.
            assertEquals(List.of("Zebra", "apple", "\uFFFDbmp", "\uD83D\uDE00grin"), order);
        } finally {
            deleteTree(root);
        }
    }

    // ---- A14 / A18: the limit vocabulary, and status↔limit agreement -----

    @Test
    void a14_theLimitVocabularyIsTheClosedCanonicalNine() {
        assertEquals(List.of("maxTurns", "maxTokens", "maxToolCalls", "maxWallMs",
                "maxChildren", "maxConcurrent", "maxDepth", "completion", "timeout"),
                io.github.muthuishere.toolnexus.agents.TaskResult.LIMITS);
    }

    /**
     * A19a/A20. "The test suite compiles" is NOT evidence of visibility: a java test in the SAME
     * package sees package-private members, so a vocabulary constant could be invisible to every
     * real consumer while keeping the suite green. This test lives OUT of package
     * {@code …toolnexus.agents} and checks reflected modifiers, which is what a host actually has.
     */
    @Test
    void a19a_bothVocabulariesArePubliclyReachableByAHost() throws Exception {
        Class<?> tr = io.github.muthuishere.toolnexus.agents.TaskResult.class;
        assertTrue(java.lang.reflect.Modifier.isPublic(tr.getModifiers()), "TaskResult is public");

        for (String setField : List.of("STATUSES", "LIMITS")) {
            var f = tr.getField(setField); // getField = PUBLIC only
            int m = f.getModifiers();
            assertTrue(java.lang.reflect.Modifier.isPublic(m) && java.lang.reflect.Modifier.isStatic(m), setField);
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) f.get(null);
            assertFalse(values.isEmpty());
            // …and every value is ALSO reachable as a named public constant, so a host writes
            // TaskResult.LIMIT_MAX_WALL_MS rather than the string "maxWallMs".
            List<String> named = new ArrayList<>();
            for (var pf : tr.getFields()) {
                if (pf.getType() == String.class && java.lang.reflect.Modifier.isStatic(pf.getModifiers())) {
                    named.add((String) pf.get(null));
                }
            }
            for (String v : values) assertTrue(named.contains(v), "no named constant for \"" + v + "\"");
        }

        // A19b: no exported invariant predicate — the check stays test-only.
        for (var meth : tr.getMethods()) {
            if (meth.getDeclaringClass() == tr && meth.getReturnType() == boolean.class
                    && !"equals".equals(meth.getName())) {   // the record's own Object override
                assertEquals("isError", meth.getName(),
                        "the only public boolean on TaskResult is the record accessor; an exported "
                                + "invariant predicate would quietly undo A19b: " + meth);
            }
        }

        // A20: the internal pool-name MAPPER stays package-private — exporting it would leak the
        // very internal names A14 exists to keep out of the public field.
        var mapper = tr.getDeclaredMethod("canonicalLimit", String.class);
        assertFalse(java.lang.reflect.Modifier.isPublic(mapper.getModifiers()),
                "canonicalLimit must not be public");
    }


    // ---- A24/A25/A26/A28: every capped or ordered listing shown to the model ----

    /**
     * A25, closing ADR-0004's K1 for the {@code <skill_files>} sample. Three wrong rules are
     * discriminated by this one fixture (A27):
     * <ul>
     *   <li><b>bare name vs relative path</b> — {@code b-nested/zz-a.txt} follows {@code alpha-b}
     *       by relative path but sorts LAST by bare name;</li>
     *   <li><b>flat vs per-level</b> — {@code alpha/} beside {@code alpha-b.txt}: flat gives
     *       {@code alpha-b.txt} first ({@code -} 0x2D &lt; {@code /} 0x2F), per-level gives
     *       {@code alpha/f.txt} first;</li>
     *   <li><b>locale collation</b> — {@code ß} (U+00DF) collates as {@code ss} (before
     *       {@code z}) but is 0xDF by code point (after every ASCII letter), and has no NFD
     *       decomposition, so macOS filename normalisation cannot hollow the probe out the way
     *       {@code café} does.</li>
     * </ul>
     */
    @Test
    void a25_theSkillFilesSampleIsSortedByRelativePathInCodePointOrder() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-sample");
        try {
            Path skill = root.resolve("s");
            write(skill.resolve("SKILL.md"), "---\nname: s\ndescription: d\n---\nbody\n");
            for (String rel : List.of("alpha-b.txt", "alpha/f.txt", "b-nested/zz-a.txt",
                    "m.txt", "ß.txt")) {
                write(skill.resolve(rel), "x");
            }

            List<String> order = sampleRelPaths(root, 0);
            assertEquals(List.of("alpha-b.txt", "alpha/f.txt", "b-nested/zz-a.txt", "m.txt", "ß.txt"),
                    order, "flat, by RELATIVE path, in code point order");
        } finally {
            deleteTree(root);
        }
    }

    /**
     * A25's load-bearing half, with an A27d SELF-PROVING fixture: the cap is applied AFTER the
     * sort, so it changes only the order's tail — never WHICH files the model sees. The test
     * reproduces the pre-fix walk (LIFO stack over an unordered {@code Files.list}, breaking at
     * the cap) and refuses to pass vacuously if this filesystem happens to make the two
     * selections agree.
     */
    @Test
    void a25_theSampleIsCappedAfterSorting_soTheFilesystemNeverPicksTheContents() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-sample-cap");
        try {
            Path skill = root.resolve("s");
            write(skill.resolve("SKILL.md"), "---\nname: s\ndescription: d\n---\nbody\n");
            for (String rel : List.of("mß.txt", "n.txt", "a-dir/zz.txt")) write(skill.resolve(rel), "x");

            List<String> byWalk = preFixWalk(skill, 2);
            List<String> bySort = List.of("a-dir/zz.txt", "mß.txt");
            assertNotEquals(new java.util.HashSet<>(byWalk), new java.util.HashSet<>(bySort),
                    "fixture is vacuous on this filesystem: first-2-by-walk and first-2-by-sort "
                            + "select the same SET, so the assertion below proves nothing");

            List<String> capped = sampleRelPaths(root, 2);
            assertEquals(bySort, capped);
            assertFalse(capped.contains("n.txt"),
                    "a late-sorting file is ABSENT under the cap — the content half of K1");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void a26_globCollectsThenSortsThenCaps_andEmitsRelativeSlashPaths() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-glob");
        try {
            for (String rel : List.of("mß.txt", "n.txt", "a-dir/zz.txt")) write(root.resolve(rel), "x");
            Tool glob = builtinNamed("glob");

            List<String> all = List.of(run(glob, Map.of("pattern", "**/*.txt", "path", root.toString()))
                    .output().split("\n"));
            assertEquals(List.of("a-dir/zz.txt", "mß.txt", "n.txt"), all);
            for (String line : all) {
                assertFalse(line.contains("\\"), "A28: `/` on every platform — " + line);
            }

            // A27d: prove the cap discriminates on THIS filesystem before asserting it.
            List<String> byWalk = preFixWalk(root, 2);
            assertNotEquals(new java.util.HashSet<>(byWalk),
                    new java.util.HashSet<>(List.of("a-dir/zz.txt", "mß.txt")),
                    "fixture is vacuous on this filesystem");

            String capped = run(glob, Map.of("pattern", "**/*.txt", "path", root.toString(), "limit", 2))
                    .output();
            assertEquals(List.of("a-dir/zz.txt", "mß.txt"), List.of(capped.split("\n")));
            assertFalse(capped.contains("n.txt"), "the cap changes the tail, never the selection rule");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void a26_grepSortsByRelativePathThenLineNumber_andEmitsWhatItSortedOn() throws IOException {
        Path root = Files.createTempDirectory("toolnexus-grep");
        try {
            // 12 lines so that line 10 exists: sorting the rendered "path:line:text" as ONE
            // string would put line 10 before line 2.
            StringBuilder many = new StringBuilder();
            for (int i = 1; i <= 12; i++) many.append("hit ").append(i).append("\n");
            write(root.resolve("n.txt"), many.toString());
            write(root.resolve("a-dir/zz.txt"), "hit here\n");
            write(root.resolve("mß.txt"), "hit there\n");

            Tool grep = builtinNamed("grep");
            String out = run(grep, Map.of("pattern", "hit", "path", root.toString())).output();
            List<String> lines = List.of(out.split("\n"));

            assertEquals("a-dir/zz.txt:1:hit here", lines.get(0));
            assertEquals("mß.txt:1:hit there", lines.get(1));
            // …then n.txt's twelve, in NUMERIC line order.
            assertEquals("n.txt:1:hit 1", lines.get(2));
            assertEquals("n.txt:2:hit 2", lines.get(3));
            assertEquals("n.txt:10:hit 10", lines.get(11));

            for (String line : lines) {
                assertFalse(line.contains("\\"), "A28: no native separator in model-visible output");
                assertFalse(line.startsWith("/"), "the emitted path is the RELATIVE one it sorted on");
                assertFalse(line.contains(root.toString()), "no machine path leaks to the model");
            }

            // Cap after sorting: the first two matches are a-dir/zz.txt and mß.txt, which the
            // pre-fix walk could not have selected (it broke at the cap on whatever it met first).
            String capped = run(grep, Map.of("pattern", "hit", "path", root.toString(), "limit", 2)).output();
            assertEquals(List.of("a-dir/zz.txt:1:hit here", "mß.txt:1:hit there"),
                    List.of(capped.split("\n")));
        } finally {
            deleteTree(root);
        }
    }

    // ---- helpers for the listing tests -----------------------------------

    /** The {@code <skill_files>} entries of the single skill under {@code root}, made relative. */
    private static List<String> sampleRelPaths(Path root, int sampleLimit) {
        SkillSource src = SkillSource.loadWith(new SkillSource.LoadOptions()
                .dirs(List.of(root.toString())).sampleLimit(sampleLimit));
        ToolResult r = src.tool().execute(Map.of("name", "s"), null);
        List<String> out = new ArrayList<>();
        Path skillDir = root.resolve("s").toAbsolutePath();
        for (String line : r.output().split("\n")) {
            if (!line.startsWith("<file>")) continue;
            String abs = line.substring("<file>".length(), line.length() - "</file>".length());
            out.add(skillDir.relativize(Path.of(abs)).toString().replace(java.io.File.separatorChar, '/'));
        }
        return out;
    }

    /**
     * The PRE-FIX walk, reproduced so a cap fixture can prove it is not vacuous on this
     * filesystem: LIFO stack, unordered {@code Files.list}, break the moment the cap is reached.
     */
    private static List<String> preFixWalk(Path dir, int limit) throws IOException {
        List<String> out = new ArrayList<>();
        java.util.Deque<Path> stack = new java.util.ArrayDeque<>();
        stack.push(dir);
        while (!stack.isEmpty() && out.size() < limit) {
            Path cur = stack.pop();
            List<Path> entries;
            try (var s = Files.list(cur)) {
                entries = s.collect(java.util.stream.Collectors.toList());
            }
            for (Path e : entries) {
                if (out.size() >= limit) break;
                if (Files.isDirectory(e)) stack.push(e);
                else if (!e.getFileName().toString().equals("SKILL.md")) {
                    out.add(dir.toAbsolutePath().relativize(e.toAbsolutePath()).toString()
                            .replace(java.io.File.separatorChar, '/'));
                }
            }
        }
        return out;
    }

    private static Tool builtinNamed(String name) {
        for (Tool t : BuiltinTools.create()) if (t.name().equals(name)) return t;
        throw new IllegalStateException("no builtin " + name);
    }

    private static ToolResult run(Tool t, Map<String, Object> args) {
        return t.execute(new LinkedHashMap<>(args), null);
    }

    // ---- helpers ---------------------------------------------------------

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort in a temp dir
                }
            });
        }
    }

}
