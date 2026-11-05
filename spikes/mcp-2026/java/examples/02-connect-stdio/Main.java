// Example 02 — connect to a local MCP server over stdio (spawn a process, talk on stdin/stdout).
//
// Run: ./gradlew -q runExample -Pex=02
//
// stdio carries one JSON-RPC message per line. stderr is the server's log stream: it is inherited
// (or copied to a stream you pass) and never parsed. There are no HTTP headers and no OAuth over
// stdio: a stdio server takes its credentials from its own environment.
package ex02;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.Server;

import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        // Demo harness: this same program is the stdio server when started with SERVE_STDIO=1.
        // In real use the command is your server, e.g. new ProcessBuilder("npx", "-y", "some-mcp-server").
        if ("1".equals(System.getenv("SERVE_STDIO"))) {
            Server.fixture().serveStdio(System.in, System.out);
            return;
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        String self = System.getProperty("java.class.path");

        // 1. Describe the process to spawn (env, args, working dir: plain ProcessBuilder).
        ProcessBuilder cmd = new ProcessBuilder(java, "-cp", self, "ex02.Main");
        cmd.environment().put("SERVE_STDIO", "1");

        // 2. Start it and get a client. Same API as the HTTP client: call, tools, callTool.
        try (Client c = Client.stdio(cmd)) { // close() stops the server process

            // 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
            List<Map<String, Object>> tools = c.tools();
            System.out.println("tool count: " + tools.size());

            Map<String, Object> res = c.callTool("echo", Mcp.obj("text", "hello over stdio"), null);
            System.out.println("echo: " + Mcp.text(res));
        }
    }
}
