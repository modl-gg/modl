package gg.modl.backend.staff.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record MinecraftStaffPermissionsResponse(
    @Nullable String minecraftUuid,
    String minecraftUsername,
    String staffUsername,
    String staffId,
    String staffRole,
    List<String> permissions,
    String email
) {
}
