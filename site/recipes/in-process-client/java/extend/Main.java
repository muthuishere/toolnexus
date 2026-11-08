// run: java -cp 'lib/*' Main.java npx -y @agentclientprotocol/claude-agent-acp@0.100.0
//      (lib/ = toolnexus-0.27.0.jar + its runtime deps; optional env ACP_MODEL, e.g. haiku)
import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.McpServe;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Tool;
import io.github.muthuishere.toolnexus.Toolkit;
import io.github.muthuishere.toolnexus.acp.AcpClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Main {
    // Permission policy: allow tool calls whose title names one of your tools, reject the rest.
    // Return an optionId to pick it; return null to answer with the first reject option.
    @SuppressWarnings("unchecked")
    static Function<Map<String, Object>, String> allowOnly(List<String> names) {
        return params -> {
            Map<String, Object> toolCall = (Map<String, Object>) params.get("toolCall");
            String title = toolCall == null || toolCall.get("title") == null ? "" : toolCall.get("title").toString();
            String want = names.stream().anyMatch(title::contains) ? "allow" : "reject";
            for (Object o : (List<Object>) params.get("options")) {
                Map<String, Object> opt = (Map<String, Object>) o;
                if (String.valueOf(opt.get("kind")).startsWith(want)) return (String) opt.get("optionId");
            }
            return null;
        };
    }

    public static void main(String[] argv) throws Exception {
        NativeTool add = NativeTool.of("add", "Add two numbers.",
                Map.of("type", "object",
                        "properties", Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number")),
                        "required", List.of("a", "b")),
                args -> String.valueOf(((Number) args.get("a")).intValue() + ((Number) args.get("b")).intValue()));
        ObjectMapper json = new ObjectMapper();

        try (Toolkit toolkit = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(add));
             McpServe.LocalServer server = toolkit.asMcpServer();
             AcpClient agent = AcpClient.start(new AcpClient.Options()
                     .command(List.of(argv))
                     .mcpServers(List.of(Map.of("type", "http", "name", "toolnexus", "url", server.url(),
                             "headers", List.of(Map.of("name", "Authorization", "value", "Bearer " + server.token())))))
                     // Agent-specific knobs are opaque data: this one isolates Claude Code (see the per-agent notes).
                     .sessionMeta(Map.of("claudeCode", Map.of("options", Map.of("settingSources", List.of(),
                             "strictMcpConfig", true, "plugins", List.of(), "skills", List.of(), "tools", List.of()))))
                     .model(System.getenv("ACP_MODEL")) // e.g. "haiku"; null = unset; passed through, never validated
                     .onPermission(allowOnly(toolkit.tools().stream().map(Tool::name).toList()))
                     .onUpdate(u -> {
                         if (u.get("update") instanceof Map<?, ?> up && "usage_update".equals(up.get("sessionUpdate"))) {
                             try { System.err.println("[usage] " + json.writeValueAsString(up)); } catch (Exception ignored) { }
                         }
                     })
                     .timeoutMs(300_000));
             Toolkit empty = Toolkit.create(new Toolkit.Options().builtins(false))) {

            LlmClient client = InProcess.createClient(new InProcess.Options().model(argv[0]).generate(agent));
            LlmClient.RunResult result = client.run("Use the add tool to add 17 and 25. Reply with just the sum.", empty);
            System.out.println(result.text);
        }
    }
}
