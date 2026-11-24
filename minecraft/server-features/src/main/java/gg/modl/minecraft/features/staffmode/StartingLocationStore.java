package gg.modl.minecraft.features.staffmode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StartingLocationStore {
    CompletableFuture<Map<UUID, PendingReturn>> load();

    void save(Map<UUID, PendingReturn> pendingReturns);

    void close();
}
