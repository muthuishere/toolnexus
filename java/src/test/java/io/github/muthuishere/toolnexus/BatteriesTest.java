package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.Batteries.Item;
import io.github.muthuishere.toolnexus.Batteries.OnError;
import io.github.muthuishere.toolnexus.Classifier.Question;
import io.github.muthuishere.toolnexus.Classifier.RecordedDecision;
import io.github.muthuishere.toolnexus.Judge.Bands;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.RecordComponent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** SPEC.md §8B "Batteries", driven off {@code examples/judge/batteries/} (add-judge-batteries). */
class BatteriesTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode load(String name) throws Exception {
        return M.readTree(Files.readString(Path.of(TestFixtures.fixture("judge/batteries/" + name))));
    }

    /** Typed questions from §8B wire JSON. */
    private static Map<String, Question> wireQuestions(JsonNode qs) {
        Map<String, Question> out = new LinkedHashMap<>();
        qs.fields().forEachRemaining(e -> {
            JsonNode q = e.getValue();
            String ins = q.get("instructions").asText();
            JsonNode cr = q.get("criteria");
            out.put(e.getKey(), switch (q.get("type").asText()) {
                case "noul" -> new Classifier.NoulQuestion(ins, cr == null ? null
                        : new Classifier.NoulCriteria(cr.get("true").asText(), cr.get("false").asText()));
                case "choice" -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    cr.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().asText()));
                    yield new Classifier.ChoiceQuestion(ins, m);
                }
                default -> {
                    List<String> l = new ArrayList<>();
                    cr.forEach(x -> l.add(x.asText()));
                    yield new Classifier.ScoreQuestion(ins, l);
                }
            });
        });
        return out;
    }

    private static Classifier failing(String msg) {
        return Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM).evaluate((s, q) -> {
            throw new RuntimeException(msg);
        }));
    }

    /** static over the recorded calls; failing for error cases; one that fails the test when calls is empty. */
    private static Classifier classifierFor(JsonNode c) throws Exception {
        String name = c.get("name").asText();
        if (c.path("error").asBoolean(false)) return failing("boom");
        JsonNode calls = c.get("calls");
        if (calls == null || calls.isEmpty()) {
            return Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM).evaluate((s, q) -> {
                fail(name + ": classifier must not be called");
                return null;
            }));
        }
        List<RecordedDecision> recs = new ArrayList<>();
        for (JsonNode k : calls) {
            recs.add(new RecordedDecision(M.convertValue(k.get("state"), Map.class), wireQuestions(k.get("questions")),
                    M.writeValueAsString(k.get("response"))));
        }
        return Classifier.fromRecorded(recs);
    }

    private static Bands bands(JsonNode o) {
        JsonNode b = o.get("bands");
        return b == null ? null : new Bands(b.get("low").asDouble(), b.get("high").asDouble());
    }

    private static OnError onError(JsonNode o) {
        JsonNode e = o.get("onError");
        return e == null ? null : OnError.valueOf(e.asText().toUpperCase());
    }

    private static String role(JsonNode o) { return o.has("role") ? o.get("role").asText() : null; }

    private static List<Item> items(JsonNode arr) {
        List<Item> out = new ArrayList<>();
        for (JsonNode x : arr) out.add(new Item(x.get("name").asText(), x.has("description") ? x.get("description").asText() : null));
        return out;
    }

    private static List<AgentRouterClassifier.Node> nodes(JsonNode arr) {
        List<AgentRouterClassifier.Node> out = new ArrayList<>();
        for (JsonNode x : arr) {
            out.add(new AgentRouterClassifier.Node(x.get("name").asText(), x.get("description").asText(),
                    x.has("agents") ? nodes(x.get("agents")) : List.of()));
        }
        return out;
    }

    /** The verdict record as JSON, enums lowercased (their toString). */
    private static JsonNode verdictJson(Record v) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (RecordComponent rc : v.getClass().getRecordComponents()) {
            Object x = rc.getAccessor().invoke(v);
            m.put(rc.getName(), x instanceof Enum<?> e ? e.toString() : x);
        }
        return M.valueToTree(m);
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (a == null || a.isNull()) return b == null || b.isNull();
        if (b == null) return false;
        if (a.isNumber() && b.isNumber()) return a.doubleValue() == b.doubleValue();
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            for (Iterator<String> it = a.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                if (!b.has(k) || !same(a.get(k), b.get(k))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static void assertVerdict(String name, JsonNode want, Record verdict) throws Exception {
        JsonNode got = verdictJson(verdict);
        for (Iterator<String> it = want.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (k.equals("error")) {
                boolean has = got.hasNonNull("error");
                assertEquals(want.get(k).asBoolean(), has, name + ": error present (verdict " + got + ")");
                continue;
            }
            assertTrue(got.has(k), name + ": verdict lacks " + k);
            assertTrue(same(want.get(k), got.get(k)), name + ": " + k + " = " + got.get(k) + ", want " + want.get(k));
        }
    }

    interface Runner { Record run(Classifier c, JsonNode options, JsonNode input) throws Exception; }

    private static int runFixture(String file, Runner r) throws Exception {
        JsonNode cases = load(file).get("cases");
        assertTrue(cases.size() > 0, file + ": no cases");
        for (JsonNode c : cases) {
            JsonNode o = c.has("options") ? c.get("options") : M.createObjectNode();
            assertVerdict(file + "/" + c.get("name").asText(), c.get("want"), r.run(classifierFor(c), o, c.get("input")));
        }
        return cases.size();
    }

    @Test
    void toolGuardFixtures() throws Exception {
        runFixture("tool-guard.json", (cl, o, in) -> {
            var opts = new ToolGuardClassifier.Options().onError(onError(o)).bands(bands(o)).role(role(o));
            if (o.has("askAt")) opts.askAt(o.get("askAt").asDouble());
            if (o.has("denyAt")) opts.denyAt(o.get("denyAt").asDouble());
            Map<String, Object> args = in.has("arguments") ? M.convertValue(in.get("arguments"), Map.class) : null;
            return new ToolGuardClassifier(cl, opts).check(new ToolGuardClassifier.Call(in.get("name").asText(), args,
                    in.has("description") ? in.get("description").asText() : null));
        });
    }

    @Test
    void toolRelevanceFixtures() throws Exception {
        runFixture("tool-relevance.json", (cl, o, in) -> new ToolRelevanceClassifier(cl,
                new ToolRelevanceClassifier.Options().onError(onError(o)).bands(bands(o)).role(role(o)))
                .select(in.get("prompt").asText(), items(in.get("tools"))));
    }

    @Test
    void skillRelevanceFixtures() throws Exception {
        runFixture("skill-relevance.json", (cl, o, in) -> new SkillRelevanceClassifier(cl,
                new SkillRelevanceClassifier.Options().onError(onError(o)).bands(bands(o)).role(role(o)))
                .select(in.get("prompt").asText(), items(in.get("skills"))));
    }

    @Test
    void toolResultFilterFixtures() throws Exception {
        runFixture("tool-result-filter.json", (cl, o, in) -> {
            List<String> chunks = new ArrayList<>();
            in.get("chunks").forEach(x -> chunks.add(x.asText()));
            Object query = M.convertValue(in.get("query"), Object.class);
            return new ToolResultFilterClassifier(cl, new ToolResultFilterClassifier.Options()
                    .onError(onError(o)).bands(bands(o)).role(role(o))).filter(query, chunks);
        });
    }

    @Test
    void isCompleteFixtures() throws Exception {
        runFixture("is-complete.json", (cl, o, in) -> new IsCompleteClassifier(cl,
                new IsCompleteClassifier.Options().onError(onError(o)).bands(bands(o)).role(role(o)))
                .check(in.get("task").asText(), in.get("answer").asText()));
    }

    @Test
    void agentRouterFixtures() throws Exception {
        runFixture("agent-router.json", (cl, o, in) -> new AgentRouterClassifier(cl,
                new AgentRouterClassifier.Options().bands(bands(o)).role(role(o)))
                .pick(in.get("task").asText(), nodes(in.get("agents")), in.get("fallback").asText()));
    }

    @Test
    void contentGuardFixtures() throws Exception {
        runFixture("content-guard.json", (cl, o, in) -> {
            var opts = new ContentGuardClassifier.Options().onError(onError(o)).bands(bands(o)).role(role(o));
            if (o.has("dimensions")) {
                List<ContentGuardClassifier.Dimension> ds = new ArrayList<>();
                o.get("dimensions").forEach(d -> ds.add(new ContentGuardClassifier.Dimension(d.get("name").asText(), d.get("instructions").asText())));
                opts.dimensions(ds);
            }
            return new ContentGuardClassifier(cl, opts).check(in.get("text").asText());
        });
    }

    @Test
    void modelRouterFixtures() throws Exception {
        runFixture("model-router.json", (cl, o, in) -> {
            List<ModelRouterClassifier.Model> ms = new ArrayList<>();
            in.get("models").forEach(m -> ms.add(new ModelRouterClassifier.Model(m.get("id").asText(), m.get("description").asText())));
            return new ModelRouterClassifier(cl, ms, new ModelRouterClassifier.Options().bands(bands(o)).role(role(o)))
                    .pick(in.get("prompt").asText(), in.get("fallback").asText());
        });
    }

    @Test
    void latestUserTextFixtures() throws Exception {
        JsonNode cases = load("user-text-cases.json").get("cases");
        assertTrue(cases.size() > 0);
        for (JsonNode c : cases) {
            List<Object> msgs = M.convertValue(c.get("messages"), List.class);
            assertEquals(c.get("want").asText(), Batteries.latestUserText(msgs), c.get("name").asText());
        }
    }

    @Test
    void onErrorIsRequired() {
        Classifier cl = Classifier.fromRecorded(List.of());
        List<Runnable> ctors = List.of(
                () -> new ToolGuardClassifier(cl, new ToolGuardClassifier.Options()),
                () -> new ToolRelevanceClassifier(cl, new ToolRelevanceClassifier.Options()),
                () -> new SkillRelevanceClassifier(cl, null),
                () -> new ToolResultFilterClassifier(cl, new ToolResultFilterClassifier.Options()),
                () -> new IsCompleteClassifier(cl, new IsCompleteClassifier.Options()),
                () -> new ContentGuardClassifier(cl, new ContentGuardClassifier.Options()));
        for (Runnable r : ctors) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
            assertTrue(e.getMessage().contains("onError"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ hooks

    /** A classifier whose every evaluate answers with the given answers JSON. */
    private static Classifier fixed(String answers) {
        return Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM).evaluate((s, q) -> {
            try {
                // decode through a static corpus keyed on exactly this call
                return Classifier.fromRecorded("m", List.of(new RecordedDecision(s, q,
                        "{\"model\":\"m\",\"answers\":" + answers + "}"))).evaluate(s, q);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    private static Classifier risk(String score) {
        return fixed("{\"risk\":{\"type\":\"score\",\"score\":" + score + ",\"confidence\":0.9,"
                + "\"probabilities\":{\"0\":0.25,\"1\":0.25,\"2\":0.25,\"3\":0.25},\"legend\":{\"0\":\"a\",\"1\":\"b\",\"2\":\"c\",\"3\":\"d\"}}}");
    }

    /** A stub openai endpoint: turn 1 calls {@code deploy} (id c1), later turns answer "done". Records bodies. */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();

        Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                bodies.add(Json.toMap(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                if (bodies.size() == 1) {
                    msg.put("content", null);
                    msg.put("tool_calls", List.of(Map.of("id", "c1", "type", "function",
                            "function", Map.of("name", "deploy", "arguments", "{}"))));
                } else {
                    msg.put("content", "done");
                }
                byte[] out = Json.stringify(Map.of("choices", List.of(Map.of("message", msg)))).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            });
            server.start();
        }

        LlmClient client(LlmClient.Hooks hooks, Function<Request, Answer> waitFor) {
            LlmClient.Options o = new LlmClient.Options().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .style("openai").model("configured").apiKey("k").maxTurns(4);
            if (hooks != null) o.hooks(hooks);
            if (waitFor != null) o.waitFor(waitFor);
            return LlmClient.create(o);
        }

        @Override public void close() { server.stop(0); }
    }

    private static Tool tool(String name, AtomicInteger runs, String output) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String source() { return "custom"; }
            @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                runs.incrementAndGet();
                return ToolResult.ok(output);
            }
        };
    }

    private static Toolkit toolkit(Tool... tools) {
        Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false));
        for (Tool t : tools) tk.register(t);
        return tk;
    }

    @Test
    void toolGuardHook() throws Exception {
        AtomicBoolean nextRan = new AtomicBoolean();
        Function<LlmClient.BeforeToolEvent, LlmClient.ToolOverride> next = ev -> { nextRan.set(true); return null; };
        ToolGuardClassifier.Options closed = new ToolGuardClassifier.Options().onError(OnError.CLOSED);

        // ask: halts pending with the guard's Request; the tool never runs, next not called.
        AtomicInteger runs = new AtomicInteger();
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", runs, "DEPLOYED"))) {
            var g = new ToolGuardClassifier(risk("1.8"), closed);
            LlmClient.RunResult r = s.client(new LlmClient.Hooks().beforeTool(g.asHook(next)), null).run("ship it", tk);
            assertEquals("pending", r.status);
            assertEquals("toolguard:c1", r.pending.id());
            assertEquals("approval", r.pending.kind());
            assertEquals("Approve the call to deploy? (medium risk)", r.pending.prompt());
            assertEquals("deploy", r.pending.data().get("tool"));
            assertEquals("medium risk", r.pending.data().get("reason"));
            assertEquals(1.8, r.pending.data().get("risk"));
            assertEquals(Map.of(), r.pending.data().get("arguments"));
            assertFalse(nextRan.get());
            assertEquals(0, runs.get());
        }

        // deny: short-circuits, the tool's output never appears.
        runs.set(0);
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", runs, "DEPLOYED"))) {
            var g = new ToolGuardClassifier(risk("2.9"), closed);
            LlmClient.RunResult r = s.client(new LlmClient.Hooks().beforeTool(g.asHook(next)), null).run("ship it", tk);
            assertFalse(nextRan.get());
            assertEquals(0, runs.get());
            assertEquals(1, r.toolCalls.size());
            assertEquals("denied by tool guard: high risk", r.toolCalls.get(0).output);
            assertTrue(r.toolCalls.get(0).isError);
        }

        // allow: next runs and the tool runs.
        runs.set(0);
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", runs, "DEPLOYED"))) {
            var g = new ToolGuardClassifier(risk("0.1"), closed);
            LlmClient.RunResult r = s.client(new LlmClient.Hooks().beforeTool(g.asHook(next)), null).run("ship it", tk);
            assertTrue(nextRan.get());
            assertEquals("DEPLOYED", r.toolCalls.get(0).output);
            assertEquals(1, runs.get());
        }

        // approved through waitFor: the tool runs once, no re-ask.
        runs.set(0);
        AtomicInteger asked = new AtomicInteger();
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", runs, "DEPLOYED"))) {
            var g = new ToolGuardClassifier(risk("1.8"), closed);
            LlmClient.RunResult r = s.client(new LlmClient.Hooks().beforeTool(g.asHook(null)),
                    q -> { asked.incrementAndGet(); return new Answer(q.id(), true); }).run("ship it", tk);
            assertNotEquals("pending", r.status);
            assertEquals("DEPLOYED", r.toolCalls.get(r.toolCalls.size() - 1).output);
            assertEquals(1, runs.get());
            assertEquals(1, asked.get());
        }
    }

    @Test
    void beforeLLMModelOverrideIsPerTurn() throws Exception {
        List<String> seen = new ArrayList<>();
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", new AtomicInteger(), "D"))) {
            LlmClient.Hooks h = new LlmClient.Hooks()
                    .beforeLLM(ev -> ev.turn() == 0 ? LlmClient.LLMOverride.withModel("small-fast") : null)
                    .afterLLM(ev -> seen.add(ev.model()));
            s.client(h, null).run("go", tk);
            assertEquals(2, s.bodies.size());
            assertEquals("small-fast", s.bodies.get(0).get("model"));
            assertEquals("configured", s.bodies.get(1).get("model"));
            assertEquals(List.of("small-fast", "configured"), seen);
        }
        // an empty model is absent: the configured model, verbatim.
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", new AtomicInteger(), "D"))) {
            s.client(new LlmClient.Hooks().beforeLLM(ev -> LlmClient.LLMOverride.withModel("")), null).run("go", tk);
            assertEquals("configured", s.bodies.get(0).get("model"));
        }
    }

    private static final List<ModelRouterClassifier.Model> MODELS = List.of(
            new ModelRouterClassifier.Model("small-fast", "cheap"), new ModelRouterClassifier.Model("large-reasoning", "dear"));
    private static final String SURE = "{\"model\":{\"type\":\"choice\",\"choice\":\"small-fast\",\"confidence\":0.91,\"probabilities\":{\"small-fast\":0.91,\"large-reasoning\":0.09}}}";
    private static final String UNSURE = "{\"model\":{\"type\":\"choice\",\"choice\":\"small-fast\",\"confidence\":0.6,\"probabilities\":{\"small-fast\":0.6,\"large-reasoning\":0.4}}}";

    @Test
    void modelRouterHook() throws Exception {
        for (String[] tc : new String[][]{{SURE, "small-fast"}, {UNSURE, "configured"}}) {
            try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", new AtomicInteger(), "D"))) {
                var r = new ModelRouterClassifier(fixed(tc[0]), MODELS);
                s.client(new LlmClient.Hooks().beforeLLM(r.asHook(null)), null).run("capital of France?", tk);
                assertFalse(s.bodies.isEmpty());
                for (Map<String, Object> b : s.bodies) assertEquals(tc[1], b.get("model"));
            }
        }
        // unsure, or sure of the configured model itself: no override at all.
        var ev = new LlmClient.BeforeLLMEvent(List.of(Map.of("role", "user", "content", "x")), List.of(), "small-fast", 0);
        for (String a : List.of(UNSURE, SURE)) {
            assertNull(new ModelRouterClassifier(fixed(a), MODELS).asHook(null).apply(ev));
        }
        // no router attached: verbatim.
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", new AtomicInteger(), "D"))) {
            s.client(null, null).run("x", tk);
            assertEquals("configured", s.bodies.get(0).get("model"));
        }
        // next's model wins over the router's; next sees the routed model.
        AtomicReference<String> nextSaw = new AtomicReference<>();
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", new AtomicInteger(), "D"))) {
            var r = new ModelRouterClassifier(fixed(SURE), MODELS);
            s.client(new LlmClient.Hooks().beforeLLM(r.asHook(e -> {
                nextSaw.set(e.model());
                return LlmClient.LLMOverride.withModel("pinned");
            })), null).run("x", tk);
            assertEquals("small-fast", nextSaw.get());
            assertEquals("pinned", s.bodies.get(0).get("model"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolRelevanceHookDropsATool() throws Exception {
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", new AtomicInteger(), "D"), tool("send_email", new AtomicInteger(), "E"))) {
            var rel = new ToolRelevanceClassifier(fixed("{\"deploy\":{\"type\":\"noul\",\"noul\":0.9},\"send_email\":{\"type\":\"noul\",\"noul\":0.05}}"),
                    new ToolRelevanceClassifier.Options().onError(OnError.OPEN));
            s.client(new LlmClient.Hooks().beforeLLM(rel.asHook(null)), null).run("ship it", tk);
            List<Object> tools = (List<Object>) s.bodies.get(0).get("tools");
            assertEquals(1, tools.size());
            assertEquals("deploy", ToolRelevanceClassifier.providerTool(tools.get(0)).name());
        }
    }

    @Test
    void contentGuardHook() throws Exception {
        var g = new ContentGuardClassifier(fixed("{\"harmful\":{\"type\":\"noul\",\"noul\":0.96},\"prompt_injection\":{\"type\":\"noul\",\"noul\":0.9}}"),
                new ContentGuardClassifier.Options().onError(OnError.CLOSED));
        try (Stub s = new Stub(); Toolkit tk = toolkit(tool("deploy", new AtomicInteger(), "D"))) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> s.client(new LlmClient.Hooks().beforeLLM(g.asHook(null)), null).run("idiot", tk));
            Throwable t = e;
            while (!(t instanceof ContentGuardClassifier.BlockedException) && t.getCause() != null) t = t.getCause();
            assertEquals("content guard blocked: harmful, prompt_injection", t.getMessage());
            assertEquals(0, s.bodies.size());
        }
        AtomicBoolean called = new AtomicBoolean();
        var review = new ContentGuardClassifier(fixed("{\"harmful\":{\"type\":\"noul\",\"noul\":0.5},\"prompt_injection\":{\"type\":\"noul\",\"noul\":0.1}}"),
                new ContentGuardClassifier.Options().onError(OnError.CLOSED));
        var ev = new LlmClient.BeforeLLMEvent(List.of(Map.of("role", "user", "content", "meh")), List.of(), "m", 0);
        review.asHook(x -> { called.set(true); return null; }).apply(ev);
        assertTrue(called.get(), "review must delegate");
        var gErr = new ContentGuardClassifier(failing("boom"), new ContentGuardClassifier.Options().onError(OnError.CLOSED));
        var blocked = assertThrows(ContentGuardClassifier.BlockedException.class, () -> gErr.asHook(null).apply(ev));
        assertEquals("content guard blocked: classifier error", blocked.getMessage());
    }

    @Test
    void toolResultFilterHook() {
        var f = new ToolResultFilterClassifier(fixed("{\"0\":{\"type\":\"noul\",\"noul\":0.9},\"1\":{\"type\":\"noul\",\"noul\":0.05},\"2\":{\"type\":\"noul\",\"noul\":0.5}}"),
                new ToolResultFilterClassifier.Options().onError(OnError.OPEN));
        AtomicReference<String> nextSaw = new AtomicReference<>();
        var ov = f.asHook(ev -> { nextSaw.set(ev.result().output()); return null; })
                .apply(new LlmClient.AfterToolEvent("t", Map.of(), ToolResult.ok("a\n\nb\n\nc"), "c1", 0));
        assertNotNull(ov);
        assertEquals("a\n\nc", ov.result().output());
        assertEquals("a\n\nc", nextSaw.get());
        // single chunk, error result, parts: untouched, no call.
        var fe = new ToolResultFilterClassifier(failing("boom"), new ToolResultFilterClassifier.Options().onError(OnError.CLOSED));
        for (ToolResult r : List.of(ToolResult.ok("one"), ToolResult.error("a\n\nb"),
                ToolResult.ok("a\n\nb", List.of(ContentPart.image("image/png", new byte[]{1, 2}))))) {
            assertNull(fe.asHook(null).apply(new LlmClient.AfterToolEvent("t", Map.of(), r, "c1", 0)), r.output());
        }
    }
}
