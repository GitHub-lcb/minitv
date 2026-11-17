package us.minitv.pcommerce.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Python-compatible digest helpers: hashlib/hmac hex digests, urllib.parse.quote
 * and base64.urlsafe_b64encode without padding.
 */
public final class PCommerceCrypto {

    private PCommerceCrypto() {
    }

    public static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot compute hmac", ex);
        }
    }

    public static String hmacSha256Hex(byte[] key, byte[] message) {
        return HexFormat.of().formatHex(hmacSha256(key, message));
    }

    public static String sha256Hex(byte[] message) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot compute sha256", ex);
        }
    }

    /** hmac.compare_digest equivalent for hex strings. */
    public static boolean constantTimeEquals(String expected, String actual) {
        String left = expected == null ? "" : expected;
        String right = actual == null ? "" : actual;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /** base64.urlsafe_b64encode(value).decode().rstrip("=") */
    public static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** urllib.parse.quote(value, safe="") */
    public static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte raw : bytes) {
            int current = raw & 0xFF;
            boolean unreserved = (current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '.' || current == '_' || current == '~';
            if (unreserved) {
                out.append((char) current);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((current >> 4) & 0x0F, 16)))
                        .append(Character.toUpperCase(Character.forDigit(current & 0x0F, 16)));
            }
        }
        return out.toString();
    }

    public static long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
