// run: java -cp 'lib/*' Serve.java   (lib/ = toolnexus-0.26.0.jar + its runtime deps)
import io.github.muthuishere.toolnexus.McpServe;
import io.github.muthuishere.toolnexus.NativeTool;
import io.github.muthuishere.toolnexus.Toolkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class Serve {
    public static void main(String[] argv) throws Exception {
        NativeTool add = NativeTool.of("add", "Add two numbers.",
                Map.of("type", "object",
                        "properties", Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number")),
                        "required", List.of("a", "b")),
                args -> String.valueOf(((Number) args.get("a")).intValue() + ((Number) args.get("b")).intValue()));

        // Any toolkit (MCP servers, skills, native, HTTP, builtins) can be served to a local agent.
        try (Toolkit toolkit = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(add));
             McpServe.LocalServer server = toolkit.asMcpServer()) { // asMcpServer(cfg) narrows what is exposed
            System.out.println("MCP endpoint: " + server.url());   // http://127.0.0.1:<port>/mcp, POST only

            // Every request needs the bearer token; this is the entry an ACP agent takes in session/new.
            Map<String, Object> entry = Map.of("type", "http", "name", "toolnexus", "url", server.url(),
                    "headers", List.of(Map.of("name", "Authorization", "value", "Bearer " + server.token())));
            HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server.url()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + server.token())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            System.out.println("tools/list: " + res.statusCode());
        }
    }
}
