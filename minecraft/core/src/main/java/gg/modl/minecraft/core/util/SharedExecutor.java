package gg.modl.minecraft.core.util;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public final class SharedExecutor<E extends ExecutorService> {
    private final Supplier<E> factory;
    private @Nullable E current;

    public SharedExecutor(Supplier<E> factory) {
        this.factory = factory;
    }

    public synchronized E get() {
        E executor = current;
        if (executor == null) {
            executor = factory.get();
            current = executor;
        }
        return executor;
    }

    public void shutdown() {
        E released = release();
        if (released != null) released.shutdown();
    }

    public void shutdownNow() {
        E released = release();
        if (released != null) released.shutdownNow();
    }

    private synchronized @Nullable E release() {
        E released = current;
        current = null;
        return released;
    }
}
