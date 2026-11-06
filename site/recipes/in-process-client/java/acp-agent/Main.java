// run: java -cp 'lib/*' Main.java npx -y @agentclientprotocol/claude-agent-acp@0.100.0
//      (lib/ = toolnexus-0.26.0.jar + its runtime deps)
import io.github.muthuishere.toolnexus.InProcess;
import io.github.muthuishere.toolnexus.LlmClient;
import io.github.muthuishere.toolnexus.McpServe;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Toolkit;
import io.github.muthuishere.toolnexus.acp.AcpClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] argv) throws Exception {
        NativeTool add = NativeTool.of("add", "Add two numbers.",
                Map.of("type", "object",
                        "properties", Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number")),
                        "required", List.of("a", "b")),
                args -> {
                    int a = ((Number) args.get("a")).intValue(), b = ((Number) args.get("b")).intValue();
                    System.out.println("\n[tool] add(" + a + ", " + b + ")");
                    return String.valueOf(a + b);
                });

        // 1. Serve YOUR toolkit on loopback, behind a fresh bearer token.
        try (Toolkit toolkit = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(add));
             McpServe.LocalServer server = toolkit.asMcpServer();
             // 2. Start the agent; mcpServers is sent to session/new exactly as written.
             AcpClient agent = AcpClient.start(new AcpClient.Options()
                     .command(List.of(argv))
                     .mcpServers(List.of(Map.of("type", "http", "name", "toolnexus", "url", server.url(),
                             "headers", List.of(Map.of("name", "Authorization", "value", "Bearer " + server.token())))))
                     .timeoutMs(300_000)); // one turn of a real agent can take minutes
             Toolkit empty = Toolkit.create(new Toolkit.Options().builtins(false))) {

            // 3. The agent runs the tools itself (over MCP), so the client's own toolkit is empty.
            LlmClient client = InProcess.createClient(new InProcess.Options().model(argv[0])
                    .generateStream((req, emit) -> {
                        try { return agent.generateStream(req, emit); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }));
            client.stream("Use the add tool to add 17 and 25. Reply with just the sum.", empty, ev -> {
                switch (ev.type()) {
                    case TEXT -> System.out.print(ev.delta()); // live text as the agent writes it
                    case TOOL_CALL -> { if ("acp".equals(ev.via())) System.out.println("\n[agent tool] " + ev.name()); }
                    case DONE -> System.out.println("\nfinal: " + ev.result().text);
                    default -> { }
                }
            });
        } // closing the AcpClient ends the agent's process; the server stops after it
    }
}
