package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors the JS "adapters produce the documented shapes" test. Serializes with
 * Jackson and asserts the structure of each provider's tool schema.
 */
class AdaptersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Tool sampleTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return NativeTool.of("t", "d", schema, args -> "");
    }

    @Test
    void toOpenAIShape() throws Exception {
        Tool t = sampleTool();
        String json = MAPPER.writeValueAsString(Adapters.toOpenAI(List.of(t)));
        JsonNode arr = MAPPER.readTree(json);
        JsonNode first = arr.get(0);
        assertEquals("function", first.get("type").asText());
        JsonNode fn = first.get("function");
        assertEquals("t", fn.get("name").asText());
        assertEquals("d", fn.get("description").asText());
        assertEquals("object", fn.get("parameters").get("type").asText());
    }

    @Test
    void toAnthropicShape() throws Exception {
        Tool t = sampleTool();
        String json = MAPPER.writeValueAsString(Adapters.toAnthropic(List.of(t)));
        JsonNode first = MAPPER.readTree(json).get(0);
        assertEquals("t", first.get("name").asText());
        assertEquals("d", first.get("description").asText());
        assertTrue(first.has("input_schema"));
        assertEquals("object", first.get("input_schema").get("type").asText());
    }

    @Test
    void toGeminiShape() throws Exception {
        Tool t = sampleTool();
        String json = MAPPER.writeValueAsString(Adapters.toGemini(List.of(t)));
        JsonNode first = MAPPER.readTree(json).get(0);
        JsonNode decls = first.get("functionDeclarations");
        assertTrue(decls.isArray());
        assertEquals("t", decls.get(0).get("name").asText());
    }
}
