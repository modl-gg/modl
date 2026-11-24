package gg.modl.minecraft.core.staff;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.util.CommandUtil;
import revxrsal.commands.command.CommandActor;

import java.util.UUID;

public final class StaffCommandUtil {
    private StaffCommandUtil() {}

    public static StaffDisplay resolveActorDisplay(CommandActor actor, Platform platform, Cache cache,
                                                   String consoleFallback, String staffFallback,
                                                   boolean useUsername) {
        return resolveDisplay(actor.uniqueId(), platform, cache, consoleFallback, staffFallback, useUsername);
    }

    public static StaffDisplay resolveDisplay(UUID uuidOrConsole, Platform platform, Cache cache,
                                              String consoleFallback, String staffFallback, boolean useUsername) {
        if (CommandUtil.isConsole(uuidOrConsole)) return new StaffDisplay(consoleFallback, consoleFallback);
        return resolvePlayerDisplay(uuidOrConsole, platform, cache, staffFallback, useUsername);
    }

    public static StaffDisplay resolvePlayerDisplay(UUID uuid, Platform platform, Cache cache,
                                                    String staffFallback) {
        return resolvePlayerDisplay(uuid, platform, cache, staffFallback, false);
    }

    public static StaffDisplay resolvePlayerDisplay(UUID uuid, Platform platform, Cache cache,
                                                    String staffFallback, boolean useUsername) {
        AbstractPlayer player = platform.getPlayer(uuid);
        String inGameName = player != null ? playerName(player, useUsername) : staffFallback;
        String panelName = cache != null ? cache.getStaffDisplayName(uuid) : null;
        return new StaffDisplay(inGameName, panelName != null ? panelName : inGameName);
    }

    private static String playerName(AbstractPlayer player, boolean useUsername) {
        return useUsername ? player.getUsername() : player.getName();
    }

    public static final class StaffDisplay {
        private final String inGameName;
        private final String panelName;

        private StaffDisplay(String inGameName, String panelName) {
            this.inGameName = inGameName;
            this.panelName = panelName;
        }

        public String getInGameName() {
            return inGameName;
        }

        public String getPanelName() {
            return panelName;
        }
    }
}
