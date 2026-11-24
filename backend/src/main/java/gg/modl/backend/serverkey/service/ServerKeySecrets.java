package gg.modl.backend.serverkey.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ServerKeySecrets {
    public static final String PREFIX = "msk_";
    static final int SECRET_BYTES = 32;
    static final int HINT_LENGTH = 8;
    private static final int SECRET_LENGTH = PREFIX.length() + (SECRET_BYTES * 8 + 5) / 6;

    private final SecureRandom random = new SecureRandom();

    public IssuedSecret issue() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedSecret(secret, hash(secret), hint(secret));
    }

    public static boolean looksLikeServerKey(String presented) {
        return presented.length() == SECRET_LENGTH && presented.startsWith(PREFIX);
    }

    public static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }

    static String hint(String secret) {
        return secret.substring(PREFIX.length(), PREFIX.length() + HINT_LENGTH);
    }

    public record IssuedSecret(String secret, String hash, String hint) {
        @Override
        public String toString() {
            return "IssuedSecret[hint=" + hint + "]";
        }
    }
}
