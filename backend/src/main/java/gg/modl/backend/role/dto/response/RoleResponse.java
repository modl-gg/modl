package gg.modl.backend.role.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record RoleResponse(
    String id,
    String name,
    @Nullable String description,
    List<String> permissions,
    boolean isDefault,
    int order,
    int userCount,
    Instant createdAt,
    Instant updatedAt
) {
}
