import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.Tools;
import io.github.muthuishere.toolnexus.Toolkit;
import io.github.muthuishere.toolnexus.annotations.Param;
import io.github.muthuishere.toolnexus.annotations.ToolMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * toolnexus (Java port) benchmark runner. Same mock LLM + same shared stdio MCP
 * server (via the official MCP Java SDK). JVM warmup is handled with explicit
 * warmup iterations before the measured loop; init is a COLD, first-touch build.
 *
 *   -config mcp|full   -runs N   -warmup W
 */
public final class BenchToolnexus {

    public static final class NativeTools {
        @ToolMethod(name = "multiply", description = "Multiply two integers locally.")
        public String multiply(@Param(name = "a", description = "a") long a,
                               @Param(name = "b", description = "b") long b) {
            return String.valueOf(a * b);
        }
    }

    public static void main(String[] args) throws Exception {
        String config = arg(args, "-config", "mcp");
        int runs = Integer.parseInt(arg(args, "-runs", "30"));
        int warmup = Integer.parseInt(arg(args, "-warmup", "5"));

        String repo = System.getenv("BENCH_REPO");
        String mcpPy = System.getenv("MCP_PYTHON");
        String mockUrl = System.getenv().getOrDefault("MOCK_URL", "http://127.0.0.1:8900");
        final String question = "What's the weather in Paris and what is 2+2?";
        if (System.getenv("OPENAI_API_KEY") == null) {
            // LlmClient reads the key from the environment; provide a harmless mock.
            // (Set externally in the run script.)
        }

        Map<String, Object> server = Map.of(
                "type", "local",
                "command", List.of(mcpPy, repo + "/benchmarks/mcp_server.py"),
                "enabled", true,
                "timeout", 30000);
        Map<String, Object> mcpConfig = Map.of("mcpServers", Map.of("bench-tools", server));

        // ---- cold init ----
        long t0 = System.nanoTime();
        Toolkit.Options opts = new Toolkit.Options().mcpConfig(mcpConfig).builtins(false);
        if (config.equals("full")) {
            opts.skillsDir(repo + "/examples/skills");
        }
        Toolkit tk = Toolkit.create(opts);
        if (config.equals("full")) {
            for (Tool t : Tools.fromObject(new NativeTools())) tk.register(t);
        }
        LlmClient agent = LlmClient.create(new LlmClient.Options()
                .baseUrl(mockUrl).style("openai").model("mock-model"));
        double initMs = (System.nanoTime() - t0) / 1_000_000.0;
        int toolCount = tk.tools().size();

        // ---- warmup (JVM JIT + class loading) ----
        for (int i = 0; i < warmup; i++) agent.run(question, tk);

        // ---- measured ----
        List<Double> lat = new ArrayList<>();
        String finalText = "";
        for (int i = 0; i < runs; i++) {
            long s = System.nanoTime();
            LlmClient.RunResult res = agent.run(question, tk);
            lat.add((System.nanoTime() - s) / 1_000_000.0);
            finalText = res.text;
        }

        tk.close();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"framework\":\"toolnexus-java-").append(config).append("\",");
        sb.append("\"language\":\"java\",");
        sb.append("\"init_ms\":").append(round(initMs)).append(",");
        sb.append("\"tool_count\":").append(toolCount).append(",");
        sb.append("\"latencies_ms\":[");
        for (int i = 0; i < lat.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(round(lat.get(i)));
        }
        sb.append("],");
        sb.append("\"final_text\":\"").append(finalText.replace("\"", "\\\"")).append("\"}");
        System.out.println(sb);
        System.exit(0);
    }

    static String arg(String[] a, String key, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return a[i + 1];
        return def;
    }

    static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
