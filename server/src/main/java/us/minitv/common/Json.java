package us.minitv.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** One shared Jackson instance for JSON-column payloads and small conversions. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialise JSON payload", ex);
        }
    }

    /** Parse a JSON column into a tree; empty/NULL columns become an empty object. */
    public static JsonNode read(String raw) {
        return read(raw, MAPPER.createObjectNode());
    }

    public static JsonNode read(String raw, JsonNode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    public static <T> T read(String raw, Class<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    public static Map<String, Object> readMap(String raw) {
        return read(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        }, Map.of());
    }

    public static <T> T read(String raw, com.fasterxml.jackson.core.type.TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }
}
