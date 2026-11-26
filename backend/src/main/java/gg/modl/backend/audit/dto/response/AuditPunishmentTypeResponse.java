package gg.modl.backend.audit.dto.response;

public record AuditPunishmentTypeResponse(int ordinal, String name, long activeCount, long unstartedCount) {
}
