package gg.modl.backend.ai.usage;

import gg.modl.backend.ai.model.AiAttemptGate;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiUsageLimits;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiUsageGuard {
    private final AiRuntimeState runtime;
    private final AiReporterLimiter reporterLimiter;
    private final AiConcurrencyLimiter concurrencyLimiter;
    private final AiBudgetLedger ledger;
    private final Clock clock;

    public Admission admit(@Nullable String reporterUuid, AiUsageLimits limits) {
        if (!runtime.admitsCalls()) {
            return Admission.paused();
        }
        Optional<AiConcurrencyLimiter.Permit> permit = concurrencyLimiter.acquire(limits.concurrentCalls());
        if (permit.isEmpty()) {
            return Admission.refused(AiFailureKind.BUSY);
        }
        if (!reporterLimiter.tryAcquire(reporterUuid, limits.reportsPerReporterPerHour(), clock.instant())) {
            permit.get().close();
            return Admission.refused(AiFailureKind.REPORTER_LIMIT);
        }
        return Admission.admitted(permit.get());
    }

    public AiAttemptGate meteredSends(AiUsageLimits limits) {
        return () -> ledger.tryConsume(limits, clock.instant());
    }

    public record Admission(Optional<AiConcurrencyLimiter.Permit> permit, @Nullable AiFailureKind refusal) {
        static Admission paused() {
            return new Admission(Optional.empty(), null);
        }

        static Admission refused(AiFailureKind kind) {
            return new Admission(Optional.empty(), kind);
        }

        static Admission admitted(AiConcurrencyLimiter.Permit permit) {
            return new Admission(Optional.of(permit), null);
        }
    }
}
