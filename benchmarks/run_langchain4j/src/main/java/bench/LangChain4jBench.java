package bench;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j benchmark runner. Same mock LLM (OpenAI base-url) + the same shared
 * stdio MCP server (via langchain4j-mcp's StdioMcpTransport) as every other
 * framework. AiServices wires the model + an MCP ToolProvider and runs the full
 * tool-calling loop until a final answer. JVM warmup handled by warmup iterations;
 * init is a COLD, first-touch build (spawn MCP + discover + build AiService).
 *
 *   -config mcp   -runs N   -warmup W
 */
public final class LangChain4jBench {

    interface Assistant {
        String chat(String question);
    }

    public static void main(String[] args) throws Exception {
        String config = arg(args, "-config", "mcp");
        int runs = Integer.parseInt(arg(args, "-runs", "30"));
        int warmup = Integer.parseInt(arg(args, "-warmup", "5"));

        String repo = System.getenv("BENCH_REPO");
        String mcpPy = System.getenv("MCP_PYTHON");
        String mockUrl = System.getenv().getOrDefault("MOCK_URL", "http://127.0.0.1:8900");
        final String question = "What's the weather in Paris and what is 2+2?";

        // ---- cold init ----
        long t0 = System.nanoTime();

        McpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of(mcpPy, repo + "/benchmarks/mcp_server.py"))
                .logEvents(false)
                .build();
        McpClient mcpClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
        ToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(List.of(mcpClient))
                .build();

        int toolCount = mcpClient.listTools().size();

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(mockUrl)
                .apiKey("sk-mock-not-a-real-key")
                .modelName("mock-model")
                .temperature(0.0)
                .build();

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .build();

        double initMs = (System.nanoTime() - t0) / 1_000_000.0;

        // ---- warmup (JVM JIT + class loading) ----
        for (int i = 0; i < warmup; i++) assistant.chat(question);

        // ---- measured ----
        List<Double> lat = new ArrayList<>();
        String finalText = "";
        for (int i = 0; i < runs; i++) {
            long s = System.nanoTime();
            finalText = assistant.chat(question);
            lat.add((System.nanoTime() - s) / 1_000_000.0);
        }

        mcpClient.close();

        StringBuilder sb = new StringBuilder();
        sb.append("BENCHJSON {\"framework\":\"langchain4j-").append(config).append("\",");
        sb.append("\"language\":\"java\",");
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
        System.exit(0);
    }

    static String arg(String[] a, String key, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return a[i + 1];
        return def;
    }

    static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
