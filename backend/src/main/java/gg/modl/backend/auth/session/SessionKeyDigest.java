package gg.modl.backend.auth.session;

import gg.modl.backend.infrastructure.util.DigestUtils;
import org.jspecify.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class SessionKeyDigest {

    private SessionKeyDigest() {
    }

    static String digest(String sessionKey) {
        return DigestUtils.sha256Hex(sessionKey);
    }

    static boolean matches(@Nullable String storedDigest, @Nullable String presentedKey) {
        if (storedDigest == null || storedDigest.isEmpty() || presentedKey == null || presentedKey.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            digest(presentedKey).getBytes(StandardCharsets.UTF_8),
            storedDigest.getBytes(StandardCharsets.UTF_8));
    }
}
