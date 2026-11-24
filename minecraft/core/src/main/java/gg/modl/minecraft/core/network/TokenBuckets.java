package gg.modl.minecraft.core.network;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class TokenBuckets {
    private static final int SWEEP_THRESHOLD = 1024;
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final int perSecond;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBuckets(int perSecond, LongSupplier nanoClock) {
        this.perSecond = perSecond;
        this.nanoClock = nanoClock;
    }

    public boolean tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        if (buckets.size() > SWEEP_THRESHOLD) sweepFull(now);
        return buckets.computeIfAbsent(key, ignored -> new Bucket(perSecond, now)).tryAcquire(now, perSecond);
    }

    private void sweepFull(long now) {
        Iterator<Bucket> iterator = buckets.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isFull(now, perSecond)) iterator.remove();
        }
    }

    private static final class Bucket {
        private double tokens;
        private long refilledAt;

        private Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.refilledAt = now;
        }

        synchronized boolean tryAcquire(long now, int perSecond) {
            refill(now, perSecond);
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }

        synchronized boolean isFull(long now, int perSecond) {
            refill(now, perSecond);
            return tokens >= perSecond;
        }

        private void refill(long now, int perSecond) {
            long elapsed = Math.max(0, now - refilledAt);
            tokens = Math.min(perSecond, tokens + (double) elapsed * perSecond / NANOS_PER_SECOND);
            refilledAt = now;
        }
    }
}
