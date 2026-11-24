package gg.modl.backend.analytics.service;

import gg.modl.backend.infrastructure.util.DateRangeUtil;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;

public record ReportPeriod(int days) {
    private static final String ALL = "all";
    private static final int UNBOUNDED = 0;

    public static ReportPeriod parse(@Nullable String value) {
        if (value == null || ALL.equals(value)) {
            return new ReportPeriod(UNBOUNDED);
        }
        return new ReportPeriod(DateRangeUtil.resolveRangeDays(value));
    }

    public Instant startingBefore(Instant now) {
        return days == UNBOUNDED ? Instant.EPOCH : now.minus(Duration.ofDays(days));
    }
}
