package gg.modl.backend.realtime.rate;

import gg.modl.backend.realtime.state.RealtimeConnectionState;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PluginInboundBudget {
    static final Allowance MEMBER = new Allowance(200, 40);
    static final Allowance CORE = new Allowance(2000, 400);
    static final Duration ERROR_INTERVAL = Duration.ofSeconds(1);
    static final Duration CLOSE_AFTER = Duration.ofSeconds(10);

    public enum Verdict {
        ADMIT,
        DROP_SILENTLY,
        DROP_AND_WARN,
        CLOSE
    }

    private final Clock clock;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public PluginInboundBudget() {
        this(Clock.systemUTC());
    }

    PluginInboundBudget(Clock clock) {
        this.clock = clock;
    }

    public Verdict admit(RealtimeConnectionState state) {
        long now = clock.millis();
        return buckets.computeIfAbsent(state.getConnectionId(),
            ignored -> new Bucket(state.getPrincipal().isNetworkMember() ? MEMBER : CORE, now)).admit(now);
    }

    public void forget(RealtimeConnectionState state) {
        buckets.remove(state.getConnectionId());
    }

    record Allowance(double capacity, double refillPerSecond) {
    }

    private static final class Bucket {
        private final Allowance allowance;
        private double tokens;
        private long refilledAt;
        private long exhaustedSince = -1;
        private long lastDropAt = Long.MIN_VALUE;
        private long warnedAt = Long.MIN_VALUE;

        private Bucket(Allowance allowance, long now) {
            this.allowance = allowance;
            this.tokens = allowance.capacity();
            this.refilledAt = now;
        }

        private synchronized Verdict admit(long now) {
            refill(now);
            boolean recentlyDropped = lastDropAt != Long.MIN_VALUE && now - lastDropAt < ERROR_INTERVAL.toMillis();
            if (!recentlyDropped) {
                exhaustedSince = -1;
            }
            if (tokens >= 1) {
                tokens -= 1;
                return Verdict.ADMIT;
            }
            lastDropAt = now;
            if (exhaustedSince < 0) {
                exhaustedSince = now;
            }
            if (now - exhaustedSince >= CLOSE_AFTER.toMillis()) {
                return Verdict.CLOSE;
            }
            if (warnedAt == Long.MIN_VALUE || now - warnedAt >= ERROR_INTERVAL.toMillis()) {
                warnedAt = now;
                return Verdict.DROP_AND_WARN;
            }
            return Verdict.DROP_SILENTLY;
        }

        private void refill(long now) {
            long elapsed = Math.max(0, now - refilledAt);
            tokens = Math.min(allowance.capacity(), tokens + elapsed * allowance.refillPerSecond() / 1000.0);
            refilledAt = now;
        }
    }
}
