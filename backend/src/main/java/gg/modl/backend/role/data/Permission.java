package gg.modl.backend.role.data;

import org.jspecify.annotations.Nullable;

public record Permission(
    String id,
    String name,
    String description,
    String category,
    @Nullable String parentId,
    boolean superAdminOnly
) {
    public Permission(String id, String name, String description, String category) {
        this(id, name, description, category, null, false);
    }

    public Permission(String id, String name, String description, String category, @Nullable String parentId) {
        this(id, name, description, category, parentId, false);
    }
}
