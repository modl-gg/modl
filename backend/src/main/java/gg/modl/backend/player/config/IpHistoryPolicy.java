package gg.modl.backend.player.config;

import java.time.Duration;
import java.time.Instant;

public interface IpHistoryPolicy {
    int ipHistoryDays();

    default Instant cutoff(Instant now) {
        return now.minus(Duration.ofDays(ipHistoryDays()));
    }

    static IpHistoryPolicy ofDays(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("IP history must be kept at least 1 day");
        }
        return () -> days;
    }
}
