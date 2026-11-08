package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-house frozen-path stdio client ({@link StdioMcpClient}) end to end through {@link McpSource#load}
 * (discover probe → fallback on the same child → initialize), against
 * {@code fixtures/stdio_behaviour_server.py}. Same intent as rust/src/mcp/stdio.rs's tests.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StdioMcpClientTest {
    private McpSource src;

    private static String fixture() throws Exception {
        return Path.of(StdioMcpClientTest.class.getResource("/fixtures/stdio_behaviour_server.py").toURI()).toString();
    }

    private McpSource load(java.util.function.Function<Request, Answer> waitFor) throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "local");
        cfg.put("command", List.of("python3", fixture()));
        cfg.put("timeout", 5000);
        src = McpSource.load(Map.of("b", cfg), new McpSource.LoadOptions().waitFor(waitFor));
        assertEquals("connected", src.status().get("b"));
        return src;
    }

    private Tool tool(String name) {
        return src.tools().stream().filter(t -> t.name().equals("b_" + name)).findFirst().orElseThrow();
    }

    @BeforeEach
    void setUp() throws Exception { load(null); }

    @AfterEach
    void tearDown() { if (src != null) src.close(); }

    @Test
    void listsEveryTool() {
        assertEquals(7, src.tools().size());
    }

    @Test
    void aFloodOfNotificationsDoesNotStallTheReply() {
        ToolResult r = tool("flood").execute(Map.of("n", 5000), new ToolContext());
        assertFalse(r.isError(), r.output());
        assertEquals("flooded", r.output());
    }

    @Test
    void twoCallsInFlightWithProgressEachGetTheirOwnReply() throws Exception {
        // 300 ms of work against a 5 s idle budget, both at once: replies are routed by id.
        CompletableFuture<ToolResult> a = CompletableFuture.supplyAsync(
                () -> tool("slow").execute(Map.of("tag", "a", "ms", 300), new ToolContext()));
        CompletableFuture<ToolResult> b = CompletableFuture.supplyAsync(
                () -> tool("slow").execute(Map.of("tag", "b", "ms", 200), new ToolContext()));
        assertEquals("done-a", a.get(10, TimeUnit.SECONDS).output());
        assertEquals("done-b", b.get(10, TimeUnit.SECONDS).output());
    }

    @Test
    void serverRequestsWhileACallIsInFlightDoNotDeadlock() {
        // 5000 pings while the server does not read stdin: replies written by the reader itself
        // would fill the stdin pipe, stop the reader, then fill stdout — both sides stuck.
        ToolResult r = tool("pings").execute(Map.of("n", 5000), new ToolContext());
        assertEquals("pinged-5000", r.output());
    }

    @Test
    void malformedAndBlankLinesAreIgnored() {
        assertEquals("clean", tool("garbage").execute(Map.of(), new ToolContext()).output());
        assertEquals("flooded", tool("flood").execute(Map.of("n", 1), new ToolContext()).output());
    }

    @Test
    void rootsListIsRefusedLikeTheSdkDid() {
        String out = tool("roots").execute(Map.of(), new ToolContext()).output();
        assertEquals("{\"code\": -32601, \"data\": {\"reason\": \"Client does not have roots capability\"}, "
                + "\"message\": \"Roots not supported\"}", out);
    }

    @Test
    void elicitationWithoutWaitForIsMethodNotFound() {
        String out = tool("ask").execute(Map.of(), new ToolContext()).output();
        assertEquals("None:null", out); // the error reply carries no result
    }

    @Test
    void elicitationBridgesToWaitFor() throws Exception {
        src.close();
        load(req -> new Answer(req.id(), true, Map.of("name", "muthu")));
        String out = tool("ask").execute(Map.of(), new ToolContext()).output();
        assertEquals("accept:{\"name\": \"muthu\"}", out);
    }

    @Test
    void childExitFailsThePendingCallAndLaterCalls() {
        ToolResult r = tool("die").execute(Map.of(), new ToolContext());
        assertTrue(r.isError());
        assertEquals("MCP stdio server closed the connection", r.output());
        ToolResult again = tool("flood").execute(Map.of("n", 1), new ToolContext());
        assertTrue(again.isError());
        assertEquals("MCP stdio server closed the connection", again.output());
    }

    @Test
    void jsonRpcErrorBecomesTheErrorMessage() throws Exception {
        Process p = new ProcessBuilder("python3", fixture()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        StdioMcpClient c = StdioMcpClient.attach(p,
                new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier().get(), null, null);
        try {
            c.initialize(5000);
            io.modelcontextprotocol.spec.McpError e = org.junit.jupiter.api.Assertions.assertThrows(
                    io.modelcontextprotocol.spec.McpError.class,
                    () -> c.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("nope", Map.of())));
            assertEquals("unknown tool nope", e.getMessage());
            assertEquals(-32602, e.getJsonRpcError().code());
        } finally {
            c.closeGracefully();
        }
        assertFalse(p.isAlive());
    }
}
