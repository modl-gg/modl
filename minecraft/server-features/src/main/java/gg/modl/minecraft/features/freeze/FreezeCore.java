package gg.modl.minecraft.features.freeze;

import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class FreezeCore implements FrozenPlayers {
    private final FeaturesLocaleManager localeManager;
    private final FreezeOps ops;
    private final Map<UUID, UUID> frozenPlayerToStaff = new ConcurrentHashMap<>();

    public void freeze(UUID target, UUID staff) {
        if (frozenPlayerToStaff.putIfAbsent(target, staff) != null) return;
        ops.onFrozen(target);
        ops.sendMessage(target, localeManager.getMessage("freeze.frozen"));
    }

    public void unfreeze(UUID target) {
        if (frozenPlayerToStaff.remove(target) == null) return;
        ops.onUnfrozen(target);
        ops.sendMessage(target, localeManager.getMessage("freeze.unfrozen"));
    }

    public void releaseLocal(UUID target) {
        if (frozenPlayerToStaff.remove(target) == null) return;
        ops.onUnfrozen(target);
    }

    @Override
    public boolean isFrozen(UUID uuid) {
        return frozenPlayerToStaff.containsKey(uuid);
    }

    @Override
    public Set<UUID> frozenPlayers() {
        return Collections.unmodifiableSet(new HashSet<>(frozenPlayerToStaff.keySet()));
    }
}
