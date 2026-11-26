package gg.modl.backend.replay.service;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ReplayValidationGate {
    static final int CONCURRENT_VALIDATIONS = 2;

    private final Semaphore permits = new Semaphore(CONCURRENT_VALIDATIONS);

    public Optional<Permit> tryEnter() {
        return permits.tryAcquire() ? Optional.of(new Permit(permits)) : Optional.empty();
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
