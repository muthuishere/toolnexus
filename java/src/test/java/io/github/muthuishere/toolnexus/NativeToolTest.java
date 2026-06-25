package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.annotations.Param;
import io.github.muthuishere.toolnexus.annotations.ToolMethod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors the JS "defineTool: string, ToolResult, and thrown error" test, plus
 * the reflection-based {@code Tools.fromObject} discovery (the Java equivalent
 * of Spring-AI's {@code @Tool}).
 */
class NativeToolTest {

    @Test
    void stringResultOk() {
        NativeTool s = NativeTool.of("s", "", null, args -> "hi");
        ToolResult r = s.execute(Map.of(), null);
        assertEquals("hi", r.output());
        assertFalse(r.isError());
        assertEquals("native", s.source());
    }

    @Test
    void toolResultPassthrough() {
        NativeTool r = NativeTool.of("r", "", null, args -> ToolResult.error("x"));
        ToolResult out = r.execute(Map.of(), null);
        assertEquals("x", out.output());
        assertTrue(out.isError());
    }

    @Test
    void thrownErrorBecomesErrorResult() {
        NativeTool e = NativeTool.of("e", "", null, args -> { throw new RuntimeException("boom"); });
        ToolResult er = e.execute(Map.of(), null);
        assertTrue(er.isError());
        assertTrue(er.output().contains("boom"));
    }

    // --- reflection (annotation) discovery ---

    static final class Calc {
        @ToolMethod(name = "add", description = "Add two numbers")
        public String add(@Param(name = "a") int a, @Param(name = "b") int b) {
            return String.valueOf(a + b);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fromObjectDiscoversAnnotatedToolWithInferredSchema() {
        List<Tool> tools = Tools.fromObject(new Calc());
        assertEquals(1, tools.size());
        Tool add = tools.get(0);
        assertEquals("add", add.name());
        assertEquals("Add two numbers", add.description());
        assertEquals("native", add.source());

        Map<String, Object> schema = add.inputSchema();
        assertEquals("object", schema.get("type"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("a"));
        assertTrue(props.containsKey("b"));
        assertEquals("number", ((Map<String, Object>) props.get("a")).get("type"));
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("a") && required.contains("b"));

        ToolResult res = add.execute(Map.of("a", 2, "b", 3), null);
        assertFalse(res.isError());
        assertEquals("5", res.output());
    }
}
