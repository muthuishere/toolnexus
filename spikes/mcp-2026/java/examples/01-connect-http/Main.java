// Example 01 — connect to an MCP server over Streamable HTTP and call a tool.
//
// Run: ./gradlew -q runExample -Pex=01
//
// The MCP 2026-07-28 protocol is stateless: there is no initialize handshake and no session.
// Every request carries its own protocol version, so "connecting" is just new Client(url).
package ex01;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.LocalServer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.Server;

import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        // A local MCP server to talk to (the spike fixture: tools `echo` and `greet`, skill `hello-world`).
        // In real use this is someone else's server, e.g. "https://api.example.com/mcp".
        try (LocalServer ts = LocalServer.start(Server.fixture())) {

            // 1. Point a client at the server's MCP endpoint. No handshake happens here.
            Client c = new Client(ts.url() + "/mcp");

            // 2. Optional: server/discover tells you which versions and capabilities the server has.
            Map<String, Object> info = c.call("server/discover", null);
            System.out.println("supported versions: " + info.get("supportedVersions"));

            // 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
            List<Map<String, Object>> tools = c.tools();
            List<String> names = tools.stream().map(t -> (String) t.get("name")).toList();
            System.out.println("tools: " + String.join(", ", names));

            // 4. Call a tool. Mcp.text returns the first text content of the result.
            Map<String, Object> res = c.callTool("echo", Mcp.obj("text", "hello over HTTP"), null);
            System.out.println("echo: " + Mcp.text(res));
        }
    }
}
