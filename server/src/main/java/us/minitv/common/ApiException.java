package us.minitv.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP error carrying a FastAPI-compatible {@code detail} payload.
 *
 * FastAPI raises {@code HTTPException(status, detail)} and serialises
 * {@code {"detail": ...}}; clients (see admin/src/api.ts) read either a string
 * or an object with {@code code}/{@code message}. Keep both shapes possible.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final Object detail;
    private final Map<String, String> headers;

    public ApiException(int status, Object detail) {
        this(status, detail, Map.of());
    }

    public ApiException(int status, Object detail, Map<String, String> headers) {
        super(detail instanceof String s ? s : String.valueOf(detail));
        this.status = status;
        this.detail = detail;
        this.headers = headers == null ? Map.of() : headers;
    }

    public int status() {
        return status;
    }

    public Object detail() {
        return detail;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public static ApiException of(int status, String message) {
        return new ApiException(status, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(401, detail(code, message), Map.of("WWW-Authenticate", "Bearer"));
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, detail(code, message));
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

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(503, message);
    }

    /** Structured detail: stable machine-readable code plus a human message. */
    public static Map<String, Object> detail(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        return payload;
    }
}
