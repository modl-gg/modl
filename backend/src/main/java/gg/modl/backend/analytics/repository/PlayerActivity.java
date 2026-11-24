package gg.modl.backend.analytics.repository;

import java.util.List;
import java.util.Map;

public record PlayerActivity(
    List<TimeBucketCount> newPlayers,
    Map<String, Long> byCountry,
    VpnCounts vpn,
    long hostingCount
) {
    public record VpnCounts(long yes, long no, long unknown) {
    }
}
