package gg.modl.minecraft.core;

import gg.modl.minecraft.api.AbstractPlayer;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;

public interface PlayerLookup {
    boolean isOnline(UUID uuid);
    @Nullable AbstractPlayer getAbstractPlayer(UUID uuid, boolean queryMojang);
    @Nullable AbstractPlayer getAbstractPlayer(String username, boolean queryMojang);
    Collection<AbstractPlayer> getOnlinePlayers();
    @Nullable AbstractPlayer getPlayer(UUID uuid);
}
