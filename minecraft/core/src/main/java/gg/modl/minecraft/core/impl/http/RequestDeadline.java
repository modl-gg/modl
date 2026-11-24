package gg.modl.minecraft.core.impl.http;

import java.time.Duration;
import java.util.function.LongSupplier;

final class RequestDeadline {
    private static final long MIN_TIMEOUT_MILLIS = 1L;

    private final LongSupplier nanoClock;
    private final long deadlineNanos;
    private final boolean bounded;

    private RequestDeadline(LongSupplier nanoClock, long deadlineNanos, boolean bounded) {
        this.nanoClock = nanoClock;
        this.deadlineNanos = deadlineNanos;
        this.bounded = bounded;
    }

    static RequestDeadline unbounded() {
        return new RequestDeadline(System::nanoTime, 0L, false);
    }

    static RequestDeadline after(Duration budget, LongSupplier nanoClock) {
        return new RequestDeadline(nanoClock, nanoClock.getAsLong() + budget.toNanos(), true);
    }

    boolean passed() {
        return bounded && remainingNanos() <= 0L;
    }

    boolean allowsWaiting(Duration wait) {
        return !bounded || remainingNanos() > wait.toNanos();
    }

    int capMillis(Duration timeout) {
        long timeoutMillis = timeout.toMillis();
        if (bounded) timeoutMillis = Math.min(timeoutMillis, Duration.ofNanos(remainingNanos()).toMillis());
        return (int) Math.max(MIN_TIMEOUT_MILLIS, Math.min(Integer.MAX_VALUE, timeoutMillis));
    }

    private long remainingNanos() {
        return deadlineNanos - nanoClock.getAsLong();
    }
}
