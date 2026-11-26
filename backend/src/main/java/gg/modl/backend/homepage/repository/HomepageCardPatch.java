package gg.modl.backend.homepage.repository;

import java.time.Instant;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record HomepageCardPatch(
    @Nullable String title,
    @Nullable String description,
    @Nullable String icon,
    @Nullable String iconColor,
    @Nullable String actionType,
    @Nullable String actionUrl,
    @Nullable String categoryId,
    @Nullable String backgroundColor,
    @Nullable Boolean enabled,
    Instant updatedAt
) {
}
