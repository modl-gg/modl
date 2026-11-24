package gg.modl.backend.infrastructure.onetimecode;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

public final class HumanCode {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final int GROUP_LENGTH = 4;
    private static final char GROUP_SEPARATOR = '-';
    private static final SecureRandom RANDOM = new SecureRandom();

    private HumanCode() {
    }

    public static String generate(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    public static String normalize(String code) {
        return SEPARATORS.matcher(code).replaceAll("").toUpperCase(Locale.ROOT);
    }

    public static String format(String code) {
        String normalized = normalize(code);
        StringBuilder grouped = new StringBuilder(normalized.length() + normalized.length() / GROUP_LENGTH);
        for (int index = 0; index < normalized.length(); index++) {
            if (index > 0 && index % GROUP_LENGTH == 0) {
                grouped.append(GROUP_SEPARATOR);
            }
            grouped.append(normalized.charAt(index));
        }
        return grouped.toString();
    }
}
