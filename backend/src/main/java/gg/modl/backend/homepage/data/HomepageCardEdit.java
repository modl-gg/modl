package gg.modl.backend.homepage.data;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record HomepageCardEdit(
    @Nullable String id,
    String title,
    String description,
    String icon,
    HomepageCardAction action,
    String actionUrl,
    String categoryId,
    boolean enabled
) {
    public HomepageCardEdit {
        id = id == null || id.isBlank() ? null : id;
        title = Objects.requireNonNullElse(title, "");
        description = Objects.requireNonNullElse(description, "");
        icon = Objects.requireNonNullElse(icon, "");
        action = Objects.requireNonNullElse(action, HomepageCardAction.LINK);
        actionUrl = Objects.requireNonNullElse(actionUrl, "");
        categoryId = Objects.requireNonNullElse(categoryId, "");
    }
}
