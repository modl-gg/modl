package gg.modl.backend.network;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class RateBuckets {
    private final int perSecond;
    private final Clock clock;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateBuckets(int perSecond, Clock clock) {
        this.perSecond = perSecond;
        this.clock = clock;
    }

    boolean tryAcquire(String key) {
        long now = clock.millis();
        return buckets.computeIfAbsent(key, ignored -> new Bucket(perSecond, now)).tryAcquire(perSecond, now);
    }

    void forget(String key) {
        buckets.remove(key);
    }

    private static final class Bucket {
        private double tokens;
        private long refilledAt;

        private Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.refilledAt = now;
        }

        private synchronized boolean tryAcquire(int perSecond, long now) {
            long elapsed = Math.max(0, now - refilledAt);
            tokens = Math.min(perSecond, tokens + elapsed * perSecond / 1000.0);
            refilledAt = now;
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }
    }
}
