package gg.modl.minecraft.core.imports;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

final class ImportConfirmation {
    static final Duration VALID_FOR = Duration.ofSeconds(60);

    private final LongSupplier nanoClock;
    private final AtomicReference<@Nullable Armed> armed = new AtomicReference<>();

    ImportConfirmation(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    void arm(String sourceId) {
        armed.set(new Armed(sourceId, nanoClock.getAsLong()));
    }

    void disarm() {
        armed.set(null);
    }

    boolean isArmed(String sourceId) {
        return isFresh(armed.get(), sourceId);
    }

    boolean consume(String sourceId) {
        return isFresh(armed.getAndSet(null), sourceId);
    }

    private boolean isFresh(@Nullable Armed since, String sourceId) {
        return since != null && since.sourceId.equals(sourceId)
                && nanoClock.getAsLong() - since.at <= VALID_FOR.toNanos();
    }

    @Value
    private static class Armed {
        String sourceId;
        long at;
    }
}
