package gg.modl.minecraft.core.impl.http;

import gg.modl.minecraft.core.util.BoundedLookupExecutor;
import gg.modl.minecraft.core.util.CircuitBreaker;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.LongSupplier;

final class RequestLane {
    private final CircuitBreaker breaker = new CircuitBreaker();
    private final ThreadPoolExecutor executor;
    private final BusyRetry busyRetry;
    private final @Nullable Duration budget;
    private final LongSupplier nanoClock;

    private RequestLane(String threadNamePrefix, int threads, int queueCapacity, BusyRetry busyRetry,
                        @Nullable Duration budget, LongSupplier nanoClock) {
        this.busyRetry = busyRetry;
        this.budget = budget;
        this.nanoClock = nanoClock;
        this.executor = BoundedLookupExecutor.newDaemonPool(threadNamePrefix, threads, threads, queueCapacity, true);
    }

    static RequestLane unbounded(String threadNamePrefix, int threads, int queueCapacity, BusyRetry busyRetry) {
        return new RequestLane(threadNamePrefix, threads, queueCapacity, busyRetry, null, System::nanoTime);
    }

    static RequestLane withBudget(String threadNamePrefix, int threads, int queueCapacity, BusyRetry busyRetry,
                                  Duration budget, LongSupplier nanoClock) {
        return new RequestLane(threadNamePrefix, threads, queueCapacity, busyRetry, budget, nanoClock);
    }

    RequestDeadline startDeadline() {
        return budget == null ? RequestDeadline.unbounded() : RequestDeadline.after(budget, nanoClock);
    }

    CircuitBreaker breaker() {
        return breaker;
    }

    Executor executor() {
        return executor;
    }

    BusyRetry busyRetry() {
        return busyRetry;
    }

    void shutdown(long timeoutSeconds) {
        BoundedLookupExecutor.shutdown(executor, timeoutSeconds);
    }
}
