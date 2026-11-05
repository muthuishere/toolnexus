package io.github.muthuishere.toolnexus.mcp2026;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.muthuishere.toolnexus.mcp2026.Mcp.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolSearchTest {
    /** 10 services x 10 verbs, realistic descriptions + schemas. */
    static LocalServer hundredToolServer(Path dir) throws Exception {
        String[][] services = {{"github", "issue"}, {"slack", "message"}, {"jira", "ticket"}, {"gmail", "email"},
                {"calendar", "event"}, {"drive", "file"}, {"stripe", "invoice"}, {"postgres", "row"}, {"s3", "object"}, {"notion", "page"}};
        List<Object> tools = new ArrayList<>();
        for (String[] s : services)
            for (String v : List.of("create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment"))
                tools.add(obj("name", s[0] + "_" + v + "_" + s[1],
                        "description", v + " a " + s[1] + " in " + s[0] + ". Requires the workspace to be connected; returns the " + s[1] + " as JSON with its id, timestamps and owner.",
                        "inputSchema", obj("type", "object", "properties", obj(
                                "id", obj("type", "string", "description", "The " + s[1] + " id (omit when creating)."),
                                "query", obj("type", "string", "description", "Free-text filter applied server-side."),
                                "body", obj("type", "object", "description", "Fields of the " + s[1] + " to write."),
                                "options", obj("type", "object", "properties", obj("dryRun", obj("type", "boolean"), "limit", obj("type", "integer")))),
                                "required", List.of("query"))));
        Path f = dir.resolve("f.json");
        Files.writeString(f, json(obj("server", obj("name", "big", "version", "1"), "tools", tools, "skillsDir", ".")));
        return LocalServer.start(Server.load(f));
    }

    @Test
    void rankingAndSize(@TempDir Path dir) throws Exception {
        try (LocalServer ts = hundredToolServer(dir)) {
            Client eager = new Client(ts.url() + "/mcp");
            Client lazy = new Client(ts.url() + "/mcp").toolSearch(true).description("SaaS workspace (GitHub, Slack, Jira, ...)");
            List<Map<String, Object>> all = eager.tools(), meta = lazy.tools();
            assertEquals(100, all.size());
            Map.of("create slack message", "slack_create_message", "search stripe invoices", "stripe_search_invoice",
                    "archive a notion page", "notion_archive_page", "s3 export", "s3_export_object").forEach((q, want) -> {
                List<Map<String, Object>> hits = lazy.searchTools(q, 5);
                assertEquals(want, hits.get(0).get("name"), q);
                assertEquals(lazy.description(), hits.get(0).get("server"));
            });
            Map<String, Object> r = lazy.invoke("search_tools", obj("query", "create slack message"), null);
            int eb = json(all).length(), lb = json(meta).length(), rb = json(r.get("content")).length();
            System.out.printf("tool JSON bytes: eager(100 tools)=%d  lazy(2 meta-tools)=%d  + one search_tools result(5 hits)=%d%n", eb, lb, rb);
            assertTrue(lb * 10 <= eb, "lazy not smaller");
        }
    }

    @Test
    void invokeCallsFoundTool() {
        try (LocalServer ts = LocalServer.start(Server.fixture())) {
            Client c = new Client(ts.url() + "/mcp").toolSearch(true);
            assertEquals("echo", c.searchTools("echo text back", 3).get(0).get("name"));
            assertEquals("found", text(c.invoke("call_tool", obj("name", "echo", "arguments", obj("text", "found")), null)));
            assertThrows(McpException.class, () -> c.invoke("echo", obj(), null), "direct tool names are hidden in ToolSearch mode");
        }
    }
}
