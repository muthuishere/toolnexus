package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SPEC §2.2a (change reuse-stdio-discover-probe) against the shared fixture
 * {@code examples/mcp-2026/legacy-stdio/server.mjs}: a fast discover error continues on the SAME
 * child (1 start); a silent discover times out, kills the probe and respawns (2 starts).
 */
class StdioDiscoverProbeReuseTest {

    private static String serverPath() {
        Path p = Path.of("..", "examples", "mcp-2026", "legacy-stdio", "server.mjs").toAbsolutePath().normalize();
        if (!Files.exists(p)) throw new IllegalStateException("fixture missing: " + p);
        return p.toString();
    }

    private static int starts(String mode, Path dir) throws Exception {
        Path count = dir.resolve("spawns-" + mode + ".txt");
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "local");
        cfg.put("command", List.of("node", serverPath()));
        cfg.put("environment", Map.of("SPAWN_COUNT_FILE", count.toString(), "DISCOVER_MODE", mode));
        cfg.put("timeout", 1500);
        try (McpSource src = McpSource.load(Map.of("legacy", cfg))) {
            assertEquals("connected", src.status().get("legacy"), mode);
            Tool echo = src.tools().stream().filter(t -> t.name().equals("legacy_echo")).findFirst().orElseThrow();
            ToolResult r = echo.execute(Map.of("text", "hi"), new ToolContext());
            assertEquals("echo:hi", r.output(), mode);
        }
        return (int) Files.readAllLines(count).stream().filter(l -> l.startsWith("start ")).count();
    }

    @Test
    void fastDiscoverErrorReusesTheProbeChild(@TempDir Path dir) throws Exception {
        assertEquals(1, starts("error", dir));
    }

    @Test
    void silentDiscoverRespawns(@TempDir Path dir) throws Exception {
        assertEquals(2, starts("silent", dir));
    }
}
