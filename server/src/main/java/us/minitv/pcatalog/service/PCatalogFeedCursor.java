package us.minitv.pcatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import us.minitv.common.ApiException;
import us.minitv.common.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Opaque, signed feed cursor - a byte-compatible port of
 * {@code recommend.service.encode_cursor/decode_cursor}.
 *
 * <p>The cursor is {@code base64url(json{"s","o","sd"}) + "." + hmac_sha256(key, payload).hex[:16]}.
 * Python signs the base64 payload string with {@code FEED_CURSOR_KEY} (default
 * {@code local-feed-cursor}), and so does this class.
 */
@Component
public class PCatalogFeedCursor {

    /** Decoded pointer into one feed session. */
    public record Resolved(String sessionId, int offset, String seed) {
    }

    private static final String HMAC = "HmacSHA256";

    private final byte[] secret;

    public PCatalogFeedCursor(@Value("${FEED_CURSOR_KEY:local-feed-cursor}") String cursorKey) {
        this.secret = (cursorKey == null ? "local-feed-cursor" : cursorKey)
                .getBytes(StandardCharsets.UTF_8);
    }

    public String encode(String sessionId, int offset, String seed) {
        String json = "{\"s\":\"" + escape(sessionId) + "\",\"o\":" + offset
                + ",\"sd\":\"" + escape(seed) + "\"}";
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    /**
     * Decode a cursor. Returns {@code null} when there is no cursor (or the raw
     * value has no dot - Python treated that as "start a new session"), and
     * raises 400 for a bad signature / malformed payload.
     */
    public Resolved decode(String raw) {
        if (raw == null || raw.indexOf('.') < 0) {
            return null;
        }
        int index = raw.lastIndexOf('.');
        String payload = raw.substring(0, index);
        String signature = raw.substring(index + 1);
        String expected = sign(payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(400, "bad feed cursor signature");
        }
        try {
            String padded = payload + "=".repeat((4 - payload.length() % 4) % 4);
            byte[] decoded = Base64.getUrlDecoder().decode(padded);
            JsonNode node = Json.mapper().readTree(decoded);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("cursor is not an object");
            }
            JsonNode rawSession = node.get("s");
            JsonNode rawOffset = node.get("o");
            JsonNode rawSeed = node.get("sd");
            if (rawSession == null || rawOffset == null || rawSeed == null) {
                throw new IllegalArgumentException("missing cursor field");
            }
            return new Resolved(scalarString(rawSession), scalarInt(rawOffset), scalarString(rawSeed));
        } catch (Exception ex) {
            throw new ApiException(400, "malformed feed cursor");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                hex.append(String.format("%02x", digest[index]));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot sign feed cursor", ex);
        }
    }

    private static String scalarString(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "True" : "False";
        }
        throw new IllegalArgumentException("cursor field is not a scalar");
    }

    private static int scalarInt(JsonNode node) {
        if (node.isNumber()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            return Integer.parseInt(node.asText());
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1 : 0;
        }
        throw new IllegalArgumentException("cursor offset is not a number");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
