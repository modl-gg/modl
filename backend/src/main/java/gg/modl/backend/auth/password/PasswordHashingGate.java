package gg.modl.backend.auth.password;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.database.UnitFrame;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

@Component
public class PasswordHashingGate implements DisposableBean {
    static final Duration ADMISSION_WAIT = Duration.ofSeconds(2);
    static final int MAX_WAITERS = 16;
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);
    private static final int LARGE_HOST_PROCESSORS = 4;
    private static final long LARGE_HOST_MEMORY = 2L * 1024 * 1024 * 1024;

    private final Semaphore permits;
    private final AtomicInteger waiting = new AtomicInteger();
    private final ThreadPoolExecutor workers;

    public PasswordHashingGate() {
        this(permitsFor(Runtime.getRuntime()));
    }

    PasswordHashingGate(int permitCount) {
        this.permits = new Semaphore(permitCount, true);
        this.workers = new ThreadPoolExecutor(permitCount, permitCount, IDLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new HashingThreads());
        this.workers.allowCoreThreadTimeOut(true);
    }

    static int permitsFor(Runtime runtime) {
        return runtime.availableProcessors() >= LARGE_HOST_PROCESSORS && runtime.maxMemory() >= LARGE_HOST_MEMORY ? 2 : 1;
    }

    public <T> T run(Supplier<T> hashing) {
        if (UnitFrame.current().isPresent()) {
            throw new IllegalStateException("A password hash must never run inside a unit of work");
        }
        admit();
        Future<T> result;
        try {
            result = workers.submit(() -> {
                try {
                    return hashing.get();
                } finally {
                    permits.release();
                }
            });
        } catch (RejectedExecutionException rejected) {
            permits.release();
            throw AuthFailures.hashingBusy();
        }
        return await(result);
    }

    private void admit() {
        if (waiting.incrementAndGet() > MAX_WAITERS) {
            waiting.decrementAndGet();
            throw AuthFailures.hashingBusy();
        }
        try {
            if (!permits.tryAcquire(ADMISSION_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw AuthFailures.hashingBusy();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw AuthFailures.hashingBusy();
        } finally {
            waiting.decrementAndGet();
        }
    }

    private static <T> T await(Future<T> result) {
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw AuthFailures.hashingBusy();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Password hashing failed", failed.getCause());
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        workers.shutdownNow();
        workers.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static final class HashingThreads implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable work) {
            Thread thread = new Thread(work, "modl-password-hash-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
