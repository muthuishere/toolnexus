package io.github.muthuishere.toolnexus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Replays {@code conformance/serve_mcp/cases.json} (SPEC §7C): the toolkit.serve MCP wire, all 8 ports. */
class ServeMcpConformanceTest {

    private static Path casesFile() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            Path p = d.resolve("conformance/serve_mcp/cases.json");
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("conformance/serve_mcp/cases.json not found above " + dir);
    }

    @Test
    void cases() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(casesFile())).get("cases");
        HttpClient http = HttpClient.newHttpClient();
        Tool echo = NativeTool.of("echo", "echo", Map.of("type", "object"), args -> "x");
        try (Toolkit tk = Toolkit.create(new Toolkit.Options().builtins(false).extraTools(echo))) {
            A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0",
                    new Toolkit.ServeOptions().mcp(new McpServe.MCPServeConfig()));
            try {
                for (JsonNode c : cases) {
                    JsonNode rq = c.get("request");
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(srv.url() + rq.get("path").asText()))
                            .timeout(Duration.ofSeconds(5))
                            .method(rq.get("method").asText(), HttpRequest.BodyPublishers.noBody());
                    if (rq.has("headers")) {
                        for (Iterator<Map.Entry<String, JsonNode>> it = rq.get("headers").properties().iterator(); it.hasNext(); ) {
                            Map.Entry<String, JsonNode> e = it.next();
                            b.header(e.getKey(), e.getValue().asText());
                        }
                    }
                    HttpResponse<Void> res = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
                    String name = c.get("name").asText();
                    assertEquals(c.get("expect").get("status").asInt(), res.statusCode(), name);
                    JsonNode hs = c.get("expect").get("headers");
                    if (hs != null) {
                        for (Iterator<Map.Entry<String, JsonNode>> it = hs.properties().iterator(); it.hasNext(); ) {
                            Map.Entry<String, JsonNode> e = it.next();
                            assertEquals(e.getValue().asText(), res.headers().firstValue(e.getKey()).orElse(null), name + " " + e.getKey());
                        }
                    }
                }
            } finally {
                srv.stop();
            }
        }
    }
}
