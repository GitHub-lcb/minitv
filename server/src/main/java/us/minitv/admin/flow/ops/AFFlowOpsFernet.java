package us.minitv.admin.flow.ops;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Byte-compatible port of Python {@code cryptography.fernet.Fernet} as used by
 * social/moderation.py: the stored moderation key must stay readable by both
 * implementations (and vice versa) across the rewrite.
 */
final class AFFlowOpsFernet {

    static final String KEY_SECRET_ENV = "MODERATION_KEY_SECRET";
    static final String KEY_BLOB_PREFIX = "v1:";
    static final int MIN_KEY_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte VERSION = (byte) 0x80;

    private AFFlowOpsFernet() {
    }

    /** The 32-byte Fernet key: sha256 of the configured master secret. */
    private static byte[] fernetKey() {
        String secret = us.minitv.common.Env.get(KEY_SECRET_ENV);
        secret = secret == null ? "" : secret.trim();
        if (secret.isEmpty()) {
            String databaseUrl = us.minitv.common.Env.get("DATABASE_URL");
            secret = "|minitv-moderation|" + (databaseUrl == null ? "local" : databaseUrl);
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static String encrypt(String raw) {
        try {
            byte[] key = fernetKey();
            byte[] signingKey = Arrays.copyOfRange(key, 0, 16);
            byte[] encryptionKey = Arrays.copyOfRange(key, 16, 32);
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            long timestamp = System.currentTimeMillis() / 1000L;
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            ByteBuffer signed = ByteBuffer.allocate(1 + 8 + 16 + ciphertext.length);
            signed.put(VERSION);
            signed.putLong(timestamp);
            signed.put(iv);
            signed.put(ciphertext);
            byte[] hmac = hmacSha256(signingKey, signed.array());
            ByteBuffer token = ByteBuffer.allocate(signed.capacity() + hmac.length);
            token.put(signed.array());
            token.put(hmac);
            return KEY_BLOB_PREFIX + Base64.getUrlEncoder().encodeToString(token.array());
        } catch (Exception ex) {
            throw new IllegalStateException("cannot encrypt moderation key", ex);
        }
    }

    /** Decrypt a stored blob; any problem degrades to "" like the Python code. */
    static String decrypt(String blob) {
        if (blob == null || blob.isEmpty()) {
            return "";
        }
        String body = blob.startsWith(KEY_BLOB_PREFIX) ? blob.substring(KEY_BLOB_PREFIX.length()) : blob;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(body);
            if (raw.length < 1 + 8 + 16 + 32 || raw[0] != VERSION) {
                return "";
            }
            byte[] signingKey = Arrays.copyOfRange(fernetKey(), 0, 16);
            byte[] encryptionKey = Arrays.copyOfRange(fernetKey(), 16, 32);
            int cipherEnd = raw.length - 32;
            byte[] signed = Arrays.copyOfRange(raw, 0, cipherEnd);
            byte[] expected = hmacSha256(signingKey, signed);
            byte[] actual = Arrays.copyOfRange(raw, cipherEnd, raw.length);
            if (!MessageDigest.isEqual(expected, actual)) {
                return "";
            }
            byte[] iv = Arrays.copyOfRange(raw, 9, 25);
            byte[] ciphertext = Arrays.copyOfRange(raw, 25, cipherEnd);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
