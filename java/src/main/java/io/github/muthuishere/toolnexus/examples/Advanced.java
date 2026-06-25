package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.HttpTool;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Toolkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verify parallel tool calling (many calls in one turn) and chained tool calling
 * (a later call depends on an earlier result, across turns). Mirrors
 * {@code js/examples/advanced.ts}.
 *
 *   OPENROUTER_API_KEY=... gradle runAdvanced
 */
public final class Advanced {

    public static void main(String[] args) throws Exception {
        Toolkit tk = Toolkit.create(new Toolkit.Options());

        // add: two numbers -> sum (integer-formatted when whole, to mirror JS `${a + b}`)
        tk.register(NativeTool.of(
                "add",
                "Add two numbers and return the sum.",
                schemaTwoNumbers(),
                in -> {
                    double a = toDouble(in.get("a"));
                    double b = toDouble(in.get("b"));
                    double sum = a + b;
                    if (sum == Math.floor(sum) && !Double.isInfinite(sum)) {
                        return String.valueOf((long) sum);
                    }
                    return String.valueOf(sum);
                }));

        // http get_post: fetch a placeholder blog post by id
        HttpTool.Options http = new HttpTool.Options();
        http.name = "get_post";
        http.description = "Fetch a placeholder blog post by id.";
        http.method = "GET";
        http.url = "https://jsonplaceholder.typicode.com/posts/{id}";
        http.inputSchema = schemaOneId();
        tk.register(HttpTool.of(http));

        // opaque: the model CANNOT guess this value, so it must call it first and wait
        tk.register(NativeTool.of(
                "todays_post_id",
                "Returns the server-chosen blog post id to read today. Cannot be guessed; you must call it.",
                null,
                in -> "3"));

        String key = System.getenv("OPENROUTER_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("no OPENROUTER_API_KEY — skipping");
            tk.close();
            System.exit(0);
        }

        String model = System.getenv("OPENROUTER_MODEL");
        if (model == null || model.isEmpty()) model = "openai/gpt-4o-mini";

        LlmClient agent = LlmClient.create(new LlmClient.Options()
                .baseUrl("https://openrouter.ai/api/v1")
                .style("openai")
                .model(model)
                .apiKey(key)
                .systemPrompt("You are a precise agent. Prefer issuing independent tool calls together in one step."));

        // ---- A) PARALLEL: two independent adds, ideally in one turn ----
        LlmClient.RunResult a = agent.run(
                "In a single step, call the add tool twice: add 2 and 3, and add 100 and 200. Then report both sums.",
                tk);
        System.out.println("A) parallel — tool calls: " + callsLine(a));
        System.out.println("A) max calls in one turn: " + maxParallel(a.messages)
                + " | answer: " + clip(a.text, 80));

        // ---- B) CHAIN: second call depends on an OPAQUE first result (forces a 2nd turn) ----
        LlmClient.RunResult b = agent.run(
                "Call todays_post_id to find which post to read, then use get_post to fetch that exact id and tell me its title.",
                tk);
        System.out.println("\nB) chain — tool calls: " + callsLine(b));
        System.out.println("B) tool-calling turns (chain depth): " + toolTurns(b.messages));
        System.out.println("B) answer: " + clip(b.text, 100));

        tk.close();

        // ---- assertions ----
        boolean parallelOK = maxParallel(a.messages) >= 2;
        boolean calledAdd = a.toolCalls.stream().filter(c -> "add".equals(c.name)).count() >= 2;
        // true chain: todays_post_id THEN get_post(id=3) across >=2 turns (id=3 is unguessable)
        boolean chainOK = toolTurns(b.messages) >= 2
                && b.toolCalls.stream().anyMatch(c -> "todays_post_id".equals(c.name))
                && b.toolCalls.stream().anyMatch(c -> "get_post".equals(c.name) && idEquals(c.args.get("id"), 3));

        System.out.println("\nparallel: " + (parallelOK ? "OK multiple calls in one turn" : "WARN model serialized")
                + " | ran " + a.toolCalls.size() + " adds");
        System.out.println("chain:    " + (chainOK
                ? "OK get_post(id=3) used the opaque result across turns"
                : "FAIL chain not observed"));
        if (!calledAdd || !parallelOK || !chainOK) {
            System.err.println("FAIL expected >=2 parallel adds AND a real cross-turn chain "
                    + "(todays_post_id -> get_post(id=3))");
            System.exit(1);
        }
        System.out.println("\nOK parallel + chained tool calling verified");
        System.exit(0);
    }

    /** Largest number of tool calls the model emitted in a single assistant turn. */
    @SuppressWarnings("unchecked")
    private static int maxParallel(List<Object> messages) {
        int m = 0;
        for (Object o : messages) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> msg = (Map<String, Object>) o;
            if ("assistant".equals(msg.get("role")) && msg.get("tool_calls") instanceof List) {
                m = Math.max(m, ((List<Object>) msg.get("tool_calls")).size());
            }
        }
        return m;
    }

    /** Number of assistant turns that issued tool calls (chain depth). */
    @SuppressWarnings("unchecked")
    private static int toolTurns(List<Object> messages) {
        int n = 0;
        for (Object o : messages) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> msg = (Map<String, Object>) o;
            if ("assistant".equals(msg.get("role")) && msg.get("tool_calls") instanceof List
                    && !((List<Object>) msg.get("tool_calls")).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static String callsLine(LlmClient.RunResult r) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < r.toolCalls.size(); i++) {
            if (i > 0) sb.append(", ");
            LlmClient.ToolCall c = r.toolCalls.get(i);
            sb.append(c.name).append("(").append(io.github.muthuishere.toolnexus.Json.stringify(c.args)).append(")");
        }
        return sb.append("]").toString();
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String oneLine = s.replace("\n", " ");
        return oneLine.length() > n ? oneLine.substring(0, n) : oneLine;
    }

    private static boolean idEquals(Object v, int expected) {
        if (v == null) return false;
        if (v instanceof Number) return ((Number) v).intValue() == expected;
        try {
            return (int) Double.parseDouble(String.valueOf(v)) == expected;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    private static Map<String, Object> schemaTwoNumbers() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "number");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "number");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("a", a);
        props.put("b", b);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("a", "b"));
        return schema;
    }

    private static Map<String, Object> schemaOneId() {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("type", "number");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", id);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("id"));
        return schema;
    }
}
