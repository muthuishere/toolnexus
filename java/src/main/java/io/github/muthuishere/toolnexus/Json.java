package io.github.muthuishere.toolnexus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;

/** Small shared Jackson (databind) helper for our own JSON (config, adapters, LLM client). */
public final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

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

    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(String json) {
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
