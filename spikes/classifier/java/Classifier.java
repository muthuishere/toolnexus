
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR 0020 §8B — the typed-decision contract. {@code evaluate(state, questions) -> Decision}.
 * Backends sit behind it exactly as MCP/native/HTTP sit behind {@code Tool}.
 */
@FunctionalInterface
public interface Classifier {

    Decision evaluate(Object state, Map<String, Question> questions);

    // ---------------------------------------------------------------- questions

    /** {@code Noul | Choice | Score}. The three {@code criteria} shapes are three record
     *  components, not one {@code Object} — the union is the sealed hierarchy. */
    sealed interface Question {
        String instructions();

        /** The wire object for this question, {@code type} discriminator included. */
        Map<String, Object> wire();

        /** 0..1 truth. {@code criteria} is ABSENT unless both labels are given. */
        record Noul(String instructions, String whenTrue, String whenFalse) implements Question {
            public Noul(String instructions) { this(instructions, null, null); }

            @Override public Map<String, Object> wire() {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "noul");
                m.put("instructions", instructions);
                if (whenTrue != null || whenFalse != null) {
                    m.put("criteria", Map.of("true", whenTrue, "false", whenFalse));
                }
                return m;
            }
        }

        /** &le;255 named options. {@code criteria} is an OBJECT (name -> description|null). */
        record Choice(String instructions, Map<String, String> criteria) implements Question {
            public Choice {
                if (criteria.size() > 255) throw new IllegalArgumentException("choice: >255 options");
            }
            @Override public Map<String, Object> wire() {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "choice");
                m.put("instructions", instructions);
                m.put("criteria", criteria);
                return m;
            }
        }

        /** 2..10 ordered levels. {@code criteria} is an ARRAY — order IS the level numbering. */
        record Score(String instructions, List<String> criteria) implements Question {
            public Score {
                if (criteria.size() < 2 || criteria.size() > 10)
                    throw new IllegalArgumentException("score: need 2..10 levels");
            }
            @Override public Map<String, Object> wire() {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "score");
                m.put("instructions", instructions);
                m.put("criteria", criteria);
                return m;
            }
        }
    }

    // ---------------------------------------------------------------- answers

    /** The heterogeneous {@code answers} map's value type, discriminated by {@code type}. */
    sealed interface Answer {
        record Noul(double noul) implements Answer {}

        record Choice(String choice, Map<String, Double> probabilities, double confidence)
                implements Answer {}

        record Score(double score, Map<String, String> legend,
                     Map<String, Double> probabilities, double confidence) implements Answer {}
    }

    record Usage(long inputTokens, long outputTokens, Double cost) {}

    record Decision(String model, Map<String, Answer> answers, Usage usage, boolean calibrated) {
        /** Typed read-out. Throws if the key answered with a different question type. */
        public Answer.Noul noul(String key) { return get(key, Answer.Noul.class); }
        public Answer.Choice choice(String key) { return get(key, Answer.Choice.class); }
        public Answer.Score score(String key) { return get(key, Answer.Score.class); }

        private <T extends Answer> T get(String key, Class<T> want) {
            Answer a = answers.get(key);
            if (a == null) throw new IllegalArgumentException("no answer for " + key);
            if (!want.isInstance(a))
                throw new IllegalStateException(key + " answered " + a.getClass().getSimpleName());
            return want.cast(a);
        }
    }

    // ---------------------------------------------------------------- wire

    ObjectMapper MAPPER = new ObjectMapper();

    /** The canonical request body: sorted keys, compact, UTF-8, no trailing newline. */
    static byte[] requestBody(String model, Object state, Map<String, Question> questions) {
        var qs = new LinkedHashMap<String, Object>();
        questions.forEach((k, q) -> qs.put(k, q.wire()));
        return Canon.write(Map.of("model", model, "state", state, "questions", qs))
                .getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static Decision parse(String json) {
        Map<String, Object> m;
        try {
            m = MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("bad decision JSON: " + e.getMessage(), e);
        }
        var answers = new LinkedHashMap<String, Answer>();
        ((Map<String, Map<String, Object>>) m.get("answers")).forEach((key, a) ->
                answers.put(key, switch ((String) a.get("type")) {
                    case "noul" -> new Answer.Noul(num(a.get("noul")));
                    case "choice" -> new Answer.Choice((String) a.get("choice"),
                            probs(a.get("probabilities")), num(a.get("confidence")));
                    case "score" -> new Answer.Score(num(a.get("score")),
                            (Map<String, String>) a.get("legend"),
                            probs(a.get("probabilities")), num(a.get("confidence")));
                    default -> throw new IllegalStateException("unknown answer type " + a.get("type"));
                }));
        var u = (Map<String, Object>) m.getOrDefault("usage", Map.of());
        var usage = new Usage((long) num(u.getOrDefault("input_tokens", 0)),
                (long) num(u.getOrDefault("output_tokens", 0)),
                u.get("cost") == null ? null : num(u.get("cost")));
        return new Decision((String) m.get("model"), answers, usage,
                (boolean) m.getOrDefault("calibrated", true));
    }

    private static double num(Object o) { return ((Number) o).doubleValue(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> probs(Object o) {
        var out = new LinkedHashMap<String, Double>();
        ((Map<String, Object>) o).forEach((k, v) -> out.put(k, num(v)));
        return out;
    }

    // ---------------------------------------------------------------- backends

    /** {@code style: "static"} — the recorded-fixture backend CI runs. No network, no key. */
    static Classifier ofStatic(String model, Map<String, String> byRequestBody) {
        return (state, questions) -> {
            String req = new String(requestBody(model, state, questions), StandardCharsets.UTF_8);
            String res = byRequestBody.get(req);
            if (res == null) throw new IllegalStateException("static backend: no fixture for " + req);
            return parse(res);
        };
    }

    /** {@code style: "systemone"} — one POST, java.net.http, no SDK. */
    static Classifier ofSystemOne(String baseUrl, String model, String apiKeyEnv) {
        HttpClient http = HttpClient.newHttpClient();
        return (state, questions) -> {
            var body = requestBody(model, state, questions);
            var req = HttpRequest.newBuilder(URI.create(baseUrl))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + System.getenv(apiKeyEnv))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                var res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 != 2)
                    throw new IllegalStateException("classifier HTTP " + res.statusCode() + ": " + res.body());
                return parse(res.body());
            } catch (Exception e) {
                throw new RuntimeException("classifier call failed: " + e.getMessage(), e);
            }
        };
    }
}
