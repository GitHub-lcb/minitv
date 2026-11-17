package us.minitv.pcatalog.support;

import java.nio.charset.StandardCharsets;

/**
 * BLAKE2b (RFC 7693) truncated to 64 bits.
 *
 * <p>Java 21 has no BLAKE2 in {@code MessageDigest}, but the For-You ranking
 * derives its deterministic explore jitter from
 * {@code hashlib.blake2b(f"{seed}:{drama_id}", digest_size=8)}, so the port has
 * to reproduce those digests bit for bit instead of substituting another hash.
 * The output bytes are the first output word {@code h[0]} serialised little
 * endian; Python's {@code int.from_bytes(digest, "big")} therefore reads that
 * byte sequence in the opposite direction, i.e. it is {@code h[0]} with its
 * bytes reversed. {@link #digest64(byte[])} returns exactly that integer.
 */
public final class PCatalogBlake2b {

    private static final long[] IV = {
            0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL,
            0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L, 0x9b05688c2b3e6c1fL,
            0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    };

    /**
     * Parameter block word 0 (little endian) for digest_length=8, key_length=0,
     * fanout=1, depth=1; every other parameter word is zero.
     */
    private static final long PARAM_WORD0 = 0x01010008L;

    private static final int[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
    };

    private PCatalogBlake2b() {
    }

    /**
     * {@code hashlib.blake2b(text, digest_size=8).digest()} read as an unsigned
     * big-endian 64-bit integer, which is how the Python ranking derives its
     * jitter.
     */
    public static long digest64(String text) {
        return digest64(text.getBytes(StandardCharsets.UTF_8));
    }

    public static long digest64(byte[] message) {
        long[] h = IV.clone();
        h[0] ^= PARAM_WORD0;
        int blocks = message.length == 0 ? 1 : (message.length + 127) / 128;
        long counter = 0L;
        byte[] block = new byte[128];
        for (int index = 0; index < blocks; index++) {
            int start = index * 128;
            int chunk = Math.min(128, message.length - start);
            java.util.Arrays.fill(block, (byte) 0);
            if (chunk > 0) {
                System.arraycopy(message, start, block, 0, chunk);
            }
            counter += chunk;
            compress(h, block, counter, index == blocks - 1);
        }
        // digest_size=8 keeps the first 8 output bytes, which are h[0] written
        // little endian; int.from_bytes(..., "big") reverses them.
        return Long.reverseBytes(h[0]);
    }

    /**
     * {@code int.from_bytes(hashlib.blake2b(text, digest_size=8).digest(), "big") / 2**64}
     * with Python's single correctly-rounded int-to-float conversion.
     */
    public static double unit(String text) {
        long word = digest64(text);
        double value = Double.parseDouble(Long.toUnsignedString(word));
        return value / 18446744073709551616.0;
    }

    private static void compress(long[] h, byte[] block, long counter, boolean last) {
        long[] m = new long[16];
        for (int i = 0; i < 16; i++) {
            m[i] = littleEndianLong(block, i * 8);
        }
        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= counter;
        if (last) {
            v[14] ^= -1L;
        }
        for (int round = 0; round < 12; round++) {
            int[] s = SIGMA[round % 10];
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private static void g(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    private static long littleEndianLong(byte[] source, int offset) {
        long value = 0L;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (source[offset + i] & 0xFFL);
        }
        return value;
    }
}
