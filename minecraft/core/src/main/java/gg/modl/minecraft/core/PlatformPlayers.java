package gg.modl.minecraft.core;

import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.AbstractPlayer;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public interface PlatformPlayers extends PlayerLookup {
    @Nullable CirrusPlayerWrapper getPlayerWrapper(UUID uuid);
    int getMaxPlayers();
    void kickPlayer(AbstractPlayer player, String reason);

    default @Nullable String getPlayerSkinTexture(UUID uuid) { return null; }

    default boolean areMenusAvailable() { return true; }
}
