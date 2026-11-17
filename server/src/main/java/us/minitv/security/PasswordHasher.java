package us.minitv.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * PBKDF2-SHA256 hashing compatible with the Python implementation:
 * {@code pbkdf2_sha256$iterations$salt$hexdigest}, salt is 16 random bytes
 * rendered as 32 hex chars and used as UTF-8 bytes.
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] saltBytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(saltBytes);
        String salt = HEX.formatHex(saltBytes);
        byte[] digest = pbkdf2(password, salt, ITERATIONS);
        return "pbkdf2_sha256$" + ITERATIONS + "$" + salt + "$" + HEX.formatHex(digest);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] digest = pbkdf2(password, parts[2], iterations);
            return MessageDigest.isEqual(HEX.formatHex(digest).getBytes(StandardCharsets.US_ASCII),
                    parts[3].getBytes(StandardCharsets.US_ASCII));
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, String salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                salt.getBytes(StandardCharsets.UTF_8), iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("PBKDF2 unavailable", ex);
        }
    }

    public static String sha256Hex(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
