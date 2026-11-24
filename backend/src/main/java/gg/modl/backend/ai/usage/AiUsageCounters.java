package gg.modl.backend.ai.usage;

import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiUsageLimits;
import java.time.Instant;
import java.util.Optional;

public record AiUsageCounters(long hour, int hourCalls, long day, int dayCalls) {
    public static final AiUsageCounters NONE = new AiUsageCounters(0, 0, 0, 0);

    private static final long SECONDS_PER_HOUR = 3600;
    private static final long SECONDS_PER_DAY = 86_400;

    public AiUsageCounters rolledTo(Instant now) {
        long currentHour = Math.floorDiv(now.getEpochSecond(), SECONDS_PER_HOUR);
        long currentDay = Math.floorDiv(now.getEpochSecond(), SECONDS_PER_DAY);
        return new AiUsageCounters(currentHour, currentHour == hour ? hourCalls : 0, currentDay, currentDay == day ? dayCalls : 0);
    }

    public Optional<AiFailureKind> refusal(AiUsageLimits limits) {
        if (dayCalls >= limits.callsPerDay()) {
            return Optional.of(AiFailureKind.DAILY_BUDGET);
        }
        if (hourCalls >= limits.callsPerHour()) {
            return Optional.of(AiFailureKind.HOURLY_BUDGET);
        }
        return Optional.empty();
    }

    public AiUsageCounters incremented() {
        return new AiUsageCounters(hour, hourCalls + 1, day, dayCalls + 1);
    }
}
