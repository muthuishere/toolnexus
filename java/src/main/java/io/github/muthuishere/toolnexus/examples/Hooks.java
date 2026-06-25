package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.Toolkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lifecycle hooks: beforeLLM / afterLLM / beforeTool / afterTool — observe, mutate,
 * and short-circuit. Mirrors {@code js/examples/hooks.ts}.
 *
 * <p>We register {@code add} + {@code get_post}, count the four hooks, and DENY
 * {@code get_post} in {@code beforeTool} (short-circuit with an error result). A
 * counter inside the {@code get_post} tool fn proves its real execution never ran.
 *
 *   OPENROUTER_API_KEY=... gradle runHooks
 */
public final class Hooks {

    public static void main(String[] args) throws Exception {
        Toolkit tk = Toolkit.create(new Toolkit.Options());

        // add: two numbers -> sum (integer-formatted when whole, to mirror JS).
        tk.register(NativeTool.of(
                "add",
                "Add two numbers.",
                schemaTwoNumbers(),
                in -> {
                    double a = toDouble(in.get("a"));
                    double b = toDouble(in.get("b"));
                    double sum = a + b;
                    if (sum == Math.floor(sum) && !Double.isInfinite(sum)) return String.valueOf((long) sum);
                    return String.valueOf(sum);
                }));

        // get_post: counts every REAL execution. We deny it in beforeTool, so this
        // counter must stay 0 — the short-circuit means the body never runs.
        AtomicInteger realGetPostHits = new AtomicInteger(0);
        tk.register(NativeTool.of(
                "get_post",
                "Fetch a post by id.",
                schemaOneId(),
                in -> {
                    realGetPostHits.incrementAndGet();
                    return "post #" + in.get("id");
                }));

        String key = System.getenv("OPENROUTER_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("no OPENROUTER_API_KEY — skipping");
            tk.close();
            System.exit(0);
        }

        String model = System.getenv("OPENROUTER_MODEL");
        if (model == null || model.isEmpty()) model = "openai/gpt-4o-mini";

        AtomicInteger beforeLLM = new AtomicInteger(0);
        AtomicInteger afterLLM = new AtomicInteger(0);
        AtomicInteger beforeTool = new AtomicInteger(0);
        AtomicInteger afterTool = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);

        LlmClient.Hooks hooks = new LlmClient.Hooks()
                .beforeLLM(ev -> {
                    beforeLLM.incrementAndGet();
                    System.out.println("  [beforeLLM] turn " + ev.turn());
                    return null; // observe only — no override
                })
                .afterLLM(ev -> {
                    afterLLM.incrementAndGet();
                    System.out.println("  [afterLLM] turn " + ev.turn() + " usage " + ev.response().get("usage"));
                })
                .beforeTool(ev -> {
                    beforeTool.incrementAndGet();
                    System.out.println("  [beforeTool] " + ev.name() + "("
                            + io.github.muthuishere.toolnexus.Json.stringify(ev.args()) + ")");
                    if ("get_post".equals(ev.name())) {
                        denied.incrementAndGet();
                        // SHORT-CIRCUIT: deny the tool. The model never sees real data and
                        // the real get_post fn never runs (realGetPostHits stays 0).
                        return LlmClient.ToolOverride.withResult(
                                ToolResult.error("DENIED: get_post is blocked by policy"));
                    }
                    return null;
                })
                .afterTool(ev -> {
                    afterTool.incrementAndGet();
                    String out = ev.result().output();
                    System.out.println("  [afterTool] " + ev.name() + " -> "
                            + out.substring(0, Math.min(40, out.length())));
                    return null; // observe only — keep the result as-is
                });

        LlmClient agent = LlmClient.create(new LlmClient.Options()
                .baseUrl("https://openrouter.ai/api/v1")
                .style("openai")
                .model(model)
                .apiKey(key)
                .systemPrompt("You are an agent. Use tools when asked.")
                .hooks(hooks));

        LlmClient.RunResult out = agent.run(
                "Add 2 and 3 with the add tool, then fetch post id 1 with get_post.", tk);
        tk.close();

        System.out.println("\nFINAL: " + clip(out.text, 100));
        System.out.println("hook counts: beforeLLM=" + beforeLLM.get()
                + " afterLLM=" + afterLLM.get()
                + " beforeTool=" + beforeTool.get()
                + " afterTool=" + afterTool.get()
                + " denied=" + denied.get()
                + " | get_post actually hit: " + realGetPostHits.get()
                + " | usage: prompt=" + out.usage.promptTokens
                + " completion=" + out.usage.completionTokens
                + " total=" + out.usage.totalTokens);

        boolean ok = beforeLLM.get() >= 1 && afterLLM.get() >= 1
                && beforeTool.get() >= 2 && afterTool.get() >= 1
                && denied.get() >= 1 && realGetPostHits.get() == 0; // deny short-circuited the real fn
        if (!ok) {
            System.err.println("FAIL hooks did not all fire / short-circuit failed");
            System.exit(1);
        }
        System.out.println("\nOK all four hooks fired; beforeTool short-circuited get_post (0 real hits)");
        System.exit(0);
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String oneLine = s.replace("\n", " ");
        return oneLine.length() > n ? oneLine.substring(0, n) : oneLine;
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
