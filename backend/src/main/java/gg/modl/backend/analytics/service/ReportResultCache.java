package gg.modl.backend.analytics.service;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

final class ReportResultCache<V> {
    private static final Duration TIME_TO_LIVE = Duration.ofSeconds(60);
    private static final int MAX_PERIODS = 16;

    private final AsyncCache<ReportPeriod, V> results = Caffeine.newBuilder()
        .expireAfterWrite(TIME_TO_LIVE)
        .maximumSize(MAX_PERIODS)
        .executor(task -> Thread.ofVirtual().start(task))
        .buildAsync();

    V get(ReportPeriod period, Supplier<V> computation) {
        try {
            return results.get(period, missing -> computation.get()).join();
        } catch (CompletionException failure) {
            throw failure.getCause() instanceof RuntimeException cause ? cause : failure;
        }
    }
}
