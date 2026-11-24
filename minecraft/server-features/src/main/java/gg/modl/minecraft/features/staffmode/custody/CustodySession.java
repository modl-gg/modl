package gg.modl.minecraft.features.staffmode.custody;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

final class CustodySession {
    enum Phase {
        LOADING,
        FREE,
        SAVING,
        HELD,
        STRANDED,
        BLOCKED
    }

    final boolean loadedFromDisk;
    volatile Phase phase = Phase.LOADING;
    volatile Optional<UUID> mark = Optional.empty();
    @Nullable UUID id;
    @Nullable CustodyRecord record;
    @Nullable Belongings belongings;
    @Nullable Object write;
    int attempts;
    boolean refusalLogged;
    boolean returnFailureLogged;

    CustodySession(boolean loadedFromDisk) {
        this.loadedFromDisk = loadedFromDisk;
    }

    boolean holdsBelongings() {
        Phase current = phase;
        return current == Phase.HELD || current == Phase.STRANDED || (current == Phase.LOADING && mark.isPresent());
    }
}
