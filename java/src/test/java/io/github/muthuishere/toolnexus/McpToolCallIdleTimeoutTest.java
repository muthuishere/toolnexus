package io.github.muthuishere.toolnexus;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §2 idle timeout + cancellation for MCP {@code tools/call} (McpSource.callWithIdleTimeout
 * and its shared watchdog), against the hermetic fixture {@code fixtures/slow_mcp_server.py}
 * (stdlib python3, stdio only, no network). Budgets are small so every test stays well under a
 * second. Each test also pins that no interrupt is left on the calling thread.
 */
class McpToolCallIdleTimeoutTest {

    private static final long BUDGET_MS = 300;
    private static McpSource src;
    private static Map<String, Tool> tools;

    static String fixture() {
        try {
            return Path.of(McpToolCallIdleTimeoutTest.class.getResource("/fixtures/slow_mcp_server.py").toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void connect() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "local");
        cfg.put("command", List.of("python3", fixture()));
        cfg.put("timeout", (int) BUDGET_MS);
        src = McpSource.load(Map.of("slow", cfg));
        assertEquals("connected", src.status().get("slow"));
        tools = new LinkedHashMap<>();
        for (Tool t : src.tools()) tools.put(t.name(), t);
    }

    @AfterAll
    static void close() throws Exception {
        if (src != null) src.close();
    }

    private static long ms(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }

    private static void assertNoInterruptLeak() {
        assertFalse(Thread.interrupted(), "interrupt flag leaked to the caller thread");
    }

    @Test
    void idleTimeoutFiresAtBudgetWithSpecMessage() {
        long t0 = System.nanoTime();
        ToolResult r = tools.get("slow_sleep").execute(Map.of("ms", 2000), new ToolContext());
        long took = ms(t0);
        assertNoInterruptLeak();
        assertTrue(r.isError());
        assertEquals("MCP tool call timed out after 300ms without progress", r.output());
        assertTrue(took >= BUDGET_MS - 20 && took < 900, "fired at " + took + "ms, budget " + BUDGET_MS);
    }

    @Test
    void progressNotificationsResetTheIdleBudget() {
        long t0 = System.nanoTime();
        // 6 × 100 ms = 600 ms total, twice the budget, but never 300 ms without progress.
        ToolResult r = tools.get("slow_progress").execute(Map.of("steps", 6, "every", 100), new ToolContext());
        long took = ms(t0);
        assertNoInterruptLeak();
        assertFalse(r.isError(), r.output());
        assertEquals("progress done", r.output());
        assertTrue(took >= 550, "returned at " + took + "ms, before the progress finished");
    }

    @Test
    void cancellationStopsTheCallPromptly() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            cancelled.set(true);
        });
        long t0 = System.nanoTime();
        canceller.start();
        ToolResult r = tools.get("slow_sleep").execute(Map.of("ms", 2000), new ToolContext(null, cancelled));
        long took = ms(t0);
        canceller.join();
        assertNoInterruptLeak();
        assertTrue(r.isError());
        assertEquals("MCP tool call cancelled", r.output());
        assertTrue(took < BUDGET_MS - 50, "cancel took " + took + "ms (must beat the idle budget)");
    }

    @Test
    void alreadyCancelledContextIsCancelledAtOnce() {
        ToolResult r = tools.get("slow_sleep").execute(Map.of("ms", 2000), new ToolContext(null, new AtomicBoolean(true)));
        assertNoInterruptLeak();
        assertTrue(r.isError());
        assertEquals("MCP tool call cancelled", r.output());
    }

    @Test
    void fastCallsSucceedAndLeaveNoInterrupt() {
        for (int i = 0; i < 20; i++) {
            ToolResult r = tools.get("slow_fast").execute(Map.of(), i % 2 == 0 ? new ToolContext() : null);
            assertNoInterruptLeak();
            assertFalse(r.isError(), r.output());
            assertEquals("fast done", r.output());
        }
    }

    /**
     * Reply and idle trip land together: the server answers at the budget. Every outcome must be
     * exactly one of the two (reply or the timeout error — never a mixed/garbled one), the
     * watchdog's interrupt must never survive the call, and the very next call on the same client
     * must succeed (no stale completion or progress state carried over).
     */
    @Test
    void replyRacingTheTimeoutCompletesOnceAndLeaksNothing() throws Exception {
        Process proc = new ProcessBuilder("python3", fixture()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        McpSyncClient client = McpClient.sync(new ProcessStdioTransport(proc, new JacksonMcpJsonMapperSupplier().get()))
                .requestTimeout(Duration.ofSeconds(10)).build();
        try {
            client.initialize();
            McpSource.ProgressRegistry progress = new McpSource.ProgressRegistry();
            long budget = 30;
            int replied = 0, timedOut = 0;
            for (int i = 0; i < 20; i++) {
                String out;
                try {
                    McpSchema.CallToolResult res = McpSource.callWithIdleTimeout(client, "sleep",
                            Map.of("ms", budget), budget, progress, new ToolContext());
                    out = ((McpSchema.TextContent) res.content().get(0)).text();
                    replied++;
                    assertEquals("sleep done", out);
                } catch (RuntimeException e) {
                    timedOut++;
                    assertEquals("MCP tool call timed out after 30ms without progress", e.getMessage());
                }
                assertNoInterruptLeak();
                McpSchema.CallToolResult next = McpSource.callWithIdleTimeout(client, "fast", Map.of(), 1000,
                        progress, new ToolContext());
                assertEquals("fast done", ((McpSchema.TextContent) next.content().get(0)).text());
                assertNoInterruptLeak();
            }
            assertEquals(20, replied + timedOut);
        } finally {
            client.closeGracefully();
            proc.destroyForcibly();
        }
    }
}
