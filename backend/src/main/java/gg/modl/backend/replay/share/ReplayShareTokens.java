package gg.modl.backend.replay.share;

import gg.modl.backend.infrastructure.util.DigestUtils;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ReplayShareTokens {
    static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final SecureRandom random = new SecureRandom();

    public String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean wellFormed(String presented) {
        return TOKEN.matcher(presented).matches();
    }

    public static String hash(String token) {
        return DigestUtils.sha256Hex(token);
    }
}
