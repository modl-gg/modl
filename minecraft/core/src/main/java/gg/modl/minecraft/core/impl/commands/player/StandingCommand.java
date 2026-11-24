package gg.modl.minecraft.core.impl.commands.player;

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.command.CommandActor;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.config.ConfigManager;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.impl.menus.StandingMenu;
import gg.modl.minecraft.core.locale.LocaleManager;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public class StandingCommand {
    private static final long COOLDOWN_MS = 60_000;
    private static final String COOLDOWN_KEY = "standing";

    private final HttpClientHolder httpClientHolder;
    private final Platform platform;
    private final LocaleManager localeManager;
    private final ConfigManager configManager;
    private final Cache cache;

    @Command("standing")
    @Description("View your current standing and punishment history")
    @PlayerOnly
    public void standing(CommandActor actor) {
        if (!platform.areMenusAvailable()) {
            actor.reply(localeManager.getMessage("general.gui_unavailable"));
            return;
        }

        UUID uuid = actor.uniqueId();

        if (!checkCooldown(actor, uuid)) return;

        ModlHttpClient httpClient = httpClientHolder.getClient();
        httpClient.getPlayerStanding(uuid).thenAccept(standing -> {
            if (standing == null || standing.getPunishments() == null) {
                replyOnMainThread(actor, "standing.error");
                return;
            }
            platform.runOnMainThread(() -> {
                if (displayStandingMenu(httpClient, uuid, standing)) {
                    CachedProfile profile = cache.getPlayerProfile(uuid);
                    if (profile != null) profile.getCooldowns().set(COOLDOWN_KEY);
                } else {
                    actor.reply(localeManager.getMessage("standing.error"));
                }
            });
        }).exceptionally(throwable -> {
            replyOnMainThread(actor, "standing.error");
            return null;
        });
    }

    private boolean checkCooldown(CommandActor actor, UUID uuid) {
        CachedProfile profile = cache.getPlayerProfile(uuid);
        if (profile == null) return true;
        if (!profile.getCooldowns().isOnCooldown(COOLDOWN_KEY, COOLDOWN_MS)) return true;

        long remaining = profile.getCooldowns().getRemainingMs(COOLDOWN_KEY, COOLDOWN_MS);
        int seconds = (int) Math.ceil(remaining / 1000.0);
        platform.runOnMainThread(() -> actor.reply(localeManager.getMessage("standing.cooldown",
                mapOf("seconds", String.valueOf(seconds)))));
        return false;
    }

    private boolean displayStandingMenu(ModlHttpClient httpClient, UUID uuid, PlayerStandingResponse standing) {
        AbstractPlayer abstractPlayer = platform.getAbstractPlayer(uuid, false);
        CirrusPlayerWrapper player = platform.getPlayerWrapper(uuid);
        if (abstractPlayer == null || player == null) {
            return false;
        }

        StandingMenu menu = createMenu(httpClient, uuid, abstractPlayer.getUsername(), standing);
        displayMenu(menu, player);
        return true;
    }

    protected StandingMenu createMenu(ModlHttpClient httpClient, UUID uuid, String username,
                                      PlayerStandingResponse standing) {
        return new StandingMenu(
                platform, httpClient, uuid, username,
                standing, configManager.getStandingGuiConfig(), localeManager);
    }

    protected void displayMenu(StandingMenu menu, CirrusPlayerWrapper player) {
        menu.display(player);
    }

    private void replyOnMainThread(CommandActor actor, String messagePath) {
        platform.runOnMainThread(() -> actor.reply(localeManager.getMessage(messagePath)));
    }
}
