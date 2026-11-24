package gg.modl.backend.email;

import jakarta.mail.internet.InternetAddress;
import org.jspecify.annotations.Nullable;
import java.util.Locale;

public final class EmailAddressUtil {
    private static final int MAX_EMAIL_LENGTH = 254;

    private EmailAddressUtil() {
    }

    public static @Nullable String normalizeIfValid(@Nullable String email) {
        String normalized = normalize(email);
        return normalized != null && isValid(normalized) ? normalized : null;
    }

    public static @Nullable String normalize(@Nullable String email) {
        if (email == null) {
            return null;
        }

        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public static String requireNormalized(String email) {
        String normalized = normalize(email);
        if (normalized == null) {
            throw new IllegalArgumentException("An email address must not be blank");
        }
        return normalized;
    }

    public static boolean isValid(@Nullable String email) {
        String normalized = normalize(email);
        if (normalized == null || normalized.length() > MAX_EMAIL_LENGTH) {
            return false;
        }

        try {
            InternetAddress address = new InternetAddress(normalized);
            address.validate();
            return normalized.equals(address.getAddress());
        } catch (Exception ex) {
            return false;
        }
    }

    public static String mask(@Nullable String email) {
        String normalized = normalize(email);
        if (normalized == null) {
            return "<empty>";
        }

        int atIndex = normalized.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }

        return normalized.charAt(0) + "***" + normalized.substring(atIndex);
    }
}
