package io.github.muthuishere.toolnexus.examples;

import io.github.muthuishere.toolnexus.Json;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.ToolResult;
import io.github.muthuishere.toolnexus.Toolkit;

import java.util.List;
import java.util.Map;

/**
 * Minimal end-to-end example (mirrors js/examples/basic.ts). Loads the shared
 * fixtures, prints MCP status, tools, the skill catalog, the first two OpenAI tool
 * schemas, then loads the hello-world skill (progressive disclosure).
 */
public final class Basic {
    public static void main(String[] args) throws Exception {
        Toolkit tk = Toolkit.create(new Toolkit.Options()
                .mcpConfig(Examples.fixture("mcp.json"))
                .skillsDir(Examples.fixture("skills")));

        System.out.println("MCP status: " + tk.mcpStatus());

        StringBuilder toolsLine = new StringBuilder("Tools: [");
        List<Tool> tools = tk.tools();
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) toolsLine.append(", ");
            toolsLine.append(tools.get(i).name()).append(" (").append(tools.get(i).source()).append(")");
        }
        toolsLine.append("]");
        System.out.println(toolsLine);

        System.out.println("\nSystem-prompt skill catalog:\n" + tk.skillsPrompt());

        System.out.println("\nOpenAI tool schema (first 2):");
        List<Map<String, Object>> openai = tk.toOpenAI();
        System.out.println(Json.pretty(openai.subList(0, Math.min(2, openai.size()))));

        ToolResult res = tk.execute("skill", Map.of("name", "hello-world"));
        System.out.println("\nskill(hello-world) ->\n" + res.output());

        tk.close();
        // The MCP stdio child may keep a non-daemon reactor thread alive; exit cleanly.
        System.exit(0);
    }
}
