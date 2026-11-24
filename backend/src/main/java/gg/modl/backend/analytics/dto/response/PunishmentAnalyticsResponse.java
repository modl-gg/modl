package gg.modl.backend.analytics.dto.response;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import java.util.List;

public record PunishmentAnalyticsResponse(
    List<TypeCount> byType,
    List<DailyPunishment> dailyPunishments,
    List<StaffPunishment> byStaff
) {
    public record TypeCount(String type, int count) {}

    public record DailyPunishment(String date, int count) {}

    public record StaffPunishment(PunishmentIssuerKind kind, String username, int count) {}
}
