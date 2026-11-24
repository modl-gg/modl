package gg.modl.minecraft.features.moderation;

import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

public interface LocalModerationOps {
    Set<UUID> moderatedPlayers();

    boolean isFrozen(UUID uuid);

    void freeze(UUID target, UUID staff);

    void unfreeze(UUID target);

    boolean isInStaffMode(UUID uuid);

    boolean hasUnreportedRefusal(UUID uuid);

    void enterStaffMode(UUID uuid);

    void exitStaffMode(UUID uuid);

    boolean isVanished(UUID uuid);

    void applyVanish(UUID uuid, boolean vanished);

    @Nullable UUID targetOf(UUID staff);

    void setTarget(UUID staff, UUID target);

    void clearTarget(UUID staff);
}
