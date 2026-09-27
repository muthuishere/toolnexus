package judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.muthuishere.toolnexus.Classifier;
import io.github.muthuishere.toolnexus.Classifier.Question;
import judge.Judge.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static judge.Judge.*;
import static org.junit.jupiter.api.Assertions.*;

class JudgeTest {
    static final ObjectMapper M = new ObjectMapper();
    static final Path SHARED = Path.of("..", "shared");

    static JsonNode read(String f) throws Exception { return M.readTree(SHARED.resolve(f).toFile()); }

    @SuppressWarnings("unchecked")
    static <T> T plain(JsonNode n) { return (T) M.convertValue(n, Object.class); }

    static Q q(JsonNode n) {
        String name = n.get("name").asText(), ins = n.get("instructions").asText();
        return switch (n.get("kind").asText()) {
            case "noul" -> noul(name, ins);
            case "choice" -> choice(name, ins, plain(n.get("options")));
            case "score" -> score(name, ins, (List<String>) plain(n.get("levels")));
            default -> throw new IllegalStateException();
        };
    }

    /** The §8B wire form of the questions, for comparing against wantQuestions. */
    static JsonNode wire(Map<String, Question> qs) {
        Map<String, Object> out = new LinkedHashMap<>();
        qs.forEach((k, v) -> out.put(k, v.wire()));
        return M.valueToTree(out);
    }

    @TestFactory
    Stream<DynamicTest> stateCases() throws Exception {
        List<DynamicTest> ts = new ArrayList<>();
        for (JsonNode c : read("state-cases.json").get("cases")) {
            ts.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                List<Q> qs = new ArrayList<>();
                c.get("questions").forEach(n -> qs.add(q(n)));
                if (c.has("wantError")) {
                    var e = assertThrows(IllegalArgumentException.class, () -> questions(qs));
                    assertEquals(c.get("wantError").asText(), e.getMessage());
                    return;
                }
                Map<String, Object> state = c.has("context")
                        ? State.of(c.get("context").get("context").asText(), c.get("context").get("message").asText(),
                                   plain(c.get("context").get("extra")))
                        : State.of(plain(c.get("state")));
                assertEquals(c.get("wantState"), M.valueToTree(state));
                assertEquals(c.get("wantQuestions"), wire(questions(qs)));
            }));
        }
        return ts.stream();
    }

    @TestFactory
    Stream<DynamicTest> gateCases() throws Exception {
        JsonNode root = read("gate-cases.json");
        List<Q> qs = new ArrayList<>();
        root.get("questions").fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            String ins = v.get("instructions").asText();
            qs.add(switch (v.get("type").asText()) {
                case "noul" -> new Q(e.getKey(), new Classifier.NoulQuestion(ins, new Classifier.NoulCriteria(
                        v.get("criteria").get("true").asText(), v.get("criteria").get("false").asText())));
                case "choice" -> choice(e.getKey(), ins, plain(v.get("criteria")));
                default -> score(e.getKey(), ins, (List<String>) plain(v.get("criteria")));
            });
        });
        List<Rule> rules = new ArrayList<>();
        for (JsonNode r : root.get("rules")) {
            String qn = r.get("question").asText(), act = r.get("action").asText();
            String tgt = r.has("target") ? r.get("target").asText() : "";
            rules.add(r.has("below") ? Rule.below(qn, r.get("below").asDouble(), act)
                    : r.has("at_least") ? Rule.atLeast(qn, r.get("at_least").asDouble(), act, tgt)
                    : Rule.is(qn, r.get("is").asText(), act, tgt));
        }
        Map<String, Object> state = State.of("triage bugs", "checkout 500");

        List<DynamicTest> ts = new ArrayList<>();
        for (JsonNode c : root.get("cases")) {
            ts.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                String resp = M.writeValueAsString(Map.of("model", "jev-static", "answers", c.get("answers"),
                        "usage", Map.of("input_tokens", 0, "output_tokens", 0)));
                Classifier cl = Classifier.create(new Classifier.Options().style(Classifier.STYLE_STATIC)
                        .model("jev-static")
                        .decisions(List.of(new Classifier.RecordedDecision(state, questions(qs), resp))));
                JsonNode bn = c.get("bands");
                Bands b = bn.isNull() ? null : new Bands(bn.get("low").asDouble(), bn.get("high").asDouble());
                Outcome o = gate(cl, state, qs, rules, b);
                JsonNode w = c.get("want");
                assertEquals(w.get("action").asText(), o.action());
                assertEquals(w.get("target").asText(), o.target());
                assertEquals(w.get("escalated").asBoolean(), o.escalated());
                if (o.escalated()) {
                    assertEquals("input", o.request().kind());
                    assertTrue(o.request().data().keySet().containsAll(List.of("question", "reason", "answers")));
                }
            }));
        }
        return ts.stream();
    }

    @Test
    void askReadsLikeTheVideo() {
        var state = State.of(Map.of("role", "You are Donkey Kong, you want to win.", "message_received", "jump off the stage now"));
        var qs = List.of(
                noul("is_appropriate", "Does the message contain inappropriate language?"),
                noul("does_this_help", "Does this help donkey kong win?"));
        var c = Classifier.create(new Classifier.Options().style(Classifier.STYLE_STATIC).model("jev-static")
                .decisions(List.of(new Classifier.RecordedDecision(state, questions(qs),
                        "{\"model\":\"jev-static\",\"answers\":{\"is_appropriate\":{\"type\":\"noul\",\"noul\":0.1},"
                                + "\"does_this_help\":{\"type\":\"noul\",\"noul\":0.5}},\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}"))));
        var d = ask(c, state, qs);
        assertEquals(Band.NO, d.get("is_appropriate").band());
        assertEquals(Band.UNCERTAIN, d.get("does_this_help").band());
    }
}
