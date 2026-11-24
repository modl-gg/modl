package gg.modl.backend.analytics.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface AnalyticsRepository {
    Map<String, Long> countTicketsByStatus(Instant startDate);

    Map<String, Long> countTicketsByCategory(Instant startDate);

    List<TimeBucketCount> countTicketsByDay(Instant startDate);

    List<CategoryResolution> averageResolutionByCategory(Instant startDate);

    PunishmentBreakdown loadPunishmentBreakdown(Instant startDate);

    Map<String, Long> countAuditLogsByLevel(Instant startDate);

    List<TimeBucketCount> countAuditLogsByHour(Instant since);

    PlayerActivity loadPlayerActivity(Instant startDate);
}
