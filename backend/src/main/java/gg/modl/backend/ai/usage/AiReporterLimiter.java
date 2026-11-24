package gg.modl.backend.ai.usage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class AiReporterLimiter {
    private static final int CAPACITY = 50_000;
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final String UNKNOWN_REPORTER = "";

    private final Cache<String, Deque<Instant>> recent = Caffeine.newBuilder()
        .maximumSize(CAPACITY)
        .expireAfterAccess(WINDOW)
        .build();

    public boolean tryAcquire(@Nullable String reporterUuid, int perHour, Instant now) {
        Deque<Instant> analyses = recent.get(normalised(reporterUuid), ignored -> new ArrayDeque<>());
        synchronized (analyses) {
            Instant windowStart = now.minus(WINDOW);
            while (!analyses.isEmpty() && !analyses.peekFirst().isAfter(windowStart)) {
                analyses.pollFirst();
            }
            if (analyses.size() >= perHour) {
                return false;
            }
            analyses.addLast(now);
            return true;
        }
    }

    static String normalised(@Nullable String reporterUuid) {
        if (reporterUuid == null || reporterUuid.isBlank()) {
            return UNKNOWN_REPORTER;
        }
        return reporterUuid.trim().toLowerCase(Locale.ROOT).replace("-", "");
    }
}
