package gg.modl.backend.ai.usage;

import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiRuntime;
import gg.modl.backend.ai.model.AiRuntimeView;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AiRuntimeState implements AiRuntime {
    private final AiBudgetLedger ledger;
    private final Clock clock;
    private final AiCircuitBreaker breaker = new AiCircuitBreaker();
    private final AtomicBoolean budgetWarned = new AtomicBoolean();
    private volatile Outcome lastSuccess = Outcome.NONE;
    private volatile Outcome lastFailure = Outcome.NONE;

    public AiRuntimeState(AiBudgetLedger ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    public boolean admitsCalls() {
        return breaker.allows(clock.instant());
    }

    public void recordSuccess() {
        lastSuccess = new Outcome(null, clock.instant());
        breaker.recordSuccess();
        budgetWarned.set(false);
    }

    public void recordFailure(AiFailureKind kind) {
        Instant now = clock.instant();
        lastFailure = new Outcome(kind, now);
        if (kind == AiFailureKind.HOURLY_BUDGET || kind == AiFailureKind.DAILY_BUDGET) {
            if (budgetWarned.compareAndSet(false, true)) {
                log.warn("AI analysis stopped: the {} AI request limit was reached", kind == AiFailureKind.DAILY_BUDGET ? "daily" : "hourly");
            }
        }
        if (breaker.recordFailure(kind, now)) {
            log.warn("AI analysis paused until {} after a {} failure", breaker.pausedUntil(now), kind);
        }
    }

    @Override
    public void reset() {
        breaker.reset();
        lastFailure = Outcome.NONE;
        budgetWarned.set(false);
    }

    @Override
    public AiRuntimeView view() {
        Instant now = clock.instant();
        AiUsageCounters counters = ledger.current(now);
        return new AiRuntimeView(lastSuccess.at(), lastFailure.kind(), lastFailure.at(), breaker.pausedUntil(now),
            counters.hourCalls(), counters.dayCalls());
    }

    private record Outcome(@Nullable AiFailureKind kind, @Nullable Instant at) {
        static final Outcome NONE = new Outcome(null, null);
    }
}
