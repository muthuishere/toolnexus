package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static io.github.muthuishere.toolnexus.Judge.noul;
import static io.github.muthuishere.toolnexus.Judge.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** SPEC.md §8B "Backends, images, cache and validation", driven off examples/judge/backends/. */
class ClassifierBackendsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String NOUL_REPLY =
            "{\"model\":\"jev-1.14.0\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.91}},\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}";

    private static JsonNode fixture(String name) {
        try {
            return M.readTree(Files.readString(Path.of(TestFixtures.fixture("judge/backends/" + name)),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Wire question map → typed questions (raw for unmodelled types). */
    private static Map<String, Classifier.Question> questions(JsonNode qs) {
        Map<String, Classifier.Question> out = new LinkedHashMap<>();
        for (Iterator<String> it = qs.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            JsonNode q = qs.get(k);
            String ins = q.path("instructions").asText();
            JsonNode c = q.get("criteria");
            out.put(k, switch (q.get("type").asText()) {
                case "noul" -> new Classifier.NoulQuestion(ins, c == null ? null
                        : new Classifier.NoulCriteria(c.get("true").asText(), c.get("false").asText()));
                case "choice" -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    c.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
                    yield new Classifier.ChoiceQuestion(ins, m);
                }
                case "score" -> {
                    List<String> l = new ArrayList<>();
                    c.forEach(n -> l.add(n.asText()));
                    yield new Classifier.ScoreQuestion(ins, l);
                }
                default -> new Classifier.RawQuestion(q.get("type").asText(), ins,
                        c == null ? null : M.convertValue(c, Object.class));
            });
        }
        return out;
    }

    /** {"$image":{mimeType,data}} / {"$text":s} markers → ContentParts. */
    private static Object state(JsonNode n) {
        if (n.isObject()) {
            if (n.has("$image")) {
                JsonNode im = n.get("$image");
                return new ContentPart(ContentPart.IMAGE, null, im.get("mimeType").asText(),
                        im.get("data").asText(), null, null);
            }
            if (n.has("$text")) return ContentPart.text(n.get("$text").asText());
            Map<String, Object> m = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> m.put(e.getKey(), state(e.getValue())));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(e -> l.add(state(e)));
            return l;
        }
        return M.convertValue(n, Object.class);
    }

    private static Classifier.Decision decision(JsonNode response) {
        return Classifier.toDecision(Json.toMap(response.toString()));
    }

    // ------------------------------------------------------------------ fake endpoint

    private HttpServer server;
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> imageHeaders = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());

    private String start(BiFunction<Integer, String, Object[]> reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n;
            synchronized (bodies) {
                paths.add(ex.getRequestURI().getPath());
                bodies.add(b);
                imageHeaders.add(ex.getRequestHeaders().getFirst(Classifier.IMAGES_HEADER));
                authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
                n = bodies.size();
            }
            Object[] r = reply.apply(n, b);
            byte[] out = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders((Integer) r[0], out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // ------------------------------------------------------------------ presets

    @Test
    void presetsFixture() {
        JsonNode fx = fixture("presets.json");
        JsonNode backends = fx.get("backends");
        assertEquals(backends.size(), Classifier.BACKENDS.size());
        backends.fields().forEachRemaining(e -> {
            Classifier.BackendPreset p = Classifier.BACKENDS.get(e.getKey());
            assertNotNull(p, e.getKey());
            JsonNode w = e.getValue();
            assertEquals(w.get("baseUrl").isNull() ? null : w.get("baseUrl").asText(), p.baseUrl(), e.getKey());
            assertEquals(w.get("model").asText(), p.model(), e.getKey());
            assertEquals(w.get("apiKeyEnv").asText(), p.apiKeyEnv(), e.getKey());
            assertEquals(w.get("wire").asText(), p.wire(), e.getKey());
            assertEquals(w.get("imagesHeader").asBoolean(), p.imagesHeader(), e.getKey());
            if (w.get("supportsImages").isNull()) {
                assertNull(p.supports(), e.getKey());
            } else {
                w.get("supportsImages").fields().forEachRemaining(m ->
                        assertEquals(m.getValue().asBoolean(), p.supports().apply(m.getKey()).images(),
                                e.getKey() + " " + m.getKey()));
            }
        });
        var unknown = assertThrows(Classifier.ClassifierException.class,
                () -> Classifier.create(new Classifier.Options().backend("nope")));
        assertEquals(fx.get("unknownBackendError").asText(), unknown.getMessage());
        var custom = assertThrows(Classifier.ClassifierException.class, () -> Classifier.create(
                new Classifier.Options().backend(Classifier.BACKEND_CUSTOM).baseUrl("http://x")));
        assertEquals(fx.get("customError").asText(), custom.getMessage());
        assertNotNull(Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_CUSTOM)
                .baseUrl("http://x").supports(new Classifier.Supports(false))));
        // The check is systemone-only: a custom-style host function never touches the wire.
        assertNotNull(Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_CUSTOM)
                .style(Classifier.STYLE_CUSTOM).evaluate((s, q) -> null)));
        assertEquals("typesafe/jev-1.14", Classifier.OPENROUTER_MODEL);
    }

    // ------------------------------------------------------------------ images

    @Test
    void imagesFixture() throws IOException {
        JsonNode fx = fixture("images.json");
        Map<String, Classifier.Question> qs = questions(fx.get("questions"));
        for (JsonNode c : fx.get("cases")) {
            String name = c.get("name").asText();
            String backend = c.get("backend").asText();
            boolean cf = "cloudflare".equals(backend);
            paths.clear();
            bodies.clear();
            imageHeaders.clear();
            String base = start((n, b) -> new Object[]{200, cf ? "{\"success\":true,\"result\":" + NOUL_REPLY + "}" : NOUL_REPLY});
            try {
                JsonNode expect = c.get("expect");
                String url = base + "/v1";
                if (cf) {
                    url = base + "/accounts/${TN_TEST_ACCOUNT}/ai/run";
                    if (expect.has("url")) {
                        assertEquals(expect.get("url").asText(), Classifier.BACKENDS.get(backend).baseUrl(), name);
                    }
                }
                Classifier.Options o = new Classifier.Options().backend(backend).baseUrl(url)
                        .env(k -> "TN_TEST_ACCOUNT".equals(k) ? "acct123" : null);
                if (c.has("model")) o.model(c.get("model").asText());
                if (c.has("supports")) o.supports(new Classifier.Supports(c.get("supports").get("images").asBoolean()));
                Classifier cl = Classifier.create(o);
                Object st = state(c.get("state"));
                if (expect.has("unsupported")) {
                    var e = assertThrows(Classifier.ClassifierUnsupported.class, () -> cl.evaluate(st, qs), name);
                    assertEquals(expect.get("error").asText(), e.getMessage(), name);
                    assertEquals("images", e.feature());
                    assertTrue(bodies.isEmpty(), name + ": a request was sent");
                } else if (expect.has("errorPrefix")) {
                    var e = assertThrows(Classifier.ClassifierException.class, () -> cl.evaluate(st, qs), name);
                    assertFalse(e instanceof Classifier.ClassifierUnsupported, name);
                    assertTrue(e.getMessage().startsWith(expect.get("errorPrefix").asText()), name + ": " + e.getMessage());
                    assertTrue(bodies.isEmpty(), name + ": a request was sent");
                } else {
                    cl.evaluate(st, qs);
                    assertEquals(expect.get("body").asText(), bodies.get(0), name);
                    assertEquals(expect.get("imagesHeader").asBoolean(), "true".equals(imageHeaders.get(0)), name);
                    if (cf) assertEquals("/accounts/acct123/ai/run", paths.get(0), name);
                }
            } finally {
                stop();
                server = null;
            }
        }
    }

    @Test
    void hostHeaderWinsOverTheImagesHeader() throws IOException {
        String base = start((n, b) -> new Object[]{200, NOUL_REPLY});
        Classifier cl = Classifier.create(new Classifier.Options().baseUrl(base)
                .headers(Map.of(Classifier.IMAGES_HEADER, "false")));
        JsonNode img = fixture("images.json").get("cases").get(1).get("state");
        cl.evaluate(state(img), Map.of("q", new Classifier.NoulQuestion("x")));
        assertEquals("false", imageHeaders.get(0));
    }

    @Test
    void imagesNeverLiftForNonWireStyles() {
        AtomicReference<Object> seen = new AtomicReference<>();
        ContentPart part = ContentPart.text("hi");
        Classifier cl = Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM)
                .evaluate((s, q) -> { seen.set(s); return new Classifier.Decision("m",
                        Map.of("q", new Classifier.NoulAnswer(0.5)), null, true); }));
        Map<String, Object> st = Map.of("t", part);
        cl.evaluate(st, Map.of("q", new Classifier.NoulQuestion("x")));
        assertTrue(seen.get() == st, "custom style must receive the state verbatim");
    }

    // ------------------------------------------------------------------ cloudflare

    @Test
    void cloudflareRepliesFixture() throws IOException {
        JsonNode fx = fixture("cloudflare-replies.json");
        Map<String, Classifier.Question> qs = questions(fx.get("questions"));
        for (JsonNode c : fx.get("cases")) {
            String name = c.get("name").asText();
            String base = start((n, b) -> new Object[]{200, c.get("reply").toString()});
            try {
                Classifier cl = Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_CLOUDFLARE).baseUrl(base));
                JsonNode expect = c.get("expect");
                if (expect.has("error")) {
                    var e = assertThrows(Classifier.ClassifierException.class, () -> cl.evaluate("x", qs), name);
                    assertEquals(expect.get("error").asText(), e.getMessage(), name);
                } else {
                    Classifier.Decision d = cl.evaluate("x", qs);
                    assertEquals(expect.get("model").asText(), d.model(), name);
                    assertEquals(expect.get("noul").asDouble(), d.noul("q").noul(), name);
                }
            } finally {
                stop();
                server = null;
            }
        }
    }

    // ------------------------------------------------------------------ openai decisions (#157)

    @Test
    void openaiDecisionsFixture() throws IOException {
        JsonNode fx = fixture("openai-decisions.json");
        Map<String, Classifier.Question> qs = questions(fx.get("questions"));
        String okReply = fx.get("replyCases").get(0).get("reply").toString();
        for (JsonNode c : fx.get("requestCases")) {
            String name = c.get("name").asText();
            bodies.clear();
            paths.clear();
            imageHeaders.clear();
            String base = start((n, b) -> new Object[]{200, okReply});
            try {
                Classifier.Options o = new Classifier.Options().backend(Classifier.BACKEND_OPENAI)
                        .baseUrl(base + "/v1").retries(1);
                if (c.has("model")) o.model(c.get("model").asText());
                Classifier.create(o).evaluate(state(c.get("state")), qs);
                assertEquals(c.get("expect").get("body").asText(), bodies.get(0), name);
                assertEquals("/v1/decisions", paths.get(0), name);
                assertNull(imageHeaders.get(0), name + ": danger-zone header sent on the decisions wire");
            } finally {
                stop();
                server = null;
            }
        }
        for (JsonNode c : fx.get("replyCases")) {
            String name = c.get("name").asText();
            JsonNode expect = c.get("expect");
            if (expect.has("normalized")) {
                assertEquals(expect.get("normalized").asText(),
                        ClassifierDecisions.reply(c.get("reply").toString(), qs), name);
            }
            String base = start((n, b) -> new Object[]{200, c.get("reply").toString()});
            try {
                Classifier cl = Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_OPENAI)
                        .baseUrl(base).retries(1));
                if (expect.has("error")) {
                    var e = assertThrows(Classifier.ClassifierException.class, () -> cl.evaluate("x", qs), name);
                    assertEquals(expect.get("error").asText(), e.getMessage(), name);
                    continue;
                }
                Classifier.Decision d = cl.evaluate("x", qs);
                assertEquals(expect.get("model").asText(), d.model(), name);
                assertEquals(expect.get("calibrated").asBoolean(), d.calibrated(), name);
                expect.path("noul").fields().forEachRemaining(e ->
                        assertEquals(e.getValue().asDouble(), d.noul(e.getKey()).noul(), name));
                expect.path("choice").fields().forEachRemaining(e ->
                        assertEquals(e.getValue().asText(), d.choice(e.getKey()).choice(), name));
                expect.path("score").fields().forEachRemaining(e ->
                        assertEquals(e.getValue().asDouble(), d.score(e.getKey()).score(), name));
            } finally {
                stop();
                server = null;
            }
        }
        for (JsonNode c : fx.get("errorCases")) {
            String name = c.get("name").asText();
            bodies.clear();
            String base = start((n, b) -> new Object[]{200, okReply});
            try {
                Classifier cl = Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_OPENAI)
                        .baseUrl(base).retries(1));
                var e = assertThrows(Classifier.ClassifierException.class,
                        () -> cl.evaluate("x", questions(c.get("questions"))), name);
                assertEquals(c.get("expect").get("error").asText(), e.getMessage(), name);
                assertEquals(c.get("expect").path("unsupported").asBoolean(false),
                        e instanceof Classifier.ClassifierUnsupported, name);
                assertTrue(bodies.isEmpty(), name + ": a request was sent");
            } finally {
                stop();
                server = null;
            }
        }
        for (JsonNode c : fx.get("captureCases")) {
            String name = c.get("name").asText();
            authHeaders.clear();
            Map<String, String> envMap = new java.util.HashMap<>();
            c.path("env").fields().forEachRemaining(e -> envMap.put(e.getKey(), e.getValue().asText()));
            String base = start((n, b) -> new Object[]{200, okReply});
            try {
                Classifier.Options o = new Classifier.Options().backend(Classifier.BACKEND_OPENAI)
                        .baseUrl(base + "/v1").retries(1).env(envMap::get);
                if (c.has("apiKeyEnv")) o.apiKeyEnv(c.get("apiKeyEnv").asText());
                Classifier.create(o).evaluate("x", qs);
                JsonNode want = c.get("expectAuthorization");
                if (want.isNull()) assertNull(authHeaders.get(0), name + ": a key was sent to a host that is not its own");
                else assertEquals(want.asText(), authHeaders.get(0), name);
            } finally {
                stop();
                server = null;
            }
        }
    }

    @Test
    void cloudflareErrorKeepsTheAccountTemplate() throws IOException {
        String base = start((n, b) -> new Object[]{500, "boom"});
        Classifier cl = Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_CLOUDFLARE)
                .baseUrl(base + "/accounts/${TN_TEST_ACCOUNT}/ai/run").retries(1).retryBaseMs(1)
                .env(k -> "TN_TEST_ACCOUNT".equals(k) ? "secretacct" : null));
        var e = assertThrows(Classifier.ClassifierException.class,
                () -> cl.evaluate("x", Map.of("q", new Classifier.NoulQuestion("y"))));
        assertFalse(e.getMessage().contains("secretacct"), e.getMessage());
        assertTrue(e.getMessage().contains("${TN_TEST_ACCOUNT}"), e.getMessage());
        assertEquals("/accounts/secretacct/ai/run", paths.get(0));
    }

    // ------------------------------------------------------------------ key routing (#155)

    /** A preset's env key is never sent to a host that is not its own; an explicit apiKeyEnv or
     * header still goes where the caller says. */
    @Test
    void keyRoutingFixture() throws IOException {
        JsonNode fx = fixture("key-routing.json");
        Map<String, Classifier.Question> qs = questions(fx.get("questions"));
        for (JsonNode c : fx.get("captureCases")) {
            String name = c.get("name").asText();
            String backend = c.get("backend").asText();
            boolean cf = "cloudflare".equals(backend);
            authHeaders.clear();
            Map<String, String> envMap = new java.util.HashMap<>();
            c.path("env").fields().forEachRemaining(e -> envMap.put(e.getKey(), e.getValue().asText()));
            String base = start((n, b) -> new Object[]{200,
                    cf ? "{\"success\":true,\"result\":" + NOUL_REPLY + "}" : NOUL_REPLY});
            try {
                Classifier.Options o = new Classifier.Options().backend(backend).baseUrl(base + "/v1")
                        .retries(1).env(envMap::get);
                if (c.has("apiKeyEnv")) o.apiKeyEnv(c.get("apiKeyEnv").asText());
                if (c.has("headers")) {
                    Map<String, String> h = new LinkedHashMap<>();
                    c.get("headers").fields().forEachRemaining(e -> h.put(e.getKey(), e.getValue().asText()));
                    o.headers(h);
                }
                if ("custom".equals(backend)) o.supports(new Classifier.Supports(false));
                Classifier.create(o).evaluate("x", qs);
                assertFalse(authHeaders.isEmpty(), name + ": no request reached the capture server");
                JsonNode want = c.get("expectAuthorization");
                for (String got : authHeaders) {
                    if (want.isNull()) assertNull(got, name + ": a key was sent to a host that is not its own");
                    else assertEquals(want.asText(), got, name);
                }
            } finally {
                stop();
                server = null;
            }
        }
        for (JsonNode c : fx.get("hostCases")) {
            assertEquals(c.get("expectKeyEnv").asText(),
                    ClassifierBackends.keyEnv(c.get("backend").asText(), c.get("baseUrl").asText()),
                    c.get("backend").asText() + " " + c.get("baseUrl").asText());
        }
    }

    // ------------------------------------------------------------------ cache keys

    @Test
    void cacheKeysFixture() {
        for (JsonNode c : fixture("cache-keys.json").get("cases")) {
            String name = c.get("name").asText();
            assertEquals(c.get("modelKey").asText(),
                    Classifier.modelCacheKey(c.get("endpoint").asText(), c.get("model").asText()), name);
            @SuppressWarnings("unchecked")
            Map<String, Object> assets = c.has("assets") ? M.convertValue(c.get("assets"), Map.class) : null;
            assertEquals(c.get("decisionKey").asText(), Classifier.decisionCacheKey(c.get("endpoint").asText(),
                    c.get("served").asText(), M.convertValue(c.get("state"), Object.class), assets,
                    questions(c.get("questions"))), name);
        }
    }

    // ------------------------------------------------------------------ V1 / T1 / C1

    @Test
    void validateCasesFixture() {
        for (JsonNode c : fixture("validate-cases.json").get("cases")) {
            String name = c.get("name").asText();
            var err = Classifier.validateDecision(questions(c.get("questions")), decision(c.get("response")));
            assertEquals(c.get("valid").asBoolean(), err.isEmpty(), name + ": " + err);
            err.ifPresent(e -> {
                assertTrue(e.getMessage().startsWith("classifier: invalid decision: "), name);
                assertTrue(e.getMessage().contains("\"" + c.get("key").asText() + "\""), name + ": " + e.getMessage());
            });
        }
    }

    @Test
    void thresholdsFixture() {
        JsonNode fx = fixture("thresholds-cases.json");
        JsonNode def = fx.get("default");
        assertEquals(new Classifier.Thresholds(def.get("yes").asDouble(), def.get("no").asDouble(),
                def.get("minConfidence").asDouble()), Classifier.Thresholds.DEFAULT);
        for (JsonNode c : fx.get("cases")) {
            String name = c.get("name").asText();
            Classifier.DecisionAnswer a = Classifier.toDecision(
                    Json.toMap("{\"answers\":{\"a\":" + c.get("answer") + "}}")).answers().get("a");
            Classifier.Thresholds th = Classifier.Thresholds.DEFAULT;
            if (c.has("thresholds")) {
                JsonNode t = c.get("thresholds");
                th = new Classifier.Thresholds(t.get("yes").asDouble(), t.get("no").asDouble(),
                        t.get("minConfidence").asDouble());
            }
            assertEquals(c.get("band").asText(), th.band(a).toString(), name);
            Judge.Bands b = Judge.Bands.DEFAULT;
            String bandsBand = a instanceof Classifier.NoulAnswer n ? b.of(n.noul()).toString()
                    : b.sure(a) ? "yes" : "uncertain";
            assertEquals(c.get("bandsBand").asText(), bandsBand, name);
        }
    }

    @Test
    void chunkQuestions() {
        Map<String, Classifier.Question> qs = new LinkedHashMap<>();
        qs.put("c", new Classifier.NoulQuestion("c"));
        qs.put("a", new Classifier.NoulQuestion("a"));
        qs.put("b", new Classifier.NoulQuestion("b"));
        var ch = Classifier.chunkQuestions(qs, 2);
        assertEquals(List.of(List.of("a", "b"), List.of("c")),
                ch.stream().map(m -> List.copyOf(m.keySet())).toList());
        assertThrows(Classifier.ClassifierException.class, () -> Classifier.chunkQuestions(qs, 0));
    }

    // ------------------------------------------------------------------ builders + cache

    @Test
    void stateBuilderAndCache() throws IOException {
        String base = start((n, b) -> new Object[]{200, NOUL_REPLY});
        List<LlmClient.MetricEvent.ClassifierEvaluate> events = Collections.synchronizedList(new ArrayList<>());
        Classifier cl = Classifier.create(new Classifier.Options().baseUrl(base)
                .cache(new Classifier.MemoryCache(Duration.ofHours(1), 100))
                .onMetric(e -> { if (e instanceof LlmClient.MetricEvent.ClassifierEvaluate ce) events.add(ce); }));
        var q = noul("q", "Is the object red?");
        Classifier.Decision d1 = cl.state("a red mug").evaluate(q);
        assertFalse(d1.cached());
        Classifier.Decision d2 = cl.state("a red mug").evaluate(q);
        assertTrue(d2.cached());
        assertEquals(1, bodies.size());
        assertTrue(events.get(1).cached());
        assertEquals(1, events.get(1).questions());
        assertFalse(events.get(1).questionsHash().isEmpty());
        cl.state("a blue mug").evaluate(q);
        assertEquals(2, bodies.size(), "a different state must miss");
        assertThrows(Classifier.ClassifierException.class, () -> cl.state("x").evaluate(q, q));
        assertEquals(2, bodies.size());
    }

    @Test
    void invalidRepliesAreNotCached() throws IOException {
        String base = start((n, b) -> new Object[]{200, "{\"model\":\"m\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":1.5}}}"});
        Classifier cl = Classifier.create(new Classifier.Options().baseUrl(base)
                .cache(new Classifier.MemoryCache(Duration.ZERO, 0)));
        Map<String, Classifier.Question> qs = Map.of("q", new Classifier.NoulQuestion("x"));
        cl.evaluate("s", qs);
        cl.evaluate("s", qs); // plain evaluate returns the reply as before, never cached
        assertEquals(2, bodies.size());
        var e = assertThrows(Classifier.ClassifierException.class, () -> cl.state("s").evaluate(noul("q", "x")));
        assertTrue(e.getMessage().startsWith("classifier: invalid decision: answer \"q\""), e.getMessage());
    }

    @Test
    void cacheFollowsTheServedModel() throws IOException {
        AtomicReference<String> served = new AtomicReference<>("jev-1.13.0");
        String base = start((n, b) -> new Object[]{200,
                "{\"model\":\"" + served.get() + "\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.5}}}"});
        Classifier.MemoryCache cache = new Classifier.MemoryCache(null, 0);
        Classifier cl = Classifier.create(new Classifier.Options().baseUrl(base).cache(cache));
        cl.state("s").evaluate(noul("q", "x"));
        assertEquals("jev-1.13.0", cache.get(Classifier.modelCacheKey(base, Classifier.DEFAULT_MODEL)));
        served.set("jev-1.14.0");
        cl.state("other").evaluate(noul("q", "x"));
        Classifier.Decision d = cl.state("s").evaluate(noul("q", "x"));
        assertFalse(d.cached());
        assertEquals(3, bodies.size());
        assertEquals("jev-1.14.0", d.model());
    }

    @Test
    void memoryCacheTtlAndMax() {
        long[] now = {0};
        Classifier.MemoryCache c = new Classifier.MemoryCache(Duration.ofNanos(10), 2, () -> now[0]);
        c.set("a", "1");
        c.set("b", "2");
        c.set("c", "3");
        assertNull(c.get("a"), "oldest-inserted goes first");
        assertEquals("3", c.get("c"));
        now[0] = 11;
        assertNull(c.get("b"), "expired");
    }

    @Test
    void statesBatchIsPerItem() throws IOException {
        String base = start((n, b) -> b.contains("\"state\":\"bad\"")
                ? new Object[]{400, "{\"error\":\"nope\"}"} : new Object[]{200, NOUL_REPLY});
        Classifier cl = Classifier.create(new Classifier.Options().baseUrl(base));
        var rs = cl.states("a", "bad", "c").evaluate(noul("q", "Is the object red?"));
        assertEquals(3, rs.size());
        assertNull(rs.get(0).error());
        assertNotNull(rs.get(1).error());
        assertNull(rs.get(1).decision());
        assertNull(rs.get(2).error());
        assertEquals("jev-1.14.0", rs.get(0).decision().model());
        assertEquals(List.of(), cl.states().evaluate(noul("q", "x")));
        var q = noul("q", "x");
        int before = bodies.size();
        assertThrows(Classifier.ClassifierException.class, () -> cl.states("a").evaluate(q, q));
        assertEquals(before, bodies.size());
    }

    // ------------------------------------------------------------------ open union + metric

    @Test
    void rawQuestionAndAnswer() throws IOException {
        String base = start((n, b) -> new Object[]{200,
                "{\"model\":\"jev-1.14.0\",\"answers\":{\"spot\":{\"type\":\"box\",\"box\":[1,2,3,4]}}}"});
        Classifier cl = Classifier.create(new Classifier.Options().baseUrl(base));
        Classifier.Decision d = cl.state("s").evaluate(raw("spot", "box", "Where is the mug?"));
        assertEquals("{\"model\":\"jev-latest\",\"questions\":{\"spot\":{\"instructions\":\"Where is the mug?\",\"type\":\"box\"}},\"state\":\"s\"}",
                bodies.get(0));
        Classifier.RawAnswer ra = d.raw("spot");
        assertEquals("box", ra.type());
        assertNotNull(ra.value().get("box"));
        assertEquals(Judge.Band.UNCERTAIN, Classifier.Thresholds.DEFAULT.band(ra));
        assertThrows(Classifier.ClassifierException.class,
                () -> cl.evaluate("s", Map.of("x", new Classifier.RawQuestion("", "y"))));
    }

    @Test
    void metricHashMatchesTheBaseFixture() throws IOException {
        JsonNode fx = M.readTree(Files.readString(Path.of(TestFixtures.fixture("judge/base.json"))));
        JsonNode req = fx.get("request");
        AtomicReference<LlmClient.MetricEvent.ClassifierEvaluate> ev = new AtomicReference<>();
        Classifier cl = Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM)
                .model(req.get("model").asText())
                .evaluate((s, q) -> new Classifier.Decision("m", Map.of(), null, true))
                .onMetric(e -> { if (e instanceof LlmClient.MetricEvent.ClassifierEvaluate ce) ev.set(ce); }));
        cl.evaluate(M.convertValue(req.get("state"), Object.class), questions(req.get("questions")));
        assertEquals(fx.get("canonicalSha256").asText(), ev.get().questionsHash());
        assertEquals(3, ev.get().questions());
        assertFalse(ev.get().cached());
    }

    @Test
    void unsupportedIsReportedAsAnErrorEvent() {
        List<LlmClient.MetricEvent.ClassifierEvaluate> events = new ArrayList<>();
        Classifier cl = Classifier.create(new Classifier.Options().backend(Classifier.BACKEND_VERCEL)
                .baseUrl("http://127.0.0.1:1")
                .onMetric(e -> { if (e instanceof LlmClient.MetricEvent.ClassifierEvaluate ce) events.add(ce); }));
        Object st = Map.of("p", new ContentPart(ContentPart.IMAGE, null, "image/png", "AAAA", null, null));
        try {
            cl.evaluate(st, Map.of("q", new Classifier.NoulQuestion("x")));
            fail("expected unsupported");
        } catch (Classifier.ClassifierUnsupported e) {
            assertInstanceOf(Classifier.ClassifierException.class, e);
            assertEquals("vercel", e.backend());
            assertEquals("typesafe-ai/jev", e.model());
        }
        assertEquals("error", events.get(0).status());
    }
}
