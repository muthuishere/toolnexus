package bench;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.mcp.McpToolset;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.modelcontextprotocol.client.transport.ServerParameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Google ADK for Java benchmark runner. Same mock LLM + same shared stdio MCP server.
 * ADK Java has no native OpenAI model, so the model is ADK's own LangChain4j bridge
 * (google-adk-langchain4j) wrapping langchain4j's OpenAiChatModel (baseUrl -> mock).
 *
 *   cold init   = McpToolset (stdio ServerParameters -> shared MCP server) + LlmAgent
 *                 + InMemoryRunner. McpToolset connects lazily (first request), so the
 *                 MCP connect + tools/list falls into warmup, not init_ms.
 *   per-request = new session + runner.runAsync(question) drained to the last event.
 *
 *   -runs N   -warmup W
 */
public class AdkJavaBench {
    static final String QUESTION = "What's the weather in Paris and what is 2+2?";

    static String arg(String[] a, String k, String d) {
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals(k)) return a[i + 1];
        return d;
    }

    public static void main(String[] args) {
        int runs = Integer.parseInt(arg(args, "-runs", "30"));
        int warmup = Integer.parseInt(arg(args, "-warmup", "5"));
        String mockUrl = System.getenv().getOrDefault("MOCK_URL", "http://127.0.0.1:8900");
        String repo = System.getenv().getOrDefault("BENCH_REPO", "");
        String mcpPy = System.getenv().getOrDefault("MCP_PYTHON", "python3");

        // ---- cold init ----
        long t0 = System.nanoTime();
        McpToolset toolset = new McpToolset(
                ServerParameters.builder(mcpPy).args(repo + "/benchmarks/mcp_server.py").build());
        OpenAiChatModel chat = OpenAiChatModel.builder()
                .baseUrl(mockUrl + "/v1")
                .apiKey("sk-mock-not-a-real-key")
                .modelName("mock-model")
                .build();
        LlmAgent agent = LlmAgent.builder()
                .name("bench_agent")
                .model(LangChain4j.builder().chatModel(chat).modelName("mock-model").build())
                .instruction("You are a helpful assistant.")
                .tools(toolset)
                .build();
        InMemoryRunner runner = new InMemoryRunner(agent, "bench");
        double initMs = (System.nanoTime() - t0) / 1e6;

        for (int i = 0; i < warmup; i++) once(runner);
        List<Double> lat = new ArrayList<>();
        String finalText = "";
        for (int i = 0; i < runs; i++) {
            long s = System.nanoTime();
            finalText = once(runner);
            lat.add((System.nanoTime() - s) / 1e6);
        }
        int toolCount = toolset.getTools(null).toList().blockingGet().size();

        List<Double> sorted = new ArrayList<>(lat);
        Collections.sort(sorted);
        double mean = lat.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        System.out.println("{\"name\":\"adk_java\",\"framework\":\"adk-java-mcp\",\"language\":\"java\""
                + ",\"init_ms\":" + r(initMs) + ",\"tool_count\":" + toolCount
                + ",\"mean_ms\":" + r(mean) + ",\"p50_ms\":" + r(pct(sorted, 50))
                + ",\"p95_ms\":" + r(pct(sorted, 95)) + ",\"n\":" + lat.size()
                + ",\"final_ok\":" + finalText.startsWith("The weather in Paris") + "}");
        System.exit(0);
    }

    static String once(InMemoryRunner runner) {
        Session s = runner.sessionService().createSession("bench", "u").blockingGet();
        String text = "";
        for (Event ev : runner.runAsync("u", s.id(), Content.fromParts(Part.fromText(QUESTION))).blockingIterable()) {
            String t = ev.stringifyContent();
            if (ev.finalResponse() && t != null && !t.isEmpty()) text = t;
        }
        return text;
    }

    static double pct(List<Double> s, int p) {
        return s.get(Math.min(s.size() - 1, (int) (p / 100.0 * (s.size() - 1) + 0.5)));
    }

    static double r(double v) { return Math.round(v * 1000) / 1000.0; }
}
