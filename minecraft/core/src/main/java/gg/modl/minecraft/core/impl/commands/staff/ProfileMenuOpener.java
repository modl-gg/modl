package gg.modl.minecraft.core.impl.commands.staff;

import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.PlayerProfileResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.CommandUtil;
import revxrsal.commands.command.CommandActor;

import java.util.UUID;
import java.util.function.BiConsumer;

public final class ProfileMenuOpener {
    private ProfileMenuOpener() {}

    public interface MenuDisplay {
        void open(PlayerProfileResponse response, String senderName, CirrusPlayerWrapper viewer);
    }

    public static void openProfileMenu(CommandActor actor, ModlHttpClient httpClient, Platform platform, Cache cache,
                                LocaleManager localeManager, String playerQuery, MenuDisplay display) {
        openProfileMenu(actor, httpClient, platform, cache, localeManager, playerQuery, display, (query, failure) -> { });
    }

    public static void openProfileMenu(CommandActor actor, ModlHttpClient httpClient, Platform platform, Cache cache,
                                LocaleManager localeManager, String playerQuery, MenuDisplay display,
                                BiConsumer<String, Throwable> onFailure) {
        UUID senderUuid = actor.uniqueId();

        StaffProfileLookup.lookupPlayerProfile(httpClient, platform, playerQuery).thenAccept(profileResponse -> {
            if (profileResponse.getStatus() == 200) {
                String senderName = CommandUtil.resolveSenderName(senderUuid, cache, platform);
                CirrusPlayerWrapper viewer = platform.getPlayerWrapper(senderUuid);
                if (viewer != null) display.open(profileResponse, senderName, viewer);
            } else actor.reply(localeManager.getMessage("general.player_not_found"));
        }).exceptionally(throwable -> {
            onFailure.accept(playerQuery, throwable);
            CommandUtil.handleException(actor, throwable, localeManager);
            return null;
        });
    }
}
