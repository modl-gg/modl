package gg.modl.backend.database.selection;

import java.time.Duration;
import java.util.function.LongSupplier;

public final class ArrivalWait {
    static final Duration RETRY = Duration.ofSeconds(5);

    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final LongSupplier nanoTime;
    private final Pause pause;

    ArrivalWait(LongSupplier nanoTime, Pause pause) {
        this.nanoTime = nanoTime;
        this.pause = pause;
    }

    public static ArrivalWait atStart() {
        return new ArrivalWait(System::nanoTime, Thread::sleep);
    }

    long now() {
        return nanoTime.getAsLong();
    }

    Duration remaining(Duration budget, long started) {
        return budget.minusNanos(nanoTime.getAsLong() - started);
    }

    void pauseWithin(Duration remaining) throws InterruptedException {
        pause.sleep(remaining.compareTo(RETRY) < 0 ? remaining : RETRY);
    }
}
