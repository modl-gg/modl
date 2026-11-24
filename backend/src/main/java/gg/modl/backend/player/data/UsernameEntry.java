package gg.modl.backend.player.data;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Locale;

public record UsernameEntry(String username, @Nullable Instant date) {

    public static String lookupKey(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
