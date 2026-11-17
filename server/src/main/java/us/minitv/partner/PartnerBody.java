package us.minitv.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Pydantic-flavoured request-body parsing for the partner surface.
 *
 * <p>The Python router takes Pydantic models whose {@code exclude_unset}
 * semantics matter (a PATCH must distinguish "field absent" from "field set to
 * null"), so bodies are read from a {@link JsonNode} instead of a record. The
 * error shapes match FastAPI's 422 payloads.
 */
public final class PartnerBody {

    private PartnerBody() {
    }

    public static JsonNode require(JsonNode body) {
        if (body == null || body.isNull() || !body.isObject()) {
            throw PartnerErrors.missingBody();
        }
        return body;
    }

    public static boolean present(JsonNode body, String field) {
        return body.has(field);
    }

    public static boolean isNull(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull();
    }

    // ---- strings ----

    public static String requiredString(JsonNode body, String field, int minLength, int maxLength) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw PartnerErrors.bodyValidation(field, "Field required", "missing");
        }
        return stringValue(field, node, minLength, maxLength);
    }

    /** Optional string: absent or null both yield null; a present value is validated. */
    public static String optionalString(JsonNode body, String field, int minLength, int maxLength) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return stringValue(field, node, minLength, maxLength);
    }

    /** A field that defaults to "" when absent but must be a string when present. */
    public static String stringOr(JsonNode body, String field, String fallback, int maxLength) {
        JsonNode node = body.get(field);
        if (node == null) {
            return fallback;
        }
        if (node.isNull()) {
            throw PartnerErrors.bodyValidation(field, "Input should be a valid string", "string_type");
        }
        return stringValue(field, node, 0, maxLength);
    }

    private static String stringValue(String field, JsonNode node, int minLength, int maxLength) {
        if (!node.isTextual()) {
            throw PartnerErrors.bodyValidation(field, "Input should be a valid string", "string_type");
        }
        String value = node.asText();
        int length = PartnerText.pyLen(value);
        if (length < minLength) {
            throw PartnerErrors.bodyValidation(field,
                    "String should have at least " + minLength + " character" + (minLength == 1 ? "" : "s"),
                    "string_too_short");
        }
        if (length > maxLength) {
            throw PartnerErrors.bodyValidation(field,
                    "String should have at most " + maxLength + " characters", "string_too_long");
        }
        return value;
    }

    // ---- integers ----

    public static Integer optionalInt(JsonNode body, String field, Integer min, Integer max) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return intValue(field, node, min, max);
    }

    public static int intOr(JsonNode body, String field, int fallback, Integer min, Integer max) {
        JsonNode node = body.get(field);
        if (node == null) {
            return fallback;
        }
        if (node.isNull()) {
            throw PartnerErrors.bodyValidation(field, "Input should be a valid integer", "int_type");
        }
        return intValue(field, node, min, max);
    }

    public static int requiredInt(JsonNode body, String field, Integer min, Integer max) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw PartnerErrors.bodyValidation(field, "Field required", "missing");
        }
        return intValue(field, node, min, max);
    }

    private static int intValue(String field, JsonNode node, Integer min, Integer max) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw PartnerErrors.bodyValidation(field, "Input should be a valid integer", "int_type");
        }
        int value = node.asInt();
        if (min != null && value < min) {
            throw PartnerErrors.bodyValidation(field,
                    "Input should be greater than or equal to " + min, "greater_than_equal");
        }
        if (max != null && value > max) {
            throw PartnerErrors.bodyValidation(field,
                    "Input should be less than or equal to " + max, "less_than_equal");
        }
        return value;
    }

    // ---- string lists ----

    /** Optional {@code list[str]}: absent or null -> null; a present list is validated element-wise. */
    public static List<String> optionalStringList(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw PartnerErrors.bodyValidation(field, "Input should be a valid list", "list_type");
        }
        List<String> values = new ArrayList<>();
        int index = 0;
        for (JsonNode item : (ArrayNode) node) {
            if (!item.isTextual()) {
                throw indexError(field, index, "Input should be a valid string", "string_type");
            }
            values.add(item.asText());
            index++;
        }
        return values;
    }

    /** Optional {@code list[int]}: absent or null -> null; a present list is validated element-wise. */
    public static List<Long> optionalLongList(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw PartnerErrors.bodyValidation(field, "Input should be a valid list", "list_type");
        }
        List<Long> values = new ArrayList<>();
        int index = 0;
        for (JsonNode item : (ArrayNode) node) {
            if (!item.isIntegralNumber() || !item.canConvertToLong()) {
                throw indexError(field, index, "Input should be a valid integer", "int_type");
            }
            values.add(item.asLong());
            index++;
        }
        return values;
    }

    private static us.minitv.common.ApiException indexError(String field, int index, String message, String type) {
        return PartnerErrors.bodyValidation(field + "." + index, message, type);
    }

    /** JSON string value for an optional field, used by upload-failed's free-form body. */
    public static String textOrNull(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
