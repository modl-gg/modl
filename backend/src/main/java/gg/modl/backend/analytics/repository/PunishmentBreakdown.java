package gg.modl.backend.analytics.repository;

import java.util.List;
import java.util.Map;

public record PunishmentBreakdown(
    Map<Integer, Long> byTypeOrdinal,
    List<TimeBucketCount> daily
) {
}
