package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.mcp.Mcp;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the port's JSON bytes (SPEC parity) across a JSON-library change: compact + pretty output,
 * sorted (Mcp / Go-compatible ToolTiers) and insertion-ordered maps, record component order and
 * NON_NULL, number formatting, escaping, enums, and the loose/strict parse paths (number types,
 * trailing tokens). The golden file was generated on the Jackson 2 build; regenerate only on a
 * deliberate, spec-approved output change: env {@code TOOLNEXUS_GOLDEN_WRITE=<path>}.
 */
class JsonByteParityTest {

    enum Color { RED, GREEN }

    static Map<String, Object> sample() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zeta", "last-inserted-first");
        m.put("alpha", 1);
        m.put("url", "https://example.com/a/b?x=1&y=<2>");
        m.put("escapes", "quote\" back\\ nl\n tab\t cr\r bell\u0007 nul\u0000 del\u007f");
        m.put("unicode", "é ü 中文 😀    ");
        m.put("html", "</script><b>&amp;</b>'");
        m.put("nullValue", null);
        m.put("bool", true);
        m.put("int", 42);
        m.put("negLong", -9007199254740993L);
        m.put("big", new BigInteger("123456789012345678901234567890"));
        m.put("dec", new BigDecimal("1.10"));
        m.put("doubles", List.of(1.0, 0.1, 1e20, 1e-7, 123456789.123, -0.0, 3.0e300));
        m.put("float", 1.5f);
        m.put("emptyMap", new LinkedHashMap<>());
        m.put("emptyList", new ArrayList<>());
        m.put("nested", Map.of("k", List.of(Map.of("deep", List.of()))));
        m.put("enum", Color.GREEN);
        m.put("tier", LlmClient.Tier.RETRY);
        m.put("answer", new Answer("a1", false, null, "declined"));
        m.put("answerData", new Answer("a2", true, Map.of("output", "x")));
        m.put("request", new Request("r1", "input", "Name?", null, null, "2026-01-01T00:00:00Z"));
        m.put("part", ContentPart.text("hi"));
        m.put("chars", new char[]{'a', 'b'});
        return m;
    }

    static String render() {
        StringBuilder b = new StringBuilder();
        Map<String, Object> s = sample();
        b.append("stringify: ").append(Json.stringify(s)).append('\n');
        b.append("pretty:\n").append(Json.pretty(s)).append('\n');
        b.append("pretty-empty: ").append(Json.pretty(Map.of())).append(' ').append(Json.pretty(List.of())).append('\n');
        b.append("mcp-sorted: ").append(Mcp.json(s)).append('\n');
        b.append("mcp-ordered: ").append(Mcp.jsonOrdered(s)).append('\n');
        b.append("go-json: ").append(ToolTiers.goJson(s)).append('\n');
        String in = "{\"i\":1,\"l\":12345678901,\"b\":123456789012345678901234567890,\"d\":1.5,\"e\":1e2,"
                + "\"z\":0.10,\"n\":null,\"t\":true,\"a\":[1,2.0,\"x\"],\"o\":{\"y\":2,\"x\":1}}";
        b.append("toMap-types: ").append(types(Json.toMap(in))).append('\n');
        b.append("toMap-roundtrip: ").append(Json.stringify(Json.toMap(in))).append('\n');
        b.append("loose-trailing: ").append(Json.parseObjectLoose("{\"a\":1} trailing")).append('\n');
        b.append("loose-bad: ").append(Json.parseObjectLoose("{nope")).append('\n');
        b.append("loose-blank: ").append(Json.parseObjectLoose("  ")).append('\n');
        b.append("parseLoose-scalar: ").append(types(Json.parseLoose("12.50"))).append('\n');
        b.append("parseLoose-trailing: ").append(types(Json.parseLoose("[1] x"))).append('\n');
        b.append("parseLoose-text: ").append(types(Json.parseLoose("not json"))).append('\n');
        b.append("parseLoose-dupkeys: ").append(Json.stringify(Json.parseLoose("{\"a\":1,\"a\":2}"))).append('\n');
        String strict;
        try {
            strict = String.valueOf(Json.toMap("{\"a\":1} x"));
        } catch (RuntimeException e) {
            strict = "threw";
        }
        b.append("toMap-trailing: ").append(strict).append('\n');
        return b.toString();
    }

    /** Value plus the Java type of every leaf, so a Long→Integer or Double→BigDecimal shift shows. */
    static String types(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            m.forEach((k, x) -> b.append(k).append('=').append(types(x)).append(','));
            return b.append('}').toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (Object x : l) b.append(types(x)).append(',');
            return b.append(']').toString();
        }
        return v == null ? "null" : v.getClass().getSimpleName() + ":" + v;
    }

    @Test
    void jsonBytesMatchTheGolden() throws Exception {
        String actual = render();
        String write = System.getenv("TOOLNEXUS_GOLDEN_WRITE");
        if (write != null) Files.writeString(Path.of(write), actual, StandardCharsets.UTF_8);
        String golden = new String(JsonByteParityTest.class.getResourceAsStream("/fixtures/json-parity.golden.txt")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(golden, actual);
    }
}
