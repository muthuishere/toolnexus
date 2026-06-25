package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Toolkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streaming: live text deltas + tool_call / tool_result / usage / done events,
 * delivered to a {@code Consumer<StreamEvent>}. Mirrors {@code js/examples/streaming.ts}.
 *
 *   OPENROUTER_API_KEY=... gradle runStreaming
 */
public final class Streaming {

    public static void main(String[] args) throws Exception {
        Toolkit tk = Toolkit.create(new Toolkit.Options());
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
                .apiKey(key));

        AtomicInteger textDeltas = new AtomicInteger(0);
        AtomicInteger toolCallEv = new AtomicInteger(0);
        AtomicInteger toolResultEv = new AtomicInteger(0);
        StringBuilder streamedText = new StringBuilder();
        AtomicReference<LlmClient.RunResult> done = new AtomicReference<>();

        System.out.print("stream: ");
        agent.stream(
                "Use the add tool to compute 21 + 21, then say the result in one short sentence.",
                tk,
                ev -> {
                    switch (ev.type()) {
                        case TEXT -> {
                            textDeltas.incrementAndGet();
                            streamedText.append(ev.delta());
                            System.out.print(ev.delta());
                            System.out.flush();
                        }
                        case TOOL_CALL -> {
                            toolCallEv.incrementAndGet();
                            System.out.println("\n[tool_call] " + ev.name() + "(" + Json.stringify(ev.args()) + ")");
                        }
                        case TOOL_RESULT -> {
                            toolResultEv.incrementAndGet();
                            System.out.println("[tool_result] " + ev.name() + " -> " + ev.output());
                        }
                        case USAGE -> System.out.println("[usage] total=" + ev.usage().totalTokens);
                        case DONE -> done.set(ev.result());
                    }
                });
        tk.close();

        LlmClient.RunResult d = done.get();
        System.out.println("\n\nsummary: textDeltas=" + textDeltas.get()
                + " toolCall=" + toolCallEv.get()
                + " toolResult=" + toolResultEv.get()
                + " | done.toolCallCount=" + (d == null ? "?" : d.toolCallCount)
                + " turns=" + (d == null ? "?" : d.turns)
                + " usage(total)=" + (d == null ? "?" : d.usage.totalTokens));

        boolean ok = textDeltas.get() > 1 && toolCallEv.get() >= 1 && toolResultEv.get() >= 1
                && d != null && d.usage.totalTokens > 0 && d.text.contains("42");
        if (!ok) {
            System.err.println("FAIL streaming did not produce deltas + tool events + final usage/answer");
            System.exit(1);
        }
        System.out.println("\nOK streaming verified: incremental text deltas, tool_call/tool_result events, final done with usage");
        System.exit(0);
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
}
