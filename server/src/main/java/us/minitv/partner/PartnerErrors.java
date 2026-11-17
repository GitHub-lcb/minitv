package us.minitv.partner;

import us.minitv.common.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Error factories for the partner surface.
 *
 * <p>Business failures mirror FastAPI's {@code HTTPException(status, detail)}
 * exactly: a bare string or a structured {@code {code, message, ...}} object.
 * Request-validation failures mirror Pydantic v2's
 * {@code {"detail": [{type, loc, msg}, ...]}} array.
 */
public final class PartnerErrors {

    private PartnerErrors() {
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(422, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    /** Structured detail with extra keys, e.g. {@code title_exists} carrying drama_id. */
    public static Map<String, Object> detail(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        return payload;
    }

    public static ApiException structured(int status, Map<String, Object> detail) {
        return new ApiException(status, detail);
    }

    /** Pydantic-style body validation error: 422 + array detail. */
    public static ApiException bodyValidation(String field, String message, String type) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("loc", field == null ? List.of("body") : List.of("body", field));
        item.put("msg", message);
        List<Map<String, Object>> errors = new ArrayList<>();
        errors.add(item);
        return new ApiException(422, errors);
    }

    /** Pydantic-style query validation error: 422 + array detail with a query loc. */
    public static ApiException queryValidation(String name, String message, String type) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("loc", List.of("query", name));
        item.put("msg", message);
        List<Map<String, Object>> errors = new ArrayList<>();
        errors.add(item);
        return new ApiException(422, errors);
    }

    public static ApiException missingBody() {
        return bodyValidation(null, "Field required", "missing");
    }
}
