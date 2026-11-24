package gg.modl.backend.auth.link;

import gg.modl.backend.infrastructure.validation.RegExpConstants;
import org.jspecify.annotations.Nullable;
import java.util.regex.Pattern;

final class MinecraftAccountFormat {
    private static final Pattern UUID = Pattern.compile(RegExpConstants.UUID);
    private static final Pattern USERNAME = Pattern.compile(RegExpConstants.MINECRAFT_USERNAME);

    private MinecraftAccountFormat() {
    }

    static boolean isUuid(@Nullable String value) {
        return value != null && UUID.matcher(value).matches();
    }

    static boolean isUsername(@Nullable String value) {
        return value != null && USERNAME.matcher(value).matches();
    }
}
