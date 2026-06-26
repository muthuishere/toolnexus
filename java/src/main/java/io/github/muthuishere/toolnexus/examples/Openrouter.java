package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.Toolkit;

/**
 * Live OpenRouter tool-calling round trip — proves the toolkit drives a real LLM
 * (mirrors js/examples/openrouter_test.ts, python/examples/openrouter_test.py, and
 * golang/examples/openrouter). Reads OPENROUTER_API_KEY from the environment —
 * never hardcode it, never print it.
 *
 *   OPENROUTER_API_KEY=... gradle runOpenrouter
 *
 * Flow: build the toolkit from the shared fixtures, hand the model the toolkit's
 * tools, ask it to echo a string via the `everything_echo` MCP tool, let the unified
 * client run the tool call and feed the result back, then print the final answer.
 */
public final class Openrouter {
    public static void main(String[] args) throws Exception {
        String key = System.getenv("OPENROUTER_API_KEY");
        if (key == null || key.isEmpty()) {
            System.err.println("OPENROUTER_API_KEY not set");
            System.exit(1);
        }
        String model = System.getenv("OPENROUTER_MODEL");
        if (model == null || model.isEmpty()) model = "openai/gpt-4o-mini";

        Toolkit tk = Toolkit.create(new Toolkit.Options()
                .mcpConfig(Examples.fixture("mcp.json"))
                .skillsDir(Examples.fixture("skills")));

        LlmClient agent = LlmClient.create(new LlmClient.Options()
                .baseUrl("https://openrouter.ai/api/v1")
                .style("openai")
                .model(model)
                .apiKey(key)
                .systemPrompt("You are a tool-using agent. Use the provided tools when helpful.\n\n"
                        + tk.skillsPrompt()));

        LlmClient.RunResult res = agent.run(
                "Use the everything_echo tool to echo the exact string \"toolnexus works\". "
                        + "Then tell me what it returned.", tk);

        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < res.toolCalls.size(); i++) {
            if (i > 0) calls.append(", ");
            calls.append(res.toolCalls.get(i).name);
        }
        System.out.println("Tool calls: [" + calls + "]");
        System.out.println("\nFINAL ASSISTANT:\n" + res.text);

        tk.close();
        boolean echoed = res.toolCalls.stream().anyMatch(c -> c.name.contains("everything_echo"));
        if (!echoed) {
            System.err.println("\n❌ expected the model to call everything_echo");
            System.exit(1);
        }
        System.out.println("\n✅ Java OpenRouter round trip OK");
        System.exit(0);
    }
}
