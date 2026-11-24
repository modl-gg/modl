package gg.modl.backend.ai.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiUsageLimits;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AiUsageGuardTest {
    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");
    private static final String REPORTER = "11111111-2222-3333-4444-555555555555";

    @Test
    void aBusyRefusalLeavesTheReportersBudgetUntouched() {
        AiReporterLimiter reporters = new AiReporterLimiter();
        AiConcurrencyLimiter busy = mock(AiConcurrencyLimiter.class);
        when(busy.acquire(anyInt())).thenReturn(Optional.empty());
        AiRuntimeState runtime = mock(AiRuntimeState.class);
        when(runtime.admitsCalls()).thenReturn(true);
        AiUsageGuard guard = new AiUsageGuard(runtime, reporters, busy, mock(AiBudgetLedger.class), Clock.fixed(NOW, ZoneOffset.UTC));
        int perHour = AiUsageLimits.DEFAULTS.reportsPerReporterPerHour();

        for (int attempt = 0; attempt < perHour; attempt++) {
            assertThat(guard.admit(REPORTER, AiUsageLimits.DEFAULTS).refusal()).isEqualTo(AiFailureKind.BUSY);
        }

        assertThat(reporters.tryAcquire(REPORTER, perHour, NOW)).isTrue();
    }
}
