package gg.modl.minecraft.features.staffmode.custody;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface CustodyStore {
    CompletableFuture<Optional<CustodyRecord>> load(UUID player);

    CompletableFuture<@Nullable Void> save(UUID player, CustodyRecord record);

    CompletableFuture<@Nullable Void> remove(UUID player);

    CompletableFuture<String> setAside(UUID player);

    String describe(UUID player);

    void close();
}
