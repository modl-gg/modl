package gg.modl.backend.database;

import org.jspecify.annotations.Nullable;

public final class StorableText {
    private static final char NUL = '\0';
    private static final String NUL_TEXT = String.valueOf(NUL);

    private StorableText() {
    }

    public static boolean isStorable(@Nullable String value) {
        return value == null || value.indexOf(NUL) < 0;
    }

    @Nullable
    public static String require(@Nullable String value) {
        if (!isStorable(value)) {
            throw new UnstorableTextException();
        }
        return value;
    }

    public static String strip(String value) {
        return value.replace(NUL_TEXT, "");
    }
}
