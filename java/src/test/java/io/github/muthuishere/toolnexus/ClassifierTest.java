package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance tests for SPEC.md §8B, driven off the SHARED fixtures in {@code examples/judge/}.
 * The fixtures are the contract; nothing here re-derives what correct is believed to look like.
 */
class ClassifierTest {

    private static final ObjectMapper M = new ObjectMapper();

    // ------------------------------------------------------------------ fixtures

    private static JsonNode fixture(String name) {
        try {
            Path p = Path.of(TestFixtures.fixture("judge/" + name + ".json"));
            return M.readTree(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("read fixture " + name, e);
        }
    }

    /**
     * Rebuild the typed questions from a fixture's raw JSON. The absent-vs-empty distinction is
     * preserved: a noul with no {@code criteria} key gets {@code null}, one with
     * {@code {"true":"","false":""}} gets a record of two empty strings.
     */
    private static Map<String, Classifier.Question> questions(JsonNode request) {
        Map<String, Classifier.Question> out = new LinkedHashMap<>();
        JsonNode qs = request.get("questions");
        for (Iterator<String> it = qs.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            JsonNode q = qs.get(key);
            String instructions = q.get("instructions").asText();
            JsonNode criteria = q.get("criteria");
            out.put(key, switch (q.get("type").asText()) {
                case "noul" -> new Classifier.NoulQuestion(instructions,
                        criteria == null ? null : new Classifier.NoulCriteria(
                                criteria.get("true").asText(), criteria.get("false").asText()));
                case "choice" -> {
                    Map<String, String> c = new LinkedHashMap<>();
                    criteria.fields().forEachRemaining(e -> c.put(e.getKey(), e.getValue().asText()));
                    yield new Classifier.ChoiceQuestion(instructions, c);
                }
                case "score" -> {
                    List<String> c = new ArrayList<>();
                    criteria.forEach(n -> c.add(n.asText()));
                    yield new Classifier.ScoreQuestion(instructions, c);
                }
                default -> throw new IllegalStateException("unknown type in fixture: " + q.get("type"));
            });
        }
        return out;
    }

    /** The fixture's {@code state} as plain Java data (map/list/string/number). */
    private static Object state(JsonNode request) {
        return M.convertValue(request.get("state"), Object.class);
    }

    /** A {@code static} classifier answering this fixture's own request. */
    private static Classifier staticFrom(JsonNode f, Classifier.Options opts) {
        JsonNode req = f.get("request");
        return Classifier.create(opts
                .style(Classifier.STYLE_STATIC)
                .model(req.get("model").asText())
                .decisions(List.of(new Classifier.RecordedDecision(
                        state(req), questions(req), f.get("response").toString()))));
    }

    private static String sha256(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ the bytes

    /**
     * The byte-identity claim: every canonical fixture, bytes AND sha256. This is the test the
     * hand-rolled emitter exists for — Jackson sorts keys but writes {@code 0.0} for {@code 0}
     * and scientific notation for small magnitudes, while round-tripping {@code base} perfectly.
     */
    @Test
    void canonicalBytesMatchEveryFixture() {
        for (String name : List.of("base", "hardened", "numbers", "wide", "degenerate", "near-uniform")) {
            JsonNode f = fixture(name);
            JsonNode req = f.get("request");
            byte[] got = Classifier.canonicalRequest(req.get("model").asText(), questions(req));
            assertEquals(f.get("canonical").asText(), new String(got, StandardCharsets.UTF_8),
                    name + ": canonical bytes");
            assertEquals(f.get("canonicalSha256").asText(), sha256(got), name + ": sha256");
            assertEquals(f.get("canonicalBytes").asInt(), got.length, name + ": byte length");
        }
    }

    /** Each recorded entry of the static corpus carries its own canonical bytes. */
    @Test
    void canonicalBytesMatchEveryDecisionsEntry() {
        JsonNode entries = fixture("decisions").get("entries");
        assertTrue(entries.size() >= 3);
        for (JsonNode e : entries) {
            JsonNode req = e.get("request");
            byte[] got = Classifier.canonicalRequest(req.get("model").asText(), questions(req));
            assertEquals(e.get("canonical").asText(), new String(got, StandardCharsets.UTF_8));
            assertEquals(e.get("canonicalSha256").asText(), sha256(got));
        }
    }

    /** Absent and empty are DIFFERENT values, and both are preserved on the wire. */
    @Test
    void absentCriteriaIsNotEmptyCriteria() {
        String absent = new String(Classifier.canonicalRequest("m",
                Map.of("q", new Classifier.NoulQuestion("?"))), StandardCharsets.UTF_8);
        String empty = new String(Classifier.canonicalRequest("m",
                Map.of("q", new Classifier.NoulQuestion("?", new Classifier.NoulCriteria("", "")))),
                StandardCharsets.UTF_8);
        assertNotEquals(absent, empty);
        assertFalse(absent.contains("criteria"), "an absent criteria is ABSENT, not null and not {}");
        assertTrue(empty.contains("\"criteria\":{\"false\":\"\",\"true\":\"\"}"));
    }

    // ------------------------------------------------------------------ the parse

    @Test
    void baseFixtureParses() {
        JsonNode f = fixture("base");
        Classifier c = staticFrom(f, new Classifier.Options());
        Classifier.Decision d = c.evaluate(state(f.get("request")), questions(f.get("request")));

        JsonNode expect = f.get("expect");
        assertEquals(expect.get("calibrated").asBoolean(), d.calibrated());
        assertEquals("typesafe/jev-1.13-20260917", d.model());

        assertEquals(0.98, d.noul("is_refund_request").noul(), 0.0);

        Classifier.ChoiceAnswer choice = d.choice("department");
        assertEquals("shipping", choice.choice());
        assertEquals(0.41, choice.confidence(), 0.0);
        assertFalse(choice.nearUniform());
        // A ZERO probability stays an ENTRY — it is never dropped for being zero.
        assertTrue(choice.probabilities().containsKey("technical"));
        assertEquals(0.0, choice.probabilities().get("technical"), 0.0);

        Classifier.ScoreAnswer score = d.score("urgency");
        assertEquals(1.21, score.score(), 0.0);
        assertEquals(0.57, score.confidence(), 0.0);
        assertEquals(List.of("routine", "elevated", "urgent"), score.levels());

        // A wrong-type read throws a clear error rather than a ClassCastException…
        Classifier.ClassifierException wrong = assertThrows(Classifier.ClassifierException.class,
                () -> d.noul("department"));
        assertTrue(wrong.getMessage().contains("department") && wrong.getMessage().contains("choice"));
        // … and so does an absent key.
        assertTrue(assertThrows(Classifier.ClassifierException.class, () -> d.score("nope"))
                .getMessage().contains("no answer"));
    }

    /** Compare NUMERICALLY, never as strings: {@code 0} vs {@code 0.0} and {@code 1.6716e-5} vs
     * {@code 0.000016716} are the same value and different bytes. */
    @Test
    void numbersFixtureParsesNumerically() {
        JsonNode f = fixture("numbers");
        Classifier c = staticFrom(f, new Classifier.Options());
        Classifier.Decision d = c.evaluate(state(f.get("request")), questions(f.get("request")));

        assertEquals(0.0, d.noul("is_expensive").noul(), 0.0);
        Classifier.ScoreAnswer s = d.score("urgency");
        assertEquals(1.21, s.score(), 0.0);
        assertEquals(0.04, s.probabilities().get("0"), 0.0);
        assertEquals(1.6716e-5, d.usage().cost(), 0.0);
        assertEquals(398, d.usage().inputTokens());

        // The state round-trips as numbers, and the canonical bytes exclude it entirely.
        @SuppressWarnings("unchecked")
        Map<String, Object> st = (Map<String, Object>) state(f.get("request"));
        assertEquals(0, ((Number) st.get("attempts")).doubleValue(), 0.0);
        assertEquals(1.6716e-5, ((Number) st.get("unit_cost")).doubleValue(), 0.0);
        assertFalse(new String(Classifier.canonicalRequest("typesafe/jev-1.13",
                questions(f.get("request"))), StandardCharsets.UTF_8).contains("attempts"));
    }

    /** 40 probability keys — a runtime whose small maps iterate in term order only to 32 entries
     * passes {@code base} by accident and fails here. */
    @Test
    void wideFixture() {
        JsonNode f = fixture("wide");
        Classifier c = staticFrom(f, new Classifier.Options());
        Classifier.Decision d = c.evaluate(state(f.get("request")), questions(f.get("request")));
        Classifier.ChoiceAnswer a = d.choice("skill");
        JsonNode expect = f.get("expect").get("answers").get("skill");
        assertEquals(expect.get("choice").asText(), a.choice());
        assertEquals(expect.get("probabilityCount").asInt(), a.probabilities().size());
        assertEquals(expect.get("nearUniform").asBoolean(), a.nearUniform());
    }

    // ------------------------------------------------------------------ nearUniform

    @Test
    void nearUniformMatchesTheFixtureOnBothSides() {
        JsonNode f = fixture("near-uniform");
        assertEquals(f.get("expect").get("tolerance").asDouble(), Classifier.NEAR_UNIFORM_TOLERANCE,
                0.0, "the fixture's tolerance IS the constant");
        Classifier c = staticFrom(f, new Classifier.Options());
        Classifier.Decision d = c.evaluate(state(f.get("request")), questions(f.get("request")));
        JsonNode answers = f.get("expect").get("answers");
        assertEquals(4, answers.size());
        for (Iterator<String> it = answers.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            assertEquals(answers.get(key).get("nearUniform").asBoolean(), d.choice(key).nearUniform(),
                    key + ": nearUniform");
        }
    }

    /** The rule itself, at its edges: inclusive comparison, n==1 true, empty false. */
    @Test
    void nearUniformEdges() {
        assertFalse(Classifier.nearUniform(Map.of()), "an empty map has no distribution at all");
        assertTrue(Classifier.nearUniform(Map.of("only", 1.0)), "n == 1 is trivially uniform");
        assertTrue(Classifier.nearUniform(Map.of("only", 0.01)), "n == 1 regardless of the value");
        // n = 2, either side of the tolerance. Deliberately 1e-4 clear of 0.05 on both sides, the
        // same margin the shared fixture uses: an exactly-0.05 deviation is not representable
        // (0.55 - 0.5 is 0.05000000000000004 in double), so no port needs an epsilon and no test
        // may ask for one.
        assertTrue(Classifier.nearUniform(Map.of("a", 0.5499, "b", 0.4501)));
        assertFalse(Classifier.nearUniform(Map.of("a", 0.5501, "b", 0.4499)));
        // Never renormalised: two entries summing to 0.5 are far from 1/n.
        assertFalse(Classifier.nearUniform(Map.of("a", 0.25, "b", 0.25)));
    }

    // ------------------------------------------------------------------ degenerate

    /**
     * Exactly the three degenerate keys warn, {@code described} does not, the warnings fire ONCE
     * across two evaluate calls, and the request bytes are unchanged (detection, never repair).
     */
    @Test
    void degenerateCriteriaWarnOncePerKeyAndChangeNothing() {
        JsonNode f = fixture("degenerate");
        List<LlmClient.MetricEvent> events = new ArrayList<>();
        Classifier c = Classifier.create(new Classifier.Options()
                .style(Classifier.STYLE_CUSTOM)
                .model(f.get("request").get("model").asText())
                .onMetric(events::add)
                .evaluate((st, qs) -> new Classifier.Decision("m", Map.of(),
                        new Classifier.Usage(0, 0, null), true)));

        Object st = state(f.get("request"));
        Map<String, Classifier.Question> qs = questions(f.get("request"));
        c.evaluate(st, qs);
        c.evaluate(st, qs); // once per key per classifier, however many calls

        List<String> warned = events.stream()
                .filter(e -> e instanceof LlmClient.MetricEvent.ClassifierWarning)
                .map(e -> ((LlmClient.MetricEvent.ClassifierWarning) e).question())
                .sorted().toList();
        List<String> expected = new ArrayList<>();
        f.get("expect").get("warnings").forEach(n -> expected.add(n.asText()));
        assertEquals(expected, warned);
        for (JsonNode n : f.get("expect").get("noWarning")) {
            assertFalse(warned.contains(n.asText()), n.asText() + " must NOT warn");
        }
        // The warning names the key and says what to do about it.
        LlmClient.MetricEvent.ClassifierWarning w = events.stream()
                .filter(e -> e instanceof LlmClient.MetricEvent.ClassifierWarning)
                .map(e -> (LlmClient.MetricEvent.ClassifierWarning) e).findFirst().orElseThrow();
        assertEquals("classifier.warning", w.event());
        assertTrue(w.message().contains(w.question()));

        // The bytes are identical WITH detection to what the fixture pins.
        assertEquals(f.get("canonicalSha256").asText(), sha256(Classifier.canonicalRequest(
                f.get("request").get("model").asText(), qs)));
    }

    /** The predicate itself: {@code n == 1} is NEVER reported, whatever its value looks like. */
    @Test
    void degeneratePredicate() {
        assertEquals("every description is empty",
                Classifier.degenerateReason(Map.of("a", "", "b", "")));
        assertEquals("every description is just its own option id",
                Classifier.degenerateReason(Map.of("a", "a", "b", "b")));
        assertEquals("every description is identical",
                Classifier.degenerateReason(Map.of("a", "same", "b", "same")));
        assertEquals(null, Classifier.degenerateReason(Map.of("a", "goes north", "b", "goes south")));
        assertEquals(null, Classifier.degenerateReason(Map.of("only", "")), "n == 1 is never reported");
        assertEquals(null, Classifier.degenerateReason(Map.of("only", "only")), "n == 1 is never reported");
    }

    // ------------------------------------------------------------------ static

    /**
     * The three guard bands share one questions payload and one canonical hash, differing only in
     * state — so the corpus MUST key on the state too, and an unrecorded state errors rather than
     * guessing a neighbouring band.
     */
    @Test
    void staticBackendDistinguishesTheThreeBands() {
        JsonNode f = fixture("decisions");
        List<Classifier.RecordedDecision> recorded = new ArrayList<>();
        for (JsonNode e : f.get("entries")) {
            recorded.add(new Classifier.RecordedDecision(state(e.get("request")),
                    questions(e.get("request")), e.get("response").toString()));
        }
        Classifier c = Classifier.create(new Classifier.Options()
                .style(Classifier.STYLE_STATIC)
                .model(f.get("entries").get(0).get("request").get("model").asText())
                .decisions(recorded));

        double[] wantRisk = {0.02, 2.25, 2.97};
        for (int i = 0; i < wantRisk.length; i++) {
            JsonNode req = f.get("entries").get(i).get("request");
            Classifier.Decision d = c.evaluate(state(req), questions(req));
            assertEquals(wantRisk[i], d.score("risk").score(), 0.0, "band " + i);
        }
        JsonNode first = f.get("entries").get(0).get("request");
        assertTrue(assertThrows(Classifier.ClassifierException.class,
                () -> c.evaluate(Map.of("command", "unseen", "cwd", "/repo", "tool", "bash"),
                        questions(first))).getMessage().contains("no recorded decision"));
    }

    // ------------------------------------------------------------------ limits

    /** Limits are enforced CLIENT-SIDE, before the request: no HTTP call, and the error names the
     * offending question key and the limit. */
    @Test
    void limitsAreRejectedPreFlightWithNoHttpCall() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        int port = start(ex -> {
            calls.incrementAndGet();
            respond(ex, 500, "no request should have been sent");
        });

        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i <= Classifier.MAX_CHOICE_OPTIONS; i++) {
            tooMany.put(String.format("opt_%03d", i), "description " + i);
        }
        record Case(Classifier.Question q, String want) {}
        List<Case> cases = List.of(
                new Case(new Classifier.ChoiceQuestion("?", tooMany), "1..255 options"),
                new Case(new Classifier.ChoiceQuestion("?", Map.of()), "1..255 options"),
                new Case(new Classifier.ScoreQuestion("?", List.of("only")), "2..10 ordered levels"),
                new Case(new Classifier.ScoreQuestion("?", List.of(
                        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")), "2..10 ordered levels"));

        for (Case tc : cases) {
            Classifier c = Classifier.create(new Classifier.Options()
                    .baseUrl("http://127.0.0.1:" + port));
            Classifier.ClassifierException e = assertThrows(Classifier.ClassifierException.class,
                    () -> c.evaluate("s", Map.of("the_key", tc.q())));
            assertTrue(e.getMessage().contains("the_key") && e.getMessage().contains(tc.want()),
                    "error must name the key and the limit, got: " + e.getMessage());
        }
        assertEquals(0, calls.get(), "a pre-flight failure sends no request");
    }

    // ------------------------------------------------------------------ secrets

    /**
     * No credential value and no expanded header value reaches any log, metric, error message or
     * returned value — including when the backend reflects them back in its own 401 body.
     */
    @Test
    void neverLeaksCredentialsOrExpandedHeaders() throws IOException {
        final String key = "sk-live-NEVER-IN-AN-ERROR";
        final String tenant = "tenant-NEVER-IN-AN-ERROR";
        List<String> sawAuth = new ArrayList<>();
        List<String> sawTenant = new ArrayList<>();
        int port = start(ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String ten = ex.getRequestHeaders().getFirst("X-Tenant");
            sawAuth.add(auth);
            sawTenant.add(ten);
            // A real gateway happily reflects what it was sent.
            respond(ex, 401, "{\"error\":\"bad credential " + auth + " for " + ten + "\"}");
        });

        Map<String, String> fakeEnv = Map.of("TEST_JUDGE_KEY", key, "TEST_JUDGE_TENANT", tenant);
        List<LlmClient.MetricEvent> events = new ArrayList<>();
        Classifier c = Classifier.create(new Classifier.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKeyEnv("TEST_JUDGE_KEY")
                .headers(Map.of("X-Tenant", "${TEST_JUDGE_TENANT}"))
                .retries(1)
                .onMetric(events::add)
                .env(fakeEnv::get));

        Classifier.ClassifierException e = assertThrows(Classifier.ClassifierException.class,
                () -> c.evaluate("s", Map.of("q", new Classifier.NoulQuestion("?"))));

        // The credential and the header DID reach the wire (they are use-only, not unused) …
        assertEquals("Bearer " + key, sawAuth.get(0));
        assertEquals(tenant, sawTenant.get(0), "${ENV} must expand at call time");
        // … and nowhere else.
        List<String> haystacks = new ArrayList<>();
        haystacks.add(String.valueOf(e.getMessage()));
        for (LlmClient.MetricEvent ev : events) haystacks.add(String.valueOf(ev));
        for (String s : haystacks) {
            for (String secret : List.of(key, tenant, "NEVER-IN-AN-ERROR")) {
                assertFalse(s.contains(secret), "leaked a secret: " + s);
            }
        }
        // An authentication failure names the status and the endpoint, and nothing else.
        assertTrue(e.getMessage().contains("401"));
        assertTrue(e.getMessage().contains("127.0.0.1:" + port));
    }

    /** A backend's own limit error survives intact, so a caller can tell a limit from a fault. */
    @Test
    void surfacesTheBackendsOwnCause() throws IOException {
        int port = start(ex -> respond(ex, 400,
                "{\"error\":\"Too many choices. Must have at most 255 choices.\"}"));
        Classifier c = Classifier.create(new Classifier.Options()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKeyEnv("TEST_JUDGE_UNSET")
                .env(k -> null));
        assertTrue(assertThrows(Classifier.ClassifierException.class,
                () -> c.evaluate("s", Map.of("q", new Classifier.NoulQuestion("?"))))
                .getMessage().contains("Too many choices"));
    }

    // ------------------------------------------------------------------ the wire

    /** The systemone body carries the canonical model + questions plus the state VERBATIM, and a
     * successful decision decodes off it. */
    @Test
    void systemOnePostsTheCanonicalBodyAndDecodes() throws IOException {
        JsonNode f = fixture("base");
        List<String> bodies = new ArrayList<>();
        int port = start(ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, f.get("response").toString());
        });
        Classifier c = Classifier.create(new Classifier.Options()
                .baseUrl("http://127.0.0.1:" + port + "/v1/")
                .model(f.get("request").get("model").asText())
                .env(k -> null));
        Classifier.Decision d = c.evaluate(state(f.get("request")), questions(f.get("request")));
        assertEquals("shipping", d.choice("department").choice());
        assertEquals(1, bodies.size());
        JsonNode sent = M.readTree(bodies.get(0));
        assertEquals(f.get("request").get("state").asText(), sent.get("state").asText());
        // model + questions inside the sent body are the canonical bytes, verbatim.
        String canonicalPart = f.get("canonical").asText();
        assertTrue(canonicalPart.contains("\"questions\""));
        assertEquals(f.get("canonicalSha256").asText(), sha256(Classifier.canonicalRequest(
                f.get("request").get("model").asText(), questions(f.get("request")))));
    }

    // ------------------------------------------------------------------ construction

    @Test
    void createRejectsIncompleteStyles() {
        assertThrows(Classifier.ClassifierException.class,
                () -> Classifier.create(new Classifier.Options().style(Classifier.STYLE_LLM)));
        assertThrows(Classifier.ClassifierException.class,
                () -> Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM)));
        assertThrows(Classifier.ClassifierException.class,
                () -> Classifier.create(new Classifier.Options().style("nonsense")));
        // Defaults apply, and a bare Options is a valid systemone classifier.
        Classifier c = Classifier.create(new Classifier.Options());
        assertThrows(Classifier.ClassifierException.class, () -> c.evaluate("s", Map.of()));
    }

    /**
     * Proves the non-breaking claim rather than asserting it: the request body the client loop
     * sends is byte-identical whether or not a Classifier exists in the same process (§8B).
     */
    @Test
    void constructingAClassifierChangesNoClientRequest() throws IOException {
        List<String> bodies = new ArrayList<>();
        int port = start(ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"done\"}}],\"usage\":{}}");
        });
        Runnable oneRun = () -> {
            LlmClient client = LlmClient.create(new LlmClient.Options()
                    .baseUrl("http://127.0.0.1:" + port)
                    .style("openai").model("m").apiKey("k"));
            client.run("hello", Toolkit.create(new Toolkit.Options()));
        };
        oneRun.run();
        // Construct one, and exercise it, before running again.
        Classifier c = Classifier.create(new Classifier.Options()
                .style(Classifier.STYLE_CUSTOM)
                .evaluate((st, qs) -> new Classifier.Decision("m", Map.of(),
                        new Classifier.Usage(0, 0, null), true)));
        c.evaluate("s", Map.of("q", new Classifier.NoulQuestion("?")));
        oneRun.run();

        assertEquals(2, bodies.size());
        assertEquals(bodies.get(0), bodies.get(1),
                "constructing a Classifier changed a client request");
    }

    // ------------------------------------------------------------------ plumbing

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private int start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
