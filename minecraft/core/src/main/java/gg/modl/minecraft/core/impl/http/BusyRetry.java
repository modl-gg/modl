package gg.modl.minecraft.core.impl.http;

import org.jspecify.annotations.Nullable;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

final class BusyRetry {
    static final String RETRY_AFTER_HEADER = "Retry-After";
    static final BusyRetry NEVER = new BusyRetry(null);

    private static final Pattern DELAY_SECONDS = Pattern.compile("\\d{1,9}");

    private final @Nullable Duration maxWait;

    private BusyRetry(@Nullable Duration maxWait) {
        this.maxWait = maxWait;
    }

    static BusyRetry onceWaitingAtMost(Duration maxWait) {
        return new BusyRetry(maxWait);
    }

    Optional<Duration> delayBeforeRetry(int statusCode, @Nullable String retryAfter) {
        if (maxWait == null || statusCode != HttpURLConnection.HTTP_UNAVAILABLE || retryAfter == null) return Optional.empty();
        String seconds = retryAfter.trim();
        if (!DELAY_SECONDS.matcher(seconds).matches()) return Optional.empty();
        Duration advised = Duration.ofSeconds(Long.parseLong(seconds));
        return Optional.of(advised.compareTo(maxWait) > 0 ? maxWait : advised);
    }
}
