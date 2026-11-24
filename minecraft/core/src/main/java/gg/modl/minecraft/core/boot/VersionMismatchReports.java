package gg.modl.minecraft.core.boot;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class VersionMismatchReports {
    private final CopyOnWriteArrayList<Consumer<VersionMismatchFailure>> listeners = new CopyOnWriteArrayList<>();

    public Runnable listen(Consumer<VersionMismatchFailure> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void report(VersionMismatchFailure mismatch) {
        for (Consumer<VersionMismatchFailure> listener : listeners) {
            listener.accept(mismatch);
        }
    }
}
