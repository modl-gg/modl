package gg.modl.backend.staff.service;

import org.jspecify.annotations.Nullable;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ReservedStaffNames {
    static final String RESERVED_MESSAGE = "This username is reserved. Please choose another one.";
    private static final Set<String> RESERVED =
        Set.of("console", "system", "server", "modl", "ai", "aimoderator", "automod", "unknown", "unknownstaff");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");

    private ReservedStaffNames() {
    }

    static boolean isReserved(@Nullable String username) {
        if (username == null) {
            return false;
        }
        String compatible = Normalizer.normalize(username, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return RESERVED.contains(NOT_LETTER_OR_DIGIT.matcher(compatible).replaceAll(""));
    }
}
