package io.artio.api.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP using a 30 second step and six decimal digits. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private Totp() { }

    public static String newSecret() {
        byte[] bytes = new byte[20]; RANDOM.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean verify(String secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) return false;
        byte[] key = Base64.getDecoder().decode(secret);
        long step = now.getEpochSecond() / 30;
        for (long offset = -1; offset <= 1; offset++) if (java.security.MessageDigest.isEqual(code.getBytes(), code(key, step + offset).getBytes())) return true;
        return false;
    }

    public static String base32(String base64Secret) {
        byte[] bytes = Base64.getDecoder().decode(base64Secret);
        StringBuilder out = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff); bits += 8;
            while (bits >= 5) { out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    private static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) { throw new IllegalStateException("TOTP calculation failed", e); }
    }
}
