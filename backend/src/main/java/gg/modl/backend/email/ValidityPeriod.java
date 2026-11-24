package gg.modl.backend.email;

import java.time.Duration;
import java.time.Instant;

public record ValidityPeriod(Duration duration) {
    private static final long SECONDS_PER_MINUTE = 60;
    private static final long SECONDS_PER_HOUR = 3600;

    public static ValidityPeriod ofSeconds(long seconds) {
        return new ValidityPeriod(Duration.ofSeconds(seconds));
    }

    public Instant expiresAfter(Instant issuedAt) {
        return Instant.ofEpochMilli(issuedAt.toEpochMilli() + duration.toMillis());
    }

    public String describe() {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds >= SECONDS_PER_HOUR && seconds % SECONDS_PER_HOUR == 0) {
            return plural(seconds / SECONDS_PER_HOUR, "hour");
        }
        if (seconds >= SECONDS_PER_MINUTE) {
            return plural(seconds / SECONDS_PER_MINUTE, "minute");
        }
        return plural(seconds, "second");
    }

    private static String plural(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}
