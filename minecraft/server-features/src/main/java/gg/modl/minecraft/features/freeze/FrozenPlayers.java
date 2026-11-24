package gg.modl.minecraft.features.freeze;

import java.util.Set;
import java.util.UUID;

public interface FrozenPlayers {
    boolean isFrozen(UUID uuid);

    Set<UUID> frozenPlayers();
}
