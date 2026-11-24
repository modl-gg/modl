package gg.modl.minecraft.features;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OnlinePlayers {
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();

    void joined(UUID uuid) {
        online.add(uuid);
    }

    void left(UUID uuid) {
        online.remove(uuid);
    }

    public int count() {
        return online.size();
    }
}
