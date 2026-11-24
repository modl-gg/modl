package gg.modl.backend.staff.dto.response;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record StaffResponse(
    @Nullable String id,
    String email,
    @Nullable String username,
    String role,
    String status,
    @Nullable String assignedMinecraftUuid,
    @Nullable String assignedMinecraftUsername,
    Instant createdAt
) {
}
