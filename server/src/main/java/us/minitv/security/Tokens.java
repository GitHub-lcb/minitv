package us.minitv.security;

import java.security.SecureRandom;
import java.util.Base64;

/** Token generation matched to Python's {@code secrets.token_urlsafe} / {@code token_hex}. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    /** 32 random bytes -> 43-char URL-safe base64 (Python token_urlsafe(32)). */
    public static String urlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    public static String hex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return java.util.HexFormat.of().formatHex(buffer);
    }
}
