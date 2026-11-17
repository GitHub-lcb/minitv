package us.minitv.pcommerce.support;

import com.fasterxml.jackson.databind.JsonNode;
import us.minitv.common.ApiException;
import us.minitv.common.Json;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-shape helpers: FastAPI-style 422 bodies and raw JSON decoding. */
public final class PCommerceRequests {

    private PCommerceRequests() {
    }

    /** FastAPI's missing-body-field 422: {"detail":[{type,loc,msg}]}. */
    public static ApiException missingField(String field) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "missing");
        item.put("loc", List.of("body", field));
        item.put("msg", "Field required");
        return new ApiException(422, List.of(item));
    }

    public static <T> T required(T value, String field) {
        if (value == null) {
            throw missingField(field);
        }
        return value;
    }

    /**
     * json.loads(body) over the raw request bytes. Throws when the payload is
     * absent or malformed; callers translate that into their own status code.
     */
    public static JsonNode readJson(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("empty body");
        }
        try {
            JsonNode node = Json.mapper().readTree(new String(body, StandardCharsets.UTF_8));
            if (node == null || node.isMissingNode() || node.isNull()) {
                throw new IllegalArgumentException("empty json");
            }
            return node;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("invalid json", ex);
        }
    }

    /** node.get(field) with Python truthiness for "or" fallbacks. */
    public static JsonNode field(JsonNode node, String name) {
        if (node == null || !node.has(name)) {
            return null;
        }
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value;
    }

    public static String text(JsonNode node, String name) {
        JsonNode value = field(node, name);
        return value == null ? null : value.asText();
    }

    public static Long longValue(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static Boolean booleanValue(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (value == null) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asInt() != 0;
        }
        return "true".equalsIgnoreCase(value.asText());
    }
}
