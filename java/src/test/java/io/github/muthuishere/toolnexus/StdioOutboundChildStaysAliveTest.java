package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the v0.9.0 stdio bug documented in
 * {@code docs/adr/0007-stdio-connect-ctx-regression.md}: after a successful connect, the
 * local (stdio) MCP child process must stay alive so {@code listTools} and {@code callTool}
 * keep working. In the Go port the child was SIGKILL'd the moment connect returned (the
 * transport Start ran on a connect-timeout context), so every post-connect call hit a dead
 * pipe ({@code transport closed}). Java owns the child via the transport lifecycle
 * ({@code StdioClientTransport.closeGracefully}), not a connect-timeout — this test proves it.
 *
 * <p>Hermetic and Java-native: launches {@link StdioMcpServerMain} as a child JVM on the
 * current test classpath (no external process, no network, no python). Mirrors the Go
 * {@code TestStdioOutboundChildStaysAlive} guard.
 */
class StdioOutboundChildStaysAliveTest {

    /** [java, -cp, <classpath>, <StdioMcpServerMain FQN>] — a genuine outbound stdio MCP server. */
    private static List<String> stdioServerCommand() {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        return List.of(javaBin, "-cp", classpath, StdioMcpServerMain.class.getName());
    }

    @Test
    void stdioChildStaysAliveAfterConnectAndToolsExecute() {
        Map<String, Object> srv = new LinkedHashMap<>();
        srv.put("command", stdioServerCommand());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("srv", srv);

        McpSource src = McpSource.load(config);
        try {
            // 1. connect ok
            assertEquals("connected", src.status().get("srv"),
                    "stdio server should connect (status=" + src.status() + ")");

            // 2. listTools returned a,b,c — if the child died at/after connect this is empty
            //    (the regression signature: connect ok, then transport closed on list).
            Map<String, Tool> byName = new LinkedHashMap<>();
            for (Tool t : src.tools()) {
                byName.put(t.name(), t);
            }
            assertTrue(byName.containsKey("srv_a"), "missing srv_a (child likely killed after connect): " + byName.keySet());
            assertTrue(byName.containsKey("srv_b"), "missing srv_b: " + byName.keySet());
            assertTrue(byName.containsKey("srv_c"), "missing srv_c: " + byName.keySet());

            // 3. a tool EXECUTES on the still-live child AFTER connect — the real regression probe.
            ToolResult res = byName.get("srv_a").execute(Map.of(), null);
            assertFalse(res.isError(), "execute srv_a on live stdio child returned error: " + res.output());
            assertEquals("a", res.output(), "srv_a should echo 'a'");
        } finally {
            src.close();
        }
    }
}
