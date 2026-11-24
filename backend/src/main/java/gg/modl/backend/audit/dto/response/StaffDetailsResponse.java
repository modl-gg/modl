package gg.modl.backend.audit.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record StaffDetailsResponse(
    String username,
    String period,
    List<PunishmentDetail> punishments,
    List<TicketDetail> tickets,
    List<DailyActivity> dailyActivity,
    List<PunishmentTypeBreakdown> punishmentTypeBreakdown,
    Summary summary
) {
    public record PunishmentDetail(
        String id,
        String playerId,
        String playerName,
        String type,
        String reason,
        @Nullable String duration,
        Instant issued,
        boolean active,
        boolean rolledBack,
        @Nullable String effectiveCategory
    ) {}

    public record TicketDetail(
        String id,
        String subject,
        String category,
        String status,
        Instant lastActivity,
        int responseTime
    ) {}

    public record DailyActivity(
        String date,
        int punishments,
        int tickets
    ) {}

    public record PunishmentTypeBreakdown(
        String type,
        int count
    ) {}

    public record Summary(
        int totalPunishments,
        int totalTickets,
        int avgResponseTime,
        int evidenceUploads
    ) {}
}
