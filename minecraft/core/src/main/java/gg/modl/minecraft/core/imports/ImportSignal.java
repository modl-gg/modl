package gg.modl.minecraft.core.imports;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ImportSignal {
    private static final long SLICE_MILLIS = 250;

    private final AtomicBoolean set = new AtomicBoolean();
    private final @Nullable ImportSignal parent;
    private final Runnable onSet;

    ImportSignal() {
        this(null, () -> { });
    }

    ImportSignal(@Nullable ImportSignal parent, Runnable onSet) {
        this.parent = parent;
        this.onSet = onSet;
    }

    void set() {
        if (set.compareAndSet(false, true)) onSet.run();
    }

    boolean isSet() {
        ImportSignal enclosing = parent;
        return set.get() || (enclosing != null && enclosing.isSet());
    }

    void await(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        long remaining = millis;
        while (!isSet() && remaining > 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(Math.min(SLICE_MILLIS, remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        }
    }
}
