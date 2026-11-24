package gg.modl.minecraft.core.util;

import org.jspecify.annotations.Nullable;

public final class StringUtil {
    private StringUtil() {}
    public static String unescapeNewlines(String str) {
        return str.replace("\\\\n", "\n").replace("\\n", "\n");
    }

    public static boolean isBlank(@Nullable String str) {
        return str == null || str.trim().isEmpty();
    }
}
