package gg.modl.minecraft.features.staffmode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class InMemoryStartingLocationStore implements StartingLocationStore {
    Map<UUID, PendingReturn> saved = Collections.emptyMap();
    int saves;
    private boolean closed;

    @Override
    public CompletableFuture<Map<UUID, PendingReturn>> load() {
        closed = false;
        return CompletableFuture.completedFuture(new LinkedHashMap<>(saved));
    }

    @Override
    public void save(Map<UUID, PendingReturn> pendingReturns) {
        if (closed) return;
        saved = pendingReturns;
        saves++;
    }

    @Override
    public void close() {
        closed = true;
    }
}
