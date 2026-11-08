package io.github.muthuishere.toolnexus.mcp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared wire constants for MCP 2026-07-28 (stateless Streamable HTTP) plus tiny JSON helpers. */
public final class Mcp {
    private Mcp() {}

    public static final String PROTOCOL_VERSION = "2026-07-28";
    public static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    public static final String META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities";
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    public static final String SKILLS_EXT = "io.modelcontextprotocol/skills";

    public static final int CODE_METHOD_NOT_FOUND = -32601;
    public static final int CODE_INVALID_PARAMS = -32602;
    public static final int CODE_HEADER_MISMATCH = -32020;
    public static final int CODE_UNSUPPORTED_PROTOCOL = -32022;

    /** Sorted keys, like Go's encoding/json, so byte sizes and wire bodies match across ports. */
    static final ObjectMapper JSON = jackson2Compatible().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    /** Same Jackson 2-compatible defaults as {@code toolnexus.Json.builder()} (another package). */
    private static JsonMapper.Builder jackson2Compatible() {
        return JsonMapper.builder()
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    /** Builds a mutable JSON object from alternating key/value pairs. */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String json(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (JacksonException e) {
            throw new McpException("not JSON: " + e.getMessage(), e);
        }
    }

    private static final ObjectMapper JSON_ORDERED = jackson2Compatible().build();

    /** Compact JSON keeping each map's insertion order (byte parity for search_tools hits, §2.8). */
    public static String jsonOrdered(Object v) {
        try {
            return JSON_ORDERED.writeValueAsString(v);
        } catch (JacksonException e) {
            throw new McpException("not JSON: " + e.getMessage(), e);
        }
    }

    static byte[] jsonBytes(Object v) { return json(v).getBytes(StandardCharsets.UTF_8); }

    /** Parse failures stay {@link IOException}s (Jackson 3's {@code JacksonException} is unchecked). */
    static Object parse(byte[] b) throws IOException {
        try {
            return JSON.readValue(b, Object.class);
        } catch (JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null; }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }

    public static String str(Object o) { return o instanceof String s ? s : ""; }

    /** Returns the first text content of a tool result ("" if none). */
    public static String text(Map<String, Object> r) {
        if (r == null) return "";
        List<Object> c = list(r.get("content"));
        if (c.isEmpty() || map(c.get(0)) == null) return "";
        return str(map(c.get(0)).get("text"));
    }
}
