// Example 05 — lazy tool search: give the model 2 tools instead of 100.
//
// Run: ./gradlew -q runExample -Pex=05
//
// With toolSearch(true), c.tools() returns two meta-tools, search_tools(query, limit?) and
// call_tool(name, arguments). The model searches (BM25 over tool names and descriptions), then
// calls what it found. c.invoke runs whatever tool call the model emits.
package ex05;

import io.github.muthuishere.toolnexus.mcp2026.Client;
import io.github.muthuishere.toolnexus.mcp2026.LocalServer;
import io.github.muthuishere.toolnexus.mcp2026.Mcp;
import io.github.muthuishere.toolnexus.mcp2026.Server;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        // A server with 100 tools: 10 services x 10 verbs.
        Server srv = new Server();
        for (String svc : List.of("github", "slack", "jira", "gmail", "calendar", "drive", "stripe", "postgres", "s3", "notion")) {
            for (String verb : List.of("create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment")) {
                String name = svc + "_" + verb;
                srv.tool(name, verb + " an item in " + svc + ".", (ctx, a) -> name + " ran with " + a.get("text"));
            }
        }
        try (LocalServer ts = LocalServer.start(srv)) {
            Client eager = new Client(ts.url() + "/mcp");
            List<Map<String, Object>> all = eager.tools();

            // 1. Turn on tool search; the description tells the model what this server is for.
            Client c = new Client(ts.url() + "/mcp")
                    .toolSearch(true)
                    .description("SaaS workspace (GitHub, Slack, Jira, ...)");
            List<Map<String, Object>> meta = c.tools();
            System.out.println("eager: " + all.size() + " tools, " + size(all) + " bytes of schema");
            System.out.println("lazy:  " + meta.size() + " tools, " + size(meta) + " bytes of schema");

            // 2. The model calls search_tools ...
            List<Map<String, Object>> hits = c.searchTools("send a slack message create", 3);
            System.out.println("top hit: " + hits.get(0).get("name"));

            // 3. ... then call_tool with the name it found. invoke routes both meta-tools.
            Map<String, Object> res = c.invoke("call_tool", Mcp.obj("name", hits.get(0).get("name"), "arguments", Mcp.obj("text", "hi team")), null);
            System.out.println("result: " + Mcp.text(res));
        }
    }

    static int size(Object v) { return Mcp.json(v).getBytes(StandardCharsets.UTF_8).length; }
}
