package gg.modl.minecraft.core.util;

import revxrsal.commands.command.CommandActor;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.locale.LocaleManager;

import java.util.UUID;

public final class CommandUtil {
    public static final UUID CONSOLE_UUID = new UUID(0, 0);

    private CommandUtil() {
    }

    public static boolean isConsole(CommandActor actor) {
        return isConsole(actor.uniqueId());
    }

    public static boolean isConsole(UUID uuid) {
        return uuid == null || CONSOLE_UUID.equals(uuid);
    }

    public static boolean rendersAsText(CommandActor actor, Platform platform, boolean printMode) {
        return printMode || isConsole(actor) || !platform.areMenusAvailable();
    }

    public static String resolveActorName(CommandActor actor, Cache cache, Platform platform) {
        if (isConsole(actor))
            return "Console";
        String panelName = cache.getStaffDisplayName(actor.uniqueId());
        if (panelName != null)
            return panelName;
        AbstractPlayer player = platform.getAbstractPlayer(actor.uniqueId(), false);
        return player != null ? player.getUsername() : actor.name();
    }

    public static String resolveIssuingServer(CommandActor actor, Platform platform) {
        return isConsole(actor) ? platform.getServerName() : platform.getPlayerServer(actor.uniqueId());
    }

    public static String resolveSenderName(UUID uuid, Cache cache, Platform platform) {
        String name = cache.getStaffDisplayName(uuid);
        if (name != null)
            return name;
        AbstractPlayer player = platform.getPlayer(uuid);
        if (player != null)
            return player.getUsername();
        return "Staff";
    }

    public static Void handleApiError(CommandActor actor, Throwable throwable, LocaleManager localeManager) {
        return handleException(actor, throwable, localeManager, "general.punishment_error");
    }

    public static Void handleException(CommandActor actor, Throwable throwable, LocaleManager localeManager) {
        return handleException(actor, throwable, localeManager, "player_lookup.error");
    }

    public static Void handleException(CommandActor actor, Throwable throwable, LocaleManager localeManager,
            String errorKey) {
        actor.reply(ApiFailures.describe(localeManager, errorKey, throwable));
        return null;
    }
}

