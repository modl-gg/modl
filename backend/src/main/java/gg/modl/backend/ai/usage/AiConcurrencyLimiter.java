package gg.modl.backend.ai.usage;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class AiConcurrencyLimiter {
    static final int MAX_WAITING = 32;
    static final Duration MAX_WAIT = Duration.ofMinutes(2);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition released = lock.newCondition();
    private int active;
    private int waiting;
    private int limit = 1;

    public Optional<Permit> acquire(int concurrentCalls) {
        return acquire(concurrentCalls, MAX_WAIT);
    }

    Optional<Permit> acquire(int concurrentCalls, Duration maxWait) {
        lock.lock();
        try {
            resize(concurrentCalls);
            if (active < limit) {
                active++;
                return Optional.of(new Permit());
            }
            if (waiting >= MAX_WAITING) {
                return Optional.empty();
            }
            return awaitSlot(maxWait);
        } finally {
            lock.unlock();
        }
    }

    int active() {
        lock.lock();
        try {
            return active;
        } finally {
            lock.unlock();
        }
    }

    private Optional<Permit> awaitSlot(Duration maxWait) {
        waiting++;
        try {
            long remaining = maxWait.toNanos();
            while (active >= limit) {
                if (remaining <= 0) {
                    return Optional.empty();
                }
                remaining = released.awaitNanos(remaining);
            }
            active++;
            return Optional.of(new Permit());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            waiting--;
        }
    }

    private void resize(int concurrentCalls) {
        int previous = limit;
        limit = Math.max(1, concurrentCalls);
        if (limit > previous) {
            released.signalAll();
        }
    }

    private void release() {
        lock.lock();
        try {
            active--;
            released.signal();
        } finally {
            lock.unlock();
        }
    }

    public final class Permit implements AutoCloseable {
        private boolean closed;

        private Permit() {
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                release();
            }
        }
    }
}
