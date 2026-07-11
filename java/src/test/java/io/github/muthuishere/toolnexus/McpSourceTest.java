package io.github.muthuishere.toolnexus;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;

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

    /**
     * MCP elicitation ⇄ §10 mapping (form→input, url→authorization, accept/decline/cancel).
     * Mirrors the JS reference unit test. The generated {@code elc-} id is not asserted.
     */
    @Test
    void elicitationBridgeMapsFormAndUrlAndAcceptDeclineCancel() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        props.put("name", nameProp);
        schema.put("properties", props);
        schema.put("required", java.util.List.of("name"));

        // form mode → kind:"input" carrying the schema in data.schema
        McpSchema.ElicitFormRequest form = new McpSchema.ElicitFormRequest("Your name?", schema, null);
        Request req = McpSource.elicitationToRequest(form);
        assertEquals("input", req.kind());
        assertEquals("Your name?", req.prompt());
        assertEquals(schema, req.data().get("schema"));
        assertNull(req.url());

        // URL mode → kind:"authorization" carrying the url, no schema
        McpSchema.ElicitUrlRequest url = new McpSchema.ElicitUrlRequest("Log in", "https://x/auth", "eid-1", null);
        Request ureq = McpSource.elicitationToRequest(url);
        assertEquals("authorization", ureq.kind());
        assertEquals("https://x/auth", ureq.url());
        assertNull(ureq.data());

        // Answer → ElicitResult
        McpSchema.ElicitResult acceptData = McpSource.answerToElicitResult(new Answer("1", true, Map.of("name", "Ada")));
        assertEquals(McpSchema.ElicitResult.Action.ACCEPT, acceptData.action());
        assertEquals(Map.of("name", "Ada"), acceptData.content());

        McpSchema.ElicitResult acceptEmpty = McpSource.answerToElicitResult(new Answer("1", true));
        assertEquals(McpSchema.ElicitResult.Action.ACCEPT, acceptEmpty.action());
        assertEquals(Map.of(), acceptEmpty.content());

        McpSchema.ElicitResult declined = McpSource.answerToElicitResult(new Answer("1", false, null, "declined"));
        assertEquals(McpSchema.ElicitResult.Action.DECLINE, declined.action());

        McpSchema.ElicitResult cancel = McpSource.answerToElicitResult(new Answer("1", false));
        assertEquals(McpSchema.ElicitResult.Action.CANCEL, cancel.action());

        McpSchema.ElicitResult expired = McpSource.answerToElicitResult(new Answer("1", false, null, "expired"));
        assertEquals(McpSchema.ElicitResult.Action.CANCEL, expired.action());
    }
}
