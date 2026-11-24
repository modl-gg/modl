package gg.modl.backend.settings.service;

import org.jspecify.annotations.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class SettingsCache<T> {
    public static final Duration LIFETIME = Duration.ofSeconds(45);

    private final Clock clock;
    private final AtomicReference<Slot<T>> slot = new AtomicReference<>(Slot.empty());

    public SettingsCache(Clock clock) {
        this.clock = clock;
    }

    public T get(Supplier<T> loader) {
        Slot<T> observed = slot.get();
        Instant now = clock.instant();
        T cached = observed.valueAt(now);
        if (cached != null) {
            return cached;
        }
        T loaded = loader.get();
        slot.compareAndSet(observed, new Slot<>(loaded, now.plus(LIFETIME)));
        return loaded;
    }

    public void invalidate() {
        slot.set(Slot.empty());
    }

    private record Slot<T>(@Nullable T value, Instant expiresAt) {
        static <T> Slot<T> empty() {
            return new Slot<>(null, Instant.MIN);
        }

        @Nullable T valueAt(Instant now) {
            return value != null && now.isBefore(expiresAt) ? value : null;
        }
    }
}
