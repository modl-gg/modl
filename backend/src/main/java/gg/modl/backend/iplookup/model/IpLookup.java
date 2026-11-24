package gg.modl.backend.iplookup.model;

import java.time.Duration;
import java.time.Instant;

public record IpLookup(String ip, IpLookupProvider provider, IpFacts facts, Instant lookedUpAt) {
    public static final Duration REFRESH_AFTER = Duration.ofDays(30);

    public boolean isFreshAt(Instant now) {
        return lookedUpAt.plus(REFRESH_AFTER).isAfter(now);
    }
}
