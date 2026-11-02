package io.github.muthuishere.toolnexus.examples;

import com.sun.net.httpserver.HttpServer;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.PromptCache;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolHints;
import io.github.muthuishere.toolnexus.ToolTiers;
import io.github.muthuishere.toolnexus.Toolkit;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prompt cache + tool tiers, hermetic: a local mock Anthropic-style server plays the model.
 * <ol>
 *   <li>{@code promptCache} puts cache breakpoints on the tools, system prompt and latest message;
 *       the mock reports cache reads from the second request on.</li>
 *   <li>{@code toolTiers} sends only the core tool; the model loads {@code deploy} with
 *       {@code tool_search} and calls it through {@code call_tool}. The permission hook sees the
 *       real tool, and the tools array (the cached prefix) is the same on every request.</li>
 * </ol>
 *
 *   gradle runPromptCache   (no API key, no network)
 */
public final class PromptCacheTiers {

    public static void main(String[] args) throws Exception {
        List<String> toolArrays = new CopyOnWriteArrayList<>();
        AtomicInteger step = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!req.contains("\"cache_control\"")) throw new IllegalStateException("no cache breakpoints");
            toolArrays.add(req.substring(req.indexOf("\"tools\"")));
            int n = step.incrementAndGet();
            String content = switch (n) {
                case 1 -> "[{\"type\":\"tool_use\",\"id\":\"s1\",\"name\":\"tool_search\",\"input\":{\"query\":\"select:deploy\"}}]";
                case 2 -> "[{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"call_tool\",\"input\":{\"name\":\"deploy\",\"arguments\":{\"env\":\"prod\"}}}]";
                default -> "[{\"type\":\"text\",\"text\":\"Deployed to prod.\"}]";
            };
            // a simulated cache: the first request writes the prefix, later ones read it
            String usage = n == 1
                    ? "{\"input_tokens\":20,\"output_tokens\":5,\"cache_creation_input_tokens\":900}"
                    : "{\"input_tokens\":20,\"output_tokens\":5,\"cache_read_input_tokens\":900}";
            byte[] b = ("{\"content\":" + content + ",\"stop_reason\":\"tool_use\",\"usage\":" + usage + "}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();

        Map<String, Object> envSchema = Map.of("type", "object",
                "properties", Map.of("env", Map.of("type", "string")));
        List<String> gated = new CopyOnWriteArrayList<>();
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(
                Tool.withHints(NativeTool.of("status", "Show service status.", envSchema,
                        (Map<String, Object> a) -> "healthy"), ToolHints.READ_ONLY),
                NativeTool.of("lint", "Lint the code.", envSchema, (Map<String, Object> a) -> "clean"),
                Tool.withHints(NativeTool.of("deploy", "Deploy the service.", envSchema,
                        (Map<String, Object> a) -> "deployed " + a.get("env")), ToolHints.DESTRUCTIVE)))) {

            LlmClient agent = LlmClient.create(new LlmClient.Options()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .style("anthropic").model("mock").apiKey("YOUR_KEY_HERE") // local host: explicit key
                    .systemPrompt("You operate one service.")
                    .promptCache(new PromptCache())
                    .toolTiers(new ToolTiers().core("status").deferred("deploy"))
                    .hooks(new LlmClient.Hooks().beforeTool(ev -> {
                        gated.add(ev.name() + (ev.hints() != null && ev.hints().destructive() ? " (destructive)" : ""));
                        return null; // allow; a real host would ask here
                    })));

            System.out.println("tier plan: " + agent.toolTierPlan(tk));
            LlmClient.RunResult res = agent.run("Deploy to prod.", tk);
            LlmClient.Usage u = res.usage;
            System.out.println("reply: " + res.text);
            System.out.println("gate saw: " + gated);
            System.out.println("usage: prompt=" + u.promptTokens + " cacheRead=" + u.cacheReadTokens
                    + " cacheWrite=" + u.cacheWriteTokens
                    + " context=" + (u.promptTokens + u.cacheReadTokens + u.cacheWriteTokens));

            boolean stable = toolArrays.stream().allMatch(a -> a.equals(toolArrays.get(0)));
            System.out.println("tools array stable over " + toolArrays.size() + " requests: " + stable);
            if (!stable || !gated.equals(List.of("deploy (destructive)")) || u.cacheReadTokens != 1800) {
                throw new AssertionError("prompt cache / tool tiers example did not behave as documented");
            }
            System.out.println("OK");
        } finally {
            server.stop(0);
        }
    }
}
