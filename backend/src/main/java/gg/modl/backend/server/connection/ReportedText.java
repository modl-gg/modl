package gg.modl.backend.server.connection;

import org.jspecify.annotations.Nullable;

final class ReportedText {
    static final int MAX_LENGTH = 128;

    private ReportedText() {
    }

    static String clean(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
