package gg.modl.backend.auth.password;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.infrastructure.exception.ValidationException;
import java.text.Normalizer;

public final class PasswordPolicy {
    public static final int MIN_CODE_POINTS = 12;
    public static final int MAX_CODE_POINTS = 128;

    private PasswordPolicy() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw new ValidationException("A password is required", "INVALID_ARGUMENT");
        }
        if (raw.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.SURROGATE)) {
            throw new ValidationException("The password is not valid text", "INVALID_ARGUMENT");
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC);
    }

    public static String requireAcceptable(String raw) {
        String normalized = normalize(raw);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_CODE_POINTS) {
            throw AuthFailures.passwordPolicy("Use at least " + MIN_CODE_POINTS + " characters");
        }
        if (length > MAX_CODE_POINTS) {
            throw AuthFailures.passwordPolicy("Use at most " + MAX_CODE_POINTS + " characters");
        }
        return normalized;
    }
}
