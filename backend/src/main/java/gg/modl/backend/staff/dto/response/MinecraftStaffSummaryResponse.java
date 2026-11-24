package gg.modl.backend.staff.dto.response;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public record MinecraftStaffSummaryResponse(
    String id,
    String username,
    String email,
    String role,
    @Nullable String minecraftUuid,
    @Nullable String minecraftUsername,
    List<String> permissions,
    @Nullable Instant lastSeen,
    long totalPlaytimeMs,
    @Nullable String lastServer,
    int punishmentsIssuedCount,
    Instant createdAt,
    Instant updatedAt
) {
}
