package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.core.locale.LocaleManager;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class Presence {
    private static final String KEY = "menus.common.presence";

    private Presence() {
    }

    public static String describe(LocaleManager locale, boolean online, long sessionMillis, @Nullable Instant lastSeen,
                                  @Nullable String server) {
        String time = online
                ? MenuItems.formatDuration(sessionMillis)
                : lastSeen != null ? MenuItems.formatDate(lastSeen) : locale.getMessage("general.never");
        if (server == null || server.trim().isEmpty()) return locale.getMessage(KEY, mapOf("time", time));
        return locale.getMessage(KEY + "_on_server", mapOf("time", time, "server", server));
    }

    public static @Nullable Instant latestUsernameDate(Account account) {
        return account.getUsernames().stream()
                .map(Account.Username::getDate)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }
}
