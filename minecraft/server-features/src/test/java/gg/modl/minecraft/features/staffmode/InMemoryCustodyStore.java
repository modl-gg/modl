package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.features.staffmode.custody.CustodyRecord;
import gg.modl.minecraft.features.staffmode.custody.CustodyStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class InMemoryCustodyStore implements CustodyStore {
    final Map<UUID, CustodyRecord> records = new HashMap<>();

    @Override
    public CompletableFuture<Optional<CustodyRecord>> load(UUID player) {
        return CompletableFuture.completedFuture(Optional.ofNullable(records.get(player)));
    }

    @Override
    public CompletableFuture<Void> save(UUID player, CustodyRecord record) {
        records.put(player, record);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> remove(UUID player) {
        records.remove(player);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<String> setAside(UUID player) {
        records.remove(player);
        return CompletableFuture.completedFuture("kept");
    }

    @Override
    public String describe(UUID player) {
        return player + ".custody";
    }

    @Override
    public void close() {
    }
}
