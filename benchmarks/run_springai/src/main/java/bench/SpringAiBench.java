package bench;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Spring AI benchmark runner. Same mock LLM (OpenAI base-url) + same shared stdio
 * MCP server (via Spring AI's MCP client starter). ChatClient's .call() runs the
 * full tool-calling loop until a final answer.
 *
 * Caveat: Spring AI's MCP discovery is entangled with Spring Boot context startup,
 * so the "init" figure below is Spring Boot context-ready time INCLUDING MCP
 * discovery (not a toolkit-only build). The per-request figure is the clean,
 * directly-comparable number. JVM warmup handled by warmup iterations.
 */
@SpringBootApplication
public class SpringAiBench {

    public static void main(String[] args) {
        int runs = Integer.parseInt(env("BENCH_RUNS", "30"));
        int warmup = Integer.parseInt(env("BENCH_WARMUP", "5"));
        String question = "What's the weather in Paris and what is 2+2?";

        long t0 = System.nanoTime();
        ConfigurableApplicationContext ctx =
                new SpringApplication(SpringAiBench.class).run(args);
        ChatModel model = ctx.getBean(ChatModel.class);

        // Aggregate tool callbacks from every ToolCallbackProvider bean; if none
        // carry callbacks, build one directly from the autoconfigured MCP clients.
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallbackProvider p : ctx.getBeansOfType(ToolCallbackProvider.class).values()) {
            callbacks.addAll(Arrays.asList(p.getToolCallbacks()));
        }
        if (callbacks.isEmpty()) {
            Map<String, McpSyncClient> clients = ctx.getBeansOfType(McpSyncClient.class);
            if (!clients.isEmpty()) {
                var provider = new SyncMcpToolCallbackProvider(
                        new ArrayList<>(clients.values()));
                callbacks.addAll(Arrays.asList(provider.getToolCallbacks()));
            }
        }
        System.err.println("[bench] tool callbacks discovered: " + callbacks.size()
                + " names=" + callbacks.stream().map(ToolCallback::getToolDefinition).toList());
        ToolCallback[] cbArray = callbacks.toArray(new ToolCallback[0]);
        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .model("mock-model")
                .toolCallbacks(cbArray)
                .internalToolExecutionEnabled(true)
                .build();
        double initMs = (System.nanoTime() - t0) / 1_000_000.0;
        int toolCount = callbacks.size();

        // Call the ChatModel directly (bypassing the ChatClient option-merge layer,
        // which was dropping the runtime tool callbacks). OpenAiChatModel runs the
        // internal tool-calling loop when internalToolExecutionEnabled is true.
        for (int i = 0; i < warmup; i++) {
            model.call(new Prompt(question, opts)).getResult().getOutput().getText();
        }

        List<Double> lat = new ArrayList<>();
        String finalText = "";
        for (int i = 0; i < runs; i++) {
            long s = System.nanoTime();
            finalText = model.call(new Prompt(question, opts))
                    .getResult().getOutput().getText();
            lat.add((System.nanoTime() - s) / 1_000_000.0);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("BENCHJSON {\"framework\":\"spring-ai-java\",\"language\":\"java\",");
        sb.append("\"init_ms\":").append(round(initMs)).append(",");
        sb.append("\"tool_count\":").append(toolCount).append(",");
        sb.append("\"latencies_ms\":[");
        for (int i = 0; i < lat.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(round(lat.get(i)));
        }
        sb.append("],");
        sb.append("\"final_text\":\"")
          .append(finalText == null ? "" : finalText.replace("\"", "\\\"").replace("\n", " "))
          .append("\"}");
        System.out.println(sb);
        try { ctx.close(); } catch (Exception ignored) {}
        System.exit(0);
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null ? def : v;
    }

    static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
