package gg.modl.backend.ai.model;

import java.time.Duration;
import java.util.function.LongSupplier;

public final class AiDeadline {
    private final LongSupplier nanoTime;
    private final long expiresAtNanos;

    private AiDeadline(LongSupplier nanoTime, long expiresAtNanos) {
        this.nanoTime = nanoTime;
        this.expiresAtNanos = expiresAtNanos;
    }

    public static AiDeadline after(Duration budget) {
        return after(budget, System::nanoTime);
    }

    public static AiDeadline after(Duration budget, LongSupplier nanoTime) {
        return new AiDeadline(nanoTime, nanoTime.getAsLong() + budget.toNanos());
    }

    public Duration remaining() {
        long left = expiresAtNanos - nanoTime.getAsLong();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    public boolean expired() {
        return remaining().isZero();
    }

    public boolean allows(Duration wait) {
        return wait.compareTo(remaining()) < 0;
    }
}
