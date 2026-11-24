package gg.modl.backend.auth.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Totp {
    public static final int DIGITS = 6;
    public static final long PERIOD_SECONDS = 30;
    public static final int SECRET_BYTES = 20;
    private static final int WINDOW_STEPS = 1;
    private static final String ALGORITHM = "HmacSHA1";
    private static final int MODULUS = 1_000_000;
    private static final int OFFSET_MASK = 0x0F;
    private static final int POSITIVE_MASK = 0x7FFFFFFF;

    private Totp() {
    }

    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), PERIOD_SECONDS);
    }

    public static String codeAt(byte[] secret, long step) {
        byte[] hash = hmac(secret, ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        int offset = hash[hash.length - 1] & OFFSET_MASK;
        int binary = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & POSITIVE_MASK;
        return String.format("%0" + DIGITS + "d", binary % MODULUS);
    }

    public static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
        byte[] presented = code.getBytes(StandardCharsets.US_ASCII);
        long current = stepAt(now);
        long matched = Long.MIN_VALUE;
        for (long step = current - WINDOW_STEPS; step <= current + WINDOW_STEPS; step++) {
            boolean equal = MessageDigest.isEqual(codeAt(secret, step).getBytes(StandardCharsets.US_ASCII), presented);
            if (equal) {
                matched = step;
            }
        }
        return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    private static byte[] hmac(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }
}
