package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.muthuishere.toolnexus.Classifier.ClassifierException;
import io.github.muthuishere.toolnexus.Classifier.Decision;
import io.github.muthuishere.toolnexus.Classifier.Question;
import io.github.muthuishere.toolnexus.Classifier.RecordedDecision;
import io.github.muthuishere.toolnexus.Judge.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.muthuishere.toolnexus.Judge.*;
import static org.junit.jupiter.api.Assertions.*;

/** SPEC.md §8B "Simple judgments", driven off {@code examples/judge/adapters/}. */
class JudgeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode fixture(String name) throws Exception {
        return M.readTree(Files.readString(Path.of(TestFixtures.fixture("judge/adapters/" + name + ".json"))));
    }

    @SuppressWarnings("unchecked")
    private static <T> T plain(JsonNode n, Class<T> c) { return M.convertValue(n, c); }

    private static List<Named> named(JsonNode qs) {
        List<Named> out = new ArrayList<>();
        for (JsonNode q : qs) {
            String kind = q.get("kind").asText(), name = q.get("name").asText(), ins = q.get("instructions").asText();
            out.add(switch (kind) {
                case "noul" -> noul(name, ins);
                case "choice" -> {
                    Map<String, String> opts = new LinkedHashMap<>();
                    q.get("options").fields().forEachRemaining(e -> opts.put(e.getKey(), e.getValue().asText()));
                    yield choice(name, ins, opts);
                }
                default -> {
                    List<String> lv = new ArrayList<>();
                    q.get("levels").forEach(l -> lv.add(l.asText()));
                    yield score(name, ins, lv);
                }
            });
        }
        return out;
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

    // ------------------------------------------------------------------ state cases

    @Test
    void everyStateCaseHolds() throws Exception {
        int n = 0;
        for (JsonNode c : fixture("state-cases").get("cases")) {
            n++;
            if (c.has("wantError")) {
                var e = assertThrows(ClassifierException.class, () -> questions(named(c.get("questions"))));
                assertTrue(e.getMessage().contains(c.get("wantError").asText()), e.getMessage());
                continue;
            }
            Map<String, Object> state;
            if (c.has("context")) {
                JsonNode x = c.get("context");
                state = State.context(x.get("context").asText(), x.get("message").asText(),
                        x.has("extra") ? plain(x.get("extra"), Map.class) : Map.of());
            } else if (c.has("roleState")) {
                JsonNode rs = c.get("roleState");
                Object data = rs.get("data").isObject() ? plain(rs.get("data"), Map.class) : rs.get("data").asText();
                state = State.of(rs.get("role").asText(), data);
            } else {
                state = State.of(plain(c.get("state"), Map.class));
            }
            assertEquals(plain(c.get("wantState"), Map.class), state, c.get("name").asText());
            Map<String, Question> qs = questions(named(c.get("questions")));
            assertEquals(plain(c.get("wantQuestions"), Map.class), Classifier.toWire(qs));
            assertEquals(List.copyOf(plain(c.get("wantQuestions"), LinkedHashMap.class).keySet()), List.copyOf(qs.keySet()));
        }
        assertEquals(fixture("state-cases").get("cases").size(), n);
        assertTrue(n >= 5);
    }

    @Test
    void roleSitsNextToDataAndNonObjectIsWrapped() {
        var s = State.of("You are Donkey Kong, you want to win.", Map.of("message_received", "hi"));
        assertEquals(List.of("role", "message_received"), List.copyOf(s.keySet()));
        assertEquals(Map.of("role", "r", "data", "plain text"), State.of("r", "plain text"));
    }

    // ------------------------------------------------------------------ gate cases

    private static final Object GATE_STATE = Map.of("report", "checkout 500 on coupon SAVE10");

    private static Classifier recorded(Map<String, Question> qs, JsonNode answers) throws Exception {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("model", Classifier.DEFAULT_MODEL);
        resp.put("answers", plain(answers, Map.class));
        return Classifier.fromRecorded(List.of(new RecordedDecision(GATE_STATE, qs, M.writeValueAsString(resp))));
    }

    private static List<Rule> rules(JsonNode rs) {
        List<Rule> out = new ArrayList<>();
        for (JsonNode r : rs) {
            String q = r.get("question").asText(), a = r.get("action").asText();
            String t = r.has("target") ? r.get("target").asText() : "";
            if (r.has("below")) out.add(Rule.below(q, r.get("below").asDouble(), a, t));
            else if (r.has("at_least")) out.add(Rule.atLeast(q, r.get("at_least").asDouble(), a, t));
            else out.add(Rule.is(q, r.get("is").asText(), a, t));
        }
        return out;
    }

    private static List<Named> asNamed(Map<String, Question> qs) {
        List<Named> l = new ArrayList<>();
        qs.forEach((k, v) -> l.add(new Named(k, v)));
        return l;
    }

    @Test
    void everyGateCaseHolds() throws Exception {
        JsonNode f = fixture("gate-cases");
        Map<String, Question> qs = wireQuestions(f.get("questions"));
        List<Rule> topRules = rules(f.get("rules"));
        int n = 0;
        for (JsonNode c : f.get("cases")) {
            n++;
            String name = c.get("name").asText();
            List<Rule> rules = c.has("rules") ? rules(c.get("rules")) : topRules;
            JsonNode bn = c.get("bands");
            Bands bands = bn == null || bn.isNull() ? null : new Bands(bn.get("low").asDouble(), bn.get("high").asDouble());
            JsonNode pn = c.get("policy");
            Outcome o = pn == null || pn.isNull()
                    ? gate(recorded(qs, c.get("answers")), GATE_STATE, asNamed(qs), rules, bands)
                    : new Policy(rules, pn.get("default").asText(), bands, pn.get("skipUncertain").asBoolean())
                            .decide(recorded(qs, c.get("answers")), GATE_STATE, asNamed(qs));
            var got = ask(recorded(qs, c.get("answers")), GATE_STATE, asNamed(qs), bands);
            JsonNode wa = c.get("wantAnswers");
            assertEquals(wa.size(), got.size(), name);
            wa.fields().forEachRemaining(e -> {
                Judge.Answer a = got.get(e.getKey());
                JsonNode x = e.getValue();
                String at = name + "/" + e.getKey();
                assertNotNull(a, at);
                assertEquals(x.get("value").asDouble(), a.value(), at);
                if (x.has("band")) assertEquals(x.get("band").asText(), a.band().name().toLowerCase(), at);
                if (x.has("sure")) assertEquals(x.get("sure").asBoolean(), a.sure(), at);
                if (x.has("choice")) assertEquals(x.get("choice").asText(), a.choice(), at);
            });
            JsonNode w = c.get("want");
            assertEquals(w.get("action").asText(), o.action(), name);
            assertEquals(w.get("target").asText(), o.target(), name);
            assertEquals(w.get("escalated").asBoolean(), o.escalated(), name);
            if (o.escalated()) {
                assertEquals("input", o.request().kind(), name);
                assertTrue(o.request().data().keySet().containsAll(List.of("question", "reason", "answers")), name);
            }
            if (o.escalated()) {
                assertEquals(w.get("question").asText(), o.request().data().get("question"), name);
                if (w.has("reason")) assertEquals(w.get("reason").asText(), o.request().data().get("reason"), name);
                assertEquals(w.get("requestId").asText(), o.request().id(), name);
            }
        }
        assertEquals(f.get("cases").size(), n);
        assertTrue(n >= 13);
    }

    // ------------------------------------------------------------------ bands / answers

    @Test
    void bandsAreExclusiveAndOverridable() {
        assertEquals(Band.UNCERTAIN, Bands.DEFAULT.of(0.30));
        assertEquals(Band.UNCERTAIN, Bands.DEFAULT.of(0.70));
        assertEquals(Band.NO, Bands.DEFAULT.of(0.29));
        assertEquals(Band.YES, new Bands(0.20, 0.50).of(0.55));
        var nearUniform = new Classifier.ChoiceAnswer("a", Map.of("a", 0.5, "b", 0.5), 0.80, true);
        assertFalse(Bands.DEFAULT.sure(nearUniform));
    }

    @Test
    void askReturnsAnswersByNameWithValue() throws Exception {
        Map<String, Question> qs = questions(List.of(noul("is_appropriate", "Is message_received appropriate?"),
                choice("component", "Which component?", Map.of("pricing", "coupon code", "checkout", "handler"))));
        String resp = "{\"model\":\"m\",\"answers\":{\"is_appropriate\":{\"type\":\"noul\",\"noul\":0.96},"
                + "\"component\":{\"type\":\"choice\",\"choice\":\"pricing\",\"confidence\":0.9,"
                + "\"probabilities\":{\"pricing\":0.9,\"checkout\":0.1}}}}";
        Classifier c = Classifier.fromRecorded("m", List.of(new RecordedDecision("s", qs, resp)));
        var a = ask(c, "s", asNamed(qs));
        assertEquals(0.96, a.get("is_appropriate").value());
        assertEquals(Band.YES, a.get("is_appropriate").band());
        assertEquals("pricing", a.get("component").choice());
        assertTrue(a.get("component").sure());
    }

    // ------------------------------------------------------------------ policy

    private static Map<String, Classifier.DecisionAnswer> answers(double first, double second) {
        Map<String, Classifier.DecisionAnswer> m = new LinkedHashMap<>();
        m.put("a", new Classifier.NoulAnswer(first));
        m.put("b", new Classifier.NoulAnswer(second));
        return m;
    }

    @Test
    void policyNoRuleFiredEscalatesAndDefaultFires() {
        List<Rule> rs = List.of(Rule.below("a", 0.3, "fail"));
        Outcome o = new Policy(rs).apply(answers(0.9, 0.9));
        assertTrue(o.escalated());
        assertEquals("no rule fired", o.request().data().get("reason"));
        assertEquals("proceed", new Policy(rs, "proceed", null, false).apply(answers(0.9, 0.9)).action());
    }

    @Test
    void skipUncertainLetsLaterRuleFire() {
        List<Rule> rs = List.of(Rule.below("a", 0.3, "fail"), Rule.atLeast("b", 0.5, "go", "next"));
        assertTrue(new Policy(rs, "", null, false).apply(answers(0.5, 0.9)).escalated());
        Outcome o = new Policy(rs, "", null, true).apply(answers(0.5, 0.9));
        assertEquals("go", o.action());
        assertEquals("next", o.target());
    }

    // ------------------------------------------------------------------ tape

    @Test
    void tapeRecordsAndReplaysAndMissNamesKey() {
        Decision d = new Decision("m", Map.of("x", new Classifier.NoulAnswer(0.8)), null, true);
        AtomicInteger calls = new AtomicInteger();
        Classifier live = Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM)
                .evaluate((s, q) -> { calls.incrementAndGet(); return d; }));
        Tape tape = new Tape(live);
        var qs = List.of(noul("x", "Is x?"));
        ask(tape.record("route"), "s", qs);
        assertEquals(0.8, ask(tape.replay("route"), "s", qs).get("x").value());
        assertEquals(1, calls.get());
        Classifier miss = tape.replay("plan"); // obtaining a replayer for an unrecorded name never fails
        var e = assertThrows(ClassifierException.class, () -> ask(miss, "s", qs));
        assertEquals("tape: no recorded decision for call \"plan\"", e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void uncertaintyIsCheckedBeforeRuleFit() {
        // An is-rule on an uncertain noul: the uncertainty wins, so skipUncertain skips it.
        List<Rule> rs = List.of(Rule.is("a", "x", "one"), Rule.atLeast("b", 0.8, "two"));
        Outcome e = new Policy(rs, "", null, false).apply(answers(0.5, 0.9));
        assertEquals("uncertain answer \"a\"", e.request().data().get("reason"));
        assertEquals("gate:0:a", e.request().id());
        assertEquals("two", new Policy(rs, "", null, true).apply(answers(0.5, 0.9)).action());
    }

    @Test
    void misfitRulesEscalateAndAreNeverSkipped() {
        var noCondition = new Rule("a", null, null, null, "x", "");
        for (Rule r : List.of(noCondition, Rule.is("a", "x", "one"))) {
            Outcome o = new Policy(List.of(r, Rule.atLeast("b", 0.5, "go")), "", null, true).apply(answers(0.9, 0.9));
            assertTrue(o.escalated());
            assertEquals("gate:0:a", o.request().id());
        }
    }

    @Test
    void skipUncertainDoesNotSkipAMissingAnswer() {
        Outcome o = new Policy(List.of(Rule.below("zz", 0.3, "fail"), Rule.atLeast("b", 0.5, "go")), "", null, true)
                .apply(answers(0.5, 0.9));
        assertTrue(o.escalated());
        assertEquals("missing answer \"zz\"", o.request().data().get("reason"));
        assertEquals("zz", o.request().data().get("question"));
        assertEquals("gate:0:zz", o.request().id());
    }

    // ------------------------------------------------------------------ byte identity

    @Test
    void builderBodyIsByteIdenticalToHandWritten() throws Exception {
        List<String> bodies = new ArrayList<>();
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = "{\"model\":\"m\",\"answers\":{\"is_appropriate\":{\"type\":\"noul\",\"noul\":0.1}}}".getBytes();
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        http.start();
        try {
            Classifier c = Classifier.create(new Classifier.Options()
                    .baseUrl("http://127.0.0.1:" + http.getAddress().getPort()).model("m")
                    .apiKeyEnv("JUDGE_TEST_FAKE_KEY").env(k -> "YOUR_KEY_HERE"));
            String role = "You are Donkey Kong, you want to win.";
            Map<String, Object> hand = new LinkedHashMap<>();
            hand.put("role", role);
            hand.put("message_received", "jump off the stage now");
            Map<String, Question> handQs = new LinkedHashMap<>();
            handQs.put("is_appropriate", new Classifier.NoulQuestion("Does message_received contain insults?"));
            c.evaluate(hand, handQs);
            ask(c, State.of(role, Map.of("message_received", "jump off the stage now")),
                    List.of(noul("is_appropriate", "Does message_received contain insults?")));
        } finally {
            http.stop(0);
        }
        assertEquals(2, bodies.size());
        assertEquals(bodies.get(0), bodies.get(1));
    }

    // ------------------------------------------------------------------ batch

    private static Classifier byState() {
        return Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM).evaluate((s, q) -> {
            if ("boom".equals(s)) throw new ClassifierException("no recorded decision");
            if ("s0".equals(s)) { try { Thread.sleep(40); } catch (InterruptedException ignored) { } }
            double v = Double.parseDouble(((String) s).substring(1)) / 10;
            return new Decision("m", Map.of("x", new Classifier.NoulAnswer(v)), null, true);
        }));
    }

    @Test
    void batchKeepsStateOrder() {
        var ds = byState().evaluateBatch(List.of("s0", "s1", "s2"), questions(List.of(noul("x", "?"))));
        assertEquals(3, ds.size());
        for (int i = 0; i < 3; i++) assertEquals(i / 10.0, ds.get(i).noul("x").noul());
    }

    @Test
    void batchFailsClosedNamingIndex() {
        var e = assertThrows(ClassifierException.class,
                () -> byState().evaluateBatch(List.of("s0", "boom", "s2"), questions(List.of(noul("x", "?")))));
        assertTrue(e.getMessage().contains("state 1"), e.getMessage());
    }

    @Test
    void batchWithSeveralFailuresNamesLowestIndex() {
        // state 2 fails fast, state 0 fails late: the error still names state 0.
        Classifier c = Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM).evaluate((s, q) -> {
            if ("late".equals(s)) { try { Thread.sleep(40); } catch (InterruptedException ignored) { } }
            throw new ClassifierException("boom " + s);
        }));
        var e = assertThrows(ClassifierException.class,
                () -> c.evaluateBatch(List.of("late", "s1", "fast"), questions(List.of(noul("x", "?")))));
        assertTrue(e.getMessage().contains("state 0"), e.getMessage());
    }

    @Test
    void emptyBatchIsAnErrorAndSendsNothing() {
        AtomicInteger calls = new AtomicInteger();
        Classifier c = Classifier.create(new Classifier.Options().style(Classifier.STYLE_CUSTOM)
                .evaluate((s, q) -> { calls.incrementAndGet(); return null; }));
        assertThrows(ClassifierException.class, () -> c.evaluateBatch(List.of(), questions(List.of(noul("x", "?")))));
        assertEquals(0, calls.get());
    }
}
