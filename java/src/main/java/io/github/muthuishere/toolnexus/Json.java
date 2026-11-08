package io.github.muthuishere.toolnexus;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Small shared Jackson (databind) helper for our own JSON (config, adapters, LLM client).
 *
 * <p>Jackson 3 — the one JSON library of this port (the MCP SDK requires it). {@link #builder()}
 * restores the Jackson 2 defaults whose change would alter our bytes or parse results;
 * {@code JsonByteParityTest} pins the output against a golden captured on Jackson 2.
 */
public final class Json {
    static final ObjectMapper MAPPER = builder().build();
    static final ObjectMapper PRETTY = builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /**
     * A mapper builder with the Jackson 2 behaviour this port's wire format depends on:
     * records/POJOs keep declaration order (Jackson 3 sorts properties alphabetically by default),
     * a trailing token after a complete value is ignored by the loose parsers (Jackson 3 fails),
     * and a JSON null for a primitive stays permitted.
     */
    static JsonMapper.Builder builder() {
        return JsonMapper.builder()
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    private Json() {}

    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static String pretty(Object value) {
        try {
            return PRETTY.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** Parse a JSON object strictly (throws on invalid JSON). Public because the {@code agents}
     * subpackage (and consumers writing mock/test harnesses) need a strict object parse — Java
     * subpackages do not share package-private access. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObjectLoose(String json) {
        try {
            if (json == null || json.isBlank()) return new java.util.LinkedHashMap<>();
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>();
        }
    }

    static Object parseLoose(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (Exception e) {
            return text;
        }
    }
}
