package gg.modl.minecraft.features.moderation;

import gg.modl.minecraft.features.freeze.FreezeCore;
import gg.modl.minecraft.features.staffmode.StaffModeCore;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
public final class FeatureModerationOps implements LocalModerationOps {
    private final FreezeCore freezeCore;
    private final StaffModeCore staffModeCore;

    @Override
    public Set<UUID> moderatedPlayers() {
        Set<UUID> players = new HashSet<>(freezeCore.frozenPlayers());
        players.addAll(staffModeCore.sessionPlayers());
        return players;
    }

    @Override
    public boolean isFrozen(UUID uuid) {
        return freezeCore.isFrozen(uuid);
    }

    @Override
    public void freeze(UUID target, UUID staff) {
        freezeCore.freeze(target, staff);
    }

    @Override
    public void unfreeze(UUID target) {
        freezeCore.unfreeze(target);
    }

    @Override
    public boolean isInStaffMode(UUID uuid) {
        return staffModeCore.isInStaffMode(uuid);
    }

    @Override
    public boolean hasUnreportedRefusal(UUID uuid) {
        return staffModeCore.hasUnreportedRefusal(uuid);
    }

    @Override
    public void enterStaffMode(UUID uuid) {
        staffModeCore.enterStaffMode(uuid);
    }

    @Override
    public void exitStaffMode(UUID uuid) {
        staffModeCore.exitStaffMode(uuid);
    }

    @Override
    public boolean isVanished(UUID uuid) {
        return staffModeCore.isVanished(uuid);
    }

    @Override
    public void applyVanish(UUID uuid, boolean vanished) {
        staffModeCore.applyVanish(uuid, vanished);
    }

    @Override
    public @Nullable UUID targetOf(UUID staff) {
        return staffModeCore.targetOf(staff);
    }

    @Override
    public void setTarget(UUID staff, UUID target) {
        staffModeCore.setTarget(staff, target);
    }

    @Override
    public void clearTarget(UUID staff) {
        staffModeCore.clearTarget(staff);
    }
}
