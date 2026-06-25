package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.HttpTool;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.Toolkit;
import io.github.muthuishere.toolnexus.Tools;
import io.github.muthuishere.toolnexus.annotations.Param;
import io.github.muthuishere.toolnexus.annotations.ToolMethod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full agent example (mirrors js/examples/agent.ts): MCP tools + skills + a native
 * (@ToolMethod) tool + an HTTP tool, driven by the unified client host loop.
 *
 *   OPENROUTER_API_KEY=... gradle runAgent
 */
public final class Agent {

    /** A plain object with an annotated tool method — the Spring-AI @Tool feel. */
    public static final class MathTools {
        @ToolMethod(name = "add", description = "Add two numbers and return the sum.")
        public String add(@Param(name = "a", description = "first addend") double a,
                          @Param(name = "b", description = "second addend") double b) {
            double sum = a + b;
            // print as an integer when whole, to mirror the JS `${a + b}` string
            if (sum == Math.floor(sum) && !Double.isInfinite(sum)) {
                return String.valueOf((long) sum);
            }
            return String.valueOf(sum);
        }
    }

    public static void main(String[] args) throws Exception {
        Toolkit tk = Toolkit.create(new Toolkit.Options()
                .mcpConfig(Examples.fixture("mcp.json"))
                .skillsDir(Examples.fixture("skills")));

        // a native (annotation) tool — collected via reflection
        for (Tool t : Tools.fromObject(new MathTools())) {
            tk.register(t);
        }

        // an HTTP tool (public test API, no auth)
        HttpTool.Options http = new HttpTool.Options();
        http.name = "get_post";
        http.description = "Fetch a placeholder blog post by id from jsonplaceholder.";
        http.method = "GET";
        http.url = "https://jsonplaceholder.typicode.com/posts/{id}";
        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "number");
        idProp.put("description", "post id 1-100");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", idProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("id"));
        schema.put("additionalProperties", false);
        http.inputSchema = schema;
        tk.register(HttpTool.of(http));

        StringBuilder toolsLine = new StringBuilder("Tools: [");
        List<Tool> tools = tk.tools();
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) toolsLine.append(", ");
            toolsLine.append(tools.get(i).name()).append(" (").append(tools.get(i).source()).append(")");
        }
        toolsLine.append("]");
        System.out.println(toolsLine);

        String key = System.getenv("OPENROUTER_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("\n(no OPENROUTER_API_KEY — skipping live loop)");
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
                .systemPrompt("You are a precise agent. Use tools to compute and fetch facts."));

        LlmClient.RunResult res = agent.run(
                "What is 21 + 21? Use the add tool. Then fetch post id 1 and tell me its title.", tk);

        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < res.toolCalls.size(); i++) {
            if (i > 0) calls.append(", ");
            calls.append(res.toolCalls.get(i).name);
        }
        System.out.println("\nTool calls: [" + calls + "]");
        System.out.println("\nFINAL:\n" + res.text);

        tk.close();
        if (!res.text.contains("42")) {
            System.err.println("❌ expected 42 in answer");
            System.exit(1);
        }
        System.out.println("\n✅ Java agent loop (native + http + mcp + skills) OK");
        System.exit(0);
    }
}
