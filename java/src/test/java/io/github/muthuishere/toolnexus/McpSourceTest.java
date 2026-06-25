package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the MCP config parser and the env-header expander. No live connect.
 * Mirrors the JS "parseMcpConfig accepts ..." and "expandEnvHeaders ..." tests.
 */
class McpSourceTest {

    private static Map<String, Object> serversBlock() {
        Map<String, Object> foo = new LinkedHashMap<>();
        foo.put("command", java.util.List.of("x"));
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("foo", foo);
        return servers;
    }

    @Test
    void parseConfigAcceptsMcpServersServersMcpAndRaw() {
        Map<String, Object> s = serversBlock();

        Map<String, Object> wrappedMcpServers = new LinkedHashMap<>();
        wrappedMcpServers.put("mcpServers", s);
        assertEquals(s, McpSource.parseConfig(wrappedMcpServers));

        Map<String, Object> wrappedServers = new LinkedHashMap<>();
        wrappedServers.put("servers", s);
        assertEquals(s, McpSource.parseConfig(wrappedServers));

        Map<String, Object> wrappedMcp = new LinkedHashMap<>();
        wrappedMcp.put("mcp", s);
        assertEquals(s, McpSource.parseConfig(wrappedMcp));

        // raw (no wrapper) — the map IS the servers map.
        assertEquals(s, McpSource.parseConfig(s));
    }

    @Test
    void parseConfigAcceptsRawJsonString() {
        String json = "{\"mcpServers\":{\"foo\":{\"command\":[\"x\"]}}}";
        Map<String, Object> parsed = McpSource.parseConfig(json);
        assertEquals(true, parsed.containsKey("foo"));
    }

    @Test
    void expandEnvHeadersExpandsViaLookupStub() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer ${TN_TEST_TOKEN}");
        headers.put("X", "plain");

        Map<String, String> env = Map.of("TN_TEST_TOKEN", "secret123");
        Map<String, String> out = McpSource.expandEnvHeaders(headers, env::get);

        assertEquals("Bearer secret123", out.get("Authorization"));
        assertEquals("plain", out.get("X"));
    }

    @Test
    void expandEnvHeadersMissingVarBecomesEmpty() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer ${NOT_SET_VAR}");
        Map<String, String> out = McpSource.expandEnvHeaders(headers, name -> null);
        assertEquals("Bearer ", out.get("Authorization"));
    }

    @Test
    void expandEnvHeadersNullInNullOut() {
        assertNull(McpSource.expandEnvHeaders(null));
        assertNull(McpSource.expandEnvHeaders(null, name -> "x"));
    }

    @Test
    void expandEnvHeadersDefaultUsesSystemGetenv() {
        // PATH is reliably present in the process environment on the CI/dev box.
        String path = System.getenv("PATH");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isEmpty());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Path", "p=${PATH}");
        Map<String, String> out = McpSource.expandEnvHeaders(headers);
        assertEquals("p=" + path, out.get("X-Path"));
    }
}
