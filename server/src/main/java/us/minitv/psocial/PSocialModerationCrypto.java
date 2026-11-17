package us.minitv.psocial;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Minimal Fernet decryption so the moderation credential written by the Python
 * admin page ({@code v1:} blob, AES-128-CBC + HMAC-SHA256) can still be read
 * after the port.
 *
 * <p>Python derives the Fernet key as
 * {@code Fernet(base64.urlsafe_b64encode(sha256(secret).digest()))}; decoding
 * that base64 again yields the raw digest, whose first 16 bytes sign and last
 * 16 bytes encrypt. Any failure degrades to {@code ""} exactly like
 * {@code decrypt_key()} does, so the caller falls back to the environment key.</p>
 */
final class PSocialModerationCrypto {

    private static final String BLOB_PREFIX = "v1:";

    private PSocialModerationCrypto() {
    }

    static String decrypt(String blob, String secret) {
        if (blob == null || blob.isEmpty()) {
            return "";
        }
        try {
            String body = blob.startsWith(BLOB_PREFIX) ? blob.substring(BLOB_PREFIX.length()) : blob;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            byte[] raw = Base64.getUrlDecoder().decode(body);
            // version(1) | timestamp(8) | iv(16) | ciphertext(>=16) | hmac(32)
            if (raw.length < 1 + 8 + 16 + 16 + 32 || (raw[0] & 0xFF) != 0x80) {
                return "";
            }
            int cipherEnd = raw.length - 32;
            byte[] signingKey = Arrays.copyOfRange(digest, 0, 16);
            byte[] encryptionKey = Arrays.copyOfRange(digest, 16, 32);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] expected = mac.doFinal(Arrays.copyOfRange(raw, 0, cipherEnd));
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
}
