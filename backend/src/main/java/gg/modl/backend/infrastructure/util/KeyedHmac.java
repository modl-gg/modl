package gg.modl.backend.infrastructure.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class KeyedHmac {
    private static final String ALGORITHM = "HmacSHA256";

    private final Supplier<String> key;

    public KeyedHmac(Supplier<String> key) {
        this.key = key;
    }

    public byte[] sign(String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(requireKey().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    public String signHex(String message) {
        return HexFormat.of().formatHex(sign(message));
    }

    public boolean verify(String message, byte[] presented) {
        return presented != null && MessageDigest.isEqual(sign(message), presented);
    }

    private String requireKey() {
        String value = key.get();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The HMAC key is not initialized");
        }
        return value;
    }
}
