package gg.modl.backend.database;

import gg.modl.backend.database.DatabaseUnavailableException.Reason;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

public final class WritePause {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private final AtomicInteger writing = new AtomicInteger();
    private static final ThreadLocal<PausedWrites> ADMITTED = new ThreadLocal<>();
    private volatile @Nullable PausedWrites paused;

    public void enter() {
        writing.incrementAndGet();
        PausedWrites current = paused;
        if (current != null && ADMITTED.get() != current) {
            writing.decrementAndGet();
            throw new DatabaseUnavailableException(Reason.MOVING);
        }
    }

    public void exit() {
        writing.decrementAndGet();
    }

    public synchronized Optional<PausedWrites> pauseAndDrain(Duration drainBudget) {
        if (paused != null) {
            throw new IllegalStateException("Writes are already paused");
        }
        PausedWrites pause = new PausedWrites();
        paused = pause;
        long deadline = System.nanoTime() + drainBudget.toNanos();
        while (writing.get() > 0) {
            if (System.nanoTime() >= deadline) {
                release(pause);
                return Optional.empty();
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                release(pause);
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.of(pause);
    }

    private synchronized void release(PausedWrites pause) {
        if (paused == pause) {
            paused = null;
        }
    }

    public final class PausedWrites {
        private PausedWrites() {
        }

        public void admit(Runnable writes) {
            PausedWrites outer = ADMITTED.get();
            ADMITTED.set(this);
            try {
                writes.run();
            } finally {
                if (outer == null) {
                    ADMITTED.remove();
                } else {
                    ADMITTED.set(outer);
                }
            }
        }

        public void resume() {
            release(this);
        }
    }
}
